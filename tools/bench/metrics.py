"""Scrape tracking-svc's Prometheus endpoint. Prints a snapshot as JSON, or the difference
between two snapshots: counters, and percentiles computed from histogram-bucket deltas (so they
cover exactly the interval, unlike the service's decaying percentile gauges).

  python3 metrics.py snap URL > a.json ; ... ; python3 metrics.py diff a.json b.json
"""
import json, re, sys, urllib.request

LINE = re.compile(r'^([a-zA-Z_:][a-zA-Z0-9_:]*)(\{[^}]*\})? ([0-9.eE+-]+|NaN|\+Inf)$')

def snap(url):
    text = urllib.request.urlopen(url + "/actuator/prometheus", timeout=10).read().decode()
    out = {}
    for line in text.splitlines():
        m = LINE.match(line)
        if m:
            out[m.group(1) + (m.group(2) or "")] = float(m.group(3))
    return out

def quantile(a, b, name, q):
    buckets = []
    for k, v in b.items():
        if k.startswith(name + "_bucket{"):
            le = re.search(r'le="([^"]+)"', k).group(1)
            buckets.append((float("inf") if le == "+Inf" else float(le), v - a.get(k, 0)))
    buckets.sort()
    total = buckets[-1][1] if buckets else 0
    if total <= 0:
        return None
    for le, count in buckets:
        if count >= q * total:
            return le
    return None

def diff(a, b):
    d = lambda k: b.get(k, 0) - a.get(k, 0)
    res = {
        "ingested": d("tracking_ingest_events_total"),
        "refused_batches": d("tracking_ingest_refused_batches_total"),
        "stored": d('tracking_persist_events_total{outcome="stored"}'),
        "duplicates": d('tracking_persist_events_total{outcome="duplicate"}'),
        "shipment_writes": d("tracking_persist_shipment_writes_total"),
        "live_published": d("tracking_live_published_total"),
    }
    n = d("tracking_persist_batch_size_count")
    res["avg_batch"] = round(d("tracking_persist_batch_size_sum") / n, 1) if n else None
    for name in ("tracking_persist_delay_seconds", "tracking_live_delay_seconds"):
        for q in (0.5, 0.99, 1.0):
            v = quantile(a, b, name, q)
            res[f"{name.replace('tracking_', '').replace('_seconds', '')}_p{int(q * 100)}_le_ms"] = None if v is None else round(v * 1000, 1)
    return res

if __name__ == "__main__":
    if sys.argv[1] == "snap":
        print(json.dumps(snap(sys.argv[2])))
    else:
        a, b = (json.load(open(p)) for p in sys.argv[2:4])
        print(json.dumps(diff(a, b)))
