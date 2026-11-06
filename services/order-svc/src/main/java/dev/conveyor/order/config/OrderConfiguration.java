package dev.conveyor.order.config;

import dev.conveyor.order.domain.OrderIdGenerator;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class OrderConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OrderConfiguration.class);

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The worker id must be unique per running instance: two instances with the same worker id
     * can mint the same order id in the same millisecond. Kubernetes passes the StatefulSet
     * ordinal (M8); locally it defaults to 0.
     */
    @Bean
    OrderIdGenerator orderIdGenerator(Clock clock, @Value("${conveyor.order.worker-id:0}") int workerId) {
        log.info("order id worker {}", workerId);
        return new OrderIdGenerator(clock, workerId);
    }
}
