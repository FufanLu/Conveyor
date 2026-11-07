package dev.conveyor.tracking.ingest;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** What a handheld posts: up to 500 reports, each a position or a scan. */
public record PingBatch(List<Report> events) {

    public static final int MAX_EVENTS = 500;
    static final Set<String> SCAN_TYPES =
            Set.of("PICKED", "LOADED", "OUT_FOR_DELIVERY", "DELIVERED", "DELIVERY_FAILED");

    public record Report(String shipmentId, Long seq, Instant recordedAt, PositionBody position, ScanBody scan) {}

    public record PositionBody(Double lat, Double lon, Float speed) {}

    public record ScanBody(String type, String location) {}

    /** Null if valid, else what is wrong with the first bad report. */
    String problem() {
        if (events == null || events.isEmpty()) {
            return "events must hold 1-" + MAX_EVENTS + " reports";
        }
        if (events.size() > MAX_EVENTS) {
            return "at most " + MAX_EVENTS + " reports per batch, got " + events.size();
        }
        for (int i = 0; i < events.size(); i++) {
            Report r = events.get(i);
            String at = "events[" + i + "]: ";
            if (r == null || r.shipmentId() == null || !r.shipmentId().matches("[A-Za-z0-9_-]{1,40}")) {
                return at + "shipmentId must be 1-40 of A-Z a-z 0-9 _ -";
            }
            if (r.seq() == null || r.seq() < 1) {
                return at + "seq must be a positive number";
            }
            if (r.recordedAt() == null) {
                return at + "recordedAt is required";
            }
            if ((r.position() == null) == (r.scan() == null)) {
                return at + "exactly one of position or scan";
            }
            if (r.position() != null) {
                PositionBody p = r.position();
                if (p.lat() == null || p.lon() == null || Math.abs(p.lat()) > 90 || Math.abs(p.lon()) > 180) {
                    return at + "position needs lat in [-90, 90] and lon in [-180, 180]";
                }
            } else if (r.scan().type() == null || !SCAN_TYPES.contains(r.scan().type())) {
                return at + "scan.type must be one of " + SCAN_TYPES;
            }
        }
        return null;
    }
}
