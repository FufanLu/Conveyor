-- Runs once per physical database with that database's shard range:
-- pg-0 gets orders_00..orders_07, pg-1 gets orders_08..orders_15.
DO $$
DECLARE
    s text;
BEGIN
    FOR i IN ${first_shard}..${last_shard} LOOP
        s := lpad(i::text, 2, '0');
        EXECUTE format($t$
            CREATE TABLE orders_%1$s (
                order_id       bigint      PRIMARY KEY,
                user_id        text        NOT NULL,
                status         text        NOT NULL,
                total_cents    bigint      NOT NULL CHECK (total_cents >= 0),
                currency       char(3)     NOT NULL,
                ship_to_region text        NOT NULL,
                version        bigint      NOT NULL CHECK (version >= 1),
                placed_at      timestamptz NOT NULL,
                updated_at     timestamptz NOT NULL)$t$, s);
        -- "My orders, newest first" is an index range scan: order ids grow with time.
        EXECUTE format('CREATE INDEX orders_%1$s_user_idx ON orders_%1$s (user_id, order_id DESC)', s);
        EXECUTE format($t$
            CREATE TABLE order_line_%1$s (
                order_id         bigint      NOT NULL REFERENCES orders_%1$s ON DELETE CASCADE,
                line_no          int         NOT NULL,
                sku              varchar(10) NOT NULL,
                quantity         int         NOT NULL CHECK (quantity > 0),
                unit_price_cents bigint      NOT NULL CHECK (unit_price_cents >= 0),
                PRIMARY KEY (order_id, line_no))$t$, s);
    END LOOP;
END
$$;

-- Idempotency keys commit in the same transaction as the order they created. A concurrent
-- request with the same key blocks on the primary key until the first one commits, then sees
-- the row and replays the order.
CREATE TABLE idempotency_key (
    user_id      text        NOT NULL,
    idem_key     text        NOT NULL,
    request_hash bytea       NOT NULL,
    order_id     bigint      NOT NULL,
    created_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idem_key)
);

-- Transactional outbox, read by Debezium from the WAL. Column names follow Debezium's outbox
-- event router defaults (id, aggregatetype, aggregateid, type, payload).
CREATE TABLE outbox (
    id            uuid        PRIMARY KEY,
    aggregatetype text        NOT NULL,
    aggregateid   text        NOT NULL,
    type          text        NOT NULL,
    payload       bytea       NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX outbox_created_idx ON outbox (created_at);
CREATE INDEX idempotency_key_created_idx ON idempotency_key (created_at);
