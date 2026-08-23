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

## Dedicated tenant shard (task #6 / FLEET_RFC §7.1)

A paying tenant on the `dedicated` plan runs **persistent services** (cron,
`:schedule`, always-on listeners). The effect gate sandboxes *what* a service
does, but not its CPU / heap / threads, and the JVM has no per-thread heap cap —
so a persistent tenant service is only safe on a runtime the tenant does not
share. The shared fleet deliberately co-locates orgs (the packer spreads cells
for load, not isolation), so the resource boundary is a **dedicated shard**: a
small pod set that serves exactly `public + that-org`, under tight cgroup limits.
No new mechanism — it composes `:executor-orgs` (SCALING.md § Sharding) with the
chart's `resources.limits`. The reconciler enforces the other half: a tenant
service (`:org-id` set) starts **only** on a pod whose `:executor-orgs` shard
names its org, never on a shared compile-all pod (`services/reconciler.clj`
`service-in-shard?`), so the dedicated pod is the *only* place the tenant's
services run.

To provision org `acme`:

1. **Flip the plan.** Set `acme`'s `:org` row `:plan` to `dedicated` (via the
   operator provisioning path — `tenancy.plan/plans` reads it). Until then,
   `/api/orgs/services/create` 403s the tenant (`:service/tier-required`).
2. **Deploy its dedicated release** — its own StatefulSet, sharded to `acme` and
   cgroup-bounded (set BOTH `cpu` and `memory` limits — the memory cap stops an
   OOM taking the node, the CPU cap stops a busy-loop starving it):

   ```bash
   helm install graphden-acme deploy/helm/graphden \
     --set executorOrgs="public,acme" \
     --set replicaCount=1 \
     --set resources.requests.cpu=250m --set resources.requests.memory=512Mi \
     --set resources.limits.cpu=1 --set resources.limits.memory=1Gi \
     --set database.jdbcUrl=jdbc:postgresql://…/graphden \
     --set secrets.authToken=… --set secrets.internalToken=… --set secrets.dbPassword=…
   ```

   It shares the same Postgres as the fleet (one graph, one `:service` table);
   `executorOrgs` scopes what THIS release compiles + runs. `replicaCount>1` runs
   a `:singleton` service once (advisory-lock arbitrated) and a `:per-pod`
   listener on each — same semantics as the shared fleet.
3. **Scope the CONTROLLERS** (mixed-fleet rule, 2026-08-23). Releases share
   one Postgres, so without scoping the two placement controllers used to
   contend for ONE advisory lock — and whichever won saw only its own SRV
   membership and tried to place every org onto its own pods (the load
   409'd on the off-shard pod and the cell wedged `:unplaced` forever).
   Fixed on two axes, both defaulted sanely:
   - **Locks are per-release now** (`fleet-controller-lock-id` derives from
     `GRAPHDEN_FLEET_DNS`, which each release's chart sets to its own
     headless Service) — each release elects its own leader.
   - **Cells are scoped per-release** (`control-loop/scope-cells`): a
     dedicated release manages exactly its `executorOrgs`; the SHARED
     release must be told which orgs dedicated releases own — set
     `fleet.excludeOrgs="acme"` on it (env
     `GRAPHDEN_FLEET_EXCLUDE_ORGS`). Alternatively run the dedicated
     release with NO controller at all: `fleet.controllerEnabled=false`
     (env `GRAPHDEN_FLEET_CONTROLLER=off`) — right for a 1-pod shard,
     where there is nothing to balance.
4. **Route `acme`'s traffic** (`acme.graphden.app` and its `/api/*`) to this
   release's Service. A misroute to the shared fleet answers `421` — the backstop,
   not the routing.

Result: `acme`'s services — created from the editor or `POST
/api/orgs/services/create` — run **only** on this pod set, sandboxed by the
effect gate AND bounded by the cgroup. The shared fleet never starts them.

Controller observability: the tick counters (`:fleet/ticks`,
`:fleet/initial-placements`, `:fleet/rebalance-moves`,
`:fleet/tick-failures`) ride the standard counters pipeline
(docs/MONITORING.md), alongside `GET /internal/fleet/status` and the
`"Fleet controller applied placement"` log line.

**Still open (topology-dependent, not shipped):** the tenant's editor lists its
services' DESIRED state only; cross-pod runtime status (running / failed) and a
tenant-mode browser e2e need this dedicated stack live and are deferred to it.

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
