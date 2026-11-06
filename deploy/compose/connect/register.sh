#!/usr/bin/env bash
# Registers (or updates) the Debezium outbox connectors, one per order database, then waits
# past the first heartbeat (10 s) before judging them: a task can report RUNNING and still die
# on its first heartbeat record, which is how the ByteArrayConverter/heartbeat clash showed up.
#
# Runs inside the connect container: make connectors. order-svc must have started once, so the
# outbox table and its publication exist.
set -euo pipefail
base=http://localhost:8083/connectors
for config in /setup/*.json; do
    name=$(basename "$config" .json)
    echo "PUT connector $name"
    curl -fsS -X PUT -H 'Content-Type: application/json' --data @"$config" "$base/$name/config" > /dev/null
    curl -fsS -X POST "$base/$name/restart?includeTasks=true&onlyFailed=true" > /dev/null || true
done
sleep 15
failed=0
for config in /setup/*.json; do
    name=$(basename "$config" .json)
    status=$(curl -fsS "$base/$name/status")
    echo "$status"
    if echo "$status" | grep -q '"state":"FAILED"'; then
        failed=1
    fi
done
exit $failed
