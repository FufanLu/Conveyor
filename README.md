<p align="right"><b>English</b> | <a href="README.zh.md">中文</a></p>

# Conveyor

**An order-fulfillment platform built for Prime Day–shaped traffic.** Conveyor follows an order
the way a package rides a fulfillment-center conveyor: checkout → stock reserved in the right FC →
payment authorized → delivery date promised → picked and shipped → every courier scan streamed
back to the customer.

Java 25 and Spring Boot 4.1 microservices. Each piece of middleware is chosen so it maps onto an
AWS managed service: the same design runs on a laptop with Docker and on AWS.

> **Status: M5 is done.** On top of the platform (M0), the catalog (M1: two-level cache, Bloom
> filter, OpenSearch, guarded writes), orders (M2: 16 shards, idempotency, outbox → Debezium →
> Kafka, CQRS read model) and inventory (M3: Redis Lua gate, Postgres facts, reconciler,
> bulkhead), every placed order now runs a Temporal saga: reserve, authorize, commit, promise a
> delivery date, confirm, with a compensation for each step and a durable 15-minute payment
> window. tracking-svc (M5) takes the courier firehose into Kafka, stores it in DynamoDB and
> pushes it to browsers over SSE. See [Roadmap](#roadmap).

## Architecture

```
           React + TS (storefront · live tracking map · ops console)
                               │ HTTPS / SSE
           gateway ── Spring Cloud Gateway ←→ Keycloak (OIDC/JWT)
           request ids · problem+json errors · timeouts · rate limit · load shedding
                               │ REST (public routes)   gRPC (internal)
  ┌─────────────┬──────────────┼──────────────┬────────────────┬──────────────┐
catalog-svc   order-svc      inventory-svc    fulfillment-svc  tracking-svc   payment-mock
OpenSearch    Postgres ×2    Redis buckets    Temporal saga    WebFlux        failure
Caffeine+     16 shards      + PG ledger      delivery promise DynamoDB, SSE  injection
Redis         + outbox       gRPC
  ▲               │ Debezium CDC                    │                ▲
  └── read model ◄── Kafka (KRaft) + schema registry ◄─┴── simulator ───┘
```

Details, data ownership and consistency choices: [docs/architecture.md](docs/architecture.md).

## Stack

| Concern | Choice | AWS counterpart |
|---|---|---|
| Language, framework | Java 25 (virtual threads, records, sealed types), Spring Boot 4.1 | — |
| Build | Gradle 9 Kotlin DSL, convention plugins, version catalog, JDK toolchain provisioning | — |
| Edge, identity | Spring Cloud Gateway, Keycloak | API Gateway, Cognito |
| Service calls | gRPC + Protobuf internally, REST + RFC 9457 errors publicly | — |
| Events | Kafka (KRaft), Apicurio schema registry | MSK, Glue Schema Registry |
| Change data capture | Debezium, transactional outbox | MSK Connect, DMS |
| Workflows | Temporal (order saga, durable timers) | Step Functions, SWF |
| Relational | PostgreSQL ×2, 16 logical shards, explicit router (shard id inside the order id) | Aurora PostgreSQL |
| Key-value | DynamoDB Local (single-table design) | DynamoDB |
| Cache | Redis + Caffeine (two-level) | ElastiCache |
| Search | OpenSearch | OpenSearch Service |
| Resilience | Resilience4j, Netflix concurrency-limits | — |
| Observability | Micrometer, Prometheus, Grafana, OpenTelemetry | CloudWatch, X-Ray |
| Delivery | Docker Compose, Helm on kind, AWS CDK (Java) | EKS |

## What is hard here

The interesting parts, by milestone:

- **Hot-SKU inventory (M3, built).** At a deal start, thousands of checkouts hit one SKU. Four
  strategies were measured on clean runners: a row lock and a conditional UPDATE plateau at
  1.3–1.9k units/s (one row is one queue), 16 bucket rows reach ~5k, and a Redis Lua gate with
  contention-free Postgres inserts reaches 6.5–8.1k units/s at p99 6–90 ms. inventory-svc uses
  the last, with a reconciler that repairs stable drift and rebuilds counters after a Redis
  restart.
- **Order saga (M4, built).** `OrderPlaced` starts one Temporal workflow per order (workflow id =
  order id, so a replayed topic starts nothing twice). Reserve → authorize → commit → delivery
  promise → confirm. A decline releases the stock, and a late commit voids the payment and then
  releases the stock. A PENDING payment waits on a durable timer, which kept running through a
  `kill -9` of the worker.
- **Outbox + CDC (M2, built).** The order row, its idempotency key and its event commit in one
  local transaction on the customer's shard; Debezium publishes the event from the WAL as
  Protobuf bytes. Writes keep working while CDC is down.
- **CQRS (M2, built).** Writes go to sharded Postgres; operations queries read an OpenSearch
  projection that drops duplicates by external versioning.
- **Two-level cache (M1).** Caffeine + Redis with pub/sub invalidation, single-flight against
  stampedes, Bloom filters against penetration, TTL jitter against avalanches.
- **Overload protection (M7).** Token-bucket rate limits plus latency-based adaptive concurrency
  limits at the gateway, shedding low-priority traffic first.
- **Tracking firehose (M5, built).** Ingest only appends to Kafka. A persister writes DynamoDB
  with conditional puts, so duplicates and out-of-order reports cannot move a shipment
  backwards. Every instance has its own live consumer group feeding SSE: history and then live
  with no gap, `Last-Event-ID` resume, and a conflated ops map. At 20,000 reports/s on a
  4-vCPU runner, ingest accepted all 660,000 reports. Coalescing the latest-position writes cut
  shipment writes 4.4×.

## Measured so far

Every number in this repository comes from running the code.

**Chapter 05 (M5).** 2,000 to 20,000 courier reports per second on a 4-vCPU runner: ingest
accepted every report at every step, and live push stayed at a median of tens of milliseconds.
The persister fell minutes behind, because half of its writes were latest-position updates
that are overwritten a second later. Coalescing them to one per shipment per 5 s cut those
writes 4.4× and gave 2.4× the stored throughput. With DynamoDB paused for 20 s mid-run,
couriers saw 0 errors and all 315,000 reports were stored exactly once.
→ [docs/illustrated/05-the-courier-firehose.md](docs/illustrated/05-the-courier-firehose.md)

**Chapter 04 (M4).** Four orders, four saga endings: confirmed, rejected for stock, payment
failed (the hold released before the customer was told), and pending until a callback. A
`kill -9` of the worker during a 150 s payment wait left a 151-second gap in the Temporal event
history, and the saga resumed from where it stopped. Replaying all 160 events on the topic
refused all 80 `OrderPlaced` events as duplicates, and no saga ran twice.
→ [docs/illustrated/04-the-saga.md](docs/illustrated/04-the-saga.md)

**Chapter 03 (M3).** Selling one SKU: a Redis Lua gate sold 10,000 units in 1.2–1.5 s, against
6.3–7.8 s with a Postgres row lock (24 runs, all without oversell). The first version of the service
answered a 10,000-buyer burst with 45% errors and stranded units while buyers were told "sold
out". A bulkhead of 64 in-flight reservations brought that to exactly 6,000 sold, 4,000 refused
and zero errors on the same burst, measured with the bulkhead on and off on clean runners.
→ [docs/illustrated/03-ten-thousand-buyers-one-sku.md](docs/illustrated/03-ten-thousand-buyers-one-sku.md)

**Chapter 02 (M2).** An order commits with its idempotency key and its outbox event in one
transaction; 20 concurrent retries with one key create exactly one order. The first run found a
Debezium task that reported RUNNING and died 10 s later on its first heartbeat, and a projection
that dead-lettered harmless duplicates. Lowering Debezium's poll interval took commit → Kafka
from a 289 ms median to 33–50 ms. With Debezium stopped, orders kept being accepted and appeared
in the read model within a second of it streaming again.
→ [docs/illustrated/02-placing-an-order.md](docs/illustrated/02-placing-an-order.md)

**Chapter 01 (M1).** 1,000 simultaneous requests for one cold SKU cause **1** database load
(textbook cache-aside: 67–151; no cache: 1,000). 10,000 requests for SKUs that do not exist cause
**3** database loads (cache-aside: 10,000), and the measured Bloom false-positive rate of 0.020%
matches the sizing formula exactly. The real run also found problems the test suite had missed,
including a Redis blip that killed startup, a Keycloak realm import that stripped
roles from tokens, and another process silently sharing port 8080 over IPv4.
→ [docs/illustrated/01-reading-a-product-under-a-stampede.md](docs/illustrated/01-reading-a-product-under-a-stampede.md)

**Chapter 00 (M0).** Chapter 00 of the illustrated docs
follows one request through the edge and shows what the first real run found: a duplicated
`X-Request-Id` header, error bodies whose `requestId` did not match the header, and a dead
upstream reported as `500` instead of `503`. It also measures what the gateway hop costs
(about 9% of throughput, +0.9 ms at p99), and what synchronous logging costs. With a healthy
sink the cost is nothing. When the log sink stalls, the gateway is held to the sink's speed:
945 vs 5,783 req/s.
→ [docs/illustrated/00-a-request-through-the-edge.md](docs/illustrated/00-a-request-through-the-edge.md)

## Quick start

Prerequisites: Docker, and any JDK 17+ to run Gradle. The build downloads JDK 25 itself.

```bash
./gradlew build                       # format check, javac -Werror, Error Prone, tests (Testcontainers), coverage
make infra-up PROFILES="search auth events"  # + OpenSearch, Keycloak, Debezium connect, registry
make run-catalog-svc                  # terminal 1 (seeds 100,000 products on first start)
make run-order-svc                    # terminal 2 (migrates both order databases)
make run-inventory-svc                # terminal 3 (gRPC on 9083)
make run-gateway                      # terminal 4
make connectors                       # register the Debezium outbox connectors
curl -i localhost:8080/api/v1/products/B0DAA66D13
curl -s 'localhost:8080/api/v1/search?q=pour-over%20kettle&size=3'
```

Keycloak (dev only) has the realm `conveyor` with users `alice`/`alice` (customer) and `ops`/`ops`
(catalog-admin); `client_id=conveyor-cli` allows the password grant for scripts. Host ports can be
moved when another project already uses them, e.g. `CONVEYOR_OPENSEARCH_PORT=9210` for compose and
`CONVEYOR_OPENSEARCH_URL=http://localhost:9210` for catalog-svc.

## Repository layout

```
build-logic/          Gradle convention plugins (java, spring-service, spring-cloud, protobuf)
libs/platform/        request ids, access log, problem+json errors, platform defaults,
                      and the ServiceContractTest every service runs
libs/proto/           gRPC contracts (inventory reservation protocol)
services/             gateway, catalog-svc, order-svc, inventory-svc, fulfillment-svc,
                      tracking-svc, payment-mock
deploy/compose/       local infrastructure, pinned images, profiles, memory limits
tools/                load generator and reproducible benchmarks
docs/                 architecture and the illustrated walkthroughs
```

## Roadmap

| | Milestone | Status |
|---|---|---|
| M0 | Platform skeleton: build, services, edge, contract tests, infra, CI | done |
| M1 | Catalog + gateway auth (Keycloak) + two-level cache + OpenSearch | done |
| M2 | Orders: sharded Postgres, outbox, Debezium → Kafka, idempotency keys, CQRS read model | done |
| M3 | Inventory: multi-FC stock, Redis gate + Postgres facts, reconciliation, bulkhead, 4-way benchmark | done |
| M4 | Fulfillment: Temporal saga, delivery promise, payment failure injection | done |
| M5 | Tracking: courier event firehose, DynamoDB, SSE | done |
| M6 | Web: storefront, live map, ops console | |
| M7 | Resilience and observability: shedding, circuit breakers, tracing, SLO dashboards, chaos | |
| M8 | Load test and delivery: Prime Day scenario, Helm on kind, AWS CDK | |
