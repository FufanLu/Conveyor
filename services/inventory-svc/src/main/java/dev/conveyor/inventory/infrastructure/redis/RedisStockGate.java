package dev.conveyor.inventory.infrastructure.redis;

import dev.conveyor.inventory.application.ReservationStore;
import dev.conveyor.inventory.application.StockGate;
import dev.conveyor.inventory.domain.Allocation;
import dev.conveyor.inventory.domain.FulfillmentCenter;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Available units per (SKU, FC) as Redis counters, claimed by one Lua script per SKU line.
 *
 * <p>Keys are {@code inv:a:{SKU}:FC}. The braces are a Redis Cluster hash tag: every FC of a
 * SKU lands in the same slot, so the whole multi-FC allocation for a line stays one atomic
 * script even after moving to a cluster. Redis runs scripts one at a time, so concurrent
 * buyers of one SKU are serialized in memory, in microseconds, instead of queueing on a
 * Postgres row lock for the length of a transaction.
 *
 * <p>Counters are created lazily from the Postgres truth (on_hand minus active holds) the
 * first time a SKU is touched, with SET NX so concurrent initializers cannot both win.
 */
@Component
public class RedisStockGate implements StockGate {

    static final String PREFIX = "inv:a:";

    // KEYS: one counter per FC, in preference order. ARGV[1]: quantity.
    // Returns {-1, i, ...} if counter i is missing; {0, total} if the FCs together hold fewer
    // than quantity units (nothing claimed); {1, taken_1, ..., taken_n} on success.
    private static final RedisScript<List<Long>> CLAIM = listOfLongs("""
            local need = tonumber(ARGV[1])
            local missing = {}
            local avail = {}
            local total = 0
            for i, key in ipairs(KEYS) do
              local v = redis.call('GET', key)
              if v then
                avail[i] = tonumber(v)
                total = total + avail[i]
              else
                missing[#missing + 1] = i
              end
            end
            if #missing > 0 then
              local out = {-1}
              for _, i in ipairs(missing) do out[#out + 1] = i end
              return out
            end
            if total < need then
              return {0, total}
            end
            local out = {1}
            for i, key in ipairs(KEYS) do
              local take = math.min(avail[i], need)
              if take > 0 then
                redis.call('DECRBY', key, take)
              end
              out[i + 1] = take
              need = need - take
            end
            return out
            """);

    private final StringRedisTemplate redis;
    private final ReservationStore truth;
    private final Counter initialized;
    private final ConcurrentHashMap<String, CompletableFuture<Void>> initializing = new ConcurrentHashMap<>();

    public RedisStockGate(StringRedisTemplate redis, ReservationStore truth, MeterRegistry registry) {
        this.redis = redis;
        this.truth = truth;
        this.initialized =
                Counter.builder("inventory.gate.counters.initialized").register(registry);
    }

    /** Redis returns Lua tables as lists of integers; RedisScript.of cannot express List<Long>. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static RedisScript<List<Long>> listOfLongs(String lua) {
        return (RedisScript) RedisScript.of(lua, List.class);
    }

    static String key(String sku, String fcId) {
        return PREFIX + "{" + sku + "}:" + fcId;
    }

    @Override
    public Optional<List<Allocation>> claim(String sku, int quantity, List<FulfillmentCenter> inOrder) {
        List<String> keys = inOrder.stream().map(fc -> key(sku, fc.id())).toList();
        for (int attempt = 0; attempt < 3; attempt++) {
            List<Long> result = redis.execute(CLAIM, keys, Integer.toString(quantity));
            long outcome = result.get(0);
            if (outcome == 1) {
                List<Allocation> allocations = new ArrayList<>();
                for (int i = 0; i < inOrder.size(); i++) {
                    long taken = result.get(i + 1);
                    if (taken > 0) {
                        allocations.add(new Allocation(sku, inOrder.get(i).id(), Math.toIntExact(taken)));
                    }
                }
                return Optional.of(allocations);
            }
            if (outcome == 0) {
                return Optional.empty();
            }
            List<String> missing = new ArrayList<>();
            for (int i = 1; i < result.size(); i++) {
                missing.add(inOrder.get(Math.toIntExact(result.get(i)) - 1).id());
            }
            initialize(sku, missing);
        }
        throw new IllegalStateException("could not initialize stock counters for " + sku);
    }

    @Override
    public void release(List<Allocation> allocations) {
        for (Allocation a : allocations) {
            // A missing counter is left missing: it will be rebuilt from the truth, which no
            // longer counts this hold.
            redis.execute(RELEASE, List.of(key(a.sku(), a.fcId())), Integer.toString(a.quantity()));
        }
    }

    private static final RedisScript<Long> RELEASE = RedisScript.of("""
            if redis.call('EXISTS', KEYS[1]) == 1 then
              return redis.call('INCRBY', KEYS[1], ARGV[1])
            end
            return -1
            """, Long.class);

    @Override
    public Map<String, Long> available(String sku, List<FulfillmentCenter> fcs) {
        List<String> keys = fcs.stream().map(fc -> key(sku, fc.id())).toList();
        List<String> values = redis.opsForValue().multiGet(keys);
        List<String> missing = new ArrayList<>();
        for (int i = 0; i < fcs.size(); i++) {
            if (values == null || values.get(i) == null) {
                missing.add(fcs.get(i).id());
            }
        }
        if (!missing.isEmpty()) {
            initialize(sku, missing);
            values = redis.opsForValue().multiGet(keys);
        }
        Map<String, Long> out = new LinkedHashMap<>();
        for (int i = 0; i < fcs.size(); i++) {
            String v = values == null ? null : values.get(i);
            out.put(fcs.get(i).id(), v == null ? 0L : Long.parseLong(v));
        }
        return out;
    }

    /**
     * Creates missing counters from the Postgres truth. Coalesced per SKU: when a deal starts,
     * a thousand buyers find the counters missing at the same moment, and without coalescing
     * each of them would run the truth query (a cache stampede of its own; seen in
     * StockConsistencyTest as Redis and pool timeouts). One caller per SKU does the work and
     * the others wait for it.
     */
    private void initialize(String sku, List<String> fcIds) {
        CompletableFuture<Void> mine = new CompletableFuture<>();
        CompletableFuture<Void> running = initializing.putIfAbsent(sku, mine);
        if (running != null) {
            running.join();
            return;
        }
        try {
            Map<String, Long> fromDb = truth.truth(sku, fcIds);
            for (String fc : fcIds) {
                Boolean set = redis.opsForValue().setIfAbsent(key(sku, fc), Long.toString(fromDb.getOrDefault(fc, 0L)));
                if (Boolean.TRUE.equals(set)) {
                    initialized.increment();
                }
            }
            mine.complete(null);
        } catch (RuntimeException e) {
            mine.completeExceptionally(e);
            throw e;
        } finally {
            initializing.remove(sku, mine);
        }
    }
}
