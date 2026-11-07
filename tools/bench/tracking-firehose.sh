#!/usr/bin/env bash
# The firehose behind docs/illustrated/05: tracking-svc against Kafka and DynamoDB Local on
# localhost. Simulated couriers post at increasing rates; for each step: what ingest accepted,
# how fast the persister drained it into DynamoDB, and the delay from the handheld's clock to
# "stored" and to "ready to push live". Then an outage: DynamoDB is paused mid-run.
# Runs in .github/workflows/bench.yml; locally needs make infra-up PROFILES="aws".
set -euo pipefail

JAVA=${JAVA:-java}
URL=http://127.0.0.1:8085
RATES=${RATES:-"2000 5000 10000 20000"}
STEP_SECONDS=${STEP_SECONDS:-30}
JAR=${JAR:-services/tracking-svc/build/libs/tracking-svc.jar}
# Latest-position writes per shipment: at most one per interval (0s = one per persister batch).
POSITION_WRITE_INTERVAL=${POSITION_WRITE_INTERVAL:-5s}
WORK=${WORK:-build/tracking-firehose}
rm -rf "$WORK" && mkdir -p "$WORK"
M="python3 tools/bench/metrics.py"

echo "machine: $(uname -sm), $(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu) CPUs"
command -v lscpu > /dev/null && lscpu | grep -E '^Model name' || true

echo "position-write-interval=$POSITION_WRITE_INTERVAL"
"$JAVA" -Xmx1g -jar "$JAR" --conveyor.tracking.position-write-interval="$POSITION_WRITE_INTERVAL" > "$WORK/tracking.log" 2>&1 &
SVC=$!
trap 'kill $SVC 2>/dev/null || true' EXIT
until curl --noproxy '*' -fs -o /dev/null $URL/actuator/health/readiness; do sleep 1; done
grep -E 'Started TrackingApplication' "$WORK/tracking.log" | sed -E 's/^.*\] \[\] [a-zA-Z.$]+ +: //'

sim() { "$JAVA" tools/simulator/CourierSim.java $URL "$@"; }

# Wait until everything ingested so far is in DynamoDB; print how long the drain took.
drain() {
    local t0=$SECONDS
    while :; do
        $M snap $URL > "$WORK/now.json"
        if python3 - "$WORK/now.json" <<'PY'
import json, sys
m = json.load(open(sys.argv[1]))
ing = m.get("tracking_ingest_events_total", 0)
done = m.get('tracking_persist_events_total{outcome="stored"}', 0) + m.get('tracking_persist_events_total{outcome="duplicate"}', 0)
sys.exit(0 if done >= ing else 1)
PY
        then echo "drained $((SECONDS - t0)) s after the couriers stopped"; return; fi
        sleep 1
    done
}

sim 500 50 1 10 > /dev/null # warm-up: JIT, connections, topic metadata
drain > /dev/null

for rate in $RATES; do
    couriers=$((rate / 20)) # 20 shipments per courier: one POST carries 20 reports
    echo "### $rate reports/s: $rate shipments, $couriers couriers at 1 Hz, ${STEP_SECONDS} s"
    $M snap $URL > "$WORK/a.json"
    sim "$rate" "$couriers" 1 "$STEP_SECONDS" | tail -1
    drain
    $M snap $URL > "$WORK/b.json"
    $M diff "$WORK/a.json" "$WORK/b.json"
done

echo "### outage: 5000 reports/s for 60 s, DynamoDB paused from t=15 s to t=35 s"
DDB=$(docker ps -qf ancestor=amazon/dynamodb-local:3.3.2 | head -1)
$M snap $URL > "$WORK/a.json"
sim 5000 250 1 60 > "$WORK/outage-sim.txt" &
SIM=$!
sleep 15; docker pause "$DDB" > /dev/null; echo "paused DynamoDB at $(date +%T)"
sleep 20; docker unpause "$DDB" > /dev/null; echo "unpaused DynamoDB at $(date +%T)"
wait $SIM
grep -E '^t=' "$WORK/outage-sim.txt" | awk 'NR % 5 == 0'
tail -1 "$WORK/outage-sim.txt"
drain
$M snap $URL > "$WORK/b.json"
$M diff "$WORK/a.json" "$WORK/b.json"
grep -cE 'tracking-persister.*(Exception|error)|SdkClientException|ApiCallTimeout' "$WORK/tracking.log" \
    | sed 's/^/persister errors logged during the outage: /' || true
