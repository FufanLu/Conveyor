package dev.conveyor.platform.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * Reactive-stack request id filter (gateway, tracking-svc). The id is written back into the
 * request headers, so the gateway forwards it downstream and the next service reuses it
 * instead of minting a new one.
 */
public final class RequestIdWebFilter implements WebFilter, Ordered {

    private static final Logger ACCESS_LOG = LoggerFactory.getLogger("conveyor.access");

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String requestId =
                RequestIds.acceptOrMint(exchange.getRequest().getHeaders().getFirst(RequestIds.HEADER));
        long startNanos = System.nanoTime();
        ServerHttpRequest request = exchange.getRequest()
                .mutate()
                .headers(headers -> headers.set(RequestIds.HEADER, requestId))
                .build();
        ServerWebExchange tagged = exchange.mutate().request(request).build();
        tagged.getAttributes().put(RequestIds.ATTRIBUTE, requestId);
        // Set at commit time, not now: the gateway copies the upstream response headers
        // (which already include X-Request-Id) into this response, and an early set() would
        // leave the client with the header twice.
        tagged.getResponse().beforeCommit(() -> {
            tagged.getResponse().getHeaders().set(RequestIds.HEADER, requestId);
            return Mono.empty();
        });
        return chain.filter(tagged).doFinally(signal -> logAccess(tagged, requestId, startNanos));
    }

    private static void logAccess(ServerWebExchange exchange, String requestId, long startNanos) {
        if (!ACCESS_LOG.isInfoEnabled()) {
            return;
        }
        // Reactive code hops threads, so the MDC is set only around the log call itself.
        MDC.put(RequestIds.MDC_KEY, requestId);
        try {
            HttpStatusCode status = exchange.getResponse().getStatusCode();
            ACCESS_LOG.info(
                    "{} {} -> {} in {} us",
                    exchange.getRequest().getMethod(),
                    exchange.getRequest().getPath().value(),
                    status == null ? "-" : status.value(),
                    (System.nanoTime() - startNanos) / 1_000);
        } finally {
            MDC.remove(RequestIds.MDC_KEY);
        }
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
