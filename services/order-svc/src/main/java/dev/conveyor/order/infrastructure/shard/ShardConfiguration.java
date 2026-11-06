package dev.conveyor.order.infrastructure.shard;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Two physical databases as two DataSource beans (so Spring Boot's health and pool metrics see
 * both), each migrated by its own Flyway run with the shard range as placeholders.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrderDatabaseProperties.class)
class ShardConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ShardConfiguration.class);

    @Bean(destroyMethod = "close")
    HikariDataSource ordersPg0DataSource(OrderDatabaseProperties properties) {
        return dataSource("orders-pg0", properties.pg0());
    }

    @Bean(destroyMethod = "close")
    HikariDataSource ordersPg1DataSource(OrderDatabaseProperties properties) {
        return dataSource("orders-pg1", properties.pg1());
    }

    @Bean
    ShardRouter shardRouter(HikariDataSource ordersPg0DataSource, HikariDataSource ordersPg1DataSource) {
        return new ShardRouter(
                List.of(group("pg0", 0, 7, ordersPg0DataSource), group("pg1", 8, 15, ordersPg1DataSource)));
    }

    private static ShardGroup group(String name, int first, int last, HikariDataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/shard")
                .placeholders(Map.of("first_shard", Integer.toString(first), "last_shard", Integer.toString(last)))
                .load();
        int applied = flyway.migrate().migrationsExecuted;
        log.info("order database {} holds shards {}-{}; {} migration(s) applied", name, first, last, applied);
        return new ShardGroup(
                name,
                first,
                last,
                dataSource,
                JdbcClient.create(dataSource),
                new NamedParameterJdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    private static HikariDataSource dataSource(String pool, OrderDatabaseProperties.Database db) {
        HikariConfig config = new HikariConfig();
        config.setPoolName(pool);
        config.setJdbcUrl(db.url());
        config.setUsername(db.username());
        config.setPassword(db.password());
        config.setMaximumPoolSize(db.maximumPoolSize());
        config.setConnectionTimeout(db.connectionTimeoutMillis());
        config.setInitializationFailTimeout(db.initializationFailTimeoutMillis());
        // Batched line inserts become one multi-row INSERT on the wire.
        config.addDataSourceProperty("reWriteBatchedInserts", "true");
        return new HikariDataSource(config);
    }
}
