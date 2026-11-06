#!/usr/bin/env bash
# The end-to-end burst behind docs/illustrated/03: start inventory-svc against Postgres and
# Redis on localhost, then let BUYERS clients reserve one unit of a deal SKU (6 x 1,000 units)
# at the same moment over gRPC, retrying RESOURCE_EXHAUSTED with jittered backoff.
# Afterwards: what was held, what was rejected, and whether Redis, Postgres and the
# reservation rows agree. Runs in .github/workflows/bench.yml; locally needs make infra-up.
set -euo pipefail

JAVA=${JAVA:-java}
BUYERS=${BUYERS:-10000}
# Reserve calls allowed in flight at once (the admission bulkhead). A huge value turns it off.
MAX_IN_FLIGHT=${MAX_IN_FLIGHT:-64}
JAR=${JAR:-services/inventory-svc/build/libs/inventory-svc.jar}
WORK=${WORK:-build/service-burst}
rm -rf "$WORK" && mkdir -p "$WORK"
"$JAVA" -Djarmode=tools -jar "$JAR" extract --destination "$WORK/app" > /dev/null
CP="$WORK/app/$(basename "$JAR"):$WORK/app/lib/*"

echo "machine: $(uname -sm), $(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu) CPUs"
command -v lscpu > /dev/null && lscpu | grep -E '^Model name' || true

echo "admission: max-in-flight=$MAX_IN_FLIGHT"
"$JAVA" -jar "$JAR" --conveyor.inventory.admission.max-in-flight="$MAX_IN_FLIGHT" > "$WORK/inventory.log" 2>&1 &
SVC=$!
trap 'kill $SVC 2>/dev/null || true' EXIT
until curl --noproxy '*' -fs -o /dev/null http://127.0.0.1:8083/actuator/health/readiness; do sleep 1; done
grep -E 'Successfully applied|Started InventoryApplication' "$WORK/inventory.log" | sed -E 's/^.*\] \[\] [a-zA-Z.$]+ +: //'

client() {
    "$JAVA" -Dlogback.configurationFile=tools/events/logback-quiet.xml -cp "$CP" tools/grpc/InventoryClient.java localhost:9083 "$@"
}
for sku in B03C6EF362 B0DAA66D13; do # deal SKUs: rows 2 and 3 of the seed
    echo "### $BUYERS buyers, $sku"
    client burst "$sku" "$BUYERS" us-east
    curl --noproxy '*' -s "http://127.0.0.1:8083/v1/inventory/$sku" | python3 -c '
import sys, json
d = json.load(sys.stdin)
print("available after:", d["available"], {f["fcId"]: f["available"] for f in d["fcs"]})'
    curl --noproxy '*' -s http://127.0.0.1:8083/actuator/prometheus \
        | grep -E '^inventory_admission_total|^inventory_reconcile_repaired_total' || true
    held=$(docker exec "$(docker ps -qf ancestor=postgres:18.6-alpine | head -1)" psql -U conveyor -d inventory -Atc \
        "select coalesce(sum(l.quantity),0) from reservation r join reservation_line l using (reservation_id)
         where r.status='HELD' and l.sku='$sku'" 2>/dev/null || echo "?")
    echo "units held in Postgres: $held"
done
sleep 12 # two reconcile passes (5 s apart) once everything is quiet
for sku in B03C6EF362 B0DAA66D13; do
    curl --noproxy '*' -s "http://127.0.0.1:8083/v1/inventory/$sku" | python3 -c '
import sys, json
d = json.load(sys.stdin)
print("after reconciliation:", d["sku"], "available", d["available"])'
done
curl --noproxy '*' -s http://127.0.0.1:8083/actuator/prometheus | grep -E '^inventory_reconcile_repaired_total' || true
grep -cE 'Redis command timed out' "$WORK/inventory.log" | sed 's/^/Redis command timeouts in the service log: /' || true
