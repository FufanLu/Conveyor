-- The logical replication publication Debezium reads. order-svc owns the outbox table, so it
-- owns the publication too; the connector runs with publication.autocreate.mode=disabled and
-- does not depend on startup order.
CREATE PUBLICATION orders_outbox FOR TABLE outbox;
