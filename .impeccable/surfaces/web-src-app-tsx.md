---
version: 1
slug: "web-src-app-tsx"
primary_target: "web/src/App.tsx"
related_targets: ["web/src"]
---

# Surface: Conveyor web (storefront, order + live tracking, ops console)

Mode: Operate. Audience: reviewers seeing the system work; the demo customer (alice) and operator (ops).
Task: find a product, buy it, watch the saga confirm it and the courier deliver it; operators watch every moving shipment and look up orders.
Constraints: real backend states only; failure is a first-class state; laptop RAM is tight (one Vite dev server); light ground chosen from the scene (daylight desk, lit ops room).
Decision: assigned direction taken unattended (owner's standing "don't ask" instruction); airport-yellow wayfinding kept as the competitive alternate.

## Direction contract

THESIS: Conveyor drawn as a transit network. Every order rides a line, every saga step and courier scan is a station, and FCs are interchanges. This refuses the card-grid SaaS dashboard and the marketplace-clone storefront.

OWN-WORLD: White platform-wall ground with black sign bands carrying white Barlow Semi Condensed legends. There is one saturated line colour per region: east cobalt, central green, south amber, west violet. Route bullets are circles carrying E/C/S/W, and stations are ringed dots on 6px lines with 45° bends. Vermilion is reserved for failure and nothing else (raise from acetate manual). Numerals are tabular at data density, with no ornament (raise from Ikeda datamatics).

STORY: A visitor searches the catalog and expands a row in place to buy (raise from streaming wall: focus expands the row, no modal). They land on their order's line and watch stations light as the saga and the couriers report. Operators see the whole network move.

FIRST VIEWPORT: The order page is the thesis. The top is a black band whose headline is the order id, set large (raise from Saville catalog: identifiers are the headline), with status as a route bullet. Below it the order's line runs full width: PLACED → CONFIRMED → READY → PICKED → LOADED → OUT FOR DELIVERY → DELIVERED. Segment lengths are proportional to the real elapsed time between events (raise from labanotation), and a failure branches off to a vermilion terminus. Below that sit the schematic route map from FC interchange to door, with the courier bullet moving live, and the promised date.

FORM: Transit-diagram wayfinding (Vignelli/Beck schematic plus station signage), candidate 7 of 7 on the ordered list; seed key cdf3e2a4.

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
