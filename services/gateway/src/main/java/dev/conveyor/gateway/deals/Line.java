package dev.conveyor.gateway.deals;

/**
 * The arithmetic of one waiting line, free of Redis so it can be tested alone. A place is a
 * sequence number; the line admits every place up to the <em>frontier</em>, which a single
 * leader advances by the admission rate once a second. Places past the deal's capacity are
 * told at once that it sold out, instead of waiting for nothing.
 */
final class Line {

    enum State {
        WAITING,
        ADMITTED,
        SOLD_OUT
    }

    record Status(State state, long place, long ahead, long etaSeconds, long pollAfterMillis) {}

    private Line() {}

    static Status status(long place, long frontier, long capacity, double rate) {
        if (place > capacity) {
            return new Status(State.SOLD_OUT, place, 0, 0, 0);
        }
        if (place <= frontier) {
            return new Status(State.ADMITTED, place, 0, 0, 0);
        }
        long ahead = place - frontier;
        long eta = (long) Math.ceil(ahead / Math.max(rate, 0.1));
        // Poll about twice before the turn comes: 1 s when close, at most every 15 s when far.
        long poll = Math.clamp(eta * 1000 / 2, 1000, 15_000);
        return new Status(State.WAITING, place, ahead, eta, poll);
    }

    /**
     * The next admission rate, additive increase and multiplicative decrease on what the deal
     * lane saw in the last second: back off by a quarter when deal orders were shed, failed, or
     * took longer than the target on average; grow by 5 % (at least 1/s) while people wait.
     */
    static double nextRate(
            double rate,
            double min,
            double max,
            long shed,
            long errors,
            double meanLatencyMillis,
            double targetMillis,
            boolean waiting) {
        if (shed > 0 || errors > 0 || meanLatencyMillis > targetMillis) {
            return Math.max(min, rate * 0.75);
        }
        if (waiting) {
            return Math.min(max, rate + Math.max(1, rate * 0.05));
        }
        return rate;
    }
}
