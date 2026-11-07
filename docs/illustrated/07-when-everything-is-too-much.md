# 07 — When everything is too much

Prime Day traffic is not a bigger normal day. It arrives as a wall: more requests than the
services behind the gateway can serve, all at once, for long enough that every queue fills. A
system without protection takes all of them, queues all of them, and answers none of them in
time. M7 makes the platform degrade on purpose. It turns away browsing before buying, fails fast
when a dependency is sick, and makes all of this visible as SLOs and traces.

Every number below was measured on GitHub-hosted runners (4 vCPUs, AMD EPYC 9V74), using
`tools/bench/edge-overload.sh` from the bench workflow and `tools/e2e/run.sh` from the e2e
workflow.

## 1. The experiment: one upstream, a fixed capacity

`CapacityStub` stands in for a saturated backend. It has 8 workers at 20 ms each, so it serves
**400 req/s**, and everything beyond that waits in an unbounded queue, the way a thread pool in
front of a database does. All gateway routes lead to it, as services sharing one hot database
would. `OverloadGen` offers load **open loop**: it sends on a fixed schedule whether or not
earlier requests finished, as real users do. A closed-loop generator slows down along with the
server and hides overload. The mix is 60 % search, 25 % product pages and 15 % orders, and the
client gives up after 5 s.

### Without protection

```
## offered 2x = 800 req/s
  order   sent   2400  ok    561 ( 23.4%)  failed   1839  ok-latency p50  2543.7 p99  4949.0 ms
  product sent   4000  ok    936 ( 23.4%)  failed   3064  ok-latency p50  2561.9 p99  4951.2 ms
  search  sent   9600  ok   2256 ( 23.5%)  failed   7344  ok-latency p50  2561.0 p99  4977.3 ms
  goodput 188 req/s of 800 offered
## offered 3x = 1200 req/s
  order   sent   3600  ok      0 (  0.0%)  failed   3600
  goodput 0 req/s of 1200 offered
```

At twice its capacity, the system still does work: it serves at least 400 requests a second.
Most of that work is wasted. A request that waited 4.9 s in the queue is answered after the
client has already given up, so **goodput** (answers delivered in time) falls to 188 req/s.
At three times capacity every request times out. The upstream is busy the whole time, entirely
on answers nobody receives. Orders fail exactly as often as searches, because the queue cannot
tell them apart.

## 2. Admission at the edge

`LoadSheddingFilter` sits in the gateway before authentication and routing. Every upstream
request takes a slot while it is in flight. A request whose class may not use the free slots is
answered `503`, `Retry-After: 1`, problem+json, in microseconds. It costs the upstream nothing.

| Class | Routes | Share of the limit |
|---|---|---|
| critical | `/api/v1/orders/**` | 100 % |
| normal | product pages, ops search | 60 % |
| sheddable | `/api/v1/search` | 40 % |
| unlimited | SSE streams, actuator | not counted |

The limit itself is not configured. It is learned, in the style of Netflix's Gradient2
and TCP Vegas, from two round-trip times: a short average of the last few dozen requests, and the
no-load RTT, the best seen recently. While they agree, the limit grows by a queue allowance of
√limit. When requests start queueing downstream, the short RTT rises, the gradient
`tolerance × noLoad / short` drops below one, and the limit shrinks before anything times out.

### The same load with protection

```
## offered 2x = 800 req/s
  order   sent   2400  ok   2399 (100.0%)  shed      1  ok-latency p50    42.8 p99    95.6 ms
  product sent   4000  ok   3663 ( 91.6%)  shed    337  ok-latency p50    37.9 p99    89.2 ms
  search  sent   9600  ok   1641 ( 17.1%)  shed   7959  ok-latency p50    38.0 p99    94.4 ms
  goodput 385 req/s of 800 offered
## offered 3x = 1200 req/s
  order   sent   3600  ok   3593 ( 99.8%)  shed      7  ok-latency p50    49.5 p99   125.0 ms
  product sent   6000  ok   4056 ( 67.6%)  shed   1944  ok-latency p50    48.2 p99    94.0 ms
  search  sent  14400  ok    153 (  1.1%)  shed  14247  ok-latency p50    22.6 p99   223.7 ms
  goodput 390 req/s of 1200 offered
```

| Offered | Goodput, unprotected | Goodput, protected | Orders OK, unprotected | Orders OK, protected | Order p99, protected |
|---:|---:|---:|---:|---:|---:|
| 0.5× | 200 | 200 | 100 % | 100 % | 30.7 ms |
| 1× | 400 | 387 | 100 % (p99 246 ms) | 100 % | 36.4 ms |
| 2× | 188 | 385 | 23.4 % | **100 %** | 95.6 ms |
| 3× | **0** | 390 | **0 %** | **99.8 %** | 125.0 ms |

Goodput stays at about the upstream's capacity however hard the wall pushes. The failures
land on search first and on orders last. At exactly 1× load the unprotected system already
answers everything 175 ms late (a full queue). With protection it answers at the no-load
latency and turns away 5 % of searches to keep it there.

