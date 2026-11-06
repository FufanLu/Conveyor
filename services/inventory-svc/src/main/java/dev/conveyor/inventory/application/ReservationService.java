package dev.conveyor.inventory.application;

import dev.conveyor.inventory.domain.Allocation;
import dev.conveyor.inventory.domain.FulfillmentCenter;
import dev.conveyor.inventory.domain.Line;
import dev.conveyor.inventory.domain.Reservation;
import dev.conveyor.inventory.domain.ReservationStatus;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reserve / Commit / Release / Expire, as driven by the order saga.
 *
 * <p>Order of operations in Reserve: claim every line in the gate (Redis), then insert the
 * reservation (Postgres). If anything fails after a claim, the claim is given back. If the
 * process dies in between, the gate is left lower than the truth (units claimed, no row), which
 * can undersell but never oversell; the reconciler finds and repairs it.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    static final int MAX_LINES = 50;
    static final int MAX_QUANTITY = 100;
    static final Duration MIN_TTL = Duration.ofMinutes(1);
    static final Duration MAX_TTL = Duration.ofMinutes(30);
    static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
    private static final Pattern SKU = Pattern.compile("B0[0-9A-F]{8}");

    private final StockGate gate;
    private final ReservationStore store;
    private final FulfillmentCenters fcs;
    private final Clock clock;

    public ReservationService(StockGate gate, ReservationStore store, FulfillmentCenters fcs, Clock clock) {
        this.gate = gate;
        this.store = store;
        this.fcs = fcs;
        this.clock = clock;
    }

    public Reservation reserve(ReserveCommand command) {
        validate(command);
        Optional<Reservation> previous = store.findByKey(command.idempotencyKey());
        if (previous.isPresent()) {
            return previous.get(); // a retry of a call that already decided
        }
        List<FulfillmentCenter> order = FulfillmentCenter.preferenceFor(command.shipToRegion(), fcs.all());
        List<Allocation> claimed = new ArrayList<>();
        List<Line> shortfalls = new ArrayList<>();
        try {
            for (Line line : command.lines()) {
                Optional<List<Allocation>> allocation = gate.claim(line.sku(), line.quantity(), order);
                if (allocation.isPresent()) {
                    claimed.addAll(allocation.get());
                } else {
                    shortfalls.add(line);
                }
            }
        } catch (RuntimeException e) {
            gate.release(claimed);
            throw e;
        }
        Duration ttl = clamp(command.holdTtl());
        Reservation reservation;
        if (shortfalls.isEmpty()) {
            reservation = new Reservation(
                    UUID.randomUUID(),
                    command.idempotencyKey(),
                    command.orderId(),
                    ReservationStatus.HELD,
                    claimed,
                    List.of(),
                    clock.instant().plus(ttl));
        } else {
            gate.release(claimed); // all-or-nothing per reservation
            reservation = new Reservation(
                    UUID.randomUUID(),
                    command.idempotencyKey(),
                    command.orderId(),
                    ReservationStatus.REJECTED,
                    List.of(),
                    shortfalls,
                    clock.instant());
        }
        try {
            store.insert(reservation);
            return reservation;
        } catch (DuplicateReservationKeyException race) {
            // A concurrent call with the same key won; give our claims back and return its result.
            if (reservation.status() == ReservationStatus.HELD) {
                gate.release(claimed);
            }
            return store.findByKey(command.idempotencyKey()).orElseThrow();
        } catch (RuntimeException e) {
            if (reservation.status() == ReservationStatus.HELD) {
                gate.release(claimed);
            }
            throw e;
        }
    }

    /** Payment captured: the hold becomes a pick obligation. */
    public Reservation commit(UUID id) {
        return store.transition(id, EnumSet.of(ReservationStatus.HELD), ReservationStatus.COMMITTED)
                .reservation();
    }

    /** Compensation or cancellation: units go back to sellable, exactly once. */
    public Reservation release(UUID id) {
        ReservationStore.Transition t = store.transition(
                id, EnumSet.of(ReservationStatus.HELD, ReservationStatus.COMMITTED), ReservationStatus.RELEASED);
        if (t.changed()) {
            gate.release(t.reservation().allocations());
        }
        return t.reservation();
    }

    /** Called periodically: overdue holds go back to sellable. */
    public int expireDue(int limit) {
        List<Reservation> expired = store.expireDue(clock.instant(), limit);
        for (Reservation r : expired) {
            gate.release(r.allocations());
        }
        if (!expired.isEmpty()) {
            log.info("expired {} reservations", expired.size());
        }
        return expired.size();
    }

    /** Available units per SKU over every FC (any FC can ship anywhere, the region only orders them). */
    public Map<String, Long> availability(List<String> skus) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (String sku : skus) {
            result.put(
                    sku,
                    gate.available(sku, fcs.all()).values().stream()
                            .mapToLong(Long::longValue)
                            .sum());
        }
        return result;
    }

    public Map<String, Long> availabilityByFc(String sku) {
        return gate.available(sku, fcs.all());
    }

    private static Duration clamp(Duration ttl) {
        if (ttl == null || ttl.isZero()) {
            return DEFAULT_TTL;
        }
        if (ttl.compareTo(MIN_TTL) < 0) {
            return MIN_TTL;
        }
        return ttl.compareTo(MAX_TTL) > 0 ? MAX_TTL : ttl;
    }

    private static void validate(ReserveCommand c) {
        if (c.idempotencyKey() == null
                || c.idempotencyKey().isBlank()
                || c.idempotencyKey().length() > 128) {
            throw new InvalidReservationException("idempotency_key is required (at most 128 characters)");
        }
        if (c.orderId() == null || c.orderId().isBlank()) {
            throw new InvalidReservationException("order_id is required");
        }
        if (c.lines() == null || c.lines().isEmpty() || c.lines().size() > MAX_LINES) {
            throw new InvalidReservationException("a reservation has 1 to " + MAX_LINES + " lines");
        }
        Set<String> skus = new HashSet<>();
        for (Line line : c.lines()) {
            if (line.sku() == null || !SKU.matcher(line.sku()).matches()) {
                throw new InvalidReservationException("bad SKU " + line.sku());
            }
            if (line.quantity() < 1 || line.quantity() > MAX_QUANTITY) {
                throw new InvalidReservationException("quantity per line is 1 to " + MAX_QUANTITY);
            }
            if (!skus.add(line.sku())) {
                throw new InvalidReservationException("SKU " + line.sku() + " appears twice");
            }
        }
    }
}
