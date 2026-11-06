package dev.conveyor.inventory.domain;

/**
 * HELD -> COMMITTED (payment captured) or RELEASED (compensation) or EXPIRED (hold timed out).
 * COMMITTED -> RELEASED (order cancelled after payment). REJECTED is final: not enough stock.
 */
public enum ReservationStatus {
    HELD,
    COMMITTED,
    RELEASED,
    EXPIRED,
    REJECTED;

    /** States whose units still count against available stock. */
    public boolean holdsStock() {
        return this == HELD || this == COMMITTED;
    }
}
