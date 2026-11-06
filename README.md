<p align="right"><b>English</b> | <a href="README.zh.md">中文</a></p>

# Conveyor

**An order-fulfillment platform built for Prime Day–shaped traffic.** Conveyor follows an order
the way a package rides a fulfillment-center conveyor: checkout → stock reserved in the right FC →
payment authorized → delivery date promised → picked and shipped → every courier scan streamed
back to the customer.

Java 25 and Spring Boot 4.1 microservices. Each piece of middleware is chosen so it maps onto an
AWS managed service: the same design runs on a laptop with Docker and on AWS.

> **Status: M0, the platform skeleton, is done.** Seven services build, boot and pass a shared
> contract test suite. The edge routes, tags and reports errors, and core infrastructure runs in
> Docker. Business logic arrives milestone by milestone (see [Roadmap](#roadmap)).

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
| Relational | PostgreSQL ×2, ShardingSphere-JDBC (16 shards) | Aurora PostgreSQL |
| Key-value | DynamoDB Local (single-table design) | DynamoDB |
| Cache | Redis + Caffeine (two-level) | ElastiCache |
| Search | OpenSearch | OpenSearch Service |
| Resilience | Resilience4j, Netflix concurrency-limits | — |
| Observability | Micrometer, Prometheus, Grafana, OpenTelemetry | CloudWatch, X-Ray |
| Delivery | Docker Compose, Helm on kind, AWS CDK (Java) | EKS |

## What is hard here

The interesting parts, by milestone:

- **Hot-SKU inventory (M3).** At a deal start, thousands of checkouts hit one SKU. Stock is split
  into Redis buckets reserved with atomic Lua scripts and reconciled against a Postgres ledger.
  Four strategies (row lock, CAS, single Lua key, buckets) get benchmarked side by side.
- **Order saga (M4).** Reserve stock, authorize payment, allocate an FC, ship. Any step can fail
  and is compensated. An unpaid order is cancelled by a durable Temporal timer, not a delay queue.
- **Outbox + CDC (M2).** The order row and its event are written in one local transaction;
  Debezium publishes the event from the WAL. Consumers are idempotent.
- **CQRS (M2).** Writes go to sharded Postgres; search and ops queries read an OpenSearch model.
- **Two-level cache (M1).** Caffeine + Redis with pub/sub invalidation, single-flight against
  stampedes, Bloom filters against penetration, TTL jitter against avalanches.
- **Overload protection (M7).** Token-bucket rate limits plus latency-based adaptive concurrency
  limits at the gateway, shedding low-priority traffic first.
- **Tracking firehose (M5).** Simulated couriers report GPS; Kafka partitions by shipment;
  tracking-svc writes DynamoDB and pushes to the browser map over SSE.

## Measured so far

Every number in this repository comes from running the code. Chapter 00 of the illustrated docs
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
./gradlew build                       # format check, javac -Werror, Error Prone, tests, coverage
make infra-up                         # Postgres ×2, Redis, Kafka   (PROFILES="search obs" for more)
make run-catalog-svc                  # in one terminal
make run-gateway                      # in another
curl -i localhost:8080/api/v1/products/B0CONVEYOR1
```

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
| M1 | Catalog + gateway auth (Keycloak) + two-level cache + OpenSearch | |
| M2 | Orders: sharded Postgres, outbox, Debezium → Kafka, idempotency keys | |
| M3 | Inventory: multi-FC stock, bucketed reservations, reconciliation, 4-way benchmark | |
| M4 | Fulfillment: Temporal saga, delivery promise, payment failure injection | |
| M5 | Tracking: courier event firehose, DynamoDB, SSE | |
| M6 | Web: storefront, live map, ops console | |
| M7 | Resilience and observability: shedding, circuit breakers, tracing, SLO dashboards, chaos | |
| M8 | Load test and delivery: Prime Day scenario, Helm on kind, AWS CDK | |
