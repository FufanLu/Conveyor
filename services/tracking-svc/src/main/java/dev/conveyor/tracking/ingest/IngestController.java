package dev.conveyor.tracking.ingest;

import dev.conveyor.tracking.domain.TrackingRecord;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The firehose entry. Ingest does one thing: append the reports to Kafka and answer once the
 * brokers have them (acks=all). No database on this path, so a slow DynamoDB never pushes back on
 * couriers; the persister catches up from the topic. 202 means "durable in Kafka", not "stored".
 */
@RestController
class IngestController {

    private final KafkaTemplate<String, byte[]> kafka;
    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final Counter accepted;
    private final Counter refused;

    IngestController(KafkaTemplate<String, byte[]> kafka, MeterRegistry registry) {
        this.kafka = kafka;
        this.accepted = Counter.builder("tracking.ingest.events").register(registry);
        this.refused = Counter.builder("tracking.ingest.refused.batches").register(registry);
    }

    @PostMapping("/v1/couriers/{courierId}/pings")
    Mono<ResponseEntity<Map<String, Object>>> ingest(@PathVariable String courierId, @RequestBody PingBatch batch) {
        String problem = batch.problem();
        if (problem != null) {
            return Mono.just(ResponseEntity.badRequest().body(Map.of("detail", problem)));
        }
        // send() can block on a metadata fetch or a full buffer: never on the event loop.
        return Mono.fromCallable(() -> send(courierId, batch))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(Mono::fromFuture)
                .map(n -> {
                    accepted.increment(n);
                    return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.<String, Object>of("accepted", n));
                })
                .onErrorResume(IngestController::kafkaFailure, e -> {
                    // Kafka did not take the batch (broker down, metadata timeout, buffer full).
                    // Nothing was acknowledged, so the handheld keeps the batch and retries.
                    log.warn("ingest from {} refused: {}", courierId, e.toString());
                    refused.increment();
                    return Mono.just(ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                            .header("Retry-After", "1")
                            .body(Map.of("detail", "Tracking ingest is unavailable; retry the batch.")));
                });
    }

    private CompletableFuture<Integer> send(String courierId, PingBatch batch) {
        List<CompletableFuture<?>> sends = new ArrayList<>(batch.events().size());
        for (PingBatch.Report r : batch.events()) {
            TrackingRecord record = new TrackingRecord(
                    r.shipmentId(),
                    r.seq(),
                    courierId,
                    r.recordedAt(),
                    r.position() == null ? null : r.position().lat(),
                    r.position() == null ? null : r.position().lon(),
                    r.position() == null ? null : r.position().speed(),
                    r.scan() == null ? null : r.scan().type(),
                    r.scan() == null ? null : r.scan().location());
            sends.add(kafka.send(
                    TrackingTopics.TRACKING_EVENTS,
                    record.shipmentId(),
                    record.toProto().toByteArray()));
        }
        return CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).thenApply(v -> sends.size());
    }

    /** Spring's and the client's Kafka exceptions, wrapped or not. */
    static boolean kafkaFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof org.springframework.kafka.KafkaException
                    || t instanceof org.apache.kafka.common.KafkaException) {
                return true;
            }
        }
        return false;
    }
}
