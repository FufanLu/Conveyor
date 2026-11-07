package dev.conveyor.tracking.store;

import static dev.conveyor.tracking.store.Attrs.n;
import static dev.conveyor.tracking.store.Attrs.s;

import dev.conveyor.tracking.config.TrackingProperties;
import dev.conveyor.tracking.domain.Shipment;
import dev.conveyor.tracking.domain.TrackingRecord;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ConditionalCheckFailedException;

/**
 * One item per shipment. Every write is conditional, so the item only moves forward: a
 * position replaces the stored one only if its seq is higher, a scan likewise for the status,
 * and the order lifecycle (ORDERED -> READY / CANCELLED) only from ORDERED. Out-of-order and
 * duplicate deliveries therefore cannot move a shipment backwards.
 */
@Component
public class ShipmentStore {

    private final DynamoDbAsyncClient dynamo;
    private final TrackingProperties properties;

    ShipmentStore(DynamoDbAsyncClient dynamo, TrackingProperties properties) {
        this.dynamo = dynamo;
        this.properties = properties;
    }

    /** From OrderPlaced. A repeat does nothing. */
    public CompletableFuture<Boolean> createOrdered(String shipmentId, String userId, String region) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("shipmentId", s(shipmentId));
        item.put("userId", s(userId));
        item.put("region", s(region));
        item.put("status", s(Shipment.ORDERED));
        item.put("statusSeq", n(0L));
        item.put("positionSeq", n(0L));
        return conditional(dynamo.putItem(p -> p.tableName(properties.shipmentsTable())
                .item(item)
                .conditionExpression("attribute_not_exists(shipmentId)")));
    }

    /** From OrderStatusChanged: READY (with the promise) or CANCELLED, only from ORDERED. */
    public CompletableFuture<Boolean> settleOrder(String shipmentId, String status, Instant promisedDelivery) {
        Map<String, AttributeValue> values = new HashMap<>();
        values.put(":to", s(status));
        values.put(":from", s(Shipment.ORDERED));
        String update = "SET #st = :to";
        if (promisedDelivery != null) {
            update += ", promisedDelivery = :p";
            values.put(":p", s(promisedDelivery.toString()));
        }
        String expression = update;
        return conditional(dynamo.updateItem(u -> u.tableName(properties.shipmentsTable())
                .key(Map.of("shipmentId", s(shipmentId)))
                .updateExpression(expression)
                .conditionExpression("#st = :from")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(values)));
    }

    /** Latest position, only if newer than the stored one. */
    public CompletableFuture<Boolean> movePosition(TrackingRecord r) {
        return conditional(dynamo.updateItem(u -> u.tableName(properties.shipmentsTable())
                .key(Map.of("shipmentId", s(r.shipmentId())))
                .updateExpression("SET lat = :lat, lon = :lon, positionSeq = :seq, positionAt = :at, courierId = :c")
                .conditionExpression("attribute_not_exists(positionSeq) OR positionSeq < :seq")
                .expressionAttributeValues(Map.of(
                        ":lat", n(r.lat()),
                        ":lon", n(r.lon()),
                        ":seq", n(r.seq()),
                        ":at", s(r.recordedAt().toString()),
                        ":c", s(r.courierId())))));
    }

    /** Status from a scan, only if newer than the stored status and the order was not cancelled. */
    public CompletableFuture<Boolean> applyScan(TrackingRecord r) {
        return conditional(dynamo.updateItem(u -> u.tableName(properties.shipmentsTable())
                .key(Map.of("shipmentId", s(r.shipmentId())))
                .updateExpression("SET #st = :st, statusSeq = :seq, statusAt = :at, courierId = :c")
                .conditionExpression("(attribute_not_exists(statusSeq) OR statusSeq < :seq) AND "
                        + "(attribute_not_exists(#st) OR #st <> :cancelled)")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(Map.of(
                        ":st", s(r.scanType()),
                        ":seq", n(r.seq()),
                        ":at", s(r.recordedAt().toString()),
                        ":c", s(r.courierId()),
                        ":cancelled", s(Shipment.CANCELLED)))));
    }

    public Mono<Shipment> find(String shipmentId) {
        return Mono.fromFuture(() -> dynamo.getItem(g -> g.tableName(properties.shipmentsTable())
                        .key(Map.of("shipmentId", s(shipmentId)))
                        .consistentRead(true)))
                .filter(r -> r.hasItem() && !r.item().isEmpty())
                .map(r -> toShipment(r.item()));
    }

    static Shipment toShipment(Map<String, AttributeValue> i) {
        return new Shipment(
                Attrs.str(i, "shipmentId"),
                Attrs.str(i, "userId"),
                Attrs.str(i, "region"),
                Attrs.str(i, "status"),
                Attrs.instant(i, "promisedDelivery"),
                Attrs.lng(i, "statusSeq"),
                Attrs.dbl(i, "lat"),
                Attrs.dbl(i, "lon"),
                Attrs.lng(i, "positionSeq"),
                Attrs.instant(i, "positionAt"),
                Attrs.str(i, "courierId"));
    }

    /** true if the write happened, false if its condition said "already newer / not applicable". */
    private static CompletableFuture<Boolean> conditional(CompletableFuture<?> write) {
        return write.handle((ok, e) -> {
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
}
