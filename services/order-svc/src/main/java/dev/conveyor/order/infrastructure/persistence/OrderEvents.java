package dev.conveyor.order.infrastructure.persistence;

import com.google.protobuf.Timestamp;
import dev.conveyor.order.domain.Order;
import dev.conveyor.proto.order.v1.OrderEvent;
import dev.conveyor.proto.order.v1.OrderLine;
import dev.conveyor.proto.order.v1.OrderPlaced;
import java.util.UUID;

/** Domain order to the published Protobuf contract (libs/proto, conveyor.order.v1). */
final class OrderEvents {

    private OrderEvents() {}

    static OrderEvent placed(Order order, UUID eventId) {
        OrderPlaced.Builder placed = OrderPlaced.newBuilder()
                .setUserId(order.userId())
                .setTotalCents(order.totalCents())
                .setCurrency(order.currency())
                .setShipToRegion(order.shipToRegion());
        order.lines()
                .forEach(l -> placed.addLines(OrderLine.newBuilder()
                        .setSku(l.sku())
                        .setQuantity(l.quantity())
                        .setUnitPriceCents(l.unitPriceCents())));
        java.time.Instant at = order.placedAt();
        Timestamp occurredAt = Timestamp.newBuilder()
                .setSeconds(at.getEpochSecond())
                .setNanos(at.getNano())
                .build();
        return OrderEvent.newBuilder()
                .setEventId(eventId.toString())
                .setOrderId(order.id().toString())
                .setOrderVersion(order.version())
                .setOccurredAt(occurredAt)
                .setPlaced(placed)
                .build();
    }

    static OrderEvent statusChanged(Order order, UUID eventId, java.time.Instant at) {
        dev.conveyor.proto.order.v1.OrderStatusChanged.Builder changed =
                dev.conveyor.proto.order.v1.OrderStatusChanged.newBuilder()
                        .setStatus(order.status().name());
        if (order.statusReason() != null) {
            changed.setReason(order.statusReason());
        }
        if (order.promisedDelivery() != null) {
            java.time.Instant p = order.promisedDelivery();
            changed.setPromisedDelivery(
                    Timestamp.newBuilder().setSeconds(p.getEpochSecond()).setNanos(p.getNano()));
        }
        return OrderEvent.newBuilder()
                .setEventId(eventId.toString())
                .setOrderId(order.id().toString())
                .setOrderVersion(order.version())
                .setOccurredAt(
                        Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano()))
                .setStatusChanged(changed)
                .build();
    }
}
