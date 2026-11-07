#!/usr/bin/env bash
# Conveyor on a local Kubernetes cluster (kind): build the eight images, load them into the
# cluster, install the middleware (conveyor-deps) and the services (conveyor), then run the smoke
# test, which places an order and waits for the saga to confirm it.
#
#   ./gradlew assemble && deploy/kind/up.sh
#
# Needs Docker, kind, kubectl and helm, and about 10 GB of memory for Docker. On a Linux host,
# OpenSearch also needs: sudo sysctl -w vm.max_map_count=262144
set -euo pipefail

CLUSTER=${CLUSTER:-conveyor}
TAG=${TAG:-dev}
SERVICES="gateway catalog-svc order-svc inventory-svc fulfillment-svc tracking-svc payment-mock"
here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/../.." && pwd)
compose="$root/deploy/compose"
t0=$SECONDS
stamp() { echo "[$((SECONDS - t0)) s] $*"; }

if ! kind get clusters | grep -qx "$CLUSTER"; then
    kind create cluster --name "$CLUSTER" --config "$here/kind-config.yaml" --wait 120s
fi
kubectl config use-context "kind-$CLUSTER" > /dev/null
stamp "cluster ready"

for s in $SERVICES; do
    docker build -q -f "$root/deploy/docker/Dockerfile" --build-arg SERVICE="$s" -t "conveyor/$s:$TAG" "$root" > /dev/null
done
stamp "images built"
# shellcheck disable=SC2046
kind load docker-image --name "$CLUSTER" $(for s in $SERVICES; do echo "conveyor/$s:$TAG"; done) > /dev/null
stamp "images loaded into the cluster"

# The same realm, init SQL and connector configs as compose: one source of truth.
apply_cm() { kubectl create configmap "$1" "${@:2}" --dry-run=client -o yaml | kubectl apply -f - > /dev/null; }
apply_cm conveyor-realm --from-file="$compose/keycloak/conveyor-realm.json"
apply_cm conveyor-pg0-init --from-file=01-databases.sql="$compose/postgres/init-pg0.sql"
apply_cm conveyor-pg1-init --from-file=01-databases.sql="$compose/postgres/init-pg1.sql"
apply_cm conveyor-connectors --from-file="$compose/connect/orders-outbox-pg0.json" \
    --from-file="$compose/connect/orders-outbox-pg1.json"

helm upgrade --install deps "$root/deploy/helm/conveyor-deps" --wait --timeout 10m > /dev/null
stamp "middleware ready: $(kubectl get deploy -l app.kubernetes.io/part-of=conveyor-deps --no-headers | wc -l | tr -d ' ') deployments"

helm upgrade --install conveyor "$root/deploy/helm/conveyor" -f "$here/values-kind.yaml" --set image.tag="$TAG" --wait --timeout 15m > /dev/null
stamp "services ready"
kubectl get pods -l app.kubernetes.io/part-of=conveyor -o wide --no-headers | awk '{print "  " $1, $2, $3, $5}'

helm test conveyor --logs --timeout 5m
stamp "smoke test passed"
