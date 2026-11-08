# 08 — Prime Day, and the road to a real cluster

The last milestone puts everything together and asks the question the project was built
around: what happens on the day? M8 packages the services for Kubernetes and proves the charts
on a real (kind) cluster. It describes the AWS estate in code with CDK. Then it runs a scaled-down
Prime Day against the whole platform on one machine and reads the result from the SLOs built in
M7. The answer is not flattering, and the chapter reports it as it came out.

## 1. From jars to a cluster in five minutes

**Images.** One Dockerfile (`deploy/docker/Dockerfile`) serves all seven services. It splits
each Spring Boot jar into layers (dependencies, loader, application), so a code change rebuilds
one small layer. The image runs as a non-root user and sizes the heap from the container's
memory limit.

**Charts.** `deploy/helm/conveyor` has one Deployment, Service, HorizontalPodAutoscaler and
PodDisruptionBudget per service, built from a single values map:

- **Probes.** Startup, liveness and readiness probes use the actuator's health groups. The
  startup probe gives a service 5 minutes for Flyway and the 100,000-row seeds before liveness
  starts counting.
- **Shutdown.** A `preStop` sleep lets the endpoints drop the pod before Spring's graceful
  shutdown begins.
- **order-svc is a StatefulSet.** Every order id carries an 8-bit worker number, and two pods
  must never share one. A StatefulSet numbers its pods, and the chart passes the pod's index
  (`apps.kubernetes.io/pod-index`, through the downward API) as `CONVEYOR_ORDER_WORKER_ID`.
- **Connectors.** A post-install Job registers the Debezium connectors once order-svc is
  ready.
- **Smoke test.** `helm test` gets a customer token from Keycloak, places an order through the
  gateway, and waits for the saga's `CONFIRMED`.

`deploy/helm/conveyor-deps` is development-grade middleware for kind: the same images and
settings as compose. Its ConfigMaps are built from the compose files themselves (realm, init
SQL, connector configs), so there is still only one copy of each.

**On a runner** (`.github/workflows/kind.yml`, on every change to the charts):

```
[ 47 s] cluster ready
[ 92 s] images built
[112 s] images loaded into the cluster
[217 s] middleware ready: 9 deployments
[290 s] services ready
  catalog-svc-5c85786f75-j9n9p   1/1 Running
  order-svc-0                    1/1 Running
  …
POST /api/v1/orders -> 201: {"orderId":"03471YQES0000","status":"PLACED", …}
after 1s: PLACED
after 2s: CONFIRMED
[296 s] smoke test passed
```

Two runs failed first, and both lessons are now comments in the charts:

1. **Service links.** Kubernetes injects `KAFKA_PORT`, `KAFKA_SERVICE_HOST` and similar
   variables into every pod in the namespace. The `apache/kafka` image reads every `KAFKA_*`
   variable as broker configuration. Every pod now sets `enableServiceLinks: false`.
2. **`$$` in container args.** The smoke test sent `Idempotency-Key: smoke-<time>-$$`, and
   order-svc answered 400, "Idempotency-Key must be 8-64 of A-Z a-z 0-9 _ -". Kubernetes expands
   `$(VAR)` in container args and rewrites `$$` to a literal `$` before the shell ever runs.
   The key arrived ending in `$`, and order-svc was right to refuse it. The first run could not
   say why, because `curl -f` discards the problem body. The smoke test now prints every
   response.

On kind, every service runs one replica (`deploy/kind/values-kind.yaml`), because the
middleware alone takes about 6.4 GB of the runner's 16. The autoscalers exist but stay idle
there: kind has no metrics-server, and its events say so.

## 2. The AWS estate, in Java

`deploy/cdk` is a CDK app in Java, a Gradle module like the services. Each local stand-in
becomes the managed service it was chosen for:

| Local | AWS (`ConveyorStack`) |
|---|---|
| Postgres ×2 | Aurora PostgreSQL ×2 (the two shard groups), `rds.logical_replication=1` for Debezium |
| Redis | ElastiCache, Multi-AZ, encrypted at rest and in transit |
| Kafka | MSK, 3 brokers over 3 AZs, TLS, IAM authentication |
| OpenSearch | OpenSearch Service, 2 AZs, encrypted, node-to-node TLS |
| DynamoDB Local | `tracking_events` (shipmentId, seq; TTL on expiresAt) and `shipments`, on demand, PITR |
| kind + Helm | EKS on Graviton, the same chart; ECR per service (immutable tags, scan on push) |
| Prometheus | Amazon Managed Service for Prometheus, so the SLO rules move unchanged |

The data tier sits in isolated subnets. Each data security group admits only the EKS nodes'
security group, and only on its own port. Environments differ by sizing: `staging`,
`production`, and `prime-day`, which is a separate, bigger environment rather than a flag. Six
JUnit tests assert the synthesized template with CDK's assertion library and need no AWS
account:

- two encrypted, deletion-protected Aurora clusters, with logical replication on;
- the DynamoDB key schemas, which must match what tracking-svc creates locally;
- no ingress rule from an address range anywhere in the data tier;
- encryption and zone spread for the cache, Kafka and search;
- an immutable, scanned repository per service;
- Prime Day's node group at 6/12/40 against production's 3/6/20.

`./gradlew :deploy:cdk:synth` writes the CloudFormation (65 resources for staging). Nothing
has been deployed, because this project has no AWS account. EKS is declared with the L1
constructs, so the stack needs no kubectl Lambda layer; the pipeline would install the chart
with the endpoints from the stack's outputs.

