package dev.conveyor.tracking.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletionException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.Test;

class IngestUnitTest {

    @Test
    void kafkaFailuresAreRecognizedThroughWrappers() {
        assertThat(IngestController.kafkaFailure(new CompletionException(new TimeoutException("metadata"))))
                .isTrue();
        assertThat(IngestController.kafkaFailure(new org.springframework.kafka.KafkaException("send failed")))
                .isTrue();
        assertThat(IngestController.kafkaFailure(new IllegalStateException("bug")))
                .isFalse();
    }
}
