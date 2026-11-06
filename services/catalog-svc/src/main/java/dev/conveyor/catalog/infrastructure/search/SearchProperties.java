package dev.conveyor.catalog.infrastructure.search;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Queries go to {@code alias}; documents live in the versioned {@code index} behind it. A
 * mapping change later becomes "build products-v2, then move the alias" with no downtime.
 */
@ConfigurationProperties("conveyor.catalog.search")
public record SearchProperties(
        @DefaultValue("http://localhost:9200") String url,
        @DefaultValue("products") String alias,
        @DefaultValue("products-v1") String index,
        @DefaultValue("true") boolean reindexOnStartup,
        @DefaultValue("1000") int bulkSize) {}
