package dev.conveyor.order.domain;

/**
 * PLACED when accepted; the fulfillment saga (fulfillment-svc) then moves it to exactly one
 * terminal state.
 */
public enum OrderStatus {
    PLACED,
    CONFIRMED,
    REJECTED,
    PAYMENT_FAILED,
    CANCELLED;

    public boolean terminal() {
        return this != PLACED;
    }
}
