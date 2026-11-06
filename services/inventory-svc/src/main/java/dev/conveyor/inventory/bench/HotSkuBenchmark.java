package dev.conveyor.inventory.bench;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * One hot SKU, many buyers: how fast can each strategy sell S units without overselling?
 *
 * <p>Every attempt claims one unit and records a reservation row, atomically where the strategy
 * allows it:
 *
 * <ul>
 *   <li>{@code ROW_LOCK}: SELECT ... FOR UPDATE on the SKU's row, UPDATE reserved, INSERT, COMMIT.
 *   <li>{@code CONDITIONAL}: UPDATE ... SET reserved = reserved + 1 WHERE reserved &lt; on_hand,
 *       INSERT, COMMIT. One statement fewer, but the row lock is still held until COMMIT.
 *   <li>{@code BUCKETS}: the SKU's stock split over N rows; a conditional UPDATE on a random
 *       bucket (others tried if it is empty), INSERT, COMMIT. N locks instead of one.
 *   <li>{@code REDIS}: a Lua decrement in Redis (no Postgres row is contended at all), then the
 *       INSERT as its own transaction; on failure the unit is given back. This is what
 *       inventory-svc does.
 * </ul>
 *
 * Run with the jar's extracted classpath, against a throwaway Postgres database and Redis:
 *
 * <pre>
 * java -cp 'inventory-svc.jar:lib/*' dev.conveyor.inventory.bench.HotSkuBenchmark \
 *   jdbc=jdbc:postgresql://127.0.0.1:5432/bench user=bench password=bench redis=redis://127.0.0.1:6379 \
 *   strategy=REDIS stock=10000 attempts=20000 concurrency=64 pool=32 buckets=16
 * </pre>
 */
public final class HotSkuBenchmark {

    enum Strategy {
        ROW_LOCK,
        CONDITIONAL,
        BUCKETS,
        REDIS
    }

    private static final String SKU = "HOT";
    private static final String REDIS_KEY = "inv:bench:{HOT}";
    private static final String CLAIM_LUA =
            "local v = tonumber(redis.call('GET', KEYS[1]) or '0') if v >= 1 then redis.call('DECRBY', KEYS[1], 1) return 1 end return 0";

    private HotSkuBenchmark() {}

