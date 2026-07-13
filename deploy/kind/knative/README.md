# Scale-to-zero on kind (Knative) — T5.3

Proves the scale-to-zero substrate for the dynamic fleet (docs/FLEET_RFC.md
T5.3): an idle graphden app scales to **0 pods**, and a request cold-starts one
on demand (the Knative activator buffers the request meanwhile). Verified on the
local kind cluster 2026-07-12.

## Why this matters (and its limit)

graphden's cold boot is ~140s (compute-bound: type-check + eager-compile). So
scale-to-zero WORKS but the first request after scale-down waits ~140s — which is
exactly why the RFC gates practical scale-to-zero on **CRaC** (development/crac/):
a ~178ms restore instead of a ~140s boot. This setup demonstrates the Knative
half; fronting the ksvc with the CRaC restore image is the remaining T5.3 step.

## Setup

```bash
KVER=knative-v1.22.1
kubectl apply -f https://github.com/knative/serving/releases/download/$KVER/serving-crds.yaml
kubectl apply -f https://github.com/knative/serving/releases/download/$KVER/serving-core.yaml
kubectl apply -f https://github.com/knative/net-kourier/releases/download/$KVER/kourier.yaml
kubectl patch configmap/config-network  -n knative-serving --type merge \
  -p '{"data":{"ingress-class":"kourier.ingress.networking.knative.dev"}}'
kubectl patch configmap/config-domain   -n knative-serving --type merge -p '{"data":{"example.com":""}}'
kubectl patch configmap/config-deployment -n knative-serving --type merge \
  -p '{"data":{"registries-skipping-tag-resolving":"kind.local,ko.local,dev.local"}}'
# faster demo: scale to zero ~30s after idle
kubectl patch configmap/config-autoscaler -n knative-serving --type merge \
  -p '{"data":{"scale-to-zero-grace-period":"30s"}}'

# local image under a registry-less-but-skipped prefix
docker tag graphden-executor:latest kind.local/graphden-executor:latest
kind load docker-image kind.local/graphden-executor:latest --name gfleet

kubectl apply -f deploy/kind/knative/graphden-ksvc.yaml
```

## Observe

```bash
# reach the ksvc through kourier (from any in-cluster pod):
GW=kourier-internal.kourier-system.svc.cluster.local
curl -H "Host: graphden-app.default.example.com" http://$GW/health   # → 200

# idle ~90s, then:
kubectl get pods -l serving.knative.dev/service=graphden-app          # → none (scaled to 0)

# a request cold-starts a pod (held by the activator ~cold-boot):
curl -H "Host: graphden-app.default.example.com" http://$GW/health    # → 200 after the cold boot (measured 115s)
```
