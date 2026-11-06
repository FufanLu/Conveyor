package dev.conveyor.catalog.infrastructure.cache;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Subscribes this instance to write announcements ({@code cat:written}) without making Redis a
 * startup dependency. A plain RedisMessageListenerContainer bean fails the whole application
 * if Redis is unreachable for a moment at startup (observed on a loaded workstation). Here the
 * subscription is established in the background with exponential backoff; until it is up, the
 * service still serves reads (falling back to the database when Redis is down), and L1
 * staleness is bounded by the L1 TTL. {@code catalog_cache_subscription_connected} shows state.
 */
public final class ProductWrittenSubscription implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ProductWrittenSubscription.class);

    private final RedisConnectionFactory connections;
    private final TwoLevelProductCache cache;
    private final AtomicReference<RedisMessageListenerContainer> container = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean connected = new AtomicBoolean();
    private volatile Thread connector;

    ProductWrittenSubscription(RedisConnectionFactory connections, TwoLevelProductCache cache, MeterRegistry registry) {
        this.connections = connections;
        this.cache = cache;
        Gauge.builder("catalog.cache.subscription.connected", connected, c -> c.get() ? 1 : 0)
                .description("1 while this instance receives write announcements from peers")
                .register(registry);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            connector = Thread.ofVirtual().name("cache-subscription").start(this::connectWithBackoff);
        }
    }

    private void connectWithBackoff() {
        Duration delay = Duration.ofMillis(500);
        int attempt = 0;
        while (running.get()) {
            attempt++;
            RedisMessageListenerContainer candidate = newContainer();
            try {
                candidate.afterPropertiesSet();
                candidate.start();
                container.set(candidate);
                connected.set(true);
                log.info("subscribed to {} after {} attempt(s)", RedisProductStore.CHANNEL, attempt);
                return;
            } catch (RuntimeException e) {
                destroyQuietly(candidate);
                log.warn(
                        "subscription to {} failed (attempt {}), retrying in {} ms: {}",
                        RedisProductStore.CHANNEL,
                        attempt,
                        delay.toMillis(),
                        e.toString());
            }
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            delay = delay.multipliedBy(2).compareTo(Duration.ofSeconds(30)) > 0
                    ? Duration.ofSeconds(30)
                    : delay.multipliedBy(2);
        }
    }

    private RedisMessageListenerContainer newContainer() {
        RedisMessageListenerContainer c = new RedisMessageListenerContainer();
        c.setConnectionFactory(connections);
        // Once subscribed, the container itself re-subscribes after Redis restarts.
        ExponentialBackOff recovery = new ExponentialBackOff(500, 2.0);
        recovery.setMaxInterval(30_000);
        c.setRecoveryBackoff(recovery);
        c.addMessageListener(
                (message, pattern) -> {
                    String body = new String(message.getBody(), StandardCharsets.UTF_8);
                    int colon = body.lastIndexOf(':');
                    cache.onWritten(body.substring(0, colon), Long.parseLong(body.substring(colon + 1)));
                },
                new ChannelTopic(RedisProductStore.CHANNEL));
        return c;
    }

    private static void destroyQuietly(RedisMessageListenerContainer c) {
        try {
            c.destroy();
        } catch (Exception ignored) {
            // nothing to clean up beyond what destroy already tried
        }
    }

    @Override
    public void stop() {
        running.set(false);
        Thread t = connector;
        if (t != null) {
            t.interrupt();
        }
        RedisMessageListenerContainer c = container.getAndSet(null);
        if (c != null) {
            destroyQuietly(c);
        }
        connected.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }
}
