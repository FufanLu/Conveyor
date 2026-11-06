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
| 01 — Reading a product under a cache stampede | | M1 |
| 02 — Placing an order: one transaction, one event | | M2 |
| 03 — 10,000 buyers, one SKU | | M3 |
| 04 — The saga, step by step and failure by failure | | M4 |
| 05 — A courier scan, from GPS to the browser map | | M5 |
