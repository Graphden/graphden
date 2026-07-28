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
| (no `fleet.*` value — inject `GRAPHDEN_FLEET_OVERLAP_WEIGHT` via `extraEnv`) | `GRAPHDEN_FLEET_OVERLAP_WEIGHT` | Overlap-accounting weight (default 0 = pure load balancing). > 0 makes both initial placement and rebalance prefer co-locating cells that share a forward-closure — turn it on when orgs run multiple code-sharing cells. The chart's `fleet:` block only wires the four knobs above; this one is read from the env directly (`init/fleet.clj`) |

Both hysteresis levers exist because a move costs a compile + cache-warm on the
target — raise `sustainTicks` / `minImprovement` if you see the fleet churning.
`overlapWeight` stays 0 unless you have evidence that co-locating code-sharing
cells saves enough memory to be worth biasing placement.

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

## Observability — `GET /internal/fleet/status`

The placement map + per-executor load, without going to Postgres. Behind the
same `GRAPHDEN_INTERNAL_TOKEN` gate as the cell commands:

```bash
kubectl exec fleet-graphden-0 -- curl -s \
  -H "Authorization: Bearer $GRAPHDEN_INTERNAL_TOKEN" \
  http://localhost:8080/internal/fleet/status
# → {"executor-id":"fleet-graphden-0…","placements":[{"org":…,"executor-id":…}…],
#    "loads":{"fleet-graphden-0…":N,…}}
```

`executor-id` is the pod that answered; `loads` is advisory (falls back to 1 per
cell if that pod hasn't primed its `:forward-deps` yet).

## How a request finds its cell

1. The front-door Service round-robins the request to some pod.
2. If that pod holds the cell, it serves it. If not, it reads the `:placement`
   map and **forward-hops** to the holder (`http://<executor-id>:<port>`).
3. A stale/absent placement falls back to `421 Misdirected` — the rare backstop,
   not the mechanism.

## Local verification with kind

The chart + fleet mechanics were validated end-to-end on a local
[kind](https://kind.sigs.k8s.io/) cluster (2026-07-12). To reproduce:

```bash
kind create cluster --name gfleet
kind load docker-image graphden-executor:latest --name gfleet   # after bb rebuild
# deploy an in-cluster Postgres (Deployment + Service named `postgres`), then:
helm install fleet deploy/helm/graphden \
  --set image.repository=graphden-executor --set image.tag=latest \
  --set image.pullPolicy=Never --set replicaCount=1 \
  --set database.jdbcUrl=jdbc:postgresql://postgres:5432/graphden \
  --set secrets.authToken=… --set secrets.internalToken=… --set secrets.dbPassword=graphden
kubectl rollout status statefulset/fleet-graphden
kubectl scale statefulset/fleet-graphden --replicas=2
```

What that run confirmed:

- **executor-id** = the pod FQDN (`fleet-graphden-0.fleet-graphden-headless.<ns>.svc.cluster.local`),
  composed via the downward API.
- **SRV membership** — the headless Service publishes one endpoint per ready pod;
  the controller resolves `GRAPHDEN_FLEET_DNS` to the live set.
- **Leader election** — both pods start the controller, but exactly ONE holds the
  fleet-controller advisory lock (`SELECT … FROM pg_locks WHERE locktype='advisory'`
  shows the controller key from a single pod).
- **Internal endpoint** — `POST /internal/fleet/cell/{load,evict}/{uuid}` is
  token-gated (401 without / with a wrong token) and runs `load-cell!` (409 for a
  cell not in the pod's shard) with a valid token.
- **Cross-pod transport** — pod-0 reaches pod-1's endpoint at its FQDN, the move
  controller's directed load/evict path (a real cell → `{"loaded":1}`).
- **Autonomous rebalance** — seeding two cells onto one pod, the leader
  controller detects the imbalance (`Fleet controller applied placement
  {:moves 1 :imbalance 2.0}`) and, after the sustain window, moves one cell to
  the idle pod via the directed transport — the `:placement` epoch flips and
  load evens 1+1. The whole loop (discover → weigh → rebalance → move) on live
  pods.
- **Forward-hop with tenancy** — two sharded tenancy pods (fleet-a shard
  `public`, fleet-b `public,orgb`, both with
  `GRAPHDEN_ADDON_CONFIGS=graphden/tenancy/addon.edn,graphden/tenancy/faas.edn`).
  A request `Host: orgb.graphden.app` to fleet-a (which doesn't hold orgb)
  forward-hops to fleet-b per `:placement` and returns fleet-b's byte-identical
  app response (vs the apex editor fleet-a serves itself, and vs `421` with no
  placement). This surfaced + fixed the Host-stripping bug (69203eff): the hop
  must preserve the tenant subdomain, the holder's routing key.

See [SCALING.md](SCALING.md) for the static-shard / BYO story this builds on, and
[FLEET_RFC.md](FLEET_RFC.md) §6 for the routing + controller internals.
