# 02 — Placing an order: one transaction, one event

M2 adds order-svc. An order is written to one of 16 logical shards on two Postgres
databases. The same local transaction claims the idempotency key and writes the
`OrderPlaced` event to an outbox table. Debezium reads that table from the write-ahead log
and publishes the event to Kafka, and a projection turns the events into an OpenSearch read
model for operators. This chapter places one order and follows it to the end of that
pipeline, then breaks the pipeline on purpose.

> Produced by running the M2 build against compose infrastructure (Postgres 18.6, Kafka 4.4.0,
> Debezium 3.7.0, OpenSearch 3.9.0, Keycloak 26.8.2) on a MacBook Pro M3 Pro whose load average
> was 14–20 from other builds. **Latencies are indicative only.** Where a number matters, the
> chapter shows repeated runs. Gateway on `:18080` and OpenSearch on `:9210` because other
> processes held the defaults (chapter 01, section 8c).

## The path

```mermaid
sequenceDiagram
    autonumber
    participant C as alice
    participant G as gateway
    participant O as order-svc
    participant K as catalog-svc
    participant P as Postgres pg-1<br/>(shards 8-15)
    participant D as Debezium
    participant Q as Kafka<br/>orders.events
    participant S as OpenSearch<br/>orders

    C->>G: POST /api/v1/orders + Idempotency-Key + Bearer token
    G->>O: POST /v1/orders (token checked; X-Forwarded-Prefix /api)
    par one virtual thread per SKU, 1.5 s budget
        O->>K: GET /v1/products/B078DDE6C4
        O->>K: GET /v1/products/B0DAA66D13
    end
    Note over O: order id = time + shard(alice) + worker + seq
    O->>P: BEGIN
    O->>P: INSERT idempotency_key … ON CONFLICT DO NOTHING
    O->>P: INSERT orders_10, order_line_10 (batched)
    O->>P: INSERT outbox (OrderPlaced, Protobuf bytes)
    O->>P: COMMIT
    O-->>C: 201 Created, Location /api/v1/orders/033M0SSFTG000
    P-->>D: WAL → logical replication slot orders_outbox_pg1
    D->>Q: outbox event router: key = order id, value = the bytes
    Q->>O: projection consumer (group order-search-projection)
    O->>S: index orders-v1/_doc/{id}, version_type=external
```

Everything up to the `201` is synchronous and touches exactly one database. Everything after
it is asynchronous, and the write path does not wait for it or depend on it (section 6).

## 1. The order

```
$ curl -i -X POST localhost:18080/api/v1/orders \
    -H "Authorization: Bearer $ALICE" -H 'Idempotency-Key: checkout-1793968652-a1' \
    -d '{"items":[{"sku":"B078DDE6C4","quantity":1},{"sku":"B0DAA66D13","quantity":2}],"shipToRegion":"us-east"}'

HTTP/1.1 201 Created
Location: http://localhost:18080/api/v1/orders/033M0SSFTG000
Content-Type: application/json
X-Request-Id: checkout-demo-0002

{"orderId":"033M0SSFTG000","status":"PLACED",
 "lines":[{"sku":"B078DDE6C4","quantity":1,"unitPriceCents":1299,"lineTotalCents":1299},
          {"sku":"B0DAA66D13","quantity":2,"unitPriceCents":16703,"lineTotalCents":33406}],
 "totalCents":34705,"currency":"USD","shipToRegion":"us-east","version":1,
 "placedAt":"2026-11-06T12:38:13.630935Z"}
```

The client sends SKUs and quantities, never prices. The unit prices come from catalog-svc at
placement time. $12.99 is the price chapter 01 set with `If-Match`, and it is now frozen into
the order line, so later price changes do not affect this order. The `Location` header carries
the public host and path: the gateway writes `X-Forwarded-Host` and `X-Forwarded-Prefix`, and
order-svc builds the URI from them.

That was the *second* attempt with this key. The first one, right after the services started:

```
HTTP/1.1 503 Service Unavailable
Retry-After: 2

{"detail":"Prices cannot be confirmed right now. Retry with the same Idempotency-Key.", …}
```

```
04:37:32.333 order-svc   POST /api/v1/orders          -> 503 in 1752837 us
04:37:32.430 catalog-svc GET /v1/products/B0DAA66D13  -> 200 in  954260 us
04:37:32.430 catalog-svc GET /v1/products/B078DDE6C4  -> 200 in  954522 us
```

