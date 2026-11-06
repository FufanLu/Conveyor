#!/usr/bin/env bash
# Cache experiments behind docs/illustrated/01-reading-a-product-under-a-stampede.md.
#
# For each cache mode (NONE, NAIVE, TWO_LEVEL) it starts catalog-svc, warms it up, then:
#   stampede     BURST concurrent requests for one cold SKU (repeated ROUNDS times, new SKU each)
#   penetration  PROBES requests for distinct SKUs that do not exist
# and reports how many reads reached Postgres, from catalog_cache_requests_total.
#
# Needs the core infrastructure (make infra-up) and a built catalog-svc jar.
#   JAVA=/path/to/jdk-25/bin/java tools/bench/cache-experiments.sh
set -euo pipefail

JAVA=${JAVA:-java}
OUT=${OUT:-build/bench/cache}
PORT=${CATALOG_PORT:-18081}
BURST=${BURST:-1000}
ROUNDS=${ROUNDS:-3}
PROBES=${PROBES:-10000}
MODES=${MODES:-NONE NAIVE TWO_LEVEL}
CATALOG_JAR=services/catalog-svc/build/libs/catalog-svc.jar
redis() { docker exec conveyor-redis-1 redis-cli "$@"; }
psql() { docker exec conveyor-postgres-0-1 psql -U conveyor -d catalog -Atc "$1"; }
mkdir -p "$OUT"

counter() {
    curl --noproxy '*' -s "http://127.0.0.1:$PORT/actuator/prometheus" \
        | grep "^catalog_cache_requests_total{outcome=\"$1\"}" | awk '{print int($2)}'
}

load() { "$JAVA" tools/loadgen/LoadGen.java "$@"; }

stop_catalog() {
    [ -e "$OUT/catalog.pid" ] && kill "$(cat "$OUT/catalog.pid")" 2>/dev/null || true
    rm -f "$OUT/catalog.pid"
    sleep 2
}
trap stop_catalog EXIT

for mode in $MODES; do
    echo "################ mode $mode"
    # Forget everything earlier runs cached, including negative entries for unknown SKUs.
    redis --scan --pattern 'cat:p:*' | xargs -r -n 500 docker exec conveyor-redis-1 redis-cli del >/dev/null
    "$JAVA" -jar "$CATALOG_JAR" --server.port="$PORT" --conveyor.catalog.cache.mode="$mode" \
        --conveyor.catalog.search.reindex-on-startup=false >"$OUT/catalog-$mode.log" 2>&1 &
    echo $! >"$OUT/catalog.pid"
    until curl --noproxy '*' -fs -o /dev/null "http://127.0.0.1:$PORT/actuator/health/readiness"; do sleep 0.5; done

    # Warm-up on unrelated SKUs: JIT, connection pools.
    load "http://127.0.0.1:$PORT/v1/products/B09E3779B1" 20000 16 0 >/dev/null

    for round in $(seq 1 "$ROUNDS"); do
        sku=$(psql "SELECT sku FROM product ORDER BY sku OFFSET $((10000 + round * 37)) LIMIT 1")
        redis del "cat:p:$sku" >/dev/null
        before=$(counter db_load)
        echo "### $mode stampede round $round: $BURST concurrent requests for cold $sku"
        load "http://127.0.0.1:$PORT/v1/products/$sku" "$BURST" "$BURST" 0 | tee -a "$OUT/$mode-stampede.txt"
        echo "database loads: $(($(counter db_load) - before))" | tee -a "$OUT/$mode-stampede.txt"
        echo
    done

    before_db=$(counter db_load)
    before_bloom=$(counter bloom_reject)
    echo "### $mode penetration: $PROBES distinct unknown SKUs"
    load "http://127.0.0.1:$PORT/v1/products/B0%08X" "$PROBES" 50 | tee -a "$OUT/$mode-penetration.txt"
    echo "database loads: $(($(counter db_load) - before_db))   bloom rejects: $(($(counter bloom_reject) - before_bloom))" \
        | tee -a "$OUT/$mode-penetration.txt"
    echo
    stop_catalog
done
