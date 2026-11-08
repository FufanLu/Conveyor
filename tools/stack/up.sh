# Sourced by tools/e2e/run.sh and tools/loadtest/prime-day.sh: the full stack on this machine.
# Starts the compose infrastructure, the eight services (traces to Jaeger) and the Debezium
# connectors. Leaves PIDS (service processes, killed on exit) and $OUT/pids ("name pid" lines).
# Needs Docker and Java 25; about 12 GB of memory.

JAVA=${JAVA:-java}
OUT=${OUT:-build/stack}
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