Both catalog lookups were first requests to a freshly started catalog-svc (JIT, connection
pools, first Redis and Postgres round trips), and order-svc's own first request also paid for
lazy JWKS discovery. The 1.5 s pricing budget ran out. The catalog's `200`s finished about 100 ms
after order-svc had already answered `503`. Nothing was stored, so the retry with the same key
created the order normally (no `Idempotent-Replayed` header). `PlaceOrderApiTest.catalogOutageIs503AndLeavesTheKeyFreeForTheRetry`
pins this behaviour. The same budget also rejected 1 of 5 orders later in the session, when two
catalog lookups took 904 ms each on the loaded laptop. Hedged requests to catalog-svc are on the
M7 list.

## 2. Where it lives: the shard gene

`033M0SSFTG000` is 13 characters of Crockford base32 encoding a 64-bit id:

```
0x018E80CE5FA80000 = 0 | 00000110001110100000001100111001011111101010 | 1010 | 00000000 | 0000000000
                     ↑   41 bits: 26,743,093,630 ms since 2026-01-01      shard   worker     sequence
                    sign  = 2026-11-06T12:38:13.630Z (placedAt: …13.630935Z)  10      0          0
```

The customer's shard is `Shards.forUser(sub)`, a 64-bit FNV-1a hash of the Keycloak subject
folded to 4 bits. alice (`6ba64ca1-f001-4f34-b72f-5dd3880ec474`) maps to **shard 10**. The order
id carries it, so `GET /v1/orders/{id}` goes straight to shard 10 without a global id → shard
lookup, and "my orders" goes there too. Every query order-svc runs is single-shard by
construction.

Shards 0–7 live on pg-0 and 8–15 on pg-1, each shard as its own pair of tables:

```
pg-0: idempotency_key order_line_00 … order_line_07 orders_00 … orders_07 outbox
pg-1: idempotency_key order_line_08 … order_line_15 orders_08 … orders_15 outbox

rows after this session:  orders_10 = 61, every other shard 0   (all orders are alice's)
```

The spread over many customers, for 160,000 synthetic subjects:

```
 0:10051  1:10044  2:10062  3: 9980  4:10158  5: 9890  6:10048  7: 9877
 8: 9984  9:10123 10: 9906 11: 9940 12: 9903 13: 9957 14: 9984 15:10093
min 9877, max 10158 (expected 10000 each; spread 2.8%)
```

Why a hand-written router rather than ShardingSphere: the outbox row has to commit in the same
local transaction as the order row, which is easy when the code chooses the database explicitly.
Every query is single-shard by design, so a SQL-rewriting layer would add a large dependency
(and a Spring Boot 4 compatibility question) and remove nothing. Sixteen logical shards on two
databases means scaling out later is "move whole tables, change the mapping", never "re-hash
customers".

## 3. Idempotency: the key commits with the order

```sql
INSERT INTO idempotency_key (user_id, idem_key, request_hash, order_id)
VALUES (:user, :key, :hash, :order) ON CONFLICT DO NOTHING
```

If the insert claims the key, the order, its lines and the outbox row follow in the same
transaction. If it does not, the key exists: when the SHA-256 of the canonical request (sorted
items + region) matches, the stored order is replayed, otherwise the request is refused.
Because the key row and the order commit together, there is no window in which a key exists
without its order, or an order without its key.

Live, against the same key:

| Request | Answer |
|---|---|
| same key, same body | `201`, `Idempotent-Replayed: true`, the original order `033M0SSFTG000` |
| same key, different body | `422` "This Idempotency-Key was already used for a different request." |
| no key | `400` "Send an Idempotency-Key header …" |
| unknown SKU | `422` with `"unknownSkus":["B0FFFFFFFF"]` |

Concurrent retries are where idempotency usually breaks. `twentyConcurrentRetriesCreateExactlyOneOrder`
sends 20 requests with the same key at the same moment. The 19 losers block on the primary key
until the winner commits, then take the replay path. The result is 20 × `201`, one order id,
19 × `Idempotent-Replayed`, and one outbox row. Keys are honoured for 24 hours, the same window
Stripe-style APIs promise.

## 4. The event: outbox → WAL → Kafka

The outbox row, on pg-1:

```
                  id                  | aggregatetype |  aggregateid  |    type     | payload_bytes |          created_at
--------------------------------------+---------------+---------------+-------------+---------------+-------------------------------
 01a1b0dd-e217-7642-87b1-d0c2699bf9e8 | orders        | 033M0SSFTG000 | OrderPlaced |           166 | 2026-11-06 12:38:13.672951+00
```

and the Kafka record Debezium made from it (`tools/events/TailOrderEvents.java`):

