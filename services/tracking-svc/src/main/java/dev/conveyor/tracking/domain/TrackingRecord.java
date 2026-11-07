package dev.conveyor.tracking.domain;

import com.google.protobuf.Timestamp;
import dev.conveyor.proto.tracking.v1.Position;
import dev.conveyor.proto.tracking.v1.Scan;
import dev.conveyor.proto.tracking.v1.ScanType;
import dev.conveyor.proto.tracking.v1.TrackingEvent;
import java.time.Instant;

/**
 * One courier report, as the service handles it. Exactly one of (lat, lon) or scanType is set.
 *
 * @param scanType PICKED, LOADED, OUT_FOR_DELIVERY, DELIVERED or DELIVERY_FAILED; null for a position
 */
public record TrackingRecord(
        String shipmentId,
        long seq,
        String courierId,
        Instant recordedAt,
        Double lat,
        Double lon,
        Float speed,
        String scanType,
        String location) {

    public boolean isPosition() {
        return scanType == null;
    }

    public TrackingEvent toProto() {
        Instant at = recordedAt;
        TrackingEvent.Builder b = TrackingEvent.newBuilder()
                .setShipmentId(shipmentId)
                .setSeq(seq)
                .setCourierId(courierId)
                .setRecordedAt(
                        Timestamp.newBuilder().setSeconds(at.getEpochSecond()).setNanos(at.getNano()));
        if (isPosition()) {
            b.setPosition(Position.newBuilder().setLat(lat).setLon(lon).setSpeed(speed == null ? 0f : speed));
        } else {
            b.setScan(Scan.newBuilder()
                    .setType(ScanType.valueOf("SCAN_TYPE_" + scanType))
                    .setLocation(location == null ? "" : location));
        }
        return b.build();
    }

    public static TrackingRecord fromProto(TrackingEvent e) {
        Timestamp ts = e.getRecordedAt();
        Instant at = Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
        if (e.getBodyCase() == TrackingEvent.BodyCase.POSITION) {
            Position p = e.getPosition();
            return new TrackingRecord(
                    e.getShipmentId(),
                    e.getSeq(),
                    e.getCourierId(),
                    at,
                    p.getLat(),
                    p.getLon(),
                    p.getSpeed(),
                    null,
                    null);
        }
        Scan s = e.getScan();
        String type = s.getType().name().substring("SCAN_TYPE_".length());
        return new TrackingRecord(
                e.getShipmentId(),
                e.getSeq(),
                e.getCourierId(),
                at,
                null,
                null,
                null,
                type,
                s.getLocation().isEmpty() ? null : s.getLocation());
    }
}
