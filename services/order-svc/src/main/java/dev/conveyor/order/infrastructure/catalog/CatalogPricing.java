package dev.conveyor.order.infrastructure.catalog;

import dev.conveyor.order.application.Pricing;
import dev.conveyor.order.application.PricingUnavailableException;
import dev.conveyor.order.application.UnknownSkusException;
import dev.conveyor.platform.web.RequestIds;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.MDC;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Prices an order by reading every SKU from catalog-svc in parallel (one virtual thread per
 * SKU), within one overall time budget. catalog-svc answers from its cache, so this is cheap;
 * when it is slow or down the order is refused with 503 rather than priced from stale data.
 *
 * <p>A circuit breaker sits in front: once half of the last calls (at least 20) failed or ran
 * past 80 % of the budget, it opens for 5 s and every order is refused at once, instead of each
 * one holding a request thread for the full 1.5 s budget while catalog is down. That keeps
 * order-svc's own capacity for the requests that do not need pricing (order status, the saga's
 * status updates) and stops piling load on a struggling catalog. After 5 s, five trial calls
 * decide whether it closes again.
 */
@Component
@EnableConfigurationProperties(CatalogProperties.class)
class CatalogPricing implements Pricing, AutoCloseable {

    private final RestClient http;
    private final CatalogProperties properties;
    private final ExecutorService lookups = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("catalog-quote-", 0).factory());

    private final CircuitBreaker breaker;

    CatalogPricing(CatalogProperties properties, MeterRegistry registry, RestClient.Builder builder) {
        this.properties = properties;
        CircuitBreakerRegistry breakers = CircuitBreakerRegistry.of(CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(50)
                .minimumNumberOfCalls(properties.breakerMinimumCalls())
                .failureRateThreshold(properties.breakerFailureRatePercent())
                .slowCallDurationThreshold(
                        properties.quoteBudget().multipliedBy(4).dividedBy(5))
                .slowCallRateThreshold(properties.breakerFailureRatePercent())
                .waitDurationInOpenState(properties.breakerOpenFor())
                .permittedNumberOfCallsInHalfOpenState(5)
                .automaticTransitionFromOpenToHalfOpenEnabled(true)
                // An unknown SKU is the customer's mistake, not catalog failing.
                .ignoreExceptions(UnknownSkusException.class)
                .build());
        this.breaker = breakers.circuitBreaker("catalog");
        // resilience4j_circuitbreaker_state{name="catalog",state=...}, _calls_seconds, _not_permitted_calls
        TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(breakers).bindTo(registry);
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build());
        factory.setReadTimeout(properties.readTimeout());
        // Spring's builder carries the observation registry: catalog calls join the trace.
        this.http = builder.clone()
                .baseUrl(properties.url())
                .requestFactory(factory)
                .build();
    }

    record CatalogProduct(String sku, Price price, long version) {
        record Price(long amountCents, String currency) {}
    }

    @Override
    public Map<String, Quote> quote(Collection<String> skus) {
        try {
            return breaker.executeCallable(() -> quoteNow(skus));
        } catch (CallNotPermittedException e) {
            throw new PricingUnavailableException("catalog circuit open; not calling it for a moment", e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new PricingUnavailableException("pricing failed", e);
        }
    }

    CircuitBreaker.State breakerState() {
        return breaker.getState();
    }

    private Map<String, Quote> quoteNow(Collection<String> skus) {
        String requestId = MDC.get(RequestIds.MDC_KEY); // MDC does not follow us into other threads
        Map<String, Future<Optional<CatalogProduct>>> pending = new LinkedHashMap<>();
        for (String sku : skus) {
            pending.put(sku, lookups.submit(() -> fetch(sku, requestId)));
        }
        long deadline = System.nanoTime() + properties.quoteBudget().toNanos();
        Map<String, Quote> quotes = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        try {
            for (var entry : pending.entrySet()) {
                long remaining = Math.max(0, deadline - System.nanoTime());
                Optional<CatalogProduct> product = entry.getValue().get(remaining, TimeUnit.NANOSECONDS);
                if (product.isEmpty()) {
                    unknown.add(entry.getKey());
                } else {
                    CatalogProduct p = product.get();
                    quotes.put(
                            p.sku(),
                            new Quote(
                                    p.sku(), p.price().amountCents(), p.price().currency()));
                }
            }
        } catch (TimeoutException e) {
            pending.values().forEach(f -> f.cancel(true));
            throw new PricingUnavailableException("catalog did not answer within " + properties.quoteBudget(), e);
        } catch (ExecutionException e) {
            pending.values().forEach(f -> f.cancel(true));
            throw new PricingUnavailableException("catalog lookup failed", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PricingUnavailableException("interrupted while pricing", e);
        }
        if (!unknown.isEmpty()) {
            throw new UnknownSkusException(unknown);
        }
        return quotes;
    }

    private Optional<CatalogProduct> fetch(String sku, String requestId) {
        return http.get()
                .uri("/v1/products/{sku}", sku)
                .headers(h -> {
                    if (requestId != null) {
                        h.set(RequestIds.HEADER, requestId);
                    }
                })
                .exchange((request, response) -> {
                    int status = response.getStatusCode().value();
                    if (status == 404) {
                        return Optional.empty();
                    }
                    if (status != 200) {
                        throw new PricingUnavailableException("catalog answered " + status + " for " + sku, null);
                    }
                    return Optional.ofNullable(response.bodyTo(CatalogProduct.class));
                });
    }

    @Override
    public void close() {
        lookups.close();
    }
}
