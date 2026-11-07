package dev.conveyor.order.infrastructure.persistence;

import dev.conveyor.order.application.OrderStore;
import dev.conveyor.order.application.Placement;
import dev.conveyor.order.domain.Order;
import dev.conveyor.order.domain.OrderId;
import dev.conveyor.order.domain.OrderLine;
import dev.conveyor.order.domain.OrderStatus;
import dev.conveyor.order.domain.Shards;
import dev.conveyor.order.infrastructure.shard.ShardGroup;
import dev.conveyor.order.infrastructure.shard.ShardRouter;
import dev.conveyor.platform.web.RequestIds;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Repository;

/** Orders, lines, idempotency keys and outbox rows, routed to the customer's shard. */
@Repository
class JdbcOrderStore implements OrderStore {

    static final String AGGREGATE = "orders"; // -> Kafka topic orders.events (outbox router)

    private final ShardRouter router;
    private final Clock clock;

    private final ObjectProvider<Tracer> tracer;

    JdbcOrderStore(ShardRouter router, Clock clock, ObjectProvider<Tracer> tracer) {
        this.router = router;
        this.clock = clock;
        this.tracer = tracer;
    }

    /**
     * The current span as a W3C traceparent, stored with the outbox row. Debezium is the Kafka
     * producer, not this service, so the trace would otherwise end at the database.
     */
    private @Nullable String traceparent() {
        Tracer t = tracer.getIfAvailable();
        Span span = t == null ? null : t.currentSpan();
        if (span == null) {
            return null;
        }
        TraceContext c = span.context();
        return "00-" + c.traceId() + "-" + c.spanId() + "-" + (Boolean.TRUE.equals(c.sampled()) ? "01" : "00");
    }

    @Override
    public Placement placeIdempotently(Order order, String idempotencyKey, byte[] requestHash) {
        int shard = order.id().shard();
        ShardGroup db = router.group(shard);
        return db.tx().execute(tx -> {
            int claimed = db.jdbc()
                    .sql("INSERT INTO idempotency_key (user_id, idem_key, request_hash, order_id)"
                            + " VALUES (:user, :key, :hash, :order) ON CONFLICT DO NOTHING")
                    .param("user", order.userId())
                    .param("key", idempotencyKey)
                    .param("hash", requestHash)
                    .param("order", order.id().value())
                    .update();
            if (claimed == 0) {
                return replay(db, order.userId(), idempotencyKey, requestHash);
            }
            insertOrder(db, shard, order);
            UUID eventId = RequestIds.uuidV7(clock);
            db.jdbc()
                    .sql("INSERT INTO outbox (id, aggregatetype, aggregateid, type, payload, traceparent)"
                            + " VALUES (:id, :aggregate, :order, 'OrderPlaced', :payload, :traceparent)")
                    .param("traceparent", traceparent())
                    .param("id", eventId)
                    .param("aggregate", AGGREGATE)
                    .param("order", order.id().toString())
                    .param("payload", OrderEvents.placed(order, eventId).toByteArray())
                    .update();
            return new Placement.Placed(order);
        });
    }

    private Placement replay(ShardGroup db, String userId, String key, byte[] requestHash) {
        Claim claim = db.jdbc()
                .sql("SELECT request_hash, order_id FROM idempotency_key WHERE user_id = :user AND idem_key = :key")
                .param("user", userId)
                .param("key", key)
                .query((rs, n) -> new Claim(rs.getBytes(1), rs.getLong(2)))
                .single();
        if (!MessageDigest.isEqual(claim.hash, requestHash)) {
            return new Placement.KeyReused();
        }
        return new Placement.Replayed(find(new OrderId(claim.orderId)).orElseThrow());
    }

    /** An existing idempotency claim: the request it was made for, and the order it created. */
    private static final class Claim {
        final byte[] hash;
        final long orderId;

        Claim(byte[] hash, long orderId) {
            this.hash = hash;
            this.orderId = orderId;
        }
    }

    private static void insertOrder(ShardGroup db, int shard, Order order) {
        String t = ShardRouter.suffix(shard);
        db.jdbc()
                .sql("INSERT INTO orders_" + t
                        + " (order_id, user_id, status, total_cents, currency, ship_to_region, version, placed_at, updated_at)"
                        + " VALUES (:id, :user, :status, :total, :currency, :region, :version, :at, :at)")
                .param("id", order.id().value())
                .param("user", order.userId())
                .param("status", order.status().name())
                .param("total", order.totalCents())
                .param("currency", order.currency())
                .param("region", order.shipToRegion())
                .param("version", order.version())
                .param("at", Timestamp.from(order.placedAt()))
                .update();
        List<SqlParameterSource> lines = new ArrayList<>();
        for (int i = 0; i < order.lines().size(); i++) {
            OrderLine line = order.lines().get(i);
            lines.add(new MapSqlParameterSource()
                    .addValue("id", order.id().value())
                    .addValue("no", i + 1)
                    .addValue("sku", line.sku())
                    .addValue("qty", line.quantity())
                    .addValue("price", line.unitPriceCents()));
        }
        db.batch()
                .batchUpdate(
                        "INSERT INTO order_line_" + t + " (order_id, line_no, sku, quantity, unit_price_cents)"
                                + " VALUES (:id, :no, :sku, :qty, :price)",
                        lines.toArray(SqlParameterSource[]::new));
    }

