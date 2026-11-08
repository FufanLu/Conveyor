package dev.conveyor.gateway.deals;

import dev.conveyor.platform.deals.DealTokens;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/**
 * An order that claims an admission ({@value #HEADER}) goes into the edge's deal lane, so the
 * claim is checked first: a forged or expired admission is refused here, before it takes a slot
 * a real buyer could use. Signature and expiry only; order-svc binds it to the customer and SKU.
 */
public final class AdmissionFilter implements WebFilter, Ordered {

    public static final String HEADER = "Conveyor-Admission";

    private final DealTokens tokens;
    private final Clock clock;

    public AdmissionFilter(DealTokens tokens, Clock clock) {
        this.tokens = tokens;
        this.clock = clock;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 15; // before LoadSheddingFilter (+20)
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String admission = exchange.getRequest().getHeaders().getFirst(HEADER);
        if (admission == null
                || tokens.readAdmission(admission, clock.instant()).isPresent()) {
            return chain.filter(exchange);
        }
        var response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FORBIDDEN);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        byte[] body = ("{\"type\":\"about:blank\",\"title\":\"Forbidden\",\"status\":403,"
                        + "\"detail\":\"This admission is not valid or has expired. Join the line again.\"}")
                .getBytes(StandardCharsets.UTF_8);
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));
    }
}
