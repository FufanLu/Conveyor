package dev.conveyor.inventory.infrastructure.persistence;

import dev.conveyor.inventory.application.DuplicateReservationKeyException;
import dev.conveyor.inventory.application.ReservationStore;
import dev.conveyor.inventory.domain.Allocation;
import dev.conveyor.inventory.domain.Reservation;
import dev.conveyor.inventory.domain.ReservationStatus;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
class JdbcReservationStore implements ReservationStore {

    private final JdbcClient jdbc;
    private final NamedParameterJdbcTemplate named;
    private final TransactionTemplate tx;

    JdbcReservationStore(JdbcClient jdbc, NamedParameterJdbcTemplate named, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.named = named;
        this.tx = tx;
    }

    @Override
    public Optional<Reservation> findByKey(String idempotencyKey) {
        return jdbc.sql("SELECT reservation_id FROM reservation WHERE idempotency_key = :key")
                .param("key", idempotencyKey)
                .query(UUID.class)
                .optional()
                .flatMap(this::find);
    }

    @Override
    public Optional<Reservation> find(UUID id) {
        record Row(String key, String orderId, String status, Instant expiresAt) {}
        Optional<Row> row = jdbc.sql(
                        "SELECT idempotency_key, order_id, status, expires_at FROM reservation WHERE reservation_id = :id")
                .param("id", id)
                .query((rs, n) -> new Row(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getTimestamp(4).toInstant()))
                .optional();
        return row.map(r -> new Reservation(
                id,
                r.key(),
                r.orderId(),
                ReservationStatus.valueOf(r.status()),
                allocations(id),
                List.of(),
                r.expiresAt()));
    }

    private List<Allocation> allocations(UUID id) {
        return jdbc.sql(
                        "SELECT sku, fc_id, quantity FROM reservation_line WHERE reservation_id = :id ORDER BY sku, fc_id")
                .param("id", id)
                .query((rs, n) -> new Allocation(rs.getString(1), rs.getString(2), rs.getInt(3)))
                .list();
    }

    @Override
    public void insert(Reservation r) {
        try {
            tx.executeWithoutResult(status -> {
                jdbc.sql("INSERT INTO reservation (reservation_id, idempotency_key, order_id, status, expires_at)"
                                + " VALUES (:id, :key, :order, :status, :expires)")
                        .param("id", r.id())
                        .param("key", r.idempotencyKey())
                        .param("order", r.orderId())
                        .param("status", r.status().name())
                        .param("expires", Timestamp.from(r.expiresAt()))
                        .update();
                if (!r.allocations().isEmpty()) {
                    List<SqlParameterSource> lines = new ArrayList<>();
                    for (Allocation a : r.allocations()) {
                        lines.add(new MapSqlParameterSource()
                                .addValue("id", r.id())
                                .addValue("sku", a.sku())
                                .addValue("fc", a.fcId())
                                .addValue("qty", a.quantity()));
                    }
                    named.batchUpdate(
                            "INSERT INTO reservation_line (reservation_id, sku, fc_id, quantity) VALUES (:id, :sku, :fc, :qty)",
                            lines.toArray(SqlParameterSource[]::new));
                }
            });
        } catch (DuplicateKeyException e) {
            throw new DuplicateReservationKeyException(r.idempotencyKey(), e);
        }
    }

    @Override
    public Transition transition(UUID id, Set<ReservationStatus> from, ReservationStatus to) {
        int changed = jdbc.sql("UPDATE reservation SET status = :to, updated_at = now()"
                        + " WHERE reservation_id = :id AND status IN (:from)")
                .param("to", to.name())
                .param("id", id)
                .param("from", from.stream().map(Enum::name).toList())
                .update();
        Reservation now = find(id).orElseThrow(() -> new IllegalArgumentException("no reservation " + id));
        return new Transition(now, changed == 1);
    }

    @Override
    public List<Reservation> expireDue(Instant now, int limit) {
        // SKIP LOCKED: several instances can sweep at once without blocking on each other's rows.
        List<UUID> ids = jdbc.sql("UPDATE reservation SET status = 'EXPIRED', updated_at = now()"
                        + " WHERE reservation_id IN (SELECT reservation_id FROM reservation"
                        + "   WHERE status = 'HELD' AND expires_at < :now ORDER BY expires_at LIMIT :limit"
                        + "   FOR UPDATE SKIP LOCKED)"
                        + " RETURNING reservation_id")
                .param("now", Timestamp.from(now))
                .param("limit", limit)
                .query(UUID.class)
                .list();
        return ids.stream().map(id -> find(id).orElseThrow()).toList();
    }

    @Override
    public Map<String, Long> truth(String sku, List<String> fcIds) {
        Map<String, Long> out = new HashMap<>();
        fcIds.forEach(fc -> out.put(fc, 0L));
        named.query(
                "SELECT s.fc_id, s.on_hand - COALESCE((SELECT sum(l.quantity) FROM reservation_line l"
                        + "   JOIN reservation r ON r.reservation_id = l.reservation_id"
                        + "   WHERE l.sku = s.sku AND l.fc_id = s.fc_id AND r.status IN ('HELD', 'COMMITTED')), 0)"
                        + " FROM stock s WHERE s.sku = :sku AND s.fc_id IN (:fcs)",
                Map.of("sku", sku, "fcs", fcIds),
                rs -> {
                    out.put(rs.getString(1), rs.getLong(2));
                });
        return out;
    }

    @Override
    public Set<Allocation> touchedSince(Instant since) {
        return new LinkedHashSet<>(jdbc.sql("SELECT DISTINCT l.sku, l.fc_id FROM reservation_line l"
                        + " JOIN reservation r ON r.reservation_id = l.reservation_id WHERE r.updated_at > :since")
                .param("since", Timestamp.from(since))
                .query((rs, n) -> new Allocation(rs.getString(1), rs.getString(2), 1))
                .list());
    }

    @Override
    public Map<String, Integer> onHand(String sku) {
        Map<String, Integer> out = new HashMap<>();
        named.query("SELECT fc_id, on_hand FROM stock WHERE sku = :sku", Map.of("sku", sku), rs -> {
            out.put(rs.getString(1), rs.getInt(2));
        });
        return out;
    }
}
