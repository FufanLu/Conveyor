package dev.conveyor.fulfillment.web;

import dev.conveyor.fulfillment.workflow.FulfillmentModel.PaymentUpdate;
import dev.conveyor.fulfillment.workflow.OrderFulfillment;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowNotFoundException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** The payment provider's webhook, turned into a workflow signal. Repeats are harmless. */
@RestController
class PaymentCallbackController {

    record Callback(String orderId, String authorizationId, String status) {}

    private final WorkflowClient client;

    PaymentCallbackController(WorkflowClient client) {
        this.client = client;
    }

    @PostMapping("/v1/payment-callbacks")
    ResponseEntity<Map<String, String>> callback(@RequestBody Callback callback) {
        if (callback.orderId() == null || callback.status() == null) {
            return ResponseEntity.badRequest().body(Map.of("detail", "orderId and status are required"));
        }
        try {
            client.newWorkflowStub(OrderFulfillment.class, OrderFulfillment.workflowId(callback.orderId()))
                    .paymentUpdated(new PaymentUpdate(callback.authorizationId(), callback.status()));
        } catch (WorkflowNotFoundException e) {
            // The saga already ended (e.g. timed out and cancelled): the late approval is moot.
            return ResponseEntity.status(HttpStatus.GONE)
                    .body(Map.of("detail", "no running saga for " + callback.orderId()));
        }
        return ResponseEntity.accepted().body(Map.of("orderId", callback.orderId()));
    }

    /** Where an order's saga is right now; 404 until its OrderPlaced event has been consumed. */
    @GetMapping("/v1/sagas/{orderId}")
    ResponseEntity<Map<String, String>> stage(@PathVariable String orderId) {
        try {
            String stage = client.newWorkflowStub(OrderFulfillment.class, OrderFulfillment.workflowId(orderId))
                    .stage();
            return ResponseEntity.ok(Map.of("orderId", orderId, "stage", stage));
        } catch (WorkflowNotFoundException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("detail", "no saga for " + orderId));
        }
    }
}
