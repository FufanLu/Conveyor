# 09 — The line

Chapter 08 ended with two things a real deal day needs that Conveyor did not have. The first
was a **virtual waiting room** in front of the deal: 80 % of the buyers got a 503 and the rest
won a race. The second was **per-route admission**, because the deal and regular checkout shared
one "critical" limit, and during the deal regular customers were turned away with the deal
crowd (35 of 240 got in). This chapter builds both. It then runs the same Prime Day again and
reports what changed and what did not.

## 1. The line is three numbers

A waiting room has to answer 7,000 people "where am I?" over and over, and the answers must
agree across every gateway instance. Conveyor keeps the whole line in three Redis values per
deal:

```
deal:lightning-1:issued    6511    INCR on every join: the last place handed out
deal:lightning-1:frontier  2309    every place up to here is admitted; only moves forward
deal:lightning-1:rate      27.2    admissions per second, set by the leader
deal:lightning-1:leader    <uuid>  3 s lease: the one instance that moves the frontier
```

A place in line is a **ticket**: `(deal, place)` signed with HMAC-SHA256. The gateway does not
store who holds which place. It reads the place back out of the ticket on every poll, so the
line costs one `INCR` per customer and nothing per poll.

```
customer                       gateway (any instance)                    Redis
   │ POST /api/v1/deals/lightning-1/queue
   │──────────────────────────────▶│ INCR issued ───────────────────────────▶│ 4212
   │◀── 201 {ticket, place 4212, ahead 1830, etaSeconds 92, pollAfterMs 46000}
   │        … 46 s …
   │ GET /api/v1/deals/lightning-1/queue/{ticket}
   │──────────────────────────────▶│ place 4212 ≤ frontier?  (cached, no Redis)
   │◀── 200 {state ADMITTED, admission, admissionExpiresAt +10 min}
   │ POST /api/v1/orders   Conveyor-Admission: <admission>
   │──────────────────────────────▶│ signature ok → deal lane → order-svc
```

Every second, each instance tries `SET leader NX PX 3000` or renews its own lease. The leader
does two things:

- It sets the **rate** from what the deal lane saw in the last second. If deal orders were shed
  or failed, or their mean latency was above 500 ms, the rate drops by a quarter. Otherwise, while
  people are waiting, it grows by 5 % (at least 1/s). This is additive increase and
  multiplicative decrease, the same family as the edge's adaptive limit.
- It **advances the frontier** by the rate with one Lua script:
  `min(capacity, issued, frontier + step)`, written only if it is larger. Two leaders during a
  lease handover cannot move the frontier twice or backwards.

Then every instance caches `issued`, `frontier` and `rate` and answers polls from memory.

**Capacity** is units × 1.1. Some admitted customers never order, so the line admits a little
more than the stock. A customer whose place is beyond that hears "sold out" in the answer to
joining, instead of waiting minutes for nothing. Stock is still enforced by the inventory
reservation in the saga. The overbooking only decides who gets to try.

**Polling cost.** Every answer carries `pollAfterMs`, about half the time to the customer's
turn: 1 s when close, at most once a minute when far back. The first run capped it at 15 s, and
section 3 shows why that was not enough.

## 2. Admissions, and a lane of their own

Once a customer is admitted, the poll answer carries an **admission**: `(deal, customer, expires
at)`, signed with the same key, valid for ten minutes. It is checked in two places:

| Where | What it checks | Why there |
|---|---|---|
| gateway `AdmissionFilter` (before the shedding filter) | signature, expiry | a forged admission must not take a slot in the deal lane |
| order-svc `DealAdmissions` | the admission's deal is the SKU's deal, its subject is the token's subject, one unit | only order-svc knows the SKU and the customer together |

order-svc refuses the deal SKU without an admission (403, with a link to the line). That is what
makes the line mandatory and not just polite: a script that skips the line and posts orders
straight away gets nothing. The secret is shared by the gateway and order-svc (in Kubernetes,
a Secret both pods mount; see `deals.secretName` in the chart).

**The deal lane.** In `LoadSheddingFilter`, an order that carries an admission goes into its own
lane. It counts against the overall concurrency limit like any order, and also against a lane
cap of half the limit. A deal can therefore use at most half of checkout; the other half stays
open for regular customers whatever the deal does. The edge SLI gained a `lane` tag
(`deal` / `default`), so the dashboards and the run report can tell the two apart. The
checkout SLO still counts both, because a deal buyer turned away is still a customer turned
away.

The tests cover each piece on its own:

- `LineTest`: the line's arithmetic.
- `DealTokensTest`: forged, expired and swapped tokens.
- `DealAdmissionsTest`: wrong customer, wrong deal, two units.
- `LoadSheddingFilterTest.aDealCannotTakeTheSlotsRegularCheckoutNeeds`: the lane cap.
- `WaitingRoomApiTest`, against a real Redis. Six customers join a 4-unit deal that admits 2 a
  second. The last two hear "sold out" at once, the fourth is admitted within seconds and gets an
  admission only when signed in, and a forged admission never reaches order-svc.

## 3. The page

![The deal's row opened: the customer's place in line drawn as a line](img/09-line-waiting.webp)

On the storefront the deal is a catalog row like any other, above the results, and it opens in
place (no modal). The line is drawn in the transit vocabulary of chapter 06:

- the station on the left is where the customer joined;
- the ringed station on the right is their turn;
- the front of the queue is the train coming toward them, in their delivery region's colour.

The figures are the gateway's own answer: place, ahead, about how long, and the current
admission rate. The page polls when the gateway says to. If a poll fails it says so ("Lost touch
with the line. Your place is kept"), and tries again in 3 s.

![Admitted: ten minutes to order](img/09-line-turn.webp)

Admitted, the row shows the ten-minute countdown and a single Place order button: one unit,
with the admission attached. An expired admission (403) sends the customer back to the line with
a sentence saying why. A 503 tells them their place is kept and to press the button again.

RUN_RESULTS
