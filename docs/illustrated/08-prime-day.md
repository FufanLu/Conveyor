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

<!-- results below -->

## Reproduce

```bash
./gradlew assemble && deploy/kind/up.sh          # kind, both charts, helm test (~10 GB for Docker)
./gradlew :deploy:cdk:test :deploy:cdk:synth     # build/cdk.out/Conveyor-staging.template.json
tools/loadtest/prime-day.sh                      # full stack + Prime Day (~12 GB)
```
