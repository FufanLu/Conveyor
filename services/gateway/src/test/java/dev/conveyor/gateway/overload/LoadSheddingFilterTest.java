package dev.conveyor.gateway.overload;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

class LoadSheddingFilterTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final LoadSheddingFilter filter =
            new LoadSheddingFilter(new LoadSheddingFilter.Settings(true, 20, 4, 100, 1.5), meters);
    private final List<Sinks.Empty<Void>> held = new ArrayList<>();

    /** Sends a request through the filter to an upstream that does not answer until released. */
    private MockServerWebExchange send(MockServerHttpRequest request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        Sinks.Empty<Void> upstream = Sinks.empty();
        held.add(upstream);
        filter.filter(exchange, e -> upstream.asMono()).subscribe();
        return exchange;
    }

    private static boolean shed(MockServerWebExchange e) {
        return e.getResponse().getStatusCode() == HttpStatus.SERVICE_UNAVAILABLE;
    }

    @Test
    void searchIsShedFirstWhileOrdersStillGetIn() {
        // limit 20: search may hold 8 slots (40 %), product pages 12 (60 %), orders 20
        List<MockServerWebExchange> searches = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            searches.add(
                    send(MockServerHttpRequest.get("/api/v1/search?q=kettle").build()));
        }
        assertThat(searches.stream().filter(LoadSheddingFilterTest::shed)).hasSize(2);

        var product =
                send(MockServerHttpRequest.get("/api/v1/products/B0DEE0D8F5").build());
        assertThat(shed(product)).isFalse(); // 9th slot, under 12

        List<MockServerWebExchange> orders = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            orders.add(send(MockServerHttpRequest.get("/api/v1/orders/" + i).build()));
        }
        // orders take slots 10..20; the 21st request is over the limit even for orders
        assertThat(orders.subList(0, 11)).noneMatch(LoadSheddingFilterTest::shed);
        assertThat(shed(orders.getLast())).isTrue();
        // a product page is shed long before that: 20 of 20 are taken, it may use 12
        assertThat(shed(send(MockServerHttpRequest.get("/api/v1/products/B0X").build())))
                .isTrue();
        assertThat(searches.getLast().getResponse().getHeaders().getFirst("Retry-After"))
                .isEqualTo("1");
        assertThat(searches.getLast().getResponse().getHeaders().getFirst("X-Conveyor-Shed"))
                .isEqualTo("sheddable");
        assertThat(meters.get("gateway.requests.shed")
                        .tag("priority", "sheddable")
                        .counter()
                        .count())
                .isEqualTo(2);
    }

    @Test
    void finishedRequestsFreeTheirSlots() {
        for (int i = 0; i < 8; i++) {
            send(MockServerHttpRequest.get("/api/v1/search").build());
        }
        assertThat(shed(send(MockServerHttpRequest.get("/api/v1/search").build())))
                .isTrue();

        held.forEach(Sinks.Empty::tryEmitEmpty);

        assertThat(shed(send(MockServerHttpRequest.get("/api/v1/search").build())))
                .isFalse();
    }

    @Test
    void streamsAndActuatorAreNeverCounted() {
        for (int i = 0; i < 50; i++) {
            assertThat(shed(send(MockServerHttpRequest.get("/api/v1/shipments/S1/events")
                            .build())))
                    .isFalse();
            assertThat(shed(send(MockServerHttpRequest.get("/actuator/health").build())))
                    .isFalse();
        }
        assertThat(shed(send(MockServerHttpRequest.get("/api/v1/search").build())))
                .isFalse();
    }

    @Test
    void shedRequestsGetAProblemBodyAndCostNoUpstreamCall() {
        for (int i = 0; i < 8; i++) {
            send(MockServerHttpRequest.get("/api/v1/search").build());
        }
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/api/v1/search").build());
        boolean[] called = {false};
        filter.filter(exchange, e -> {
                    called[0] = true;
                    return Mono.empty();
                })
                .block();
        assertThat(called[0]).isFalse();
        assertThat(exchange.getResponse().getBodyAsString().block())
                .contains("\"status\":503")
                .contains("retry in a moment");
    }
}
