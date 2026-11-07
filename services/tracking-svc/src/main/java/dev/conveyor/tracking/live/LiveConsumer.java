package dev.conveyor.tracking.live;

import com.google.protobuf.InvalidProtocolBufferException;
import dev.conveyor.proto.tracking.v1.TrackingEvent;
import dev.conveyor.tracking.domain.TrackingRecord;
import dev.conveyor.tracking.ingest.TrackingTopics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * A browser's SSE connection lands on any instance, but the persister group splits partitions
 * between instances, so no single instance sees every report. The live consumer therefore uses
 * a group of its own per instance (random suffix) starting at the latest offset: every instance
 * sees every report, and nothing is committed that matters, because history comes from DynamoDB.
 */
@Component
class LiveConsumer {

    private final LiveHub hub;

    LiveConsumer(LiveHub hub) {
        this.hub = hub;
    }

    @KafkaListener(
            id = "tracking-live",
            topics = TrackingTopics.TRACKING_EVENTS,
            groupId = "tracking-live-#{T(java.util.UUID).randomUUID()}",
            properties = {"auto.offset.reset=latest", "enable.auto.commit=true"})
    void on(ConsumerRecord<String, byte[]> record) throws InvalidProtocolBufferException {
        hub.publish(TrackingRecord.fromProto(TrackingEvent.parseFrom(record.value())));
    }
}
