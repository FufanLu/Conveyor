package dev.conveyor.order.api;

import dev.conveyor.order.domain.Order;
import java.time.Instant;
import java.util.List;

/** Wire formats of the order API. */
final class OrderRequests {

    private OrderRequests() {}

    record PlaceOrderRequest(List<Item> items, String shipToRegion) {
        record Item(String sku, int quantity) {}
    }

    record OrderResponse(
            String orderId,
            String status,
            List<Line> lines,
            long totalCents,
            String currency,
            String shipToRegion,
            long version,
            Instant placedAt) {

        record Line(String sku, int quantity, long unitPriceCents, long lineTotalCents) {}

        static OrderResponse of(Order o) {
            return new OrderResponse(
                    o.id().toString(),
                    o.status().name(),
                    o.lines().stream()
                            .map(l -> new Line(l.sku(), l.quantity(), l.unitPriceCents(), l.totalCents()))
                            .toList(),
                    o.totalCents(),
                    o.currency(),
                    o.shipToRegion(),
                    o.version(),
                    o.placedAt());
        }
    }

    record OrderPage(List<OrderResponse> orders, String next) {}
}