## 3. Prime Day on one machine

`tools/loadtest/prime-day.sh` brings up the whole platform on one 4-vCPU runner. That is all
the compose middleware plus the seven service JVMs, the same stack as the M7 end-to-end run.
`PrimeDay.java` then sends load through the gateway as real customers:

- **Customers.** 200 are created through Keycloak's admin API and signed in.
- **Load.** It is open loop: requests go out on schedule whether or not earlier ones finished,
  as real traffic does. Browsing and regular orders run throughout, at different rates per
  phase:

| Phase | Length | Browse | Regular orders | Deal |
|---|---|---|---|---|
| warm | 60 s | 40/s | 4/s | — |
| deal | 60 s | 80/s | 4/s | 7,000 buyers for a 6,000-unit SKU: 117/s |
| peak | 120 s | 120/s | 15/s | — |
| recover | 60 s | 40/s | 4/s | — |

Simulated couriers report the whole time. The verdict then comes from the platform itself:
deal orders by final status from the ops order search, the deal SKU's stock from inventory-svc,
and the SLOs from Prometheus.

### What one machine did

Four runs; the third failed in its own setup. The first exposed two problems in the harness,
not the platform:

- it browsed anonymously from one address, so the per-address rate limit (50/s) answered 429
  to the whole crowd;
- its warm phase overlapped Keycloak hashing 200 passwords.

Both were fixed for the later runs. The fourth run used a tenth of the courier load, to find
out how much of the machine the firehose was using.

| | Run 2: 2,000 couriers (≈2,000 reports/s) | Run 4: 200 couriers |
|---|---:|---:|
| Deal buyers / accepted / confirmed | 7,000 / 811 / 811 | 7,000 / 1,481 / 1,481 |
| Deal stock afterwards (6,000 − confirmed) | 5,189 | 4,519 |
| Peak-phase orders answered 201 | 1,711 of 1,800 | **1,800 of 1,800** |
| Checkout availability (SLO 99.9 %) | 31.5 % | 39.9 % |
| Checkout under 300 ms (SLO 99 %) | 51.4 % | 77.0 % |
| Checkout p99 at the edge | 1,882 ms | 906 ms |
| Recovery phase: orders / browsing OK | 100 % / 83–95 % | 100 % / 99.7 % |
| Saga backlog drained after load ended | 1 s | 107 s |
| Burn-rate alerts | 2 pages, 2 tickets | 1 page, 2 tickets |

### Reading it

**Correctness held under every run.** Every deal order the platform accepted was confirmed by
the saga. The stock left afterwards was exactly 6,000 minus the confirmed count, in every run:
nothing was sold twice, and no hold was lost. Every customer who did not get through got a
fast 503 with `Retry-After`. No one waited out a timeout.

**Capacity did not.** The deal asks for 117 orders a second. On a single shared box, with every
database, broker, search engine, workflow engine and service JVM on the same four vCPUs, the
platform confirms about 25. The gateway shed the difference, and because orders are checkout
traffic, those sheds count against the 99.9 % objective. The SLO failed, the dashboards showed
it, and the burn-rate rules paged within minutes, as M7 designed them to. The run measures
the edge of one machine, not of the design.

**The firehose matters on a shared box.** Cutting the couriers from 2,000 to 200 shipments
nearly doubled the deal orders accepted (811 to 1,481). Peak-phase orders went from 95 % to
100 %, and checkout p99 halved. In production, tracking runs on its own pods with DynamoDB and
MSK as managed services. The prime-day sizing in the CDK stack gives the cluster 6–40 nodes
instead of one.

**The saga is the next bottleneck.** In run 4, 1,481 deal sagas plus the regular orders took
107 s after the load stopped to finish. The Temporal dev server keeps its history in SQLite in
a single process. Temporal Cloud, or a proper Temporal cluster on Postgres or Cassandra, is the
production answer.

**What a real deal day needs that this does not have.** A **virtual waiting room** in front of
the deal: hand out places in line at the edge and admit buyers at the rate the platform can
confirm, instead of turning 80 % of them away with a 503. Also **per-route admission**, so that
a deal on one SKU cannot use up the order capacity that regular checkout needs. Here, during
the deal, regular orders were shed along with the deal (35 of 240 accepted in run 4), because both are
"critical" and share one limit. These are the first two items for whatever comes after M8.

### The firehose run, phase by phase (run 2)

```
  warm     order    sent    240  {-1=23, 201=171, 503=46}     p50 621.5 p99 2495.2 ms
  deal     deal     sent   7000  {201=811, 503=6189}          p50 576.8 p99 1913.3 ms
  deal     search   sent   2861  {200=2, 503=2859}
  peak     order    sent   1800  {-1=30, 201=1711, 503=59}    p50 259.2 p99 9563.2 ms
  recover  order    sent    240  {201=240}                    p50  58.2 p99  262.3 ms
couriers: 1,960 reports/s accepted, 0 errors; 321,502 of 748,930 stored by the end (DynamoDB Local)
```

During the deal, search was shed almost entirely (2 of 2,861 answered) and the deal still got
through at the rate the machine could confirm. That is the priority order chapter 07 built,
doing its job on the real platform.

## Reproduce

```bash
./gradlew assemble && deploy/kind/up.sh          # kind, both charts, helm test (~10 GB for Docker)
./gradlew :deploy:cdk:test :deploy:cdk:synth     # build/cdk.out/Conveyor-staging.template.json
tools/loadtest/prime-day.sh                      # full stack + Prime Day (~12 GB)
```
