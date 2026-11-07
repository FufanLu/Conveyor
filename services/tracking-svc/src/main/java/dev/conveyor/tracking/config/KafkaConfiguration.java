package dev.conveyor.tracking.config;

import com.google.protobuf.InvalidProtocolBufferException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * A failed batch (DynamoDB throttling or down) is retried with backoff for as long as it takes,
 * never skipped: skipping would lose reports, and the conditional writes make the retries
 * harmless. Only bytes that are not a TrackingEvent at all are skipped, at once.
 */
@Configuration(proxyBeanMethods = false)
class KafkaConfiguration {

    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(200, 2);
        backOff.setMaxInterval(10_000);
        DefaultErrorHandler handler = new DefaultErrorHandler(backOff);
        handler.addNotRetryableExceptions(InvalidProtocolBufferException.class);
        return handler;
    }
}
