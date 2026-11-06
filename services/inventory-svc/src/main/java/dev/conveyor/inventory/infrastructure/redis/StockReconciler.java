package dev.conveyor.inventory.infrastructure.redis;

import dev.conveyor.inventory.application.ReservationStore;
import dev.conveyor.inventory.domain.Allocation;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the Redis counters honest against Postgres.
 *
 * <p><b>Drift.</b> A process that dies between a Redis claim and the reservation insert (or
 * between a release in Postgres and in Redis) leaves a counter below the truth. Every pass
 * compares the counter of each recently touched (SKU, FC) with on_hand minus active holds. In
 * flight, a claim also makes the counter look low for a few milliseconds, so a difference is
 * repaired only if it is <i>stable</i>: same counter value, same truth, same difference, on two
 * passes in a row. A busy key is left alone until it is quiet; then the relative correction is
 * exact.
 *
 * <p><b>Redis restarts.</b> With AOF fsync once per second, a crash can lose the last second
 * of decrements, which would make counters too high: the one drift that can oversell. When
 * Redis's run_id changes, every counter is dropped and rebuilt lazily from Postgres.
 */
@Component
public class StockReconciler {

    private static final Logger log = LoggerFactory.getLogger(StockReconciler.class);

    private final StringRedisTemplate redis;
    private final ReservationStore store;
    private final Clock clock;
    private final Counter repaired;
    private final Counter flushes;
    private final Map<Allocation, Observation> previous = new HashMap<>();
    private volatile String runId;

    StockReconciler(StringRedisTemplate redis, ReservationStore store, Clock clock, MeterRegistry registry) {
        this.redis = redis;
        this.store = store;
        this.clock = clock;
        this.repaired = Counter.builder("inventory.reconcile.repaired").register(registry);
        this.flushes = Counter.builder("inventory.reconcile.flushes").register(registry);
    }

    record Observation(long counter, long truth) {
        long drift() {
            return counter - truth;
        }
    }

    @Scheduled(fixedDelayString = "${conveyor.inventory.reconcile-interval:PT5S}", initialDelayString = "PT5S")
    void pass() {
        try {
            checkRunId();
            reconcileRecent(Duration.ofMinutes(5));
        } catch (RuntimeException e) {
            log.warn("reconcile pass failed: {}", e.toString());
        }
    }

    void checkRunId() {
        Properties info = redis.execute(c -> c.serverCommands().info("server"), true);
        onRunId(info == null ? null : info.getProperty("run_id"));
    }

    /** Separated from checkRunId so a test can present a restarted Redis. */
    void onRunId(String current) {
        if (runId != null && current != null && !runId.equals(current)) {
            int dropped = dropAllCounters();
            flushes.increment();
            log.warn(
                    "Redis restarted (run_id {} -> {}): dropped {} stock counters; they rebuild from Postgres",
                    runId,
                    current,
                    dropped);
        }
        runId = current;
    }

    /** One pass over recently touched counters. Returns how many were repaired. */
    int reconcileRecent(Duration window) {
        int fixed = 0;
        for (Allocation pair : store.touchedSince(clock.instant().minus(window))) {
            String key = RedisStockGate.key(pair.sku(), pair.fcId());
            String value = redis.opsForValue().get(key);
            if (value == null) {
                previous.remove(pair);
                continue;
            }
            long truth = store.truth(pair.sku(), List.of(pair.fcId())).getOrDefault(pair.fcId(), 0L);
            Observation now = new Observation(Long.parseLong(value), truth);
            Observation before = previous.put(pair, now);
            if (now.drift() != 0 && Objects.equals(before, now)) {
                redis.opsForValue().increment(key, -now.drift());
                repaired.increment();
                previous.remove(pair);
                fixed++;
                log.warn(
                        "repaired {}: counter {} vs truth {}, corrected by {}",
                        key,
                        now.counter(),
                        truth,
                        -now.drift());
            }
        }
        return fixed;
    }

    private int dropAllCounters() {
        int n = 0;
        try (Cursor<String> keys = redis.scan(ScanOptions.scanOptions()
                .match(RedisStockGate.PREFIX + "*")
                .count(1000)
                .build())) {
            while (keys.hasNext()) {
                redis.unlink(keys.next());
                n++;
            }
        }
        previous.clear();
        return n;
    }
}
