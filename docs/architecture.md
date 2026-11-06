# Architecture

Conveyor follows one order from checkout to the customer's door: place the order, reserve stock
in the right fulfillment center (FC), authorize payment, promise a delivery date, pick and ship,
then stream every courier scan back to the customer. The load model is a Prime Day peak: a
burst of checkouts at a deal start, plus a constant firehose of tracking events.

This document describes the target system. Each section says which milestone builds it;
anything not yet built is marked **planned**.

## System view

```
           React + TS (storefront · live tracking map · ops console)        [M6]
                               │ HTTPS / SSE
           gateway ── Spring Cloud Gateway ←→ Keycloak (OIDC/JWT)           [M0, auth M1]
           request ids · problem+json errors · timeouts · rate limit · load shedding
                               │ REST (public routes)   gRPC (internal)
  ┌─────────────┬──────────────┼──────────────┬────────────────┬──────────────┐
catalog-svc   order-svc      inventory-svc    fulfillment-svc  tracking-svc   payment-mock
OpenSearch    Postgres ×2    Redis buckets    Temporal saga    WebFlux        failure
Caffeine+     16 shards      + PG ledger      delivery promise DynamoDB, SSE  injection
Redis         + outbox       gRPC
  ▲               │ Debezium CDC                    │                ▲
  └── read model ◄── Kafka (KRaft) + schema registry ◄─┴── simulator ───┘
                                       (deal-start traffic, courier GPS events)
```

## Services

| Service | Port | Stack | Owns | Milestone |
|---|---|---|---|---|
| `gateway` | 8080 | WebFlux (Spring Cloud Gateway) | public API surface, edge policies | M0 (routing, ids, errors) · M1 auth · M7 shedding |
| `catalog-svc` | 8081 | MVC + virtual threads | products, search index | M1 |
| `order-svc` | 8082 | MVC + virtual threads | orders (sharded), outbox | M2 |
| `inventory-svc` | 8083 (gRPC 9083) | MVC + virtual threads | stock per FC, reservations | M3 |
| `fulfillment-svc` | 8084 | Temporal workers | order saga, delivery promise | M4 |
| `tracking-svc` | 8085 | WebFlux | shipment events, live push | M5 |
| `payment-mock` | 8086 | MVC + virtual threads | payment authorizations (simulated) | M4 |

Each service owns its data. No service reads another service's database; they talk through
gRPC (synchronous, internal), REST (through the gateway), or Kafka events.

Public routes are by resource, not by service, so clients never learn the topology:

| Public path | Upstream |
|---|---|
| `/api/v1/products/**`, `/api/v1/search/**` | catalog-svc |
| `/api/v1/orders/**` | order-svc |
| `/api/v1/delivery-promises/**` | fulfillment-svc |
| `/api/v1/shipments/**` | tracking-svc |

`inventory-svc` and `payment-mock` have no public route.

## Platform contract (built in M0)

Every service depends on `libs/platform`, and `ServiceContractTest` checks the contract over
real HTTP in every service's build:

- **Request ids.** `X-Request-Id` is accepted if it matches `[A-Za-z0-9._:-]{8,128}`, otherwise
  replaced by a fresh UUIDv7 (time-ordered, RFC 9562). The gateway writes it into the forwarded
  request, so one id follows the call everywhere. It is in every log line (MDC) and in every
  error body, and the client receives it exactly once.
- **Errors.** Every error, on servlet and reactive stacks alike, is RFC 9457
  `application/problem+json` with `requestId` and `timestamp`. Bodies never contain hosts, URLs,
  exception messages or stack traces. At the gateway, an unreachable upstream is `503` with
  `Retry-After`, a slow one `504`. Neither is reported as a generic `500`.
- **Operability.** `/actuator/health/{liveness,readiness}` for Kubernetes probes,
  `/actuator/prometheus` with real `http_server_requests` histogram buckets (so P99 is computed
  across instances with `histogram_quantile`), graceful shutdown.
- **Logging.** One access-log line per request (logger `conveyor.access`), written through a
  non-blocking async appender. See chapter 00 of the illustrated docs for why this matters on
  the gateway.
- **Defaults, not mandates.** Platform settings load at the lowest precedence
  (`PlatformDefaultsEnvironmentPostProcessor`), so any service can override any of them.

## Consistency choices (planned, M2–M5)

| Decision | Choice | Why |
|---|---|---|
| Multi-step order workflow | Saga orchestrated by Temporal | Steps span services and minutes (payment, FC allocation). 2PC would hold locks across services; Temporal gives durable timers, retries and visible history. |
| Order write + event publish | Transactional outbox, Debezium CDC | One local transaction instead of a DB write plus a Kafka send that can half-fail. |
| Event consumption | At-least-once delivery, idempotent consumers | Effectively-once without distributed transactions. |
| Hot-SKU stock | Bucketed Redis counters + Postgres ledger, reconciled | A single counter per SKU serializes the whole deal start. To be benchmarked against row locks, CAS, and a single Lua key. |
| Order reads | CQRS: OpenSearch read model fed by CDC | Search and ops queries never touch the sharded write path. |
| Tracking events | DynamoDB, partition key = shipment id | Append-heavy, key-value access, scales by partition. |

## Local infrastructure

`deploy/compose/compose.yaml` pins every image tag. The core services always start; everything
else is behind a profile so a laptop runs only what the current milestone needs.

| Profile | Containers | Memory limit | Measured (idle) |
|---|---|---|---|
| core (always on) | postgres-0, postgres-1, redis, kafka | 2.1 GiB | ≈ 440 MiB: Kafka 352, Postgres 37 + 35, Redis 14 |
| `search` | opensearch | 1 GiB | not yet measured |
| `workflow` | temporal (dev server + UI) | 384 MiB | not yet measured |
| `events` | Debezium connect, Apicurio registry | 1.25 GiB | not yet measured |
| `auth` | keycloak | 768 MiB | not yet measured |
| `aws` | dynamodb-local | 384 MiB | not yet measured |
| `obs` | prometheus, grafana | 640 MiB | not yet measured |
| `tools` | kafka-ui | 384 MiB | not yet measured |

Measured with `docker stats` on Docker Desktop 4.37 (M3 Pro) right after the health checks
passed. The optional profiles are validated with `docker compose config` in CI; their runtime
footprint is filled in as each milestone first runs them.

Host ports: Postgres 5432/5433, Redis 6379, Kafka 9092, OpenSearch 9200, Temporal 7233 (UI 8233),
Connect 8183, schema registry 8181, Keycloak 8180, DynamoDB 8000, Prometheus 9090, Grafana 3000,
Kafka UI 8280.
