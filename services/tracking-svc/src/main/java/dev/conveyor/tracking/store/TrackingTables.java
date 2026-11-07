package dev.conveyor.tracking.store;

import dev.conveyor.tracking.config.TrackingProperties;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.dynamodb.DynamoDbAsyncClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeDefinition;
import software.amazon.awssdk.services.dynamodb.model.BillingMode;
import software.amazon.awssdk.services.dynamodb.model.KeySchemaElement;
import software.amazon.awssdk.services.dynamodb.model.KeyType;
import software.amazon.awssdk.services.dynamodb.model.ResourceInUseException;
import software.amazon.awssdk.services.dynamodb.model.ScalarAttributeType;

/**
 * Creates the two tables on startup if they are missing (on-demand capacity). In AWS they would
 * come from the CDK stack (M8); creating them here keeps DynamoDB Local and tests zero-setup.
 *
 * <pre>
 * tracking_events  PK shipmentId (S)  SK seq (N)   every report; TTL attribute expiresAt
 * shipments        PK shipmentId (S)               latest status + latest position
 * </pre>
 */
@Component
class TrackingTables implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(TrackingTables.class);

    private final DynamoDbAsyncClient dynamo;
    private final TrackingProperties properties;

    TrackingTables(DynamoDbAsyncClient dynamo, TrackingProperties properties) {
        this.dynamo = dynamo;
        this.properties = properties;
    }

    @Override
    public void afterPropertiesSet() {
        create(properties.eventsTable(), true);
        create(properties.shipmentsTable(), false);
        try {
            dynamo.updateTimeToLive(r -> r.tableName(properties.eventsTable())
                            .timeToLiveSpecification(t -> t.enabled(true).attributeName("expiresAt")))
                    .join();
        } catch (CompletionException e) {
            log.debug(
                    "TTL already set on {}: {}",
                    properties.eventsTable(),
                    e.getCause().toString());
        }
    }

    private void create(String table, boolean withSortKey) {
        try {
            dynamo.createTable(r -> {
                        r.tableName(table).billingMode(BillingMode.PAY_PER_REQUEST);
                        if (withSortKey) {
                            r.attributeDefinitions(
                                            AttributeDefinition.builder()
                                                    .attributeName("shipmentId")
                                                    .attributeType(ScalarAttributeType.S)
                                                    .build(),
                                            AttributeDefinition.builder()
                                                    .attributeName("seq")
                                                    .attributeType(ScalarAttributeType.N)
                                                    .build())
                                    .keySchema(
                                            KeySchemaElement.builder()
                                                    .attributeName("shipmentId")
                                                    .keyType(KeyType.HASH)
                                                    .build(),
                                            KeySchemaElement.builder()
                                                    .attributeName("seq")
                                                    .keyType(KeyType.RANGE)
                                                    .build());
                        } else {
                            r.attributeDefinitions(AttributeDefinition.builder()
                                            .attributeName("shipmentId")
                                            .attributeType(ScalarAttributeType.S)
                                            .build())
                                    .keySchema(KeySchemaElement.builder()
                                            .attributeName("shipmentId")
                                            .keyType(KeyType.HASH)
                                            .build());
                        }
                    })
                    .join();
            log.info("created DynamoDB table {}", table);
        } catch (CompletionException e) {
            if (!(e.getCause() instanceof ResourceInUseException)) {
                throw e;
            }
        }
    }
}
