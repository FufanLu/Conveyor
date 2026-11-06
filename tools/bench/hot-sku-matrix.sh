#!/usr/bin/env bash
# The hot-SKU benchmark matrix behind docs/illustrated/03-ten-thousand-buyers-one-sku.md:
# every strategy x every concurrency level, ROUNDS times, against a throwaway Postgres
# database and Redis. Used by .github/workflows/bench.yml (clean runner) and runnable anywhere:
#
#   ./gradlew :services:inventory-svc:bootJar
#   JDBC=jdbc:postgresql://127.0.0.1:5432/bench DB_USER=bench DB_PASSWORD=bench REDIS=redis://127.0.0.1:6379 \
#     JAVA=/path/to/jdk-25/bin/java tools/bench/hot-sku-matrix.sh
set -euo pipefail

JAVA=${JAVA:-java}
JDBC=${JDBC:-jdbc:postgresql://127.0.0.1:5432/bench}
DB_USER=${DB_USER:-bench}
DB_PASSWORD=${DB_PASSWORD:-bench}
REDIS=${REDIS:-redis://127.0.0.1:6379}
STOCK=${STOCK:-10000}
ATTEMPTS=${ATTEMPTS:-20000}
POOL=${POOL:-32}
BUCKETS=${BUCKETS:-16}
ROUNDS=${ROUNDS:-2}
CONCURRENCY=${CONCURRENCY:-16 64 256}
STRATEGIES=${STRATEGIES:-ROW_LOCK CONDITIONAL BUCKETS REDIS}
JAR=${JAR:-services/inventory-svc/build/libs/inventory-svc.jar}
WORK=${WORK:-build/hot-sku-bench}

rm -rf "$WORK" && mkdir -p "$WORK"
"$JAVA" -Djarmode=tools -jar "$JAR" extract --destination "$WORK/app" > /dev/null
CP="$WORK/app/$(basename "$JAR"):$WORK/app/lib/*"

echo "machine: $(uname -sm), $(getconf _NPROCESSORS_ONLN 2>/dev/null || sysctl -n hw.ncpu) CPUs"
command -v lscpu > /dev/null && lscpu | grep -E '^Model name' || true
echo "params: stock=$STOCK attempts=$ATTEMPTS pool=$POOL buckets=$BUCKETS rounds=$ROUNDS"
for c in $CONCURRENCY; do
    for round in $(seq 1 "$ROUNDS"); do
        for s in $STRATEGIES; do
            echo "### concurrency=$c round=$round"
            "$JAVA" -Dlogback.configurationFile=tools/events/logback-quiet.xml -cp "$CP" \
                dev.conveyor.inventory.bench.HotSkuBenchmark \
                jdbc="$JDBC" user="$DB_USER" password="$DB_PASSWORD" redis="$REDIS" \
                strategy="$s" stock="$STOCK" attempts="$ATTEMPTS" concurrency="$c" pool="$POOL" buckets="$BUCKETS"
        done
    done
done
