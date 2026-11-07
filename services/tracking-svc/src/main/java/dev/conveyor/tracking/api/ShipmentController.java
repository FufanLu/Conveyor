package dev.conveyor.tracking.api;

import dev.conveyor.tracking.api.TrackingView.Report;
import dev.conveyor.tracking.api.TrackingView.ShipmentView;
import dev.conveyor.tracking.config.TrackingProperties;
import dev.conveyor.tracking.domain.Shipment;
import dev.conveyor.tracking.domain.TrackingRecord;
import dev.conveyor.tracking.live.LiveHub;
import dev.conveyor.tracking.store.EventStore;
import dev.conveyor.tracking.store.ShipmentStore;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * A customer's view of their shipment. The SSE stream sends the stored history first and then
 * live reports, without a gap and without repeats: the live subscription is opened before the
 * history query, buffered, and filtered to seq numbers above the last one the history sent. A
 * reconnecting browser sends Last-Event-ID and gets only what it missed.
 */
@RestController
class ShipmentController {

    static final Duration HEARTBEAT = Duration.ofSeconds(15);

    private final ShipmentStore shipments;
    private final EventStore events;
    private final LiveHub hub;
    private final int buffer;

    ShipmentController(ShipmentStore shipments, EventStore events, LiveHub hub, TrackingProperties properties) {
        this.shipments = shipments;
        this.events = events;
        this.hub = hub;
        this.buffer = properties.subscriberBuffer();
    }

    @GetMapping("/v1/shipments/{shipmentId}")
    Mono<ShipmentView> get(@PathVariable String shipmentId, @AuthenticationPrincipal Jwt jwt) {
        return owned(shipmentId, jwt).map(ShipmentView::of);
    }

    @GetMapping(value = "/v1/shipments/{shipmentId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<Report>> stream(
            @PathVariable String shipmentId,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        long after = parseSeq(lastEventId);
        return owned(shipmentId, jwt).flatMapMany(s -> {
            AtomicLong sent = new AtomicLong(after);
            // Subscribe to live first, buffering up to subscriberBuffer reports, then read the
            // history, then drain the buffer. Overlap is removed by the seq filter below.
            var live = hub.watch(shipmentId).replay(buffer);
            var connection = live.connect();
            Flux<TrackingRecord> history = events.history(shipmentId, after);
            return history.concatWith(live)
                    .filter(r -> r.seq() > sent.get())
                    .doOnNext(r -> sent.set(r.seq()))
                    .map(ShipmentController::toEvent)
                    .mergeWith(heartbeats())
                    .doFinally(x -> connection.dispose());
        });
    }

    static ServerSentEvent<Report> toEvent(TrackingRecord r) {
        return ServerSentEvent.builder(Report.of(r))
                .id(Long.toString(r.seq()))
                .event(r.isPosition() ? "position" : "scan")
                .build();
    }

    static <T> Flux<ServerSentEvent<T>> heartbeats() {
        return Flux.interval(HEARTBEAT)
                .map(i -> ServerSentEvent.<T>builder().comment("keepalive").build());
    }

    /** 404 for a shipment that is missing or belongs to someone else (do not confirm it exists). */
    private Mono<Shipment> owned(String shipmentId, Jwt jwt) {
        return ReactiveSecurityContextHolder.getContext()
                .map(c -> c.getAuthentication().getAuthorities().stream()
                        .anyMatch(a -> "ROLE_ops".equals(a.getAuthority())))
                .defaultIfEmpty(false)
                .flatMap(ops -> shipments
                        .find(shipmentId)
                        .filter(s -> ops || jwt.getSubject().equals(s.userId())))
                .switchIfEmpty(Mono.error(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "No shipment " + shipmentId + ".")));
    }

    private static long parseSeq(String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        try {
            return Math.max(0, Long.parseLong(lastEventId.trim()));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
