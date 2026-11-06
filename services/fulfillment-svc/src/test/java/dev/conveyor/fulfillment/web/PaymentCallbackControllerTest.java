package dev.conveyor.fulfillment.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Callbacks and queries for orders whose saga does not exist answer 410 / 404, not 500. */
class PaymentCallbackControllerTest {

    private TestWorkflowEnvironment env;
    private PaymentCallbackController controller;

    @BeforeEach
    void setUp() {
        env = TestWorkflowEnvironment.newInstance();
        env.start();
        controller = new PaymentCallbackController(env.getWorkflowClient());
    }

    @AfterEach
    void tearDown() {
        env.close();
    }

    @Test
    void aLateCallbackForAFinishedOrUnknownSagaIsGone() {
        assertThat(controller
                        .callback(new PaymentCallbackController.Callback("404404", "auth-1", "AUTHORIZED"))
                        .getStatusCode()
                        .value())
                .isEqualTo(410);
    }

    @Test
    void aSagaThatHasNotStartedYetIsNotFound() {
        assertThat(controller.stage("404404").getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void aCallbackWithoutOrderIdIsABadRequest() {
        assertThat(controller
                        .callback(new PaymentCallbackController.Callback(null, "a", "AUTHORIZED"))
                        .getStatusCode()
                        .value())
                .isEqualTo(400);
    }
}
