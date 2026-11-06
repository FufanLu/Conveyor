package dev.conveyor.fulfillment.activities;

import dev.conveyor.fulfillment.workflow.FulfillmentModel.FulfillmentRequest;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.Hold;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.OrderUpdate;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.Payment;
import io.temporal.activity.ActivityInterface;

/** Every activity is idempotent: keys are derived from the order, so retries are safe. */
@ActivityInterface
public interface FulfillmentActivities {

    Hold reserve(FulfillmentRequest request);

    Payment authorize(String orderId, long amountCents);

    /** Returns the reservation status after the call (COMMITTED, or EXPIRED / RELEASED if too late). */
    String commit(String reservationId);

    void release(String reservationId);

    void voidPayment(String authorizationId);

    void updateOrder(OrderUpdate update);
}
