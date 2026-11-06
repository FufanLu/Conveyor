package dev.conveyor.order.application;

import java.time.Instant;
import java.util.List;

/** The operations read model (CQRS): orders as projected from orders.events into OpenSearch. */
public interface OrderSearch {

    Result search(Query query);

    record Query(String status, String sku, Instant placedAfter, int size) {}

    record Result(long total, List<Summary> orders) {}

    record Summary(
            String orderId, String userId, String status, long totalCents, int lines, Instant placedAt, long version) {}
}