```
orders.events-6@0  timestamp 2026-11-06T12:40:40.174Z (CreateTime)
key     033M0SSFTG000
header  id = 01a1b0dd-e217-7642-87b1-d0c2699bf9e8
header  eventType = OrderPlaced
header  eventId = 01a1b0dd-e217-7642-87b1-d0c2699bf9e8
header  __debezium.context.connectorLogicalName = orders-pg1    (+ taskId, connectorName, runId)
value   166 bytes: 0a 24 30 31 61 31 62 30 64 64 2d 65 32 31 37 2d …
event_id: "01a1b0dd-e217-7642-87b1-d0c2699bf9e8"
order_id: "033M0SSFTG000"
order_version: 1
occurred_at { seconds: 1793968693 nanos: 630935000 }
placed {
  user_id: "6ba64ca1-f001-4f34-b72f-5dd3880ec474"
  lines { sku: "B078DDE6C4" quantity: 1 unit_price_cents: 1299 }
  lines { sku: "B0DAA66D13" quantity: 2 unit_price_cents: 16703 }
  total_cents: 34705  currency: "USD"  ship_to_region: "us-east"
}
```

The value is the same 166 bytes order-svc wrote. With `ByteArrayConverter` Debezium does not
re-encode anything, so the contract is the Protobuf schema in `libs/proto`
(`conveyor.order.v1.OrderEvent`), with no JSON in between. The key is the order id, so every
event of one order lands on one partition, in order.

The record's timestamp is **two and a half minutes after the commit**. That is not latency. It
is the first thing the real run found.

### 4a. The connector reported RUNNING and was dead

`make connectors` registered both connectors and printed `"state":"RUNNING"` for both. Ten
seconds later both tasks died:

```
ERROR WorkerSourceTask{id=orders-outbox-pg1-0} Task threw an uncaught and unrecoverable exception.
Caused by: org.apache.kafka.connect.errors.DataException: Invalid schema type for ByteArrayConverter: STRUCT
```

Ten seconds is `heartbeat.interval.ms`. Debezium's heartbeat records have a STRUCT value. They
do not go through the outbox router, so they reached the byte-array converter as structs and
failed. The fix keeps the heartbeats, which advance the replication slot when the outbox is
quiet, and drops them right before conversion: a `Filter` SMT with a `TopicNameMatches`
predicate on `__debezium-heartbeat.*`, and the outbox router negated on the same predicate.
Connect still commits the offsets of filtered records. `register.sh` now waits past the first
heartbeat and fails if any task is `FAILED`.

Nothing was lost. The order above had been committed at 12:38:13 while the connector was dead.
When the fixed connector started at 12:40:40, Debezium resumed from the slot's confirmed LSN
and delivered it.

### 4b. Why commit → Kafka took ~290 ms

With the connector healthy, `tools/bench/order-pipeline-latency.py` placed orders one after
another and timed each stage:

| Debezium `poll.interval.ms` | Run | commit → Kafka (min / median / max) | 201 → searchable (min / median / max) |
|---|---|---|---|
| 500 (default) | 1 | 7 / **289** / 483 ms | 353 / 461 / 1,014 ms |
| 50 | 1 | 10 / **33** / 60 ms | 50 / 71 / 167 ms |
| 50 | 2 | 10 / **50** / 93 ms | 42 / 90 / 472 ms |

20 orders per run. "commit" is `outbox.created_at`, the transaction's start time. "searchable"
means the projection's document can be fetched by id.

The shape of the default row gave the cause away: a minimum of 7 ms and a ceiling just under
500 ms. The connector reads WAL continuously into an internal queue, and Connect drains that
queue every `poll.interval.ms`, so an event waits half a poll interval on average. At 50 ms, the
whole path from commit to searchable order is under 100 ms at the median. The cost is a poll
loop that wakes up more often, which is negligible next to WAL decoding.

## 5. The read model

The projection consumes `orders.events` with three consumers over twelve partitions and indexes
one document per order, using external versioning with `order_version`. Kafka delivers at
least once (and Debezium can resend after a restart), so a duplicate must be harmless:
OpenSearch refuses a version that is not higher than the indexed one, and the projection counts
the refusal as a duplicate.

Operators query the read model, never the shards:

```
GET /api/v1/ops/orders?sku=B0DAA66D13&size=3        (token with realm role "ops")

total 61
  033M1XRB2G000 PLACED 18002 2 2026-11-06T12:43:08.248768Z v1
  033M1XQM2G000 PLACED 18002 2 2026-11-06T12:43:08.064880Z v1
  033M1XQ7TG000 PLACED 18002 2 2026-11-06T12:43:07.966747Z v1

same request with alice's token → 403
```

A cross-customer query like "all orders containing SKU x" would hit all 16 shards on the write
side. Here it is one search, and it lags the database by the CDC pipeline (section 4b).

### 5a. Duplicates were treated as failures

`OrderProjectionTest` sends the same event twice and waits for the duplicate counter. The
counter never moved. Instead the consumer logged `Record in retry and not yet recovered`, seeked
back, failed again, and after four attempts dead-lettered a perfectly good duplicate. The cause:

```
projection failed at orders.events-6@1 (key 0ABCDEFGH7024):
  org.opensearch.client.transport.httpclient5.ResponseException: method [PUT], …
  URI [/orders-v1/_doc/0ABCDEFGH7024?version_type=external&version=1], status line [HTTP/1.1 409 Conflict]
```

The handler caught `OpenSearchException` with status 409, but the Java client reported this 409
as the transport's `ResponseException`, which is an `IOException`. `VersionConflicts.is(…)` now
recognises both shapes. catalog-svc's indexer (chapter 01) had the same catch in its code, so
such a 409 would have been retried and logged as a failure there too. Its test passed anyway,
because the stale write was refused either way. Both are fixed.

### 5b. Two smaller ones

- If the projection writes before the index exists, OpenSearch auto-creates `orders-v1` with
  dynamic mappings (SKUs as analysed text) and no alias. The listener therefore starts with
  `autoStartup=false`, and the bootstrap starts it once index and alias exist.
- `/api/v1/ops/orders` returned 404 at the gateway: the route was missing. It is now a route,
  and `GatewayRoutingTest` lists it.

## 6. Pulling the plug on CDC

The point of the outbox is that the write path does not depend on Kafka or Debezium. To test
it: stop the connect container, place five orders, then start it again.

```
04:49:46 connect stopped
04:49:47 order 033M3EE26G000: HTTP 201 in 1102 ms
04:49:47 order 033M3EF56G000: HTTP 201 in 154 ms
04:49:47 order 033M3EFF2G000: HTTP 201 in 81 ms
04:49:47 order 033M3EFREG000: HTTP 201 in 196 ms
04:49:47 order 033M3EGTPG000: HTTP 201 in 151 ms
04:49:52 5 s later, searchable: 0/5
04:49:55 connect starting
04:50:08 all 5 searchable, 15.4–15.7 s after 'docker compose start connect'
```

Customers kept placing orders. The events waited in Postgres's WAL, held by the replication
slot, and arrived once Debezium was back. Almost all of the 15 s was Kafka Connect starting
(worker initialisation 5.1 s, connectors running 4 s later); from the moment streaming resumed
to all five orders being searchable took under a second.

The first time this experiment ran, the same catch-up took **138 s**, because Connect's
worker initialisation alone took 127,210 ms: it scanned every class on its plugin path. With
`CONNECT_PLUGIN_DISCOVERY=service_load` (KIP-898), Connect finds plugins through their
ServiceLoader manifests instead, and initialisation took 4,093 and 5,114 ms in the two runs
since.

The price of the replication slot: while Debezium is down, Postgres keeps every WAL segment
since the slot's confirmed position. An outage of hours on a busy database fills disks.
Alerting on slot lag is on the M7 list.

## 7. Infrastructure found along the way

- Apicurio Registry 3 serves health on its management port, 9000 (`/health/ready`), not on 8080.
  The compose health check pointed at 8080, so the container reported unhealthy.
- After `register.sh` was rewritten, the connect container briefly read the new file with the
  old file's length (Docker Desktop's file sharing) and failed with a bash syntax error. A
  second run read it correctly.

## 8. What it costs to run

| Container | Memory |
|---|---|
| Debezium connect (2 connectors) | 406 MiB |
| Apicurio registry | 272 MiB |
| Kafka | 571 MiB |
| Postgres pg-0 / pg-1 | 132 / 68 MiB |
| OpenSearch (products + orders) | 1.03 GiB |

## Reproduce

```bash
./gradlew build                                   # includes order-svc's Testcontainers suite (2 x Postgres, Kafka, OpenSearch)
make infra-up PROFILES="search auth events"
java -jar services/catalog-svc/build/libs/catalog-svc.jar &
java -jar services/order-svc/build/libs/order-svc.jar &      # migrates both databases, creates the publication
java -jar services/gateway/build/libs/gateway.jar &
make connectors                                   # Debezium outbox connectors; fails if a task dies

TOKEN=$(curl -s localhost:8180/realms/conveyor/protocol/openid-connect/token \
  -d grant_type=password -d client_id=conveyor-cli -d username=alice -d password=alice | jq -r .access_token)
curl -i -X POST localhost:8080/api/v1/orders -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: demo-$(date +%s)" -H 'Content-Type: application/json' \
  -d '{"items":[{"sku":"B078DDE6C4","quantity":1}],"shipToRegion":"us-east"}'

java -Djarmode=tools -jar services/order-svc/build/libs/order-svc.jar extract --destination build/order-svc-extracted
java -Dlogback.configurationFile=tools/events/logback-quiet.xml -cp 'build/order-svc-extracted/lib/*' \
  tools/events/TailOrderEvents.java localhost:9092 - 5
python3 tools/bench/order-pipeline-latency.py --gateway http://localhost:8080 --opensearch http://localhost:9200
```
