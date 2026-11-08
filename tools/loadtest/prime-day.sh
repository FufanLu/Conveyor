#!/usr/bin/env bash
# Prime Day on one runner: the full stack (tools/stack/up.sh), 2,000 couriers reporting the whole
# time, and PrimeDay.java's phases through the gateway (warm, a 6,000-unit deal with 7,000
# buyers, peak, recovery). Then the SLOs and the system's own view of the run from Prometheus,
# and the deal SKU's stock from inventory-svc.
#
# Runs in .github/workflows/prime-day.yml. Needs Docker, Java 25, ~12 GB of memory.
set -uo pipefail

OUT=${OUT:-build/prime-day}
CUSTOMERS=${CUSTOMERS:-200}
DEAL_BUYERS=${DEAL_BUYERS:-7000}
# Simulated shipments, one report each per second for the whole run.
COURIER_SHIPMENTS=${COURIER_SHIPMENTS:-2000}
# shellcheck source=../stack/up.sh
source tools/stack/up.sh

echo "### couriers: $COURIER_SHIPMENTS shipments, 1 Hz, for the whole run"
"$JAVA" tools/simulator/CourierSim.java http://localhost:8085 "$COURIER_SHIPMENTS" "$(( (COURIER_SHIPMENTS + 19) / 20 ))" 1 420 \
    > "$OUT/couriers.txt" 2>&1 &
PIDS+=($!)

echo "### prime day"
START=$(date +%s)
"$JAVA" tools/loadtest/PrimeDay.java http://localhost:8080 http://localhost:8180 "$CUSTOMERS" "$DEAL_BUYERS" | tee "$OUT/prime-day.txt"
END=$(date +%s)
RANGE="$((END - START))s"

echo "### couriers"
tail -1 "$OUT/couriers.txt"

echo "### the run as Prometheus saw it (last $RANGE)"
sleep 20 # one more scrape
python3 - "$RANGE" <<'PY'
import json, sys, urllib.parse, urllib.request
rng = sys.argv[1]
def q(expr):
    url = "http://localhost:9090/api/v1/query?query=" + urllib.parse.quote(expr)
    return json.load(urllib.request.urlopen(url))["data"]["result"]
def show(title, expr, fmt=lambda v: f"{float(v):.4g}", label=None):
    rows = q(expr)
    print(f"  {title}:")
    for r in sorted(rows, key=lambda r: json.dumps(r["metric"], sort_keys=True)):
        name = " ".join(f"{k}={v}" for k, v in sorted(r["metric"].items()) if k in (label or r["metric"].keys()))
        print(f"    {name or '-':40} {fmt(r['value'][1])}")
show("edge requests by priority and outcome",
     f'sum by (priority, outcome) (increase(gateway_edge_requests_seconds_count[{rng}]))', lambda v: f"{float(v):.0f}")
show("checkout availability (SLO 99.9 %)",
     f'1 - sum(increase(gateway_edge_requests_seconds_count{{priority="critical",outcome=~"server_error|shed"}}[{rng}]))'
     f' / sum(increase(gateway_edge_requests_seconds_count{{priority="critical"}}[{rng}]))', lambda v: f"{100*float(v):.3f} %")
show("checkout under 300 ms (SLO 99 %)",
     f'sum(increase(gateway_edge_requests_seconds_bucket{{priority="critical",outcome="ok",le="0.3"}}[{rng}]))'
     f' / sum(increase(gateway_edge_requests_seconds_count{{priority="critical",outcome="ok"}}[{rng}]))', lambda v: f"{100*float(v):.2f} %")
show("p99 at the edge by priority (ms)",
     f'histogram_quantile(0.99, sum by (priority, le) (rate(gateway_edge_requests_seconds_bucket{{outcome="ok"}}[{rng}])))',
     lambda v: f"{1000*float(v):.0f}")
show("gateway concurrency limit, min and max over the run",
     f'min_over_time(gateway_concurrency_limit[{rng}]) or max_over_time(gateway_concurrency_limit[{rng}])')
show("sagas started", f'sum by (outcome) (increase(fulfillment_sagas_started_total[{rng}]))', lambda v: f"{float(v):.0f}")
show("catalog breaker: refused calls", f'sum(increase(resilience4j_circuitbreaker_not_permitted_calls_total[{rng}]))',
     lambda v: f"{float(v):.0f}")
show("tracking: reports ingested / stored",
     f'sum(increase(tracking_ingest_events_total[{rng}])) or sum by (outcome) (increase(tracking_persist_events_total[{rng}]))',
     lambda v: f"{float(v):.0f}")
show("tracking live delay p99 (ms)",
     f'histogram_quantile(0.99, sum by (le) (rate(tracking_live_delay_seconds_bucket[{rng}])))', lambda v: f"{1000*float(v):.0f}")
show("burn-rate alerts that fired", 'ALERTS{alertstate="firing"}', lambda v: "firing")
PY

echo "### the deal SKU's stock (inventory-svc)"
curl -s http://localhost:8083/v1/inventory/B09E3779B1; echo
