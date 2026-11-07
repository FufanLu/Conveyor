#!/usr/bin/env bash
# The overload experiment behind docs/illustrated/07: the gateway in front of one shared
# upstream with a fixed capacity (CapacityStub: WORKERS x SERVICE_MS; catalog and order routes
# both lead to it, as services sharing a saturated database would), offered 0.5x to 3x that capacity by an
# open-loop generator, once with the gateway's load shedding on and once with it off.
# Needs Redis on localhost (rate-limit buckets; limits raised so one client is not throttled).
set -euo pipefail

JAVA=${JAVA:-java}
WORKERS=${WORKERS:-8}
SERVICE_MS=${SERVICE_MS:-20}
SECONDS_PER_STEP=${SECONDS_PER_STEP:-20}
CAPACITY=$((WORKERS * 1000 / SERVICE_MS))
JAR=${JAR:-services/gateway/build/libs/gateway.jar}
WORK=${WORK:-build/edge-overload}
mkdir -p "$WORK"

echo "machine: $(uname -sm), $(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu) CPUs"
command -v lscpu > /dev/null && lscpu | grep -E '^Model name' || true

PIDS=()
trap 'kill "${PIDS[@]}" 2>/dev/null || true' EXIT
"$JAVA" tools/bench/CapacityStub.java 18081 "$WORKERS" "$SERVICE_MS" > "$WORK/stub-18081.log" 2>&1 &
PIDS+=($!)
sleep 3
cat "$WORK"/stub-18081.log

for shedding in true false; do
    "$JAVA" -Xmx512m -jar "$JAR" --server.port=18080 \
        --conveyor.upstreams.catalog=http://127.0.0.1:18081 --conveyor.upstreams.order=http://127.0.0.1:18081 \
        --conveyor.gateway.shedding.enabled=$shedding \
        --conveyor.gateway.rate.browse=100000 --conveyor.gateway.rate.browse-burst=100000 \
        --conveyor.gateway.rate.orders=100000 --conveyor.gateway.rate.orders-burst=100000 \
        > "$WORK/gateway-$shedding.log" 2>&1 &
    GW=$!
    until curl --noproxy '*' -fs -o /dev/null http://127.0.0.1:18080/actuator/health; do sleep 1; done
    "$JAVA" tools/bench/OverloadGen.java http://127.0.0.1:18080 200 5 > /dev/null # warm-up
    echo "### shedding=$shedding, upstream capacity $CAPACITY req/s shared by all routes"
    for factor in 0.5 1 2 3; do
        rate=$(python3 -c "print(int($CAPACITY * $factor))")
        echo "## offered ${factor}x = $rate req/s"
        "$JAVA" tools/bench/OverloadGen.java http://127.0.0.1:18080 "$rate" "$SECONDS_PER_STEP"
        if [ "$shedding" = true ]; then
            curl --noproxy '*' -s http://127.0.0.1:18080/actuator/prometheus | grep -E '^gateway_concurrency_limit' || true
        fi
        sleep 8 # let queues drain between steps
    done
    kill $GW; wait $GW 2>/dev/null || true
done
