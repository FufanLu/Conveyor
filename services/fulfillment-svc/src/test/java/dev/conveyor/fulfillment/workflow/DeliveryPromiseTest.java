package dev.conveyor.fulfillment.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import dev.conveyor.fulfillment.workflow.FulfillmentModel.Allocation;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeliveryPromiseTest {

    private static final Instant NOW = Instant.parse("2026-11-06T15:30:00Z");

    @Test
    void allLocalIsTwoDaysAllElseFour() {
        var local = List.of(new Allocation("A", "east-1", 1), new Allocation("B", "east-2", 1));
        var split = List.of(new Allocation("A", "east-1", 1), new Allocation("A", "west-2", 1));

        assertThat(DeliveryPromise.estimate(local, "us-east", NOW)).isEqualTo("2026-11-08T21:00:00Z");
        assertThat(DeliveryPromise.estimate(split, "us-east", NOW)).isEqualTo("2026-11-10T21:00:00Z");
        assertThat(DeliveryPromise.regionOf("central-1")).isEqualTo("us-central");
    }
}
