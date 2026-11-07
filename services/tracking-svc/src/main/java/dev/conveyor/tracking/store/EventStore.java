package dev.conveyor.tracking.store;

import static dev.conveyor.tracking.store.Attrs.n;
import static dev.conveyor.tracking.store.Attrs.s;

import dev.conveyor.tracking.config.TrackingProperties;
import dev.conveyor.tracking.domain.TrackingRecord;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;
import software.amazon.awssdk.services.dynamodb.model.QueryRequest;

/**
 * Every report, keyed (shipmentId, seq). The put is conditional on the key not existing, so a
 * redelivered Kafka record or a handheld re-sending a batch is refused by DynamoDB itself. One
 * shipment's reports are one partition key, read back in seq order with a single Query.
 */
@Component
public class EventStore {

    private final DynamoDbAsyncClient dynamo;
    private final TrackingProperties properties;

    EventStore(DynamoDbAsyncClient dynamo, TrackingProperties properties) {
        this.dynamo = dynamo;
        this.properties = properties;
    }

    /** Completes with true if stored, false if this (shipment, seq) was already there. */
    public CompletableFuture<Boolean> append(TrackingRecord r) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("shipmentId", s(r.shipmentId()));
        item.put("seq", n(r.seq()));
        item.put("courierId", s(r.courierId()));
        item.put("recordedAt", s(r.recordedAt().toString()));
        item.put("expiresAt", n(Instant.now().plus(properties.eventRetention()).getEpochSecond()));
        if (r.isPosition()) {
            item.put("lat", n(r.lat()));
            item.put("lon", n(r.lon()));
            if (r.speed() != null) {
                item.put("speed", n(r.speed().doubleValue()));
            }
        } else {
            item.put("scanType", s(r.scanType()));
            if (r.location() != null) {
                item.put("location", s(r.location()));
            }
        }
        return dynamo.putItem(p -> p.tableName(properties.eventsTable())
                        .item(item)
                        .conditionExpression("attribute_not_exists(seq)"))
                .handle((ok, e) -> {
                    if (e == null) {
                        return true;
                    }
                    Throwable cause = e instanceof CompletionException ? e.getCause() : e;
                    if (cause instanceof ConditionalCheckFailedException) {
                        return false;
                    }
                    throw new CompletionException(cause);
                });
    }

    /** One shipment's reports after {@code afterSeq}, in order (paginated under the hood). */
    public Flux<TrackingRecord> history(String shipmentId, long afterSeq) {
        QueryRequest query = QueryRequest.builder()
                .tableName(properties.eventsTable())
                .keyConditionExpression("shipmentId = :id AND seq > :after")
                .expressionAttributeValues(Map.of(":id", s(shipmentId), ":after", n(afterSeq)))
                .build();
        return Flux.from(dynamo.queryPaginator(query).items()).map(EventStore::toRecord);
    }

    static TrackingRecord toRecord(Map<String, AttributeValue> item) {
        Double speed = Attrs.dbl(item, "speed");
        return new TrackingRecord(
                Attrs.str(item, "shipmentId"),
                Attrs.lng(item, "seq"),
                Attrs.str(item, "courierId"),
                Attrs.instant(item, "recordedAt"),
                Attrs.dbl(item, "lat"),
                Attrs.dbl(item, "lon"),
                speed == null ? null : speed.floatValue(),
                Attrs.str(item, "scanType"),
                Attrs.str(item, "location"));
    }
}
