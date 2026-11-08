package dev.conveyor.gateway.overload;

import dev.conveyor.gateway.deals.LaneStats;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.InetSocketAddress;
import java.security.Principal;
import java.util.Optional;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LoadSheddingFilter.Settings.class)
class OverloadConfiguration {

    @Bean
    LoadSheddingFilter loadSheddingFilter(
            LoadSheddingFilter.Settings settings, MeterRegistry registry, LaneStats dealLane) {
        return new LoadSheddingFilter(settings, registry, dealLane);
    }

    /**
     * Rate-limit key: the signed-in customer (token subject), else the client address. A
     * customer cannot dodge the limit by reconnecting, and anonymous search shares one bucket
     * per address.
     */
    @Bean
    KeyResolver principalOrAddress() {
        return exchange -> exchange.getPrincipal()
                .map(Principal::getName)
                .map(name -> "user:" + name)
                .switchIfEmpty(Mono.fromSupplier(() -> "addr:"
                        + Optional.ofNullable(exchange.getRequest().getRemoteAddress())
                                .map(InetSocketAddress::getHostString)
                                .orElse("unknown")));
    }
}
