package dev.conveyor.tracking.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.conveyor.tracking.domain.TrackingRecord;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpsMapUnitTest {

    private static TrackingRecord at(String id, long seq, double lat) {
        return new TrackingRecord(id, seq, "c", Instant.EPOCH, lat, 0.0, 1f, null, null);
    }

    @Test
    void aFrameKeepsTheNewestPositionPerShipmentEvenIfReportsArriveOutOfOrder() {
        var dots = OpsMapController.latestPerShipment(
                List.of(at("A", 1, 1), at("B", 1, 10), at("A", 3, 3), at("A", 2, 2), at("B", 2, 20)));

        assertThat(dots)
                .extracting(TrackingView.Dot::shipmentId, TrackingView.Dot::seq, TrackingView.Dot::lat)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("A", 3L, 3.0),
                        org.assertj.core.groups.Tuple.tuple("B", 2L, 20.0));
    }
}
