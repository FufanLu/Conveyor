#!/usr/bin/env python3
"""Order pipeline latency, stage by stage, for docs/illustrated/02-placing-an-order.md.

Places N orders through the gateway one after another (fresh Idempotency-Key each), then for
each order measures:
  commit -> Kafka       outbox.created_at (transaction start) to the Kafka record's CreateTime
  201 -> searchable     client receives 201 to the order being visible in the OpenSearch projection
A timeout is reported as a timeout, never as a latency.

  python3 tools/bench/order-pipeline-latency.py --gateway http://localhost:8080 --opensearch http://localhost:9200
Needs: Keycloak user alice/alice, docker (for psql and the Kafka CLI), the tail tool's classpath.
"""
import argparse, json, subprocess, sys, time, urllib.request, uuid, statistics

ap = argparse.ArgumentParser()
ap.add_argument("--gateway", default="http://localhost:8080")
ap.add_argument("--opensearch", default="http://localhost:9200")
ap.add_argument("--keycloak", default="http://localhost:8180")
ap.add_argument("-n", type=int, default=20)
ap.add_argument("--timeout", type=float, default=10.0)
args = ap.parse_args()

def http(method, url, body=None, headers=None):
    req = urllib.request.Request(url, data=body, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(req, timeout=10) as r:
            return r.status, dict(r.headers), r.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()

def token():
    _, _, b = http("POST", f"{args.keycloak}/realms/conveyor/protocol/openid-connect/token",
                   b"grant_type=password&client_id=conveyor-cli&username=alice&password=alice",
                   {"Content-Type": "application/x-www-form-urlencoded"})
    return json.loads(b)["access_token"]

def shard_of(order_id):
    alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    v = 0
    for c in order_id:
        v = v * 32 + alphabet.index(c)
    return (v >> 18) & 15

body = json.dumps({"items": [{"sku": "B078DDE6C4", "quantity": 1}, {"sku": "B0DAA66D13", "quantity": 1}],
                   "shipToRegion": "us-east"}).encode()
tok = token()
results = []
for i in range(args.n):
    t_send = time.time()
    status, headers, resp = http("POST", f"{args.gateway}/api/v1/orders", body,
                                 {"Authorization": f"Bearer {tok}", "Content-Type": "application/json",
                                  "Idempotency-Key": f"latency-{uuid.uuid4()}"})
    t_201 = time.time()
    if status != 201:
        print(f"order {i}: HTTP {status} {resp[:200]!r}", file=sys.stderr)
        continue
    oid = json.loads(resp)["orderId"]
    visible = None
    while time.time() - t_201 < args.timeout:
        s, _, b = http("GET", f"{args.opensearch}/orders/_doc/{oid}")
        if s == 200 and json.loads(b).get("found"):
            visible = time.time()
            break
        time.sleep(0.005)
    results.append({"oid": oid, "http_ms": (t_201 - t_send) * 1000,
                    "searchable_ms": None if visible is None else (visible - t_201) * 1000})

# commit -> Kafka from the database and the topic
for r in results:
    db = 0 if shard_of(r["oid"]) < 8 else 1
    out = subprocess.run(["docker", "exec", f"conveyor-postgres-{db}-1", "psql", "-U", "conveyor", "-d", "orders", "-Atc",
                          f"select extract(epoch from created_at) from outbox where aggregateid='{r['oid']}'"],
                         capture_output=True, text=True).stdout.strip()
    r["committed"] = float(out) if out else None
dump = subprocess.run(["docker", "exec", "conveyor-kafka-1", "/opt/kafka/bin/kafka-console-consumer.sh",
                       "--bootstrap-server", "localhost:9092", "--topic", "orders.events", "--from-beginning",
                       "--timeout-ms", "5000", "--property", "print.key=true", "--property", "print.timestamp=true",
                       "--property", "print.value=false"], capture_output=True, text=True).stdout
kafka_ts = {}
for line in dump.splitlines():
    parts = line.split("\t")
    if len(parts) >= 2 and parts[0].startswith("CreateTime:"):
        kafka_ts[parts[1].strip()] = int(parts[0].split(":")[1]) / 1000.0
for r in results:
    k = kafka_ts.get(r["oid"])
    r["cdc_ms"] = None if (k is None or r["committed"] is None) else (k - r["committed"]) * 1000

def summary(name, xs):
    vals = [x for x in xs if x is not None]
    missing = len(xs) - len(vals)
    if not vals:
        return f"{name:22s} no data ({missing} missing)"
    vals.sort()
    return (f"{name:22s} n={len(vals):2d}  min {vals[0]:7.1f}  median {statistics.median(vals):7.1f}  "
            f"max {vals[-1]:7.1f} ms" + (f"   ({missing} timed out / missing)" if missing else ""))

print(f"{len(results)} orders placed")
print(summary("POST -> 201", [r["http_ms"] for r in results]))
print(summary("commit -> Kafka", [r["cdc_ms"] for r in results]))
print(summary("201 -> searchable", [r["searchable_ms"] for r in results]))
