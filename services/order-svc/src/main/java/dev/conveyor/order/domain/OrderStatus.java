package dev.conveyor.order.domain;

/** M2 creates orders as PLACED; the fulfillment saga (M4) moves them on. */
public enum OrderStatus {
    PLACED
}
