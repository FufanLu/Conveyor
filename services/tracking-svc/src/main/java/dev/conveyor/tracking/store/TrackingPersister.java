package dev.conveyor.tracking.store;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.conveyor.proto.tracking.v1.TrackingEvent;
import dev.conveyor.tracking.domain.TrackingRecord;
import dev.conveyor.tracking.ingest.TrackingTopics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Kafka -> DynamoDB. Works in batches (one poll): every report is appended with its own
 * conditional put, all in flight at once; the shipment item then gets one position update per
 * shipment (the newest in the batch), not one per report. A courier sending a ping a second
 * with 10 s batches costs 10 event writes and 1 shipment write.
 *
 * <p>The batch is acknowledged only after every write completed; a failure makes the container
 * redeliver the batch, and the conditional puts turn the repeats into counted duplicates.
 */
@Component
class TrackingPersister {

    private final EventStore events;
    private final ShipmentStore shipments;
    private final Counter stored;
    private final Counter duplicates;
    private final Counter shipmentWrites;
    private final Timer delay;
    private final DistributionSummary batchSize;

    TrackingPersister(EventStore events, ShipmentStore shipments, MeterRegistry registry) {
        this.events = events;
        this.shipments = shipments;
        this.stored = Counter.builder("tracking.persist.events")
                .tag("outcome", "stored")
                .register(registry);
        this.duplicates = Counter.builder("tracking.persist.events")
                .tag("outcome", "duplicate")
                .register(registry);
        this.shipmentWrites =
                Counter.builder("tracking.persist.shipment.writes").register(registry);
        // Handheld clock to "stored in DynamoDB": shows how far the persister is behind.
        this.delay = Timer.builder("tracking.persist.delay")
                .publishPercentiles(0.5, 0.99)
                .publishPercentileHistogram()
                .register(registry);
        this.batchSize =
                DistributionSummary.builder("tracking.persist.batch.size").register(registry);
    }

    @KafkaListener(
            id = "tracking-persister",
            topics = TrackingTopics.TRACKING_EVENTS,
            groupId = "tracking-persister",
            concurrency = "${conveyor.tracking.persister-concurrency:3}",
            batch = "true")
    void persist(List<ConsumerRecord<String, byte[]>> batch) throws InvalidProtocolBufferException {
        List<TrackingRecord> records = new ArrayList<>(batch.size());
        for (ConsumerRecord<String, byte[]> r : batch) {
            records.add(TrackingRecord.fromProto(TrackingEvent.parseFrom(r.value())));
        }
        List<CompletableFuture<Boolean>> appends = new ArrayList<>(records.size());
        Map<String, TrackingRecord> newestPosition = new LinkedHashMap<>();
        List<CompletableFuture<Boolean>> scans = new ArrayList<>();
        for (TrackingRecord r : records) {
            appends.add(events.append(r));
            if (r.isPosition()) {
                newestPosition.merge(r.shipmentId(), r, (a, b) -> a.seq() >= b.seq() ? a : b);
            } else {
                scans.add(shipments.applyScan(r));
            }
        }
        List<CompletableFuture<Boolean>> moves = new ArrayList<>(newestPosition.size());
        newestPosition.values().forEach(r -> moves.add(shipments.movePosition(r)));
        CompletableFuture.allOf(appends.toArray(CompletableFuture[]::new)).join();
        CompletableFuture.allOf(scans.toArray(CompletableFuture[]::new)).join();
        CompletableFuture.allOf(moves.toArray(CompletableFuture[]::new)).join();
        int fresh = (int) appends.stream().filter(CompletableFuture::join).count();
        stored.increment(fresh);
        duplicates.increment(records.size() - fresh);
        shipmentWrites.increment(moves.size() + scans.size());
        batchSize.record(records.size());
        Instant now = Instant.now();
        records.forEach(r -> delay.record(Duration.between(r.recordedAt(), now)));
    }
}
