package dev.conveyor.catalog.infrastructure.cache;

import dev.conveyor.catalog.application.ProductCache;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CacheProperties.class)
class CacheConfiguration {

    private static final Logger log = LoggerFactory.getLogger(CacheConfiguration.class);

    /** Cache loads block on Redis and JDBC; virtual threads make that cheap. */
    @Bean(destroyMethod = "close")
    ExecutorService cacheLoadExecutor() {
        return Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("cache-load-", 0).factory());
    }

    @Bean
    ProductCache productCache(
            CacheProperties properties,
            RedisProductStore l2,
            SkuFilter skus,
            CacheMetrics metrics,
            MeterRegistry registry,
            ExecutorService cacheLoadExecutor) {
        log.info("product cache mode: {}", properties.mode());
        return switch (properties.mode()) {
            case TWO_LEVEL -> new TwoLevelProductCache(l2, skus, properties, metrics, registry, cacheLoadExecutor);
            case NAIVE -> new NaiveCacheAsideProductCache(l2, properties, metrics);
            case NONE -> new UncachedProducts(metrics);
        };
    }

    /** Write announcements from peers keep this instance's L1 from serving older versions. */
    @Bean
    @ConditionalOnProperty(
            prefix = "conveyor.catalog.cache",
            name = "mode",
            havingValue = "TWO_LEVEL",
            matchIfMissing = true)
    ProductWrittenSubscription productWrittenSubscription(
            RedisConnectionFactory connections, ProductCache cache, MeterRegistry registry) {
        return new ProductWrittenSubscription(connections, (TwoLevelProductCache) cache, registry);
    }
}
