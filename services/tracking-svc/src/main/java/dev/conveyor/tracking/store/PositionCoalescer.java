package dev.conveyor.tracking.store;

import dev.conveyor.tracking.config.TrackingProperties;
import dev.conveyor.tracking.domain.TrackingRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Write coalescing for the shipment item's "latest position". The event table gets every
 * report; the shipment item only needs to be roughly current, so it is written at most once per
 * positionWriteInterval per shipment. A newer position that arrives inside the interval waits
 * in memory (replacing any older one that waits), and the flusher writes it when the interval
 * is up. A courier reporting every second then costs one shipment write per interval, not one
 * per report.
 *
 * <p>What can be lost: a waiting position, if the instance dies before the flush. The event
 * table still has it, and the next report replaces it anyway. An interval of zero turns
 * coalescing off.
 */
@Component
class PositionCoalescer {

    private static final long IDLE_NANOS = TimeUnit.MINUTES.toNanos(10);

    private final ShipmentStore shipments;
    private final long intervalNanos;
    private final Map<String, Slot> slots = new ConcurrentHashMap<>();
    private final Counter coalesced;

    private static final class Slot {
        long lastWriteNanos;
        TrackingRecord waiting;
    }

    PositionCoalescer(ShipmentStore shipments, TrackingProperties properties, MeterRegistry registry) {
        this.shipments = shipments;
        this.intervalNanos = properties.positionWriteInterval().toNanos();
        this.coalesced = Counter.builder("tracking.persist.positions.coalesced").register(registry);
    }

    /** Writes what is due now; parks the rest. One newest position per shipment expected. */
    List<CompletableFuture<Boolean>> offer(Collection<TrackingRecord> newest) {
        List<CompletableFuture<Boolean>> writes = new ArrayList<>();
        long now = System.nanoTime();
        for (TrackingRecord r : newest) {
            if (intervalNanos == 0) {
                writes.add(shipments.movePosition(r));
                continue;
            }
            boolean[] due = new boolean[1];
            slots.compute(r.shipmentId(), (id, slot) -> {
                Slot s = slot == null ? new Slot() : slot;
                if (slot == null || now - s.lastWriteNanos >= intervalNanos) {
                    s.lastWriteNanos = now;
                    s.waiting = null;
                    due[0] = true;
                } else {
                    if (s.waiting != null) {
                        coalesced.increment();
                    }
                    if (s.waiting == null || s.waiting.seq() < r.seq()) {
                        s.waiting = r;
                    }
                }
                return s;
            });
            if (due[0]) {
                writes.add(shipments.movePosition(r));
            }
        }
        return writes;
    }

    @Scheduled(fixedDelayString = "PT0.5S")
    void flush() {
        long now = System.nanoTime();
        List<CompletableFuture<Boolean>> writes = new ArrayList<>();
        slots.forEach((id, ignored) -> slots.computeIfPresent(id, (k, s) -> {
            if (s.waiting != null && now - s.lastWriteNanos >= intervalNanos) {
                writes.add(shipments.movePosition(s.waiting));
                s.waiting = null;
                s.lastWriteNanos = now;
            } else if (s.waiting == null && now - s.lastWriteNanos > IDLE_NANOS) {
                return null; // forget shipments that went quiet
            }
            return s;
        }));
        if (!writes.isEmpty()) {
            CompletableFuture.allOf(writes.toArray(CompletableFuture[]::new))
                    .exceptionally(e -> null)
                    .join();
        }
    }

    int writesPending() {
        return (int) slots.values().stream().filter(s -> s.waiting != null).count();
    }
}
