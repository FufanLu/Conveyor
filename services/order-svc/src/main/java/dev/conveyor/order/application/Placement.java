package dev.conveyor.order.application;

import dev.conveyor.order.domain.Order;

/** Outcome of an idempotent order placement. */
public sealed interface Placement {

    /** A new order was created (and its OrderPlaced event written to the outbox). */
    record Placed(Order order) implements Placement {}

    /** The same key and the same request were seen before: this is that order, unchanged. */
    record Replayed(Order order) implements Placement {}

    /** The key was used before for a different request. */
    record KeyReused() implements Placement {}
}
