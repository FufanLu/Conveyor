package dev.conveyor.gateway.deals;

import dev.conveyor.platform.deals.DealTokens;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The waiting rooms, one per deal, shared by every gateway instance through Redis:
 *
 * <pre>
 *   deal:{id}:issued    INCR on every join: the last place handed out
 *   deal:{id}:join:{customer}:{Idempotency-Key}   the place a join got, for an hour
 *   deal:{id}:frontier  places up to here are admitted; only moves forward
 *   deal:{id}:rate      admissions per second, set by the leader
 *   deal:{id}:leader    lease (SET NX PX 3000) of the instance that advances the frontier
 * </pre>
 *
 * Once a second each instance tries to take or renew the lease. The leader turns what its deal
 * lane saw (LaneStats) into a new rate and advances the frontier by it with one Lua script, so
 * two leaders during a lease handover cannot move it twice or backwards. Every instance then
 * caches issued, frontier and rate, and answers polls from the cache without touching Redis:
 * 7,000 customers polling cost the line nothing.
 */
public final class WaitingRooms implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(WaitingRooms.class);
    private static final Duration LEASE = Duration.ofSeconds(3);

    /** KEYS frontier, issued; ARGV capacity, step. Returns {frontier, issued}. */
    @SuppressWarnings("unchecked")
    private static final RedisScript<List<Long>> ADVANCE =
            RedisScript.of("""
            local f = tonumber(redis.call('GET', KEYS[1]) or '0')
            local i = tonumber(redis.call('GET', KEYS[2]) or '0')
            local n = math.min(tonumber(ARGV[1]), i, f + tonumber(ARGV[2]))
            if n > f then redis.call('SET', KEYS[1], n) else n = f end
            return {n, i}
            """, (Class<List<Long>>) (Class<?>) List.class);

    /**
     * KEYS join key, issued; ARGV ttl seconds. Returns {place, 1 if replayed}. A join retried
     * after a timeout must not take a second place: the second Prime Day run had no such key,
     * and 7,000 customers retrying joins took 19,724 places (docs/illustrated/09).
     */
    @SuppressWarnings("unchecked")
    private static final RedisScript<List<Long>> JOIN = RedisScript.of("""
            local p = redis.call('GET', KEYS[1])
            if p then return {tonumber(p), 1} end
            local n = redis.call('INCR', KEYS[2])
            redis.call('SET', KEYS[1], n, 'EX', tonumber(ARGV[1]))
            return {n, 0}
            """, (Class<List<Long>>) (Class<?>) List.class);

    /** A place in line and whether it is an earlier join's, replayed. */
    record Joined(long place, boolean replayed) {}

    /** One deal's line as this instance last saw it. */
    static final class Room {
        final DealSettings.Deal deal;
        volatile long issued;
        volatile long frontier;
        volatile double rate;
        double carry; // fractional admissions owed, leader only

        Room(DealSettings.Deal deal) {
            this.deal = deal;
            this.rate = deal.initialRate();
        }

        String key(String part) {
            return "deal:" + deal.id() + ":" + part;
        }
    }

    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final ReactiveStringRedisTemplate redis;
    private final LaneStats lane;
    private final DealTokens tokens;
    private final String instance = UUID.randomUUID().toString();
    private final Disposable ticker;

    public WaitingRooms(
            DealSettings settings, ReactiveStringRedisTemplate redis, LaneStats lane, MeterRegistry meters) {
        this.redis = redis;
        this.lane = lane;
        this.tokens = new DealTokens(settings.secret());
        for (DealSettings.Deal deal : settings.items()) {
            Room room = new Room(deal);
            rooms.put(deal.id(), room);
            Gauge.builder("deal.waiting", room, r -> Math.max(0, r.issued - r.frontier))
                    .tag("deal", deal.id())
                    .description("customers in line")
                    .register(meters);
            Gauge.builder("deal.admitted", room, r -> r.frontier)
                    .tag("deal", deal.id())
                    .register(meters);
            Gauge.builder("deal.admission.rate", room, r -> r.rate)
                    .tag("deal", deal.id())
                    .register(meters);
        }
        this.ticker = rooms.isEmpty()
                ? Flux.never().subscribe()
                : Flux.interval(Duration.ofSeconds(1))
                        .onBackpressureDrop()
                        .concatMap(t -> tick().onErrorResume(e -> {
                            log.warn("waiting room tick failed: {}", e.toString());
                            return Mono.empty();
                        }))
                        .subscribe();
    }

    DealTokens tokens() {
        return tokens;
    }

    Optional<Room> room(String id) {
        return Optional.ofNullable(rooms.get(id));
    }

    List<Room> all() {
        return List.copyOf(rooms.values());
    }

    /** A place at the back of the line, or the one this customer's earlier try with the same key got. */
    Mono<Joined> join(Room room, String customer, String idempotencyKey) {
        return redis.execute(
                        JOIN,
                        List.of(room.key("join:" + customer + ":" + idempotencyKey), room.key("issued")),
                        List.of("3600"))
                .next()
                .map(r -> new Joined(r.get(0), r.get(1) == 1L));
    }

    private Mono<Void> tick() {
        LaneStats.Window window = lane.drain();
        return Flux.fromIterable(rooms.values())
                .concatMap(room -> tick(room, window))
                .then();
    }

    private Mono<Void> tick(Room room, LaneStats.Window window) {
        var values = redis.opsForValue();
        Mono<Boolean> leader = values.setIfAbsent(room.key("leader"), instance, LEASE)
                .flatMap(won -> won
                        ? Mono.just(true)
                        : values.get(room.key("leader"))
                                .filter(instance::equals)
                                .flatMap(mine -> redis.expire(room.key("leader"), LEASE))
                                .defaultIfEmpty(false));
        return leader.flatMap(lead -> lead ? advance(room, window) : refresh(room));
    }

    private Mono<Void> advance(Room room, LaneStats.Window window) {
        DealSettings.Deal deal = room.deal;
        double rate = Line.nextRate(
                room.rate,
                deal.minRate(),
                deal.maxRate(),
                window.shed(),
                window.errors(),
                window.meanLatencyMillis(),
                (double) deal.targetLatency().toMillis(),
                room.issued > room.frontier);
        room.carry += rate;
        long step = (long) room.carry;
        room.carry -= step;
        return redis.execute(
                        ADVANCE,
                        List.of(room.key("frontier"), room.key("issued")),
                        List.of(Long.toString(deal.places()), Long.toString(step)))
                .next()
                .flatMap(result -> {
                    room.frontier = result.get(0);
                    room.issued = result.get(1);
                    room.rate = rate;
                    return redis.opsForValue().set(room.key("rate"), Double.toString(rate));
                })
                .then();
    }

    private Mono<Void> refresh(Room room) {
        return redis.opsForValue()
                .multiGet(List.of(room.key("issued"), room.key("frontier"), room.key("rate")))
                .doOnNext(v -> {
                    room.issued = parse(v.get(0), room.issued);
                    room.frontier = parse(v.get(1), room.frontier);
                    if (v.get(2) != null) {
                        room.rate = Double.parseDouble(v.get(2));
                    }
                })
                .then();
    }

    private static long parse(String value, long fallback) {
        return value == null ? fallback : Long.parseLong(value);
    }

    @Override
    public void destroy() {
        ticker.dispose();
    }
}