    @Override
    public StatusChange changeStatus(OrderId id, OrderStatus to, String reason, java.time.Instant promised) {
        ShardGroup db = router.group(id.shard());
        String t = ShardRouter.suffix(id.shard());
        return db.tx().execute(tx -> {
            int updated = db.jdbc()
                    .sql("UPDATE orders_" + t
                            + " SET status = :to, status_reason = :reason, promised_delivery = :promised,"
                            + " version = version + 1, updated_at = now() WHERE order_id = :id AND status = 'PLACED'")
                    .param("to", to.name())
                    .param("reason", reason)
                    .param("promised", promised == null ? null : Timestamp.from(promised))
                    .param("id", id.value())
                    .update();
            Optional<Order> now = find(id);
            if (now.isEmpty()) {
                return new StatusChange.NotFound();
            }
            Order order = now.get();
            if (updated == 0) {
                return order.status() == to ? new StatusChange.AlreadyThere(order) : new StatusChange.Conflict(order);
            }
            UUID eventId = RequestIds.uuidV7(clock);
            db.jdbc()
                    .sql("INSERT INTO outbox (id, aggregatetype, aggregateid, type, payload, traceparent)"
                            + " VALUES (:id, :aggregate, :order, 'OrderStatusChanged', :payload, :traceparent)")
                    .param("traceparent", traceparent())
                    .param("id", eventId)
                    .param("aggregate", AGGREGATE)
                    .param("order", id.toString())
                    .param(
                            "payload",
                            OrderEvents.statusChanged(order, eventId, clock.instant())
                                    .toByteArray())
                    .update();
            return new StatusChange.Changed(order);
        });
    }

    @Override
    public Optional<Order> find(OrderId id) {
        ShardGroup db = router.group(id.shard());
        String t = ShardRouter.suffix(id.shard());
        List<OrderRow> rows = db.jdbc()
                .sql("SELECT * FROM orders_" + t + " WHERE order_id = :id")
                .param("id", id.value())
                .query(OrderRow::map)
                .list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(withLines(db, t, rows).getFirst());
    }

    @Override
    public List<Order> listForUser(String userId, Optional<OrderId> before, int limit) {
        int shard = Shards.forUser(userId);
        ShardGroup db = router.group(shard);
        String t = ShardRouter.suffix(shard);
        List<OrderRow> rows = db.jdbc()
                .sql("SELECT * FROM orders_" + t + " WHERE user_id = :user"
                        + (before.isPresent() ? " AND order_id < :before" : "")
                        + " ORDER BY order_id DESC LIMIT :limit")
                .param("user", userId)
                .param("before", before.map(OrderId::value).orElse(Long.MAX_VALUE))
                .param("limit", limit)
                .query(OrderRow::map)
                .list();
        return rows.isEmpty() ? List.of() : withLines(db, t, rows);
    }

    private static List<Order> withLines(ShardGroup db, String t, List<OrderRow> rows) {
        Map<Long, List<OrderLine>> lines = new HashMap<>();
        db.batch()
                .query(
                        "SELECT order_id, sku, quantity, unit_price_cents FROM order_line_" + t
                                + " WHERE order_id IN (:ids) ORDER BY order_id, line_no",
                        Map.of("ids", rows.stream().map(OrderRow::orderId).toList()),
                        rs -> {
                            lines.computeIfAbsent(rs.getLong(1), k -> new ArrayList<>())
                                    .add(new OrderLine(rs.getString(2), rs.getInt(3), rs.getLong(4)));
                        });
        Map<Long, Order> orders = new LinkedHashMap<>();
        for (OrderRow r : rows) {
            orders.put(r.orderId(), r.toOrder(lines.getOrDefault(r.orderId(), List.of())));
        }
        return List.copyOf(orders.values());
    }

    private record OrderRow(
            long orderId,
            String userId,
            String status,
            long totalCents,
            String currency,
            String region,
            long version,
            java.time.Instant placedAt,
            String statusReason,
            java.time.Instant promisedDelivery) {

        static OrderRow map(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
            return new OrderRow(
                    rs.getLong("order_id"),
                    rs.getString("user_id"),
                    rs.getString("status"),
                    rs.getLong("total_cents"),
                    rs.getString("currency").trim(),
                    rs.getString("ship_to_region"),
                    rs.getLong("version"),
                    rs.getTimestamp("placed_at").toInstant(),
                    rs.getString("status_reason"),
                    rs.getTimestamp("promised_delivery") == null
                            ? null
                            : rs.getTimestamp("promised_delivery").toInstant());
        }

        Order toOrder(List<OrderLine> lines) {
            return new Order(
                    new OrderId(orderId),
                    userId,
                    OrderStatus.valueOf(status),
                    lines,
                    totalCents,
                    currency,
                    region,
                    version,
                    placedAt,
                    statusReason,
                    promisedDelivery);
        }
    }
}
