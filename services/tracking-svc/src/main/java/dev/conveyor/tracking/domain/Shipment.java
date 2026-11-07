package dev.conveyor.tracking.domain;

import java.time.Instant;

/**
 * The latest known state of one shipment (one per order in this model; shipment id = order id).
 * Status and position are tracked separately, each with the seq of the report that set it, so a
 * late scan cannot be overwritten by a newer position and vice versa.
 */
public record Shipment(
        String shipmentId,
        String userId,
        String region,
        String status,
        Instant promisedDelivery,
        long statusSeq,
        Double lat,
        Double lon,
        long positionSeq,
        Instant positionAt,
        String courierId) {

    /** Lifecycle before the courier reports anything: ORDERED, then READY (order confirmed) or CANCELLED. */
    public static final String ORDERED = "ORDERED";

    public static final String READY = "READY";
    public static final String CANCELLED = "CANCELLED";
}
