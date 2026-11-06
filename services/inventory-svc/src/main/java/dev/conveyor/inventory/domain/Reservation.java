package dev.conveyor.inventory.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Reservation(
        UUID id,
        String idempotencyKey,
        String orderId,
        ReservationStatus status,
        List<Allocation> allocations,
        List<Line> shortfalls,
        Instant expiresAt) {

    public Reservation {
        allocations = List.copyOf(allocations);
        shortfalls = List.copyOf(shortfalls);
    }
}
