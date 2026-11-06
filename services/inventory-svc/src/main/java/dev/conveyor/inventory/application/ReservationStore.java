package dev.conveyor.inventory.application;

import dev.conveyor.inventory.domain.Allocation;
import dev.conveyor.inventory.domain.Reservation;
import dev.conveyor.inventory.domain.ReservationStatus;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Durable facts in Postgres: reservations and their lines, plus on-hand stock. */
public interface ReservationStore {

    Optional<Reservation> findByKey(String idempotencyKey);

    Optional<Reservation> find(UUID id);

    /** Inserts a reservation; throws DuplicateReservationKeyException if the key is taken. */
    void insert(Reservation reservation);

    /**
     * Moves the reservation to {@code to} if it is currently in one of {@code from}. Returns
     * the reservation as it is after the call and whether this call made the change.
     */
    Transition transition(UUID id, Set<ReservationStatus> from, ReservationStatus to);

    /** Atomically marks up to {@code limit} overdue HELD reservations EXPIRED and returns them. */
    List<Reservation> expireDue(Instant now, int limit);

    /** on_hand minus units held or committed, per FC id (FCs without a stock row count 0). */
    Map<String, Long> truth(String sku, List<String> fcIds);

    /** Physical units per FC id. */
    Map<String, Integer> onHand(String sku);

    /** (sku, fc) pairs whose reservations changed since {@code since}. */
    Set<Allocation> touchedSince(Instant since);

    record Transition(Reservation reservation, boolean changed) {}
}
