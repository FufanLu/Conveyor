#!/usr/bin/env bash
# The full stack on one machine, driven by a browser: compose infrastructure, all eight services
# (traces exported to Jaeger), the Debezium connectors, the production web build, then
#   1. journey.mjs: Keycloak sign-in, order -> saga -> courier -> delivered, a declined card, ops
#   2. the trace of the customer's POST, from Jaeger's API: which services it crossed
#   3. chaos: catalog-svc frozen (SIGSTOP) while orders keep coming; the circuit breaker opens,
#      orders fail fast; catalog thawed, the breaker closes
# Runs in .github/workflows/e2e.yml (16 GB runner). Needs Docker, Java 25 and Node 24.
set -uo pipefail

JAVA=${JAVA:-java}
OUT=${OUT:-build/e2e}
COMPOSE="docker compose -f deploy/compose/compose.yaml"
PROFILES="--profile search --profile auth --profile events --profile workflow --profile aws --profile obs"
mkdir -p "$OUT/logs"
PIDS=()
cleanup() { kill "${PIDS[@]}" 2>/dev/null || true; }
trap cleanup EXIT

echo "### infrastructure"
$COMPOSE $PROFILES up -d --wait 2>&1 | tail -3

export CONVEYOR_TRACING_EXPORT=true
declare -A PORT=([catalog-svc]=8081 [order-svc]=8082 [inventory-svc]=8083 [fulfillment-svc]=8084
                 [tracking-svc]=8085 [payment-mock]=8086 [gateway]=8080)
for s in catalog-svc order-svc inventory-svc fulfillment-svc tracking-svc payment-mock gateway; do
    "$JAVA" -Xmx768m -jar "services/$s/build/libs/$s.jar" > "$OUT/logs/$s.log" 2>&1 &
    PIDS+=($!)
    echo "$s $!" >> "$OUT/pids"
done
for s in "${!PORT[@]}"; do
    for i in $(seq 1 180); do
        curl -fs -o /dev/null "http://localhost:${PORT[$s]}/actuator/health" && break
        sleep 1
    done
    echo "$s up: $(grep -o 'Started [A-Za-z]* in [0-9.]* seconds' "$OUT/logs/$s.log" | head -1)"
done

echo "### connectors"
docker exec conveyor-connect-1 bash /setup/register.sh | grep -o '"name":"[^"]*","connector":{"state":"[A-Z]*"' || true

echo "### web (production build, vite preview)"
(cd web && npm ci --no-audit --no-fund > /dev/null && npx vite build > /dev/null \
    && CONVEYOR_GATEWAY=http://localhost:8080 npx vite preview > "../$OUT/logs/web.log" 2>&1) &
PIDS+=($!)
until curl -fs -o /dev/null http://localhost:5280; do sleep 1; done

echo "### journey"
(cd tools/e2e && npm ci --no-audit --no-fund > /dev/null 2>&1 || npm install --no-audit --no-fund > /dev/null)
OUT="$OUT" JAVA="$JAVA" node tools/e2e/journey.mjs
JOURNEY=$?

echo "### the customer's POST as one trace (Jaeger)"
sleep 10 # batch span export
curl -s "http://localhost:16686/api/traces?service=gateway&limit=200&lookback=1h" > "$OUT/traces.json"
python3 - "$OUT/traces.json" <<'PY'
import json, sys
data = json.load(open(sys.argv[1]))["data"]
best = None
for t in data:
    services = {t["processes"][s["processID"]]["serviceName"] for s in t["spans"]}
    ops = [s["operationName"] for s in t["spans"]]
    if any("post" in o.lower() and "orders" in o.lower() for o in ops):
        if best is None or len(services) > len(best[1]):
            best = (t, services)
if best is None:
    print("no POST /orders trace found among", len(data), "gateway traces")
    sys.exit(0)
t, services = best
print(f"trace {t['traceID']}: {len(t['spans'])} spans across {sorted(services)}")
start = min(s["startTime"] for s in t["spans"])
for s in sorted(t["spans"], key=lambda s: s["startTime"])[:40]:
    svc = t["processes"][s["processID"]]["serviceName"]
    print(f"  +{(s['startTime'] - start) / 1000:8.1f} ms  {s['duration'] / 1000:8.1f} ms  {svc:16} {s['operationName']}")
PY

echo "### chaos: catalog-svc frozen while orders keep coming"
TOKEN=$(curl -s localhost:8180/realms/conveyor/protocol/openid-connect/token \
    -d grant_type=password -d client_id=conveyor-cli -d username=alice -d password=alice \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])')
CATALOG=$(grep '^catalog-svc ' "$OUT/pids" | cut -d' ' -f2)
order() {
    curl -s -o /dev/null -w '%{http_code} %{time_total}\n' -X POST localhost:8080/api/v1/orders \
        -H "Authorization: Bearer $TOKEN" -H "Idempotency-Key: chaos-$1-$RANDOM$RANDOM" \
        -H 'Content-Type: application/json' -d '{"items":[{"sku":"B0DEE0D8F5","quantity":1}],"shipToRegion":"us-east"}'
}
breaker() {
    curl -s localhost:8082/actuator/prometheus | grep -E '^resilience4j_circuitbreaker_state\{.*name="catalog".*\} 1' \
        | grep -o 'state="[a-z_]*"'
}
echo "before: breaker $(breaker); one order: $(order warm)"
kill -STOP "$CATALOG"
echo "catalog-svc stopped (SIGSTOP)"
for i in $(seq 1 40); do
    echo "order $i: $(order "$i")  breaker $(breaker)"
    sleep 0.2 # stay under the gateway's 10 orders/s per-customer rate limit
done | tee "$OUT/chaos.txt" | awk 'NR<=3 || NR%5==0'
kill -CONT "$CATALOG"
echo "catalog-svc resumed"
sleep 6 # past the 5 s open period: half-open, trial calls
for i in $(seq 1 8); do
    echo "after $i: $(order "after-$i")  breaker $(breaker)"
    sleep 0.2
done | tee -a "$OUT/chaos.txt"

echo "### SLO recording rules over the run (Prometheus)"
curl -s 'localhost:9090/api/v1/query?query=slo:edge_requests:rate5m' | python3 -c '
import json,sys
for r in json.load(sys.stdin)["data"]["result"]:
    print(" ", r["metric"].get("priority"), r["metric"].get("outcome"), round(float(r["value"][1]), 3), "req/s")' || true

exit $JOURNEY
