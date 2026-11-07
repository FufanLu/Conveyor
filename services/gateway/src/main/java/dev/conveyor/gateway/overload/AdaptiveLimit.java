package dev.conveyor.gateway.overload;

/**
 * A concurrency limit that finds the upstreams' capacity from latency alone, in the style of
 * Netflix's Gradient2 (concurrency-limits) and TCP Vegas. It keeps two round-trip times: a
 * short exponential average of the last few dozen requests, and the no-load RTT, the lowest
 * recently seen (a minimum that drifts upward by e every 30 s, so a lasting change of the
 * upstream is learned within a minute or two whatever the request rate). While the short RTT stays within {@code tolerance} of
 * the no-load RTT, the limit grows by a queue allowance of sqrt(limit). When requests start
 * queueing downstream, the gradient tolerance * noLoad / short drops below 1 and shrinks the
 * limit in proportion, before timeouts and errors appear.
 *
 * <p>The first version used a long average (600 samples) as the baseline. Under sustained
 * overload at 1,000 req/s that window is under a second long, so the queueing itself became the
 * baseline and the limit stopped shrinking. The second let the minimum drift up per request;
 * at 400 req/s that is 5x in 20 s, and the limit ran to its maximum while every request queued
 * for 100 ms (docs/illustrated/07). The drift is per unit of time now.
 *
 * <p>Synchronized: one update per completed request is cheap next to an HTTP round trip.
 */
public final class AdaptiveLimit {

    private final int min;
    private final int max;
    private final double tolerance;
    private double limit;
    private double shortRtt = Double.NaN;
    private double noLoadRtt = Double.NaN;
    private long lastSampleNanos;
    private static final double DRIFT_NANOS = 30e9;

    public AdaptiveLimit(int initial, int min, int max, double tolerance) {
        this.limit = initial;
        this.min = min;
        this.max = max;
        this.tolerance = tolerance;
    }

    public synchronized int limit() {
        return (int) limit;
    }

    /**
     * One completed request.
     *
     * @param rttNanos its round trip
     * @param inflight requests in flight when it finished; the limit only grows when it is used
     */
    public void sample(long rttNanos, int inflight) {
        sample(rttNanos, inflight, System.nanoTime());
    }

    synchronized void sample(long rttNanos, int inflight, long nowNanos) {
        double rtt = rttNanos;
        if (Double.isNaN(shortRtt)) {
            shortRtt = rtt;
            noLoadRtt = rtt;
            lastSampleNanos = nowNanos;
            return;
        }
        double elapsed = Math.max(0, nowNanos - lastSampleNanos);
        lastSampleNanos = nowNanos;
        shortRtt += (rtt - shortRtt) / 20.0;
        noLoadRtt = Math.min(noLoadRtt * Math.exp(elapsed / DRIFT_NANOS), rtt);
        double gradient = Math.max(0.5, Math.min(1.0, tolerance * noLoadRtt / shortRtt));
        boolean appLimited = inflight < limit / 2; // no evidence that more would be fine
        double queue = Math.sqrt(limit);
        double next = appLimited && gradient >= 1.0 ? limit : limit * gradient + queue;
        // Smooth: move a fifth of the way per sample.
        limit = Math.max(min, Math.min(max, limit * 0.8 + next * 0.2));
    }

    synchronized double shortRttNanos() {
        return shortRtt;
    }

    synchronized double noLoadRttNanos() {
        return noLoadRtt;
    }
}
