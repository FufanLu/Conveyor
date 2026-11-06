package dev.conveyor.catalog.infrastructure.cache;

import dev.conveyor.catalog.domain.Product;
import java.time.Duration;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The shared L2 tier in Redis. One hash per SKU: {@code v} = version, {@code d} = JSON.
 * Version 0 with empty data is a negative entry ("this SKU does not exist").
 *
 * <p>Every write goes through one Lua script that compares versions on the server, so it is
 * atomic with respect to every other writer: an older version can never replace a newer one.
 * That closes the classic cache-aside race, in which a slow reader that loaded v6 from the
 * database writes v6 into the cache after a writer has already stored v7.
 */
@Component
public class RedisProductStore {

    static final String KEY_PREFIX = "cat:p:";
    static final String CHANNEL = "cat:written";

    // KEYS[1] = product key; ARGV[1] = version, ARGV[2] = JSON (empty for a negative entry),
    // ARGV[3] = TTL in milliseconds. Returns 1 if written, 0 if an equal or newer version is stored.
    private static final RedisScript<Long> PUT_IF_NEWER = RedisScript.of("""
            local current = redis.call('HGET', KEYS[1], 'v')
            if current and tonumber(current) >= tonumber(ARGV[1]) then
              return 0
            end
            redis.call('HSET', KEYS[1], 'v', ARGV[1], 'd', ARGV[2])
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;
    private final JsonMapper json;

    public RedisProductStore(StringRedisTemplate redis, JsonMapper json) {
        this.redis = redis;
        this.json = json;
    }

    /** What L2 holds for a SKU. */
    public sealed interface Lookup {
        record Hit(Product product) implements Lookup {}

        record Absent() implements Lookup {}

        record Miss() implements Lookup {}
    }

    public Lookup get(String sku) {
        List<Object> fields = redis.opsForHash().multiGet(KEY_PREFIX + sku, List.of("v", "d"));
        Object version = fields.get(0);
        if (version == null) {
            return new Lookup.Miss();
        }
        if (Long.parseLong(version.toString()) == 0) {
            return new Lookup.Absent();
        }
        return new Lookup.Hit(json.readValue(fields.get(1).toString(), Product.class));
    }

    /** Stores {@code product} unless Redis already holds the same or a newer version. */
    public boolean putIfNewer(Product product, Duration ttl) {
        return run(product.sku(), product.version(), json.writeValueAsString(product), ttl);
    }

    /** Records "no such SKU"; never replaces a real product. */
    public boolean putAbsent(String sku, Duration ttl) {
        return run(sku, 0, "", ttl);
    }

    /** Tells every catalog instance (including this one) that {@code sku} is now at {@code version}. */
    public void publishWritten(String sku, long version) {
        redis.convertAndSend(CHANNEL, sku + ":" + version);
    }

    private boolean run(String sku, long version, String data, Duration ttl) {
        Long written = redis.execute(
                PUT_IF_NEWER, List.of(KEY_PREFIX + sku), Long.toString(version), data, Long.toString(ttl.toMillis()));
        return written != null && written == 1L;
    }
}
