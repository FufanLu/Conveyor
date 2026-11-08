package dev.conveyor.gateway.deals;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;
import static org.springframework.web.reactive.function.server.RequestPredicates.POST;

import dev.conveyor.platform.deals.DealTokens;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

/**
 * The waiting room's API, served by the gateway itself (no upstream):
 *
 * <pre>
 *   GET  /api/v1/deals                      deals and their lines (public)
 *   POST /api/v1/deals/{id}/queue           join: a ticket and a place (Idempotency-Key: a
 *                                           retried join gets the same place back)
 *   GET  /api/v1/deals/{id}/queue/{ticket}  where the place is; once admitted, an admission
 *                                           for the signed-in customer
 * </pre>
 *
 * A poll says when to poll again ({@code pollAfterMs}, also as Retry-After), about twice before
 * the customer's turn, so the line costs a few requests per customer however long it is.
 */
public final class WaitingRoomRoutes {

    private static final java.util.regex.Pattern KEY = java.util.regex.Pattern.compile("[A-Za-z0-9_-]{8,64}");

    private final WaitingRooms rooms;
    private final DealTokens tokens;
    private final Clock clock;

    public WaitingRoomRoutes(WaitingRooms rooms, Clock clock) {
        this.rooms = rooms;
        this.tokens = rooms.tokens();
        this.clock = clock;
    }

    public RouterFunction<ServerResponse> routes() {
        return RouterFunctions.route(GET("/api/v1/deals"), this::list)
                .andRoute(POST("/api/v1/deals/{id}/queue"), this::join)
                .andRoute(GET("/api/v1/deals/{id}/queue/{ticket}"), this::poll);
    }

    private Mono<ServerResponse> list(ServerRequest request) {
        var deals = rooms.all().stream()
                .map(r -> Map.<String, Object>of(
                        "id",
                        r.deal.id(),
                        "sku",
                        r.deal.sku(),
                        "units",
                        r.deal.units(),
                        "joined",
                        r.issued,
                        "admitted",
                        r.frontier,
                        "admissionsPerSecond",
                        Math.round(r.rate * 10) / 10.0))
                .toList();
        return ServerResponse.ok().contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("deals", deals));
    }

    private Mono<ServerResponse> join(ServerRequest request) {
        Optional<WaitingRooms.Room> room = rooms.room(request.pathVariable("id"));
        if (room.isEmpty()) {
            return problem(request, HttpStatus.NOT_FOUND, "No deal " + request.pathVariable("id") + ".");
        }
        String key = request.headers().firstHeader("Idempotency-Key");
        if (key == null || !KEY.matcher(key).matches()) {
            return problem(
                    request,
                    HttpStatus.BAD_REQUEST,
                    "Send an Idempotency-Key header (8-64 of A-Z a-z 0-9 _ -); a retried join keeps its place.");
        }
        WaitingRooms.Room r = room.get();
        return request.principal()
                .map(java.security.Principal::getName)
                .defaultIfEmpty("anonymous")
                .flatMap(customer -> rooms.join(r, customer, key))
                .flatMap(joined -> {
                    long place = joined.place();
                    String ticket = tokens.ticket(new DealTokens.Ticket(r.deal.id(), place, clock.instant()));
                    URI self = URI.create("/api/v1/deals/" + r.deal.id() + "/queue/" + ticket);
                    ServerResponse.BodyBuilder builder = joined.replayed()
                            ? ServerResponse.ok().header("Idempotent-Replayed", "true")
                            : ServerResponse.created(self);
                    return answer(request, r, place, ticket, builder);
                })
                .onErrorResume(e -> problem(
                        request,
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "The line cannot take new places right now; retry in a moment."));
    }

    private Mono<ServerResponse> poll(ServerRequest request) {
        String id = request.pathVariable("id");
        Optional<WaitingRooms.Room> room = rooms.room(id);
        Optional<DealTokens.Ticket> ticket = tokens.readTicket(request.pathVariable("ticket"))
                .filter(t -> t.dealId().equals(id));
        if (room.isEmpty() || ticket.isEmpty()) {
            return problem(request, HttpStatus.NOT_FOUND, "No such place in line.");
        }
        return answer(request, room.get(), ticket.get().seq(), request.pathVariable("ticket"), ServerResponse.ok());
    }

    private Mono<ServerResponse> answer(
            ServerRequest request, WaitingRooms.Room r, long place, String ticket, ServerResponse.BodyBuilder builder) {
        Line.Status status = Line.status(place, r.frontier, r.deal.places(), r.rate);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("dealId", r.deal.id());
        body.put("sku", r.deal.sku());
        body.put("ticket", ticket);
        body.put("state", status.state());
        body.put("place", status.place());
        body.put("ahead", status.ahead());
        body.put("etaSeconds", status.etaSeconds());
        if (status.state() == Line.State.WAITING) {
            body.put("pollAfterMs", status.pollAfterMillis());
            builder.header("Retry-After", Long.toString(Math.max(1, status.pollAfterMillis() / 1000)));
        }
        if (status.state() != Line.State.ADMITTED) {
            return builder.contentType(MediaType.APPLICATION_JSON).bodyValue(body);
        }
        return request.principal()
                .filter(JwtAuthenticationToken.class::isInstance)
                .map(p -> ((JwtAuthenticationToken) p).getToken().getSubject())
                .map(subject -> {
                    Instant expires = clock.instant().plus(r.deal.admissionTtl());
                    body.put("admission", tokens.admission(new DealTokens.Admission(r.deal.id(), subject, expires)));
                    body.put("admissionExpiresAt", expires.toString());
                    return body;
                })
                .defaultIfEmpty(body)
                .flatMap(b -> builder.contentType(MediaType.APPLICATION_JSON).bodyValue(b));
    }

    private static Mono<ServerResponse> problem(ServerRequest request, HttpStatus status, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setInstance(URI.create(request.path()));
        return ServerResponse.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .bodyValue(problem);
    }
}
