package dev.conveyor.tracking.live;

import dev.conveyor.tracking.config.TrackingProperties;
import dev.conveyor.tracking.domain.TrackingRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Component;
import reactor.core.publisher.BufferOverflowStrategy;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.util.concurrent.Queues;

/**
 * Fan-out inside one instance. Each watched shipment has its own topic (a multicast sink that
 * exists only while someone watches), so a report costs one map lookup, not a scan over every
 * subscriber. Each subscriber gets a bounded queue: a browser that stops reading loses its
 * oldest reports (counted), and never slows the Kafka consumer or the other subscribers.
 */
@Component
public class LiveHub {

    private final Map<String, Topic> topics = new ConcurrentHashMap<>();
    private final Sinks.Many<TrackingRecord> all = Sinks.many().multicast().directBestEffort();
    private final int buffer;
    private final Counter published;
    private final Counter dropped;
    private final Timer delay;

    LiveHub(TrackingProperties properties, MeterRegistry registry) {
        this.buffer = properties.subscriberBuffer();
        this.published = Counter.builder("tracking.live.published").register(registry);
        this.dropped = Counter.builder("tracking.live.dropped").register(registry);
        // Handheld clock to "ready to push": ingest, Kafka, and this instance's live consumer.
        this.delay = Timer.builder("tracking.live.delay")
                .publishPercentiles(0.5, 0.99)
                .publishPercentileHistogram()
                .maximumExpectedValue(Duration.ofMinutes(5))
                .register(registry);
        Gauge.builder("tracking.live.watched.shipments", topics, Map::size).register(registry);
    }

    private static final class Topic {
        final Sinks.Many<TrackingRecord> sink = Sinks.many().multicast().directBestEffort();
        final AtomicInteger watchers = new AtomicInteger();
    }

    /** Called by the live consumer thread only, so emissions to one sink never race. */
    void publish(TrackingRecord r) {
        published.increment();
        delay.record(Duration.between(r.recordedAt(), Instant.now()));
        Topic t = topics.get(r.shipmentId());
        if (t != null) {
            t.sink.tryEmitNext(r);
        }
        all.tryEmitNext(r);
    }

    /** Live reports of one shipment from now on, through this subscriber's bounded queue. */
    public Flux<TrackingRecord> watch(String shipmentId) {
        return Flux.defer(() -> {
            Topic t = topics.compute(shipmentId, (k, existing) -> {
                Topic topic = existing == null ? new Topic() : existing;
                topic.watchers.incrementAndGet();
                return topic;
            });
            return t.sink.asFlux()
                    .onBackpressureBuffer(buffer, r -> dropped.increment(), BufferOverflowStrategy.DROP_OLDEST)
                    .doFinally(s -> topics.computeIfPresent(
                            shipmentId, (k, topic) -> topic.watchers.decrementAndGet() == 0 ? null : topic));
        });
    }

    /** Every report on this instance's live consumer, for the ops map. */
    public Flux<TrackingRecord> all() {
        return all.asFlux()
                .onBackpressureBuffer(
                        Queues.SMALL_BUFFER_SIZE * 16, r -> dropped.increment(), BufferOverflowStrategy.DROP_OLDEST);
    }
}
