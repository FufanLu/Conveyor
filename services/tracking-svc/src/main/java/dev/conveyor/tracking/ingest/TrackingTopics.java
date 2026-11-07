package dev.conveyor.tracking.ingest;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * tracking-svc owns tracking.events and declares it (the broker refuses implicit topic
 * creation). 24 partitions keyed by shipment id: one shipment's reports stay in order, and the
 * persister can spread over up to 24 consumers.
 */
@Configuration(proxyBeanMethods = false)
public class TrackingTopics {

    public static final String TRACKING_EVENTS = "tracking.events";
    public static final String ORDER_EVENTS = "orders.events";

    @Bean
    NewTopic trackingEvents() {
        return TopicBuilder.name(TRACKING_EVENTS).partitions(24).replicas(1).build();
    }
}
