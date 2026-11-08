package dev.conveyor.gateway.deals;

import java.util.concurrent.atomic.AtomicLong;

/**
 * What the deal lane of the edge (LoadSheddingFilter) saw: the waiting room's feedback. The
 * leader reads and resets it once a second to set the admission rate.
 */
public final class LaneStats {

    public record Window(long completed, long shed, long errors, double meanLatencyMillis) {}

    private final AtomicLong completed = new AtomicLong();
    private final AtomicLong shed = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong latencyNanos = new AtomicLong();

    public void completed(long nanos, boolean error) {
        completed.incrementAndGet();
        latencyNanos.addAndGet(nanos);
        if (error) {
            errors.incrementAndGet();
        }
    }

    public void shed() {
        shed.incrementAndGet();
    }

    Window drain() {
        long n = completed.getAndSet(0);
        long nanos = latencyNanos.getAndSet(0);
        return new Window(n, shed.getAndSet(0), errors.getAndSet(0), n == 0 ? 0 : nanos / 1e6 / n);
    }
}
