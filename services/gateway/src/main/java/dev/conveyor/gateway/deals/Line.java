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
        // Poll about twice before the turn comes: 1 s when close, at most once a minute when far.
        // The first Prime Day run capped it at 15 s: 6,000 customers in line then sent ~400
        // polls a second through the same saturated gateway as checkout (docs/illustrated/09).
        long poll = Math.clamp(eta * 1000 / 2, 1000, 60_000);
        return new Status(State.WAITING, place, ahead, eta, poll);
    }

    /**
     * How many more places the line may admit now. Admissions are valid for a while (ten
     * minutes); the ones handed out within that window and not yet turned into orders are
     * outstanding. The line keeps outstanding admissions at stock x overbook, so admissions
     * handed to customers who walked away come back once they expire. The third Prime Day run
     * capped places at units x 1.1 instead, and gave 6,601 admissions for 1,043 orders.
     *
     * @param available the deal SKU's unreserved stock
     * @param overbook admissions per unit of stock
     * @param admittedInWindow places admitted within the admission lifetime
     * @param soldInWindow units reserved within the same window
     */
    static long allowance(long available, double overbook, long admittedInWindow, long soldInWindow) {
        long outstanding = Math.max(0, admittedInWindow - soldInWindow);
        // round first: 100 x 1.1 is 110.00000000000001 in binary floating point
        return Math.max(0, (long) Math.ceil(Math.round(available * overbook * 1e6) / 1e6) - outstanding);
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
