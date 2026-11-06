package dev.conveyor.order.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record Order(
        OrderId id,
        String userId,
        OrderStatus status,
        List<OrderLine> lines,
        long totalCents,
        String currency,
        String shipToRegion,
        long version,
        Instant placedAt,
        String statusReason,
        Instant promisedDelivery) {

    public Order {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(status, "status");
        lines = List.copyOf(lines);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("an order needs at least one line");
        }
        if (id.shard() != Shards.forUser(userId)) {
            throw new IllegalArgumentException("order id " + id + " does not carry the shard of its customer");
        }
    }

    /** A new order: version 1, total computed from the lines. */
    public static Order place(
            OrderId id, String userId, List<OrderLine> lines, String currency, String shipToRegion, Instant at) {
        long total = 0;
        for (OrderLine line : lines) {
            total = Math.addExact(total, line.totalCents());
        }
        return new Order(id, userId, OrderStatus.PLACED, lines, total, currency, shipToRegion, 1, at, null, null);
    }
}
