package dev.conveyor.order.infrastructure.catalog;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("conveyor.order.catalog")
public record CatalogProperties(
        @DefaultValue("http://localhost:8081") String url,
        @DefaultValue("500ms") Duration connectTimeout,
        @DefaultValue("1s") Duration readTimeout,
        @DefaultValue("1500ms") Duration quoteBudget,
        @DefaultValue("20") int breakerMinimumCalls,
        @DefaultValue("50") float breakerFailureRatePercent,
        @DefaultValue("5s") Duration breakerOpenFor) {}
