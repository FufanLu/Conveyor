package dev.conveyor.tracking.config;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * DynamoDB location and table names, and the live push knobs.
 *
 * @param dynamoEndpoint  DynamoDB Local in development; empty for the real service (default endpoint)
 * @param eventsTable     every report, partition key shipmentId, sort key seq
 * @param shipmentsTable  one item per shipment with its latest status and position
 * @param eventRetention  DynamoDB TTL on reports
 * @param mapInterval     how often the ops map stream sends the latest position per shipment
 * @param subscriberBuffer reports buffered per SSE subscriber before the oldest are dropped
 * @param positionWriteInterval at most one latest-position write per shipment per interval (0 = every batch)
 */
@ConfigurationProperties("conveyor.tracking")
public record TrackingProperties(
        @DefaultValue("http://localhost:8000") URI dynamoEndpoint,
        @DefaultValue("us-east-1") String region,
        @DefaultValue("tracking_events") String eventsTable,
        @DefaultValue("shipments") String shipmentsTable,
        @DefaultValue("90d") Duration eventRetention,
        @DefaultValue("500ms") Duration mapInterval,
        @DefaultValue("256") int subscriberBuffer,
        @DefaultValue("5s") Duration positionWriteInterval) {}
