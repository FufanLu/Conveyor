package dev.conveyor.fulfillment.workflow;

import dev.conveyor.fulfillment.activities.FulfillmentActivities;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.FulfillmentRequest;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.Hold;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.OrderUpdate;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.Outcome;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.Payment;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.PaymentUpdate;
import io.temporal.activity.ActivityOptions;
import io.temporal.common.RetryOptions;
import io.temporal.failure.ActivityFailure;
import io.temporal.workflow.Saga;
import io.temporal.workflow.Workflow;
import java.time.Duration;
import java.time.Instant;

/**
 * The order saga. Every step that has an effect registers its compensation before the next
 * step runs, and any failure after that point runs them in reverse: void the payment, release
 * the stock. Activities are retried by Temporal with backoff; all of them are idempotent (keys
 * derived from the order id), so a retry after a lost response does no harm.
 *
 * <p>A PENDING payment waits for a signal with a durable timer: the workflow can be evicted,
 * the worker restarted, and the 15 minutes still run out on time. No delay queue, no cron job.
 */
public class OrderFulfillmentWorkflow implements OrderFulfillment {

    static final Duration PAYMENT_WINDOW = Duration.ofMinutes(15);

    private final FulfillmentActivities activities = Workflow.newActivityStub(
            FulfillmentActivities.class,
            ActivityOptions.newBuilder()
                    .setStartToCloseTimeout(Duration.ofSeconds(10))
                    .setScheduleToCloseTimeout(Duration.ofMinutes(5))
                    .setRetryOptions(RetryOptions.newBuilder()
                            .setInitialInterval(Duration.ofMillis(500))
                            .setBackoffCoefficient(2)
                            .setMaximumInterval(Duration.ofSeconds(10))
                            .build())
                    .build());

    private String stage = "started";
    private PaymentUpdate paymentUpdate;

    @Override
    public Outcome fulfill(FulfillmentRequest request) {
        String orderId = request.orderId();
        stage = "reserving stock";
        Hold hold = activities.reserve(request);
        if (!hold.held()) {
            finish(new OrderUpdate(orderId, "REJECTED", "out of stock: " + hold.shortfalls(), null), "rejected");
            return Outcome.REJECTED;
        }
        Saga saga = new Saga(new Saga.Options.Builder().build());
        saga.addCompensation(activities::release, hold.reservationId());
        try {
            stage = "authorizing payment";
            Payment payment = activities.authorize(orderId, request.totalCents());
            if ("PENDING".equals(payment.status())) {
                stage = "waiting for payment";
                boolean completed = Workflow.await(PAYMENT_WINDOW, () -> paymentUpdate != null);
                if (!completed) {
                    saga.compensate();
                    finish(
                            new OrderUpdate(orderId, "CANCELLED", "payment not completed within 15 minutes", null),
                            "cancelled");
                    return Outcome.CANCELLED;
                }
                payment = new Payment(payment.authorizationId(), paymentUpdate.status(), null);
            }
            if (!"AUTHORIZED".equals(payment.status())) {
                saga.compensate();
                finish(new OrderUpdate(orderId, "PAYMENT_FAILED", payment.reason(), null), "payment failed");
                return Outcome.PAYMENT_FAILED;
            }
            saga.addCompensation(activities::voidPayment, payment.authorizationId());

            stage = "committing stock";
            String committed = activities.commit(hold.reservationId());
            if (!"COMMITTED".equals(committed)) {
                saga.compensate();
                finish(
                        new OrderUpdate(
                                orderId, "CANCELLED", "stock hold " + committed + " before payment completed", null),
                        "cancelled");
                return Outcome.CANCELLED;
            }
            Instant promised = DeliveryPromise.estimate(
                    hold.allocations(), request.shipToRegion(), Instant.ofEpochMilli(Workflow.currentTimeMillis()));
            finish(new OrderUpdate(orderId, "CONFIRMED", null, promised), "confirmed");
            return Outcome.CONFIRMED;
        } catch (ActivityFailure failure) {
            // Retries exhausted (or a non-retryable error): undo what was done, tell the order.
            saga.compensate();
            finish(new OrderUpdate(orderId, "CANCELLED", "fulfillment failed at: " + stage, null), "cancelled");
            return Outcome.CANCELLED;
        }
    }

    private void finish(OrderUpdate update, String finalStage) {
        stage = "updating order";
        activities.updateOrder(update);
        stage = finalStage;
    }

    @Override
    public void paymentUpdated(PaymentUpdate update) {
        this.paymentUpdate = update;
    }

    @Override
    public String stage() {
        return stage;
    }
}
