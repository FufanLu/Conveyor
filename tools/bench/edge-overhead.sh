#!/usr/bin/env bash
# Measures what the gateway hop costs, and what synchronous vs asynchronous logging costs the
# gateway's Netty event loop, both with a healthy log sink and with a stalled one.
# Reproduces the tables in docs/illustrated/00-a-request-through-the-edge.md.
#
#   ./gradlew :services:gateway:bootJar :services:catalog-svc:bootJar
#   JAVA=/path/to/jdk-25/bin/java tools/bench/edge-overhead.sh
#
# Every case starts fresh JVMs, warms up, then measures. The request hits a path catalog-svc
# does not serve yet, so it measures the platform (routing, filters, error contract, logging)
# and nothing else. Load generator, gateway and catalog-svc share one machine, so read the
# numbers as relative costs, not capacity.
set -euo pipefail

JAVA=${JAVA:-java}
N=${N:-50000}
C=${C:-16}
WARM=${WARM:-30000}
OUT=${OUT:-build/bench/edge}
# Override on shared machines (e.g. an HPC compute node) where 8080/8081 may be taken.
GATEWAY_PORT=${GATEWAY_PORT:-8080}
CATALOG_PORT=${CATALOG_PORT:-8081}
GATEWAY_JAR=services/gateway/build/libs/gateway.jar
CATALOG_JAR=services/catalog-svc/build/libs/catalog-svc.jar
mkdir -p "$OUT"

# Like start(), but stdout goes through a FIFO into SlowSink (1 ms per line), a log consumer
# that has fallen behind. The number of lines it received is written to $OUT/<label>.lines.
start_slow_sink() { # name, label, then java args
    local name=$1 label=$2
    shift 2
    rm -f "$OUT/$name.fifo" "$OUT/$label.lines"
    mkfifo "$OUT/$name.fifo"
    "$JAVA" tools/bench/SlowSink.java "$OUT/$label.lines" <"$OUT/$name.fifo" &
    "$JAVA" "$@" >"$OUT/$name.fifo" 2>&1 &
    echo $! >"$OUT/$name.pid"
}

start() { # name, then java args
    local name=$1
    shift
    "$JAVA" "$@" >"$OUT/$name.log" 2>&1 &
    echo $! >"$OUT/$name.pid"
}

wait_up() {
    # --noproxy: HPC nodes often export http_proxy, which would capture loopback requests too.
    until curl --noproxy '*' -fs -o /dev/null "http://127.0.0.1:$1/actuator/health"; do sleep 0.3; done
}

stop_all() {
    for pid_file in "$OUT"/*.pid; do
        [ -e "$pid_file" ] || continue
        kill "$(cat "$pid_file")" 2>/dev/null || true
        rm -f "$pid_file"
    done
    sleep 1
}
trap stop_all EXIT

measure() { # label, url
    echo "### $1"
    "$JAVA" tools/loadgen/LoadGen.java "$2" "$N" "$C" "$WARM" | tee "$OUT/$1.txt"
    echo
}

run_case() { # label, then extra gateway args ("direct" = no gateway; "*-slow-sink" = SlowSink)
    local label=$1
    shift
    start catalog -jar "$CATALOG_JAR" --server.port="$CATALOG_PORT"
    wait_up "$CATALOG_PORT"
    if [ "$label" = direct ]; then
        measure "$label" "http://127.0.0.1:$CATALOG_PORT/v1/products/B0CONVEYOR1"
    else
        local gateway_args=(-jar "$GATEWAY_JAR" --server.port="$GATEWAY_PORT"
            --conveyor.upstreams.catalog="http://127.0.0.1:$CATALOG_PORT" "$@")
        case $label in
            *-slow-sink) start_slow_sink gateway "$label" "${gateway_args[@]}" ;;
            *) start gateway "${gateway_args[@]}" ;;
        esac
        wait_up "$GATEWAY_PORT"
        measure "$label" "http://127.0.0.1:$GATEWAY_PORT/api/v1/products/B0CONVEYOR1"
    fi
    stop_all
    case $label in
        *-slow-sink)
            # SlowSink drains what is left in the pipe, then writes its count at EOF.
            until [ -s "$OUT/$label.lines" ]; do sleep 0.5; done
            echo "requests sent (warm-up + measured): $((WARM + N))" | tee -a "$OUT/$label.txt"
            echo "log lines that reached the slow sink: $(cat "$OUT/$label.lines")" | tee -a "$OUT/$label.txt"
            echo
            ;;
    esac
}

# CASES="gateway-sync-log-slow-sink gateway-async-log-slow-sink" runs a subset.
CASES=${CASES:-direct gateway-sync-log gateway-async-log gateway-no-access-log gateway-sync-log-slow-sink gateway-async-log-slow-sink}
SYNC_LOG=--logging.config=tools/bench/logback-sync.xml
for case_label in $CASES; do
    case $case_label in
        direct) run_case direct ;;
        gateway-sync-log) run_case gateway-sync-log "$SYNC_LOG" ;;
        gateway-async-log) run_case gateway-async-log ;;
        gateway-no-access-log) run_case gateway-no-access-log --logging.level.conveyor.access=WARN ;;
        gateway-sync-log-slow-sink) run_case gateway-sync-log-slow-sink "$SYNC_LOG" ;;
        gateway-async-log-slow-sink) run_case gateway-async-log-slow-sink ;;
        *) echo "unknown case: $case_label" >&2; exit 2 ;;
    esac
done
