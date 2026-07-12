# Deploying the dynamic fleet (Kubernetes / Helm)

This guide deploys Graphden as a **dynamic executor fleet** — multiple executor
pods that auto-place tenant cells and rebalance load under an in-app,
leader-locked placement controller. The design is [FLEET_RFC.md](FLEET_RFC.md);
this is the operational how-to.

The chart lives in `deploy/helm/graphden`.

## What the fleet is

- Each **pod** is a full executor. Its identity (`GRAPHDEN_EXECUTOR_ID`) is its
  own stable DNS name, so any pod can forward a misrouted request to the pod
  that holds the target **cell** (a root fn + its forward ref-closure).
- One pod wins a Postgres **advisory lock** and runs the **placement
  controller**: every tick it reads live cell weights + the executor set, places
  new tenant cells, and rebalances *sustained* load imbalance by moving cells
  (load-on-target → flip routing epoch → evict-source).
- **Membership** is discovered from the headless Service's **SRV** record, so it
  tracks pods as an HPA scales the StatefulSet in and out.

No custom controller/CRD and no RBAC are needed — the controller is part of the
app, and discovery is plain DNS, not the k8s API.

## Prerequisites

- A Kubernetes cluster and `helm` 3+.
- A **managed PostgreSQL** reachable from the cluster (the fleet does not run its
  own DB). Point `database.jdbcUrl` at it.
- The `graphden/executor-server` image pushed to a registry your cluster can pull
  (build with `bb rebuild`'s jar + `docker build`, then push/tag).

## Install

```bash
helm install fleet deploy/helm/graphden \
  --set image.repository=your-registry/graphden-executor \
  --set image.tag=0.1.0 \
  --set replicaCount=3 \
  --set database.jdbcUrl=jdbc:postgresql://your-managed-pg:5432/graphden \
  --set database.username=graphden \
  --set secrets.dbPassword=... \
  --set secrets.authToken=... \
  --set secrets.internalToken=...
```

`secrets.internalToken` is the shared control-plane secret gating the internal
cell-command endpoint. **If it is empty the controller cannot move cells** (the
endpoint fail-closes) — set the same value on every pod (the chart does this via
one Secret).

To bring your own Secret instead of chart-managed:

```bash
--set secrets.create=false --set secrets.existingSecret=my-graphden-secret
# keys: auth-token, internal-token, db-password (, vault-token if vault.enabled)
```

## Autoscaling

```bash
helm upgrade fleet deploy/helm/graphden --reuse-values \
  --set autoscaling.enabled=true \
  --set autoscaling.minReplicas=3 \
  --set autoscaling.maxReplicas=12 \
  --set autoscaling.targetCPUUtilizationPercentage=70
```

The HPA owns the pod **count**; the placement controller owns **which cells run
where**. As the HPA adds/removes pods, SRV membership follows and the controller
load/evicts cells to match. (When `autoscaling.enabled`, the StatefulSet's
`replicas` field is omitted so the HPA is authoritative.)

## Controller tuning

| Value | Env | Meaning |
|-------|-----|---------|
| `fleet.controllerPeriodMs` | `GRAPHDEN_FLEET_CONTROLLER_PERIOD_MS` | Tick period (default 30 s) |
| `fleet.sustainTicks` | `GRAPHDEN_FLEET_SUSTAIN_TICKS` | Ticks an imbalance must persist before a move fires (time hysteresis) |
| `fleet.minImprovement` | `GRAPHDEN_FLEET_MIN_IMPROVEMENT` | Magnitude floor — drop a plan that improves imbalance by less (magnitude hysteresis) |
| `fleet.maxMoves` | `GRAPHDEN_FLEET_MAX_MOVES` | Per-tick cap on rebalance moves |

Both hysteresis levers exist because a move costs a compile + cache-warm on the
target — raise `sustainTicks` / `minImprovement` if you see the fleet churning.

## Verify

```bash
# Pods up:
kubectl rollout status statefulset/fleet-graphden

# Membership resolvable (from a pod):
kubectl exec fleet-graphden-0 -- \
  nslookup -type=SRV _http._tcp.fleet-graphden-headless.default.svc.cluster.local

# Which pod is the leader / what it did — grep the logs:
kubectl logs -l app.kubernetes.io/name=graphden | grep "Fleet controller"
```

A pod that is the leader logs `Fleet controller applied placement {...}` when it
places or moves cells. Non-leaders stay quiet.

## How a request finds its cell

1. The front-door Service round-robins the request to some pod.
2. If that pod holds the cell, it serves it. If not, it reads the `:placement`
   map and **forward-hops** to the holder (`http://<executor-id>:<port>`).
3. A stale/absent placement falls back to `421 Misdirected` — the rare backstop,
   not the mechanism.

See [SCALING.md](SCALING.md) for the static-shard / BYO story this builds on, and
[FLEET_RFC.md](FLEET_RFC.md) §6 for the routing + controller internals.
