package dev.conveyor.inventory.grpc;

import io.grpc.Status;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Bulkhead in front of Reserve. The work behind a reservation is bounded by one Redis
 * connection and a 32-connection Postgres pool; letting 10,000 calls in at once only builds
 * queues in front of them until their 250 ms timeouts fire (measured: 45% of a 10,000-buyer
 * burst failed that way, chapter 03). Instead, at most {@code max-in-flight} reservations run;
 * others wait up to {@code queue-wait} for a slot and are then refused with RESOURCE_EXHAUSTED.
 * The refusal happens before anything is claimed, so a retry with the same idempotency key is
 * always safe.
 */
@Component
class ReservationAdmission {

    private final Semaphore slots;
    private final Duration queueWait;
    private final Counter admitted;
    private final Counter refused;

    ReservationAdmission(
            @Value("${conveyor.inventory.admission.max-in-flight:64}") int maxInFlight,
            @Value("${conveyor.inventory.admission.queue-wait:500ms}") Duration queueWait,
            MeterRegistry registry) {
        this.slots = new Semaphore(maxInFlight, true);
        this.queueWait = queueWait;
        this.admitted = Counter.builder("inventory.admission")
                .tag("outcome", "admitted")
                .register(registry);
        this.refused =
                Counter.builder("inventory.admission").tag("outcome", "refused").register(registry);
        Gauge.builder("inventory.admission.in.flight", slots, s -> maxInFlight - s.availablePermits())
                .register(registry);
    }

    <T> T admit(Supplier<T> call) {
        boolean entered;
        try {
            entered = slots.tryAcquire(queueWait.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw Status.CANCELLED.withDescription("interrupted").asRuntimeException();
        }
        if (!entered) {
            refused.increment();
            throw Status.RESOURCE_EXHAUSTED
                    .withDescription(
                            "inventory is at capacity; retry with the same idempotency_key after a jittered backoff")
                    .asRuntimeException();
        }
        admitted.increment();
        try {
            return call.get();
        } finally {
            slots.release();
        }
    }
}
