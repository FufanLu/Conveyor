package dev.conveyor.fulfillment.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.conveyor.fulfillment.activities.FulfillmentActivities;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.Allocation;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.FulfillmentRequest;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.Hold;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.Item;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.OrderUpdate;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.Outcome;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.Payment;
import dev.conveyor.fulfillment.workflow.FulfillmentModel.PaymentUpdate;
import io.temporal.api.enums.v1.WorkflowIdReusePolicy;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowExecutionAlreadyStarted;
import io.temporal.client.WorkflowOptions;
import io.temporal.client.WorkflowStub;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The saga against a time-skipping Temporal test server: a 15-minute wait takes milliseconds. */
class OrderFulfillmentWorkflowTest {

    /** Scripted downstream answers; every call is recorded in order. */
    static final class Downstream implements FulfillmentActivities {
        final List<String> calls = new CopyOnWriteArrayList<>();
        final List<OrderUpdate> updates = new CopyOnWriteArrayList<>();
        volatile String reserveStatus = "HELD";
        volatile String paymentStatus = "AUTHORIZED";
        volatile String commitStatus = "COMMITTED";
        final AtomicInteger authorizeFailuresLeft = new AtomicInteger();
        volatile List<Allocation> allocations = List.of(new Allocation("B0SKU", "east-1", 2));

        @Override
        public Hold reserve(FulfillmentRequest request) {
            calls.add("reserve");
            return "HELD".equals(reserveStatus)
                    ? new Hold("res-1", "HELD", allocations, List.of())
                    : new Hold("res-1", "REJECTED", List.of(), List.of("B0SKUx2"));
        }

        @Override
        public Payment authorize(String orderId, long amountCents) {
            calls.add("authorize");
            if (authorizeFailuresLeft.getAndDecrement() > 0) {
                throw new IllegalStateException("payment provider 500");
            }
            return new Payment("auth-1", paymentStatus, "DECLINED".equals(paymentStatus) ? "card declined" : null);
        }

        @Override
        public String commit(String reservationId) {
            calls.add("commit");
            return commitStatus;
        }

        @Override
        public void release(String reservationId) {
            calls.add("release");
        }

        @Override
        public void voidPayment(String authorizationId) {
            calls.add("void");
        }

        @Override
        public void updateOrder(OrderUpdate update) {
            calls.add("update:" + update.status());
            updates.add(update);
        }
    }

    private TestWorkflowEnvironment env;
    private Downstream downstream;
    private WorkflowClient client;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        Worker worker = env.newWorker(OrderFulfillment.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(OrderFulfillmentWorkflow.class);
        downstream = new Downstream();
        worker.registerActivitiesImplementations(downstream);
        env.start();
        client = env.getWorkflowClient();
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    private static final FulfillmentRequest ORDER =
            new FulfillmentRequest("1001", "user-1", List.of(new Item("B0SKU", 2)), "us-east", 4_998);

    private OrderFulfillment stub(String orderId) {
        return client.newWorkflowStub(
                OrderFulfillment.class,
                WorkflowOptions.newBuilder()
                        .setTaskQueue(OrderFulfillment.TASK_QUEUE)
                        .setWorkflowId(OrderFulfillment.workflowId(orderId))
                        .setWorkflowIdReusePolicy(WorkflowIdReusePolicy.WORKFLOW_ID_REUSE_POLICY_REJECT_DUPLICATE)
                        .build());
    }

    @Test
    void happyPathConfirmsWithALocalTwoDayPromise() {
        Instant start = Instant.ofEpochMilli(env.currentTimeMillis());

        assertThat(stub("1001").fulfill(ORDER)).isEqualTo(Outcome.CONFIRMED);

        assertThat(downstream.calls).containsExactly("reserve", "authorize", "commit", "update:CONFIRMED");
        OrderUpdate confirmed = downstream.updates.getFirst();
        assertThat(confirmed.promisedDelivery())
                .isEqualTo(DeliveryPromise.estimate(downstream.allocations, "us-east", start));
    }

    @Test
    void noStockRejectsTheOrderWithoutTouchingPayment() {
        downstream.reserveStatus = "REJECTED";

        assertThat(stub("1001").fulfill(ORDER)).isEqualTo(Outcome.REJECTED);

        assertThat(downstream.calls).containsExactly("reserve", "update:REJECTED");
        assertThat(downstream.updates.getFirst().reason()).contains("B0SKUx2");
    }

    @Test
    void aDeclinedCardReleasesTheStock() {
        downstream.paymentStatus = "DECLINED";

        assertThat(stub("1001").fulfill(ORDER)).isEqualTo(Outcome.PAYMENT_FAILED);

        assertThat(downstream.calls).containsExactly("reserve", "authorize", "release", "update:PAYMENT_FAILED");
        assertThat(downstream.updates.getFirst().reason()).isEqualTo("card declined");
    }

    @Test
    void transientPaymentErrorsAreRetriedByTemporalNotByUs() {
        downstream.authorizeFailuresLeft.set(3);

        assertThat(stub("1001").fulfill(ORDER)).isEqualTo(Outcome.CONFIRMED);

        assertThat(downstream.calls.stream().filter("authorize"::equals)).hasSize(4);
    }

    @Test
    void aPendingPaymentWaitsForTheCallbackSignal() {
        downstream.paymentStatus = "PENDING";
        OrderFulfillment saga = stub("1001");
        WorkflowClient.start(saga::fulfill, ORDER);

        env.sleep(Duration.ofMinutes(3)); // the customer takes a while with 3-D Secure
        assertThat(client.newWorkflowStub(OrderFulfillment.class, "order-1001").stage())
                .isEqualTo("waiting for payment");
        client.newWorkflowStub(OrderFulfillment.class, "order-1001")
                .paymentUpdated(new PaymentUpdate("auth-1", "AUTHORIZED"));

        assertThat(WorkflowStub.fromTyped(saga).getResult(Outcome.class)).isEqualTo(Outcome.CONFIRMED);
        assertThat(downstream.calls).containsExactly("reserve", "authorize", "commit", "update:CONFIRMED");
    }

    @Test
    void aPaymentThatNeverCompletesIsCancelledAfterFifteenMinutes() {
        downstream.paymentStatus = "PENDING";
        Instant start = Instant.ofEpochMilli(env.currentTimeMillis());

        assertThat(stub("1001").fulfill(ORDER)).isEqualTo(Outcome.CANCELLED);

        assertThat(Duration.between(start, Instant.ofEpochMilli(env.currentTimeMillis())))
                .isGreaterThanOrEqualTo(OrderFulfillmentWorkflow.PAYMENT_WINDOW);
        assertThat(downstream.calls).containsExactly("reserve", "authorize", "release", "update:CANCELLED");
    }

    @Test
    void anExpiredHoldAtCommitVoidsThePaymentThenReleases() {
        downstream.commitStatus = "EXPIRED";

        assertThat(stub("1001").fulfill(ORDER)).isEqualTo(Outcome.CANCELLED);

        // Compensations run in reverse order of the steps they undo.
        assertThat(downstream.calls)
                .containsExactly("reserve", "authorize", "commit", "void", "release", "update:CANCELLED");
    }

    @Test
    void aSecondStartForTheSameOrderIsRefused() {
        assertThat(stub("1001").fulfill(ORDER)).isEqualTo(Outcome.CONFIRMED);

        assertThatThrownBy(() -> WorkflowClient.start(stub("1001")::fulfill, ORDER))
                .isInstanceOf(WorkflowExecutionAlreadyStarted.class);
        assertThat(downstream.calls.stream().filter("reserve"::equals)).hasSize(1);
    }
}
