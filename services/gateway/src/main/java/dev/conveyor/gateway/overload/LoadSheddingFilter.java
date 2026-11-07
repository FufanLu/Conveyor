package dev.conveyor.gateway.overload;

import dev.conveyor.platform.web.RequestIds;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Admission at the edge. Every upstream request takes a slot while it is in flight; a request
 * whose priority may not use the free slots is answered 503 with Retry-After at once, before
 * token validation or routing spend anything on it. The limit adapts to the upstreams'
 * latency (AdaptiveLimit). Runs before Spring Security so shed traffic costs almost nothing.
 */
public final class LoadSheddingFilter implements WebFilter, Ordered {

    @ConfigurationProperties("conveyor.gateway.shedding")
    public record Settings(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("64") int initialLimit,
            @DefaultValue("8") int minLimit,
            @DefaultValue("1000") int maxLimit,
            @DefaultValue("2.0") double tolerance) {}

    private final Settings settings;
    private final AdaptiveLimit limit;
    private final AtomicInteger inflight = new AtomicInteger();
    private final Map<Priority, Counter> shed = new EnumMap<>(Priority.class);
    private final Map<Priority, Counter> admitted = new EnumMap<>(Priority.class);

    public LoadSheddingFilter(Settings settings, MeterRegistry registry) {
        this.settings = settings;
        this.limit = new AdaptiveLimit(
                settings.initialLimit(), settings.minLimit(), settings.maxLimit(), settings.tolerance());
        Gauge.builder("gateway.concurrency.limit", limit, AdaptiveLimit::limit).register(registry);
        Gauge.builder("gateway.concurrency.inflight", inflight, AtomicInteger::get)
                .register(registry);
        for (Priority p : Priority.values()) {
            if (p == Priority.UNLIMITED) {
                continue;
            }
            String tag = p.name().toLowerCase(java.util.Locale.ROOT);
            shed.put(
                    p,
                    Counter.builder("gateway.requests.shed")
                            .tag("priority", tag)
                            .register(registry));
            admitted.put(
                    p,
                    Counter.builder("gateway.requests.admitted")
                            .tag("priority", tag)
                            .register(registry));
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 20; // after the request-id filter, before security
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        Priority priority = Priority.of(exchange.getRequest());
        if (!settings.enabled() || priority == Priority.UNLIMITED) {
            return chain.filter(exchange);
        }
        int now = inflight.incrementAndGet();
        if (now > limit.limit() * priority.share) {
            inflight.decrementAndGet();
            shed.get(priority).increment();
            return reject(exchange, priority);
        }
        admitted.get(priority).increment();
        long start = System.nanoTime();
        return chain.filter(exchange).doFinally(signal -> {
            int left = inflight.getAndDecrement();
            limit.sample(System.nanoTime() - start, left);
        });
    }

    private static Mono<Void> reject(ServerWebExchange exchange, Priority priority) {
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        response.getHeaders().set("Retry-After", "1");
        response.getHeaders().set("X-Conveyor-Shed", priority.name().toLowerCase(java.util.Locale.ROOT));
        String requestId = response.getHeaders().getFirst(RequestIds.HEADER);
        String body = "{\"type\":\"about:blank\",\"title\":\"Service Unavailable\",\"status\":503,"
                + "\"detail\":\"Conveyor is busy; retry in a moment.\",\"instance\":\""
                + exchange.getRequest().getPath().value().replace("\"", "") + "\""
                + (requestId == null ? "" : ",\"requestId\":\"" + requestId + "\"") + "}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }
}
