package dev.conveyor.tracking.api;

import dev.conveyor.tracking.domain.Shipment;
import dev.conveyor.tracking.domain.TrackingRecord;
import java.time.Instant;

final class TrackingView {

    private TrackingView() {}

    record Report(
            long seq,
            String kind,
            Instant recordedAt,
            Double lat,
            Double lon,
            Float speed,
            String scanType,
            String location) {
        static Report of(TrackingRecord r) {
            return new Report(
                    r.seq(),
                    r.isPosition() ? "position" : "scan",
                    r.recordedAt(),
                    r.lat(),
                    r.lon(),
                    r.speed(),
                    r.scanType(),
                    r.location());
        }
    }

    record ShipmentView(
            String shipmentId,
            String status,
            String region,
            Instant promisedDelivery,
            Double lat,
            Double lon,
            Instant positionAt,
            long lastSeq) {
        static ShipmentView of(Shipment s) {
            return new ShipmentView(
                    s.shipmentId(),
                    s.status(),
                    s.region(),
                    s.promisedDelivery(),
                    s.lat(),
                    s.lon(),
                    s.positionAt(),
                    Math.max(s.positionSeq(), s.statusSeq()));
        }
    }

    /** One dot on the ops map. */
    record Dot(String shipmentId, double lat, double lon, long seq) {}
}
