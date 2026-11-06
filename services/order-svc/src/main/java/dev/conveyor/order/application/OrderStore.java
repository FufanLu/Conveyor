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

    /** Newest first, strictly older than {@code before} if given. Single-shard. */
    List<Order> listForUser(String userId, Optional<OrderId> before, int limit);
}
