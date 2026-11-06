package dev.conveyor.order.infrastructure.shard;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** The two physical Postgres databases that hold the 16 logical order shards (0-7 and 8-15). */
@ConfigurationProperties("conveyor.order.databases")
public record OrderDatabaseProperties(Database pg0, Database pg1) {

    public record Database(
            String url,
            String username,
            String password,
            @DefaultValue("16") int maximumPoolSize,
            @DefaultValue("2000") long connectionTimeoutMillis,
            // How long startup keeps trying to reach the database before giving up. Hikari's
            // default (1 ms) turns a database that is still starting, or a blip, into a crash
            // loop; seen with Testcontainers on a loaded machine.
            @DefaultValue("30000") long initializationFailTimeoutMillis) {}
}