## 3. Two versions of the limit that did not work

The first runner run, before the numbers above, used a different baseline:

```
## offered 1x = 400 req/s   (second version)
  order   ok 100.0%  ok-latency p50   100.8 p99   164.6 ms
gateway_concurrency_limit 1000.0
## offered 3x = 1200 req/s
  order   ok  80.5%   shed 701
```

- **Version 1** compared the short RTT with a long average over 600 requests. At 1,000 req/s
  that window is under a second long. Sustained queueing became "normal" within seconds, and
  the limit stopped shrinking. On the laptop it let orders fall to 64 % at 3× load.
- **Version 2** used the minimum RTT, letting it drift upward by a small factor per request.
  At 400 req/s that is a fivefold drift in 20 seconds. The baseline crept up to the queued
  latency, and the limit ran to its maximum of 1000 (above). Every request queued for 100 ms,
  and at 3× load one order in five was shed.
- **Version 3**, the one measured in section 2, drifts by a factor of e every 30 s of wall
  time, whatever the request rate. A test pins it: `sustainedQueueingNeverBecomesTheBaseline
  AtHighRequestRates` replays 20 s of 400 req/s at 100 ms.

The laptop could not have found this. It ran with a load average above 30 from other projects'
builds, so its own scheduling stalls looked like downstream queueing, and the minimum-RTT
version collapsed to its floor of 8 there. Both diagnoses came from runner runs, where the
machine did nothing else.

## 4. A dependency that is sick, not slow

Admission protects against too much traffic. A circuit breaker protects against a dependency
that cannot answer. order-svc prices every order through catalog-svc within a 1.5 s budget.
When catalog hangs, each order holds a request thread for the whole 1.5 s before failing, and
each one adds load to a service that is already struggling.

Resilience4j now sits in front of the pricing call, with these settings:

- a window of the last 50 calls;
- it opens when 50 % of them fail or run past 80 % of the budget, after at least 20 calls;
- it stays open for 5 s, then lets five trial calls through;
- an unknown SKU never counts, because that is the customer's mistake, not catalog's.

While the breaker is open, orders are refused at once with the same 503 "pricing unavailable".
`CatalogBreakerTest` drives it against a real local HTTP server:

1. Four failures open the breaker.
2. The next order is refused in under 50 ms, and the server sees no request.
3. The server recovers; after the open period, five trial orders succeed and the breaker
   closes.

The end-to-end run (section 6) does the same to the real catalog-svc by freezing its process.

## 5. Seeing it: SLOs at the edge, traces through the outbox

**SLIs.** The admission filter records `gateway_edge_requests_seconds` for every upstream
request, by priority and outcome (`ok`, `client_error`, `server_error`, `shed`), with a
histogram bucket exactly at the 300 ms objective.

| SLO | Objective | Bad events |
|---|---|---|
| Checkout availability | 99.9 % | 5xx or shed, `priority="critical"` |
| Checkout latency | 99 % under 300 ms | slower good requests |
| Browse availability | 99 % | 5xx or shed, normal and sheddable |

**Alerts** use the multi-window, multi-burn-rate recipe from the Google SRE Workbook. A page
fires when the error budget burns 14.4× too fast over 1 h and is still burning over the last
5 min. A ticket fires at 6× over 6 h, confirmed over 30 min. `promtool test rules` runs in CI and
proves four cases:

- a steady 5 % error rate pages within ten minutes;
- 3 % of orders shed pages (shedding checkout counts against it);
- 0.05 % errors never pages;
- a two-minute blip at 30 % errors, after an hour of health, does not page. The 5-minute window
  spikes to about 12 %, but the hour sees 1 %.

A Grafana dashboard (Conveyor → SLOs and overload) shows the budgets, the burn rates, the
limit against requests in flight, and the breaker. The alerts link to
[`docs/runbooks/checkout-availability.md`](../runbooks/checkout-availability.md).

**Traces.** Every service now exports OpenTelemetry spans over OTLP; Jaeger runs in the compose
`obs` profile. HTTP clients are built from Spring's `RestClient.Builder`, so they carry the
trace. The gRPC client to inventory has an observation interceptor, and Kafka observations are
on. One link was missing: order-svc never sends to Kafka itself. Debezium publishes the outbox
row from the WAL, so the trace ended at the database. order-svc now writes the current W3C
`traceparent` into an outbox column, Debezium's outbox router places that column in a
`traceparent` Kafka header, and the consumers' observations continue the trace from there.

## 6. The whole thing in a browser

<!-- filled in from the e2e run -->

## Reproduce

```bash
./gradlew :services:gateway:test                    # limiter, shedding, SLI tests
./gradlew :services:order-svc:test --tests '*CatalogBreakerTest'
docker run --rm -v "$PWD/deploy/compose/prometheus:/r" -w /r --entrypoint promtool \
  prom/prometheus:v3.16.0 test rules slo.rules.test.yml
tools/bench/edge-overload.sh                        # needs Redis on :6379
tools/e2e/run.sh                                    # full stack + browser; ~12 GB of RAM
```
