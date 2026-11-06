package dev.conveyor.order.infrastructure.persistence;

import dev.conveyor.order.infrastructure.shard.ShardGroup;
import dev.conveyor.order.infrastructure.shard.ShardRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps the outbox and the idempotency table small. Outbox rows are only needed until
 * Debezium has read them from the WAL (seconds); a day of retention leaves room to inspect
 * them. Idempotency keys are honoured for 24 hours, the window Stripe-style APIs promise: a
 * retry after that creates a new order.
 */
@Component
class Housekeeping {

    private static final Logger log = LoggerFactory.getLogger(Housekeeping.class);

    private final ShardRouter router;

    Housekeeping(ShardRouter router) {
        this.router = router;
    }

    @Scheduled(fixedDelayString = "PT10M", initialDelayString = "PT1M")
    void purge() {
        for (ShardGroup db : router.groups()) {
            int outbox = db.jdbc()
                    .sql("DELETE FROM outbox WHERE created_at < now() - interval '1 day'")
                    .update();
            int keys = db.jdbc()
                    .sql("DELETE FROM idempotency_key WHERE created_at < now() - interval '24 hours'")
                    .update();
            if (outbox + keys > 0) {
                log.info("{}: purged {} outbox rows and {} idempotency keys", db.name(), outbox, keys);
            }
        }
    }
}
