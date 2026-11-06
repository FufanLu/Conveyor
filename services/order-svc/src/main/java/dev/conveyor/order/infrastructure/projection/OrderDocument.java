package dev.conveyor.order.infrastructure.projection;

import java.util.List;

/** One order in the operations read model. */
public record OrderDocument(
        String orderId,
        String userId,
        String status,
        long totalCents,
        String currency,
        String shipToRegion,
        List<String> skus,
        int lines,
        String placedAt,
        long version) {}
