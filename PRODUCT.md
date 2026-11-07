# Product

<!-- impeccable:product-schema 1 -->

> Written without an interview. The owner's standing instruction for this project is to work
> autonomously ("继续 别问我 自己干就行了"). Facts marked *(inferred)* come from the M6 brief and
> the repository; everything else is stated in the repo's README and docs.

## Platform

web

## Stack

React + TypeScript built with Vite, in `web/`, talking to the existing services through the
gateway (`/api/v1/...`). *(inferred: named in the M6 brief.)* The owner's laptop is short on
memory, so the dev footprint stays small: a single Vite dev server, no SSR, no heavyweight UI kit.

## Users

- **Interviewers and engineers reviewing the project** (primary for the web app). They open it
  to see the system work: an order placed, a saga confirming it, a package moving. *(inferred)*
- **Customers** in the story: a signed-in shopper (Keycloak user `alice`) who searches products,
  places an order and watches the delivery.
- **Operators** in the story: a fulfillment-ops user (Keycloak role `ops`, user `ops`) who
  watches every moving shipment and looks up orders during a peak event.

## Product Purpose

Conveyor is an order-fulfillment platform built for Prime Day–shaped traffic. It follows an
order the way a package rides a fulfillment-center conveyor: checkout → stock reserved in the
right FC → payment authorized → delivery date promised → picked and shipped → every courier
scan streamed back to the customer. The web app is where that journey becomes visible.
Success: someone with no context can place an order and, within a minute, watch it be
confirmed and see couriers moving, then open the ops view and see the whole fleet.

## Positioning

The interface shows real distributed-systems behavior, not a mock. Order states come from the
Temporal saga (PLACED → CONFIRMED / REJECTED / PAYMENT_FAILED / CANCELLED, with a promised
delivery date), shipment states from courier scans (ORDERED → READY → PICKED → LOADED →
OUT_FOR_DELIVERY → DELIVERED), and positions from an SSE stream fed by Kafka.

## Operating Context

- Six fulfillment centers: `east-1` (Newark), `east-2` (Baltimore), `central-1` (Joliet),
  `south-1` (Dallas), `west-1` (Tracy), `west-2` (Kent). Regions: us-east, us-central,
  us-south, us-west.
- The catalog holds 100,000 seeded products (ASIN-like SKUs such as `B0DEE0D8F5`, a title,
  brand, category, price, rating).
- Couriers are simulated (`tools/simulator/CourierSim.java`). Positions arrive about once a
  second per shipment. The ops map stream is conflated into one frame every 500 ms.
- Sign-in is Keycloak OIDC (realm `conveyor`, public client `conveyor-cli`).

## Capabilities and Constraints

- APIs: catalog product/search, place order (requires `Idempotency-Key`), get order, ops order
  search (role ops), shipment state, shipment SSE stream (history then live, `Last-Event-ID`
  resume), ops map SSE (role ops).
- Payment outcomes are deterministic in the mock: totals ending in .13 are declined, totals
  ending in .37 go pending and are approved later by a callback.
- No real payment entry, addresses or accounts beyond Keycloak's demo users.

## Brand Commitments

The name **Conveyor** and its metaphor: a package riding a fulfillment-center conveyor.
Amazon-flavored vocabulary (FC, SKU, Prime-Day peak, promised delivery). It must not imitate
Amazon's own brand, logo, colors or trade dress. *(inferred)*

## Evidence on Hand

- Real behavior and numbers in `docs/illustrated/00–05` (measured on runners and laptop).
- Seeded catalog and inventory data. Simulated courier routes.
- No customers, testimonials, logos, prices or SLAs exist beyond what the code produces. Do not
  invent them.

## Product Principles

1. Show the system working: live state, not illustrations of state.
2. Every status shown is one the backend actually emits, with its real name.
3. Failure is a first-class state (declined, out of stock, pending payment, stream reconnecting),
   never hidden.
4. Fast to understand for a newcomer, dense enough for an operator.

## Accessibility & Inclusion

WCAG 2.2 AA as the working standard: keyboard operable, visible focus, 4.5:1 text contrast,
and live updates announced politely without stealing focus. *(inferred)*
