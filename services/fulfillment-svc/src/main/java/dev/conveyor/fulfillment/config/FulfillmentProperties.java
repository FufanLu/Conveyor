package dev.conveyor.fulfillment.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the saga reaches its collaborators, and its timing knobs.
 *
 * @param temporalTarget  host:port of the Temporal frontend
 * @param namespace       Temporal namespace
 * @param workerEnabled   start the worker and the Kafka starter (off in tests without Temporal)
 * @param holdTtl         how long inventory holds stock without a commit
 * @param callbackBaseUrl how payment-mock reaches this service for PENDING authorizations
 */
@ConfigurationProperties("conveyor.fulfillment")
public record FulfillmentProperties(
        @DefaultValue("localhost:7233") String temporalTarget,
        @DefaultValue("conveyor") String namespace,
        @DefaultValue("true") boolean workerEnabled,
        @DefaultValue("localhost:9083") String inventoryTarget,
        @DefaultValue("http://localhost:8086") String paymentUrl,
        @DefaultValue("http://localhost:8082") String orderUrl,
        @DefaultValue("20m") Duration holdTtl,
        @DefaultValue("http://localhost:8084") String callbackBaseUrl) {}
