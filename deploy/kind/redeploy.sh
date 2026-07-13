#!/usr/bin/env bash
# Redeploy the current graphden image into the persistent kind fleet.
# Assumes: `bb rebuild` already built graphden-executor:latest, and the
# `gfleet` cluster exists (deploy/kind/cluster.yaml). Idempotent.
set -uo pipefail
CTX=kind-gfleet
kind load docker-image graphden-executor:latest --name gfleet
kubectl --context "$CTX" apply -f "$(dirname "$0")/postgres.yaml"
kubectl --context "$CTX" wait --for=condition=available deploy/postgres --timeout=120s
helm --kube-context "$CTX" upgrade --install fleet "$(dirname "$0")/../helm/graphden" \
  --set image.repository=graphden-executor \
  --set image.tag=latest \
  --set image.pullPolicy=Never \
  --set replicaCount=2 \
  --set service.type=NodePort \
  --set service.nodePort=30080 \
  --set database.jdbcUrl=jdbc:postgresql://postgres:5432/graphden \
  --set secrets.authToken="${AUTH_TOKEN:-testauth}" \
  --set secrets.internalToken="${GRAPHDEN_INTERNAL_TOKEN:-testinternal}" \
  --set secrets.dbPassword=graphden \
  --set probes.startupFailureThreshold=45 \
  --set resources.requests.cpu=250m \
  --set resources.requests.memory=640Mi
# roll pods to pick up a new image (upgrade doesn't restart on identical spec)
kubectl --context "$CTX" rollout restart statefulset/fleet-graphden
echo "→ fleet redeploying; front-door on host localhost:30080 (NodePort)"
