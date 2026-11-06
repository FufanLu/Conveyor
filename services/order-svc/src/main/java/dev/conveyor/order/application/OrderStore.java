package dev.conveyor.order.application;

import dev.conveyor.order.domain.Order;
import dev.conveyor.order.domain.OrderId;
import java.util.List;
import java.util.Optional;

/** The sharded system of record for orders. */
public interface OrderStore {

    /**
     * In one local transaction on the customer's shard: claim the idempotency key, insert the
     * order and its lines, and insert the OrderPlaced event into the outbox. Either all of it
     * commits or none of it does.
     */
    Placement placeIdempotently(Order order, String idempotencyKey, byte[] requestHash);

    Optional<Order> find(OrderId id);

    /**
     * PLACED to a terminal status, in one transaction with its OrderStatusChanged outbox event.
     * Repeating the same transition is a no-op; a different terminal status is a conflict.
     */
    StatusChange changeStatus(
            OrderId id, dev.conveyor.order.domain.OrderStatus to, String reason, java.time.Instant promisedDelivery);

    sealed interface StatusChange {
        record Changed(Order order) implements StatusChange {}

        record AlreadyThere(Order order) implements StatusChange {}

        record Conflict(Order order) implements StatusChange {}

        record NotFound() implements StatusChange {}
    }

    /** Newest first, strictly older than {@code before} if given. Single-shard. */
    List<Order> listForUser(String userId, Optional<OrderId> before, int limit);
}