    public static void main(String[] args) throws Exception {
        Map<String, String> a = new HashMap<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            a.put(arg.substring(0, eq), arg.substring(eq + 1));
        }
        Strategy strategy = Strategy.valueOf(a.get("strategy"));
        int stock = Integer.parseInt(a.getOrDefault("stock", "10000"));
        int attempts = Integer.parseInt(a.getOrDefault("attempts", Integer.toString(stock * 2)));
        int concurrency = Integer.parseInt(a.getOrDefault("concurrency", "64"));
        int pool = Integer.parseInt(a.getOrDefault("pool", "32"));
        int buckets = Integer.parseInt(a.getOrDefault("buckets", "16"));

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(a.get("jdbc"));
        config.setUsername(a.getOrDefault("user", "bench"));
        config.setPassword(a.getOrDefault("password", "bench"));
        config.setMaximumPoolSize(pool);
        config.setConnectionTimeout(30_000);
        try (HikariDataSource db = new HikariDataSource(config);
                RedisClient redisClient = RedisClient.create(a.getOrDefault("redis", "redis://127.0.0.1:6379"));
                StatefulRedisConnection<String, String> redisConnection = redisClient.connect()) {
            RedisCommands<String, String> redis = redisConnection.sync();
            setUp(db, redis, stock, buckets);

            long[] latencies = new long[attempts];
            AtomicInteger next = new AtomicInteger();
            AtomicInteger sold = new AtomicInteger();
            AtomicInteger rejected = new AtomicInteger();
            AtomicInteger errors = new AtomicInteger();
            AtomicLong lastSaleNanos = new AtomicLong();
            long start = System.nanoTime();
            List<Future<Void>> workers = new ArrayList<>();
            try (ExecutorService buyers = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int w = 0; w < concurrency; w++) {
                    workers.add(buyers.submit(() -> {
                        int i;
                        while ((i = next.getAndIncrement()) < attempts) {
                            long t0 = System.nanoTime();
                            try {
                                boolean ok = attempt(strategy, db, redis, buckets);
                                if (ok) {
                                    sold.incrementAndGet();
                                    lastSaleNanos.accumulateAndGet(System.nanoTime(), Math::max);
                                } else {
                                    rejected.incrementAndGet();
                                }
                            } catch (Exception e) {
                                if (errors.incrementAndGet() <= 3) {
                                    System.err.println("attempt failed: " + e);
                                }
                            }
                            latencies[i] = System.nanoTime() - t0;
                        }
                        return null;
                    }));
                }
            }
            long end = System.nanoTime();
            for (Future<Void> worker : workers) {
                worker.get(); // surfaces anything a worker threw outside an attempt
            }
            report(
                    strategy,
                    stock,
                    attempts,
                    concurrency,
                    pool,
                    buckets,
                    latencies,
                    sold.get(),
                    rejected.get(),
                    errors.get(),
                    (lastSaleNanos.get() - start) / 1e9,
                    (end - start) / 1e9);
            verify(strategy, db, redis, stock, sold.get());
        }
    }

    private static boolean attempt(Strategy s, HikariDataSource db, RedisCommands<String, String> redis, int buckets)
            throws SQLException {
        return switch (s) {
            case ROW_LOCK ->
                inTransaction(db, c -> {
                    try (PreparedStatement select =
                            c.prepareStatement("SELECT on_hand, reserved FROM bench.stock WHERE sku = ? FOR UPDATE")) {
                        select.setString(1, SKU);
                        try (ResultSet rs = select.executeQuery()) {
                            rs.next();
                            if (rs.getInt(2) >= rs.getInt(1)) {
                                return false;
                            }
                        }
                    }
                    try (PreparedStatement update =
                            c.prepareStatement("UPDATE bench.stock SET reserved = reserved + 1 WHERE sku = ?")) {
                        update.setString(1, SKU);
                        update.executeUpdate();
                    }
                    insertReservation(c);
                    return true;
                });
            case CONDITIONAL ->
                inTransaction(db, c -> {
                    try (PreparedStatement update = c.prepareStatement(
                            "UPDATE bench.stock SET reserved = reserved + 1 WHERE sku = ? AND reserved < on_hand")) {
                        update.setString(1, SKU);
                        if (update.executeUpdate() == 0) {
                            return false;
                        }
                    }
                    insertReservation(c);
                    return true;
                });
            case BUCKETS ->
                inTransaction(db, c -> {
                    int first = ThreadLocalRandom.current().nextInt(buckets);
                    try (PreparedStatement update =
                            c.prepareStatement("UPDATE bench.stock_bucket SET reserved = reserved + 1"
                                    + " WHERE sku = ? AND bucket = ? AND reserved < on_hand")) {
                        for (int k = 0; k < buckets; k++) {
                            update.setString(1, SKU);
                            update.setInt(2, (first + k) % buckets);
                            if (update.executeUpdate() == 1) {
                                insertReservation(c);
                                return true;
                            }
                        }
                    }
                    return false;
                });
            case REDIS -> {
                Long claimed = redis.eval(CLAIM_LUA, ScriptOutputType.INTEGER, REDIS_KEY);
                if (claimed == null || claimed == 0) {
                    yield false;
                }
                try (Connection c = db.getConnection()) {
                    insertReservation(c); // autocommit: its own short transaction, no hot row
                    yield true;
                } catch (SQLException e) {
                    redis.incr(REDIS_KEY); // give the unit back
                    throw e;
                }
            }
        };
    }

    private static void insertReservation(Connection c) throws SQLException {
        try (PreparedStatement insert =
                c.prepareStatement("INSERT INTO bench.reservation (id, sku, quantity) VALUES (?, ?, 1)")) {
            insert.setObject(1, UUID.randomUUID());
            insert.setString(2, SKU);
            insert.executeUpdate();
        }
    }

    interface Work {
        boolean run(Connection c) throws SQLException;
    }

    private static boolean inTransaction(HikariDataSource db, Work work) throws SQLException {
        try (Connection c = db.getConnection()) {
            c.setAutoCommit(false);
            try {
                boolean ok = work.run(c);
                if (ok) {
                    c.commit();
                } else {
                    c.rollback();
                }
                return ok;
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        }
    }

    private static void setUp(HikariDataSource db, RedisCommands<String, String> redis, int stock, int buckets)
            throws SQLException {
        try (Connection c = db.getConnection();
                Statement st = c.createStatement()) {
            st.execute("CREATE SCHEMA IF NOT EXISTS bench");
            st.execute("DROP TABLE IF EXISTS bench.stock, bench.stock_bucket, bench.reservation");
            st.execute("CREATE TABLE bench.stock (sku text PRIMARY KEY, on_hand int NOT NULL, reserved int NOT NULL,"
                    + " CHECK (reserved <= on_hand))");
            st.execute(
                    "CREATE TABLE bench.stock_bucket (sku text, bucket int, on_hand int NOT NULL, reserved int NOT NULL,"
                            + " PRIMARY KEY (sku, bucket), CHECK (reserved <= on_hand))");
            st.execute("CREATE TABLE bench.reservation (id uuid PRIMARY KEY, sku text NOT NULL, quantity int NOT NULL,"
                    + " created_at timestamptz NOT NULL DEFAULT now())");
            st.execute("INSERT INTO bench.stock VALUES ('" + SKU + "', " + stock + ", 0)");
            for (int b = 0; b < buckets; b++) {
                int share = stock / buckets + (b < stock % buckets ? 1 : 0);
                st.execute("INSERT INTO bench.stock_bucket VALUES ('" + SKU + "', " + b + ", " + share + ", 0)");
            }
            st.execute("VACUUM ANALYZE bench.stock");
        }
        redis.set(REDIS_KEY, Integer.toString(stock));
    }

    private static void report(
            Strategy s,
            int stock,
            int attempts,
            int concurrency,
            int pool,
            int buckets,
            long[] lat,
            int sold,
            int rejected,
            int errors,
            double selloutSeconds,
            double totalSeconds) {
        long[] sorted = lat.clone();
        Arrays.sort(sorted);
        System.out.printf(
                "strategy=%s stock=%d attempts=%d concurrency=%d pool=%d%s%n",
                s, stock, attempts, concurrency, pool, s == Strategy.BUCKETS ? " buckets=" + buckets : "");
        System.out.printf(
                "sold=%d rejected=%d errors=%d  sold out after %.2f s  -> %.0f units/s  (all %d attempts in %.2f s)%n",
                sold, rejected, errors, selloutSeconds, sold / selloutSeconds, attempts, totalSeconds);
        System.out.printf(
                "attempt latency ms: p50 %.2f  p90 %.2f  p99 %.2f  max %.2f%n",
                pct(sorted, 50), pct(sorted, 90), pct(sorted, 99), sorted[sorted.length - 1] / 1e6);
    }

    private static double pct(long[] sorted, double p) {
        return sorted[(int) Math.min(sorted.length - 1, Math.ceil(p / 100 * sorted.length) - 1)] / 1e6;
    }

    private static void verify(
            Strategy s, HikariDataSource db, RedisCommands<String, String> redis, int stock, int sold)
            throws SQLException {
        long rows;
        long reserved;
        try (Connection c = db.getConnection();
                Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM bench.reservation")) {
                rs.next();
                rows = rs.getLong(1);
            }
            String sql = switch (s) {
                case ROW_LOCK, CONDITIONAL -> "SELECT reserved FROM bench.stock";
                case BUCKETS -> "SELECT sum(reserved) FROM bench.stock_bucket";
                case REDIS -> "SELECT " + (stock - Long.parseLong(redis.get(REDIS_KEY)));
            };
            try (ResultSet rs = st.executeQuery(sql)) {
                rs.next();
                reserved = rs.getLong(1);
            }
        }
        boolean ok = rows == sold && reserved == sold && sold <= stock;
        System.out.printf(
                "check: reservation rows %d, units claimed %d, stock %d -> %s%n",
                rows, reserved, stock, ok ? "no oversell, counters agree" : "MISMATCH");
        if (!ok) {
            System.exit(2);
        }
    }
}
