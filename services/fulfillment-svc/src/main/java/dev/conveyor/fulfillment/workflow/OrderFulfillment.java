package dev.conveyor.fulfillment.workflow;

import dev.conveyor.fulfillment.workflow.FulfillmentModel.FulfillmentRequest;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.Outcome;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.PaymentUpdate;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

@WorkflowInterface
public interface OrderFulfillment {

    String TASK_QUEUE = "order-fulfillment";

    static String workflowId(String orderId) {
        return "order-" + orderId;
    }

    @WorkflowMethod
    Outcome fulfill(FulfillmentRequest request);

    /** payment-mock completed a PENDING authorization. */
    @SignalMethod
    void paymentUpdated(PaymentUpdate update);

    /** Where the saga is right now, for operators. */
    @QueryMethod
    String stage();
}
