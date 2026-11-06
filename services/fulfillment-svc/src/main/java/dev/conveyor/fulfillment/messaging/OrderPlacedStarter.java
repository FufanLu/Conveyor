package dev.conveyor.fulfillment.messaging;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.FulfillmentRequest;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.Item;
import dev.conveyor.fulfillment.workflow.OrderFulfillment;
import dev.conveyor.proto.order.v1.OrderEvent;
import dev.conveyor.proto.order.v1.OrderPlaced;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import java.time.Duration;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * OrderPlaced on orders.events starts one saga per order. The workflow id is derived from the
 * order id and reuse is refused, so a redelivered event (Kafka is at least once, Debezium may
 * re-send after a restart) hits WorkflowExecutionAlreadyStarted and is acknowledged as a
 * duplicate. Temporal is the dedup table; this consumer keeps no state.
 */
@Component
class OrderPlacedStarter {

    private static final Logger log = LoggerFactory.getLogger(OrderPlacedStarter.class);

    private final WorkflowClient client;
    private final Counter started;
    private final Counter duplicates;

    OrderPlacedStarter(WorkflowClient client, MeterRegistry registry) {
        this.client = client;
        this.started = Counter.builder("fulfillment.sagas.started")
                .tag("outcome", "started")
                .register(registry);
        this.duplicates = Counter.builder("fulfillment.sagas.started")
                .tag("outcome", "duplicate")
                .register(registry);
    }

    @KafkaListener(
            id = "fulfillment-starter",
            topics = "orders.events",
            groupId = "fulfillment-svc",
            concurrency = "3",
            autoStartup = "${conveyor.fulfillment.worker-enabled:true}")
    void on(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        OrderEvent event = OrderEvent.parseFrom(record.value());
        if (event.getBodyCase() != OrderEvent.BodyCase.PLACED) {
            return; // our own status changes come back on the same topic
        }
        start(event.getOrderId(), event.getPlaced());
    }

    void start(String orderId, OrderPlaced placed) {
        FulfillmentRequest request = new FulfillmentRequest(
                orderId,
                placed.getUserId(),
                placed.getLinesList().stream()
                        .map(l -> new Item(l.getSku(), l.getQuantity()))
                        .toList(),
                placed.getShipToRegion(),
                placed.getTotalCents());
        OrderFulfillment saga = client.newWorkflowStub(
                OrderFulfillment.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(OrderFulfillment.TASK_QUEUE)
                        .setWorkflowId(OrderFulfillment.workflowId(orderId))
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                        .setWorkflowExecutionTimeout(Duration.ofHours(1))
                        .build());
        try {
            var _ = WorkflowClient.start(saga::fulfill, request);
            started.increment();
            log.info("saga started for order {}", orderId);
        } catch (WorkflowExecutionAlreadyStarted e) {
            duplicates.increment();
            log.info("saga for order {} already exists; duplicate event dropped", orderId);
        }
    }
}
