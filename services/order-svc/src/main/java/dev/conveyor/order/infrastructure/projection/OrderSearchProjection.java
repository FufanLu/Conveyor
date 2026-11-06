package dev.conveyor.order.infrastructure.projection;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.conveyor.proto.order.v1.OrderEvent;
import dev.conveyor.proto.order.v1.OrderLine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Instant;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.opensearch.client.opensearch._types.VersionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * CQRS read side: every order event becomes (or replaces) one OpenSearch document. Kafka
 * delivers at least once and Debezium can re-send after a restart, so the projection relies on
 * OpenSearch external versioning: a document is written only if the event's order_version is
 * higher than the indexed one. Duplicates and late events are counted and dropped.
 */
@Component
class OrderSearchProjection {

    private static final Logger log = LoggerFactory.getLogger(OrderSearchProjection.class);

    private final OpenSearchClient client;
    private final OrderSearchProperties properties;
    private final Counter applied;
    private final Counter duplicates;

    OrderSearchProjection(OpenSearchClient client, OrderSearchProperties properties, MeterRegistry registry) {
        this.client = client;
        this.properties = properties;
        this.applied = Counter.builder("order.projection.events")
                .tag("outcome", "applied")
                .register(registry);
        this.duplicates = Counter.builder("order.projection.events")
                .tag("outcome", "duplicate")
                .register(registry);
    }

    static final String LISTENER_ID = "order-search-projection";

    /** Started by OpenSearchOrderSearch once the index and alias exist (see there). */
    @KafkaListener(
            id = LISTENER_ID,
            topics = OrderTopics.ORDER_EVENTS,
            groupId = "order-search-projection",
            concurrency = "3",
            autoStartup = "false")
    void on(ConsumerRecord<String, byte[]> record) throws IOException {
        try {
            project(record);
        } catch (IOException | RuntimeException e) {
            // The error handler retries, then dead-letters; say which record is stuck and why.
            log.warn(
                    "projection failed at {}-{}@{} (key {}): {}",
                    record.topic(),
                    record.partition(),
                    record.offset(),
                    record.key(),
                    e.toString());
            throw e;
        }
    }

    private void project(ConsumerRecord<String, byte[]> record) throws IOException {
        OrderEvent event;
        try {
            event = OrderEvent.parseFrom(record.value());
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException(
                    "not an OrderEvent at " + record.topic() + "-" + record.partition() + "@" + record.offset(), e);
        }
        if (event.getBodyCase() == OrderEvent.BodyCase.STATUS_CHANGED) {
            projectStatus(event);
            return;
        }
        if (event.getBodyCase() != OrderEvent.BodyCase.PLACED) {
            log.debug("ignoring {} for {}", event.getBodyCase(), event.getOrderId());
            return;
        }
        var placed = event.getPlaced();
        OrderDocument doc = new OrderDocument(
                event.getOrderId(),
                placed.getUserId(),
                "PLACED",
                placed.getTotalCents(),
                placed.getCurrency(),
                placed.getShipToRegion(),
                placed.getLinesList().stream().map(OrderLine::getSku).toList(),
                placed.getLinesCount(),
                Instant.ofEpochSecond(
                                event.getOccurredAt().getSeconds(),
                                event.getOccurredAt().getNanos())
                        .toString(),
                event.getOrderVersion());
        write(doc);
    }

    /**
     * Events of one order share a partition, so the PLACED document is already indexed when its
     * status change arrives; the change is applied to it under the new version.
     */
    private void projectStatus(OrderEvent event) throws IOException {
        var current = client.get(g -> g.index(properties.index()).id(event.getOrderId()), OrderDocument.class);
        if (!current.found() || current.source() == null) {
            throw new IllegalStateException("status change for " + event.getOrderId() + " before its PLACED event");
        }
        OrderDocument d = current.source();
        write(new OrderDocument(
                d.orderId(),
                d.userId(),
                event.getStatusChanged().getStatus(),
                d.totalCents(),
                d.currency(),
                d.shipToRegion(),
                d.skus(),
                d.lines(),
                d.placedAt(),
                event.getOrderVersion()));
    }

    private void write(OrderDocument doc) throws IOException {
        try {
            client.index(i -> i.index(properties.index())
                    .id(doc.orderId())
                    .document(doc)
                    .version(doc.version())
                    .versionType(VersionType.External));
            applied.increment();
        } catch (IOException | RuntimeException e) {
            if (!VersionConflicts.is(e)) {
                throw e;
            }
            duplicates.increment(); // already at this version or newer: at-least-once delivery at work
        }
    }
}
