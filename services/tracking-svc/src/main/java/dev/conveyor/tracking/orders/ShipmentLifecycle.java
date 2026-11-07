package dev.conveyor.tracking.orders;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Timestamp;
import dev.conveyor.proto.order.v1.OrderEvent;
import dev.conveyor.proto.order.v1.OrderStatusChanged;
import dev.conveyor.tracking.domain.Shipment;
import dev.conveyor.tracking.ingest.TrackingTopics;
import dev.conveyor.tracking.store.ShipmentStore;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Order events -> shipment items. OrderPlaced creates the shipment (it carries the owner and
 * the region); the saga's OrderStatusChanged makes it READY with the promised date, or
 * CANCELLED. Both events of an order share a partition, so PLACED is always applied first.
 * Every write is conditional, so redelivery is harmless.
 */
@Component
class ShipmentLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ShipmentLifecycle.class);

    private final ShipmentStore shipments;

    ShipmentLifecycle(ShipmentStore shipments) {
        this.shipments = shipments;
    }

    @KafkaListener(id = "tracking-shipments", topics = TrackingTopics.ORDER_EVENTS, groupId = "tracking-shipments")
    void on(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        OrderEvent event = OrderEvent.parseFrom(record.value());
        switch (event.getBodyCase()) {
            case PLACED ->
                shipments
                        .createOrdered(
                                event.getOrderId(),
                                event.getPlaced().getUserId(),
                                event.getPlaced().getShipToRegion())
                        .join();
            case STATUS_CHANGED -> {
                OrderStatusChanged change = event.getStatusChanged();
                boolean confirmed = "CONFIRMED".equals(change.getStatus());
                Instant promised = null;
                if (change.hasPromisedDelivery()) {
                    Timestamp ts = change.getPromisedDelivery();
                    promised = Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
                }
                boolean applied = shipments
                        .settleOrder(event.getOrderId(), confirmed ? Shipment.READY : Shipment.CANCELLED, promised)
                        .join();
                log.info(
                        "shipment {} -> {}{}",
                        event.getOrderId(),
                        confirmed ? "READY" : "CANCELLED",
                        applied ? "" : " (already settled)");
            }
            case BODY_NOT_SET -> log.warn("order event without body at offset {}", record.offset());
        }
    }
}
