package dev.conveyor.platform.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class RequestIdWebFilterTest {

    private final RequestIdWebFilter filter = new RequestIdWebFilter();

    @Test
    void forwardsCallerIdDownstreamAndEchoesIt() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/orders").header(RequestIds.HEADER, "caller-id-0001"));
        AtomicReference<ServerWebExchange> seenDownstream = new AtomicReference<>();

        StepVerifier.create(filterAndCommit(exchange, seenDownstream)).verifyComplete();

        assertThat(seenDownstream.get().getRequest().getHeaders().getFirst(RequestIds.HEADER))
                .isEqualTo("caller-id-0001");
        assertThat(exchange.getResponse().getHeaders().getFirst(RequestIds.HEADER))
                .isEqualTo("caller-id-0001");
    }

    @Test
    void mintsIdAndWritesItIntoTheForwardedRequest() {
        MockServerWebExchange exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/orders"));
        AtomicReference<ServerWebExchange> seenDownstream = new AtomicReference<>();

        StepVerifier.create(filterAndCommit(exchange, seenDownstream)).verifyComplete();

        String forwarded = seenDownstream.get().getRequest().getHeaders().getFirst(RequestIds.HEADER);
        assertThat(forwarded).isNotNull();
        assertThat(UUID.fromString(forwarded).version()).isEqualTo(7);
        assertThat(exchange.getResponse().getHeaders().getFirst(RequestIds.HEADER))
                .isEqualTo(forwarded);
        assertThat((String) seenDownstream.get().getAttribute(RequestIds.ATTRIBUTE))
                .isEqualTo(forwarded);
    }

    @Test
    void responseCarriesExactlyOneIdEvenWhenUpstreamAlreadySetOne() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/orders").header(RequestIds.HEADER, "caller-id-0003"));
        // What the gateway does when it proxies: copies upstream headers into the response.
        WebFilterChain proxyingChain = ex -> {
            ex.getResponse().getHeaders().add(RequestIds.HEADER, "caller-id-0003");
            return Mono.empty();
        };

        StepVerifier.create(filter.filter(exchange, proxyingChain)
                        .then(exchange.getResponse().setComplete()))
                .verifyComplete();

        assertThat(exchange.getResponse().getHeaders().get(RequestIds.HEADER)).containsExactly("caller-id-0003");
    }

    /** Runs the filter, then commits the response the way a real handler would. */
    private Mono<Void> filterAndCommit(MockServerWebExchange exchange, AtomicReference<ServerWebExchange> sink) {
        return filter.filter(exchange, capture(sink))
                .then(exchange.getResponse().setComplete());
    }

    private static WebFilterChain capture(AtomicReference<ServerWebExchange> sink) {
        return exchange -> {
            sink.set(exchange);
            return Mono.empty();
        };
    }
}
