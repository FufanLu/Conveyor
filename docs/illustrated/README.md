# Conveyor, illustrated

Each chapter follows one request, or one order, through the code that exists at that milestone,
and shows what actually happens: the bytes on the wire, the log lines, the rows, the Kafka
records, the timings.

The rule for these chapters: **nothing is made up.** Every request, header, log line and number
was produced by running the code at the commit the chapter was written against, and every
chapter ends with the commands that reproduce it. When the real run disagrees with the design,
the chapter records the disagreement and the fix.

| Chapter | Follows | Milestone |
|---|---|---|
| [00 — A request through the edge](00-a-request-through-the-edge.md) | `GET /api/v1/products/{sku}` through the gateway into catalog-svc; request ids, error contract, failure modes, and what logging costs an event loop | M0 |
| [01 — Reading a product under a stampede](01-reading-a-product-under-a-stampede.md) | Product reads through L1 / Bloom / Redis / Postgres; stampede, penetration and avalanche experiments; guarded price changes with ETags and Keycloak tokens; what the real run broke | M1 |
| [02 — Placing an order: one transaction, one event](02-placing-an-order.md) | An order through pricing, the shard gene, idempotency, the outbox, Debezium, Kafka and the OpenSearch projection; a dead connector, a 290 ms poll interval, and a CDC outage | M2 |
| [03 — 10,000 buyers, one SKU](03-ten-thousand-buyers-one-sku.md) | Reservations over gRPC; four ways to sell one hot SKU measured on clean runners; the service failing a 10,000-buyer burst, and the bulkhead that fixed it; keeping Redis honest | M3 |
| [04 — The saga, step by step and failure by failure](04-the-saga.md) | One Temporal workflow per order: four endings, compensations in reverse, a durable payment wait that survives `kill -9`, a full-topic replay that starts nothing twice, and a 75-order backlog | M4 |
| [05 — The courier firehose](05-the-courier-firehose.md) | Courier reports through Kafka into DynamoDB and out over SSE; conditional writes that only move forward; a gap-free history-then-live stream; 2k–20k reports/s measured on clean runners; write coalescing (4.4× fewer writes); DynamoDB paused mid-run | M5 |
| [06 — The web, drawn as a transit map](06-the-web-as-a-transit-map.md) | The platform as a transit network: orders as lines with time-true segments, failure branches, a schematic courier route, an ops network diagram; SSE over fetch with Last-Event-ID; a demo mode that ports the catalog seed; what a fresh reviewer found | M6 |
| [07 — When everything is too much](07-when-everything-is-too-much.md) | Overload at the edge measured on runners (goodput 0 vs 390 req/s at 3×); an adaptive limit that failed twice before it worked; a circuit breaker; SLOs with burn-rate alerts tested by promtool; one trace through the outbox; the full stack driven by a browser, and what four runs of it found | M7 |
| [08 — Prime Day, and the road to a real cluster](08-prime-day.md) | Layered images and Helm charts proven on kind (296 s to a confirmed order, and the two Kubernetes surprises on the way); the AWS estate in CDK Java with template tests; a scaled Prime Day on one machine: where capacity ended, why correctness held, and what a real deal day needs | M8 |
