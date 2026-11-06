package dev.conveyor.order.infrastructure.projection;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

/**
 * order-svc owns orders.events and declares it (the broker refuses implicit topic creation).
 * 12 partitions keyed by order id: ordering per order, parallelism across orders.
 */
@Configuration(proxyBeanMethods = false)
public class OrderTopics {

    public static final String ORDER_EVENTS = "orders.events";
    public static final String ORDER_EVENTS_DLT = "orders.events.dlt";

    @Bean
    NewTopic orderEvents() {
        return TopicBuilder.name(ORDER_EVENTS).partitions(12).replicas(1).build();
    }

    @Bean
    NewTopic orderEventsDeadLetters() {
        return TopicBuilder.name(ORDER_EVENTS_DLT).partitions(1).replicas(1).build();
    }
}
