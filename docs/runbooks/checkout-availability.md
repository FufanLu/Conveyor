# Runbook: checkout availability and latency

Alerts: `CheckoutErrorBudgetBurnFast` (page), `CheckoutErrorBudgetBurnSlow` (ticket),
`CheckoutLatencyBudgetBurnFast` (page). The SLO is 99.9 % of order requests answered without a
5xx or a shed at the gateway, and 99 % of those within 300 ms. Rules:
`deploy/compose/prometheus/slo.rules.yml`. Dashboard: Grafana → Conveyor → "SLOs and overload".

## 1. Is the edge shedding orders?

```promql
sum by (priority, outcome) (rate(gateway_edge_requests_seconds_count[1m]))
max(gateway_concurrency_limit)   max(gateway_concurrency_inflight)
```

`outcome="shed"` with `priority="critical"` means the adaptive limit is full even though
search (40 %) and product pages (60 %) are already being shed. The limit shrinks when upstream
latency rises, so the cause is downstream: continue at step 2. If the limit sits at its floor
(`conveyor.gateway.shedding.min-limit`, 8) while upstream latency is normal, the gateway itself is
short of CPU: scale it out.

## 2. Which upstream is slow or failing?

- Traces: Jaeger (`http://localhost:16686`, service `gateway`, operation `http post /api/v1/orders`)
  shows where the time goes: order-svc → catalog-svc (pricing) → Postgres shard → outbox.
- Logs carry `[requestId traceId]`; the gateway's `X-Request-Id` is in every problem+json body.

```promql
histogram_quantile(0.99, sum by (service, le) (rate(http_server_requests_seconds_bucket[1m])))
```

## 3. Catalog circuit breaker open?

```promql
resilience4j_circuitbreaker_state{name="catalog", state="open"}
```

While open, order-svc refuses orders at once with 503 "pricing unavailable" instead of holding
each for the 1.5 s pricing budget. It retries catalog every 5 s with five trial calls. Fix
catalog (its cache, OpenSearch, Postgres) and the breaker closes by itself; do not restart
order-svc to "reset" it.

## 4. Orders accepted but never confirmed

Not an availability alert, but customers see it as one: `fulfillment_sagas_started_total` flat
while orders are placed means the outbox → Debezium → Kafka path is stuck. Check
`make connectors` status (`curl localhost:8183/connectors?expand=status`) and the Temporal UI
(`http://localhost:8233`) for workflows waiting on an activity.

## 5. After

Write down what burned the budget and for how long (the 30-day "error budget left" panel). If
the budget is spent, feature work that touches checkout waits until it recovers.
