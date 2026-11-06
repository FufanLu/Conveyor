package dev.conveyor.order.infrastructure.shard;

import javax.sql.DataSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * One physical database and the contiguous range of logical shards it holds. Everything for a
 * shard (order tables, idempotency keys, outbox) lives in the same database, so one local
 * transaction covers an order and its event.
 */
public record ShardGroup(
        String name,
        int firstShard,
        int lastShard,
        DataSource dataSource,
        JdbcClient jdbc,
        NamedParameterJdbcTemplate batch,
        TransactionTemplate tx) {

    public boolean holds(int shard) {
        return shard >= firstShard && shard <= lastShard;
    }
}
