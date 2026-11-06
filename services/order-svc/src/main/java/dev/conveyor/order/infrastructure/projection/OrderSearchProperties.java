package dev.conveyor.order.infrastructure.projection;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("conveyor.order.search")
public record OrderSearchProperties(
        @DefaultValue("http://localhost:9200") String url,
        @DefaultValue("orders") String alias,
        @DefaultValue("orders-v1") String index) {}
