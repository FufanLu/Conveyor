package dev.conveyor.gateway.deals;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DealSettings.class)
class DealConfiguration {

    @Bean
    LaneStats dealLane() {
        return new LaneStats();
    }

    @Bean
    WaitingRooms waitingRooms(
            DealSettings settings, ReactiveStringRedisTemplate redis, LaneStats dealLane, MeterRegistry meters) {
        return new WaitingRooms(settings, redis, dealLane, meters);
    }

    @Bean
    RouterFunction<ServerResponse> waitingRoomRoutes(WaitingRooms rooms) {
        return new WaitingRoomRoutes(rooms, Clock.systemUTC()).routes();
    }

    @Bean
    AdmissionFilter admissionFilter(WaitingRooms rooms) {
        return new AdmissionFilter(rooms.tokens(), Clock.systemUTC());
    }
}
