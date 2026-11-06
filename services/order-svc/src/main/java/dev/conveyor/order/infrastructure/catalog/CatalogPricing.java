package dev.conveyor.order.infrastructure.catalog;

import dev.conveyor.order.application.Pricing;
import dev.conveyor.order.application.PricingUnavailableException;
import dev.conveyor.order.application.UnknownSkusException;
import dev.conveyor.platform.web.RequestIds;
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
 */
@Component
@EnableConfigurationProperties(CatalogProperties.class)
class CatalogPricing implements Pricing, AutoCloseable {

    private final RestClient http;
    private final CatalogProperties properties;
    private final ExecutorService lookups = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("catalog-quote-", 0).factory());

    CatalogPricing(CatalogProperties properties) {
        this.properties = properties;
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .version(HttpClient.Version.HTTP_1_1)
                .build());
        factory.setReadTimeout(properties.readTimeout());
        this.http = RestClient.builder()
                .baseUrl(properties.url())
                .requestFactory(factory)
                .build();
    }

    record CatalogProduct(String sku, Price price, long version) {
        record Price(long amountCents, String currency) {}
    }

    @Override
    public Map<String, Quote> quote(Collection<String> skus) {
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
