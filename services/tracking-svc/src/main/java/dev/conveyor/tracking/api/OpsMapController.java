package dev.conveyor.tracking.api;

import dev.conveyor.tracking.api.TrackingView.Dot;
import dev.conveyor.tracking.config.TrackingProperties;
import dev.conveyor.tracking.domain.TrackingRecord;
import dev.conveyor.tracking.live.LiveHub;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * The operations map: every moving shipment. At firehose rates a browser cannot draw one event
 * per report, so the stream is conflated: every mapInterval it sends one frame holding the
 * latest position of each shipment that moved. The browser's load is bounded by the number of
 * shipments, not by how often couriers report.
 */
@RestController
class OpsMapController {

    private final LiveHub hub;
    private final TrackingProperties properties;

    OpsMapController(LiveHub hub, TrackingProperties properties) {
        this.hub = hub;
        this.properties = properties;
    }

    @GetMapping(value = "/v1/ops/map", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    Flux<ServerSentEvent<Collection<Dot>>> map() {
        return hub.all()
                .filter(TrackingRecord::isPosition)
                .buffer(properties.mapInterval())
                .filter(b -> !b.isEmpty())
                .map(OpsMapController::latestPerShipment)
                .map(dots -> ServerSentEvent.<Collection<Dot>>builder(dots)
                        .event("positions")
                        .build())
                .mergeWith(ShipmentController.heartbeats());
    }

    static Collection<Dot> latestPerShipment(List<TrackingRecord> batch) {
        Map<String, Dot> latest = new LinkedHashMap<>();
        for (TrackingRecord r : batch) {
            latest.merge(
                    r.shipmentId(),
                    new Dot(r.shipmentId(), r.lat(), r.lon(), r.seq()),
                    (a, b) -> a.seq() >= b.seq() ? a : b);
        }
        return latest.values();
    }
}
