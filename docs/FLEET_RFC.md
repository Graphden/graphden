# RFC: Dynamic fleet — load-based placement & rebalancing

**Status: PROPOSAL / RFC. Nothing here is shipped.** The as-built fleet
(static org sharding, `421` backstop, per-org quota, advisory-lock singletons,
SSE invalidation, BYO) is in [SCALING.md](SCALING.md). This document designs the
*next* step: automatic placement and rebalancing of tenant graphs across a fleet
of executors by load and size, and the runtime substrate to run it on.

Read SCALING.md first — this RFC extends it and reuses most of its machinery.

## 1. Problem & goals

Today assignment is **static and operator-configured**: each pod is told its
org set via `GRAPHDEN_EXECUTOR_ORGS`, loads that shard at startup, and `421`s
anything else. There is no brain that reacts to load, and the shard predicate is
fixed at context-build time.

Goals:

- **Automatic initial placement** of tenant graphs onto executors.
- **Rebalancing** when load or graph size shifts, minimising disruption.
- **Request routing** to the executor that currently holds the target — making
  `421` a rare backstop, not the mechanism.
- **Memory efficiency**: amortise the shared base-package footprint (measured
  **~640 MB per pod, zero tenants** — 2026-07) across many tenant pieces.

Non-goals (explicit, to bound scope):

- **Distributed execution of a single call.** One request's fn-closure executes
  entirely on one pod. Splitting the *middle* of a call across pods (RPC inside
  the graph) is a different, much harder system and is out of scope. This is the
  load-bearing constraint of §3.
- Cross-region placement, follow-the-sun, or data-locality-aware placement.
- Replacing the token as the org authority (routing never widens access).

## 2. The core tension

Static org sharding is deliberately **brainless**: the org is in the request's
hostname, so routing needs no coordinator and no per-call lookup (SCALING.md
§ "Sharding by org"). Load-based rebalancing breaks that assumption and forces
three new pieces into existence:

1. **A live routing map** `unit → executor`. Today: none (static env var). If
   assignments change at runtime, both the router and the pods must agree on the
   *current* owner.
2. **Dynamic membership.** An executor must **load and evict** a unit at runtime.
   Today it only loads its shard at startup and the predicate is immutable.
   Loading is nearly free — the existing **delta-invalidation** compiles fns into
   the registry incrementally; only **eviction** is new.
3. **The placement controller (the brain).** Computes each unit's weight from
   `fn-count` (from the graph) and `load` (from live metrics), packs units onto
   executors, and moves units when the fleet drifts out of balance — with
   hysteresis so it doesn't thrash, and churn-minimisation so a rebalance moves
   the fewest units.

None of the three exist yet; all three are prerequisites for rebalancing,
independent of the runtime substrate.

## 3. Unit of placement: the **cell**, not the org

The as-built shard key is the whole org. This RFC generalises it: the unit of
placement is a **cell** = *a root fn plus its transitive ref-closure*. An org has
**one or more** cells; a pod holds a **mix** of cells from **many** orgs (a
sleeping admin endpoint of org A packed next to a hot checkout endpoint of org
B). "Whole org = one cell" is just the trivial case we start from.

- **Org = addressing / tenancy boundary.** The request still names its org in the
  Host; RLS and effect-gating are still per-org.
- **Cell = scheduling / placement boundary.** What actually gets assigned, loaded,
  evicted, weighed, and moved.

**Indivisibility (the §1 non-goal made concrete).** A cell is atomic: its whole
closure must live on the pod that runs it, because a single call can't span pods.
So a cell can only be split off at an **independent entry point** — a route,
endpoint, or service whose closure doesn't call into another cell's private fns.
Cells may *overlap* (share a util fn); the shared fn is simply compiled on both
pods (cheap, and base packages are already duplicated everywhere). A cell's
closure never leaves `org ∪ public` — already guaranteed by
`reject-cross-org-refs!`.

Consequence for routing: addressing becomes `(org, entry)`, not just `org`. That
needs either L7 (path-aware) routing at the edge, or an internal forward hop
between sibling pods (§6). This is the price of fine granularity and is called
out as a first-class trade-off, not hidden.

## 4. Two orthogonal axes (correcting a false coupling)

Pod **footprint** and placement **model** are independent — do both:

| Axis | Options | Note |
|------|---------|------|
| **Footprint** | fat JVM (~640 MB) ↔ lean native-image (GraalVM, ~100 MB, sub-second start) | Worth doing under *any* model. Shrinks the base tax and the cold-start that makes eviction/rebalance cheap. |
| **Placement model** | own control plane (this RFC) ↔ delegate to Knative-per-service | Independent of footprint. |

Even a 100 MB base doesn't remove the packing problem: at 100–200 orgs it is the
same order of magnitude, and we explicitly want to pack *pieces* of orgs
together for load levelling. So the base slim-down is a **cost/latency
optimisation**, not a substitute for the control plane.

## 5. Chosen direction

**Own control plane (grouped cells) + pod slim-down as a parallel track.**

- The brain is ours because our packing is finer-grained and load-aware than
  what a per-service autoscaler expresses (Knative packs *replicas of one
  service*, not *mixed cells of many tenants*).
- GraalVM native-image is pursued in parallel to cut the base tax and make
  load/evict/rebalance cheap — it does **not** conflict with owning the control
  plane.
- Knative / k8s HPA still have a role **below** the brain: autoscaling the
  replica count of a shard the controller has already composed (§7).

## 6. Architecture

```
                     ┌────────────────────────────────────────────┐
   request           │  Edge router (L7)                           │
  (org, path) ─────► │  reads the live routing map (org,entry)→pod │
                     └───────────────┬────────────────────────────┘
                        route to owner│         (miss → 421 backstop,
                                       ▼          or internal forward)
              ┌─────────────┐  ┌─────────────┐  ┌─────────────┐
              │  executor 1 │  │  executor 2 │  │  executor 3 │
              │ cells: a1,  │  │ cells: b1,  │  │ cells: a2,  │
              │  c3, (base) │  │  a3, (base) │  │  b2, (base) │
              └─────────────┘  └─────────────┘  └─────────────┘
                     ▲ load/evict cell (delta-compile / evict)
                     │ heartbeat: held cells + live weight
              ┌──────┴───────────────────────────────────────────┐
              │  Placement controller (the brain)                 │
              │  weight(cell)=f(fn-count, load); pack; rebalance  │
              │  writes the routing map; drives load/evict        │
              └───────────────────────────────────────────────────┘
```

### 6.1 Live routing map

- Source of truth: a table (`:placement` / `:cell-assignment`) in the shared
  Postgres — `(org, entry-fn-id) → executor-id`, versioned by an epoch so a
  move is atomic to readers.
- The edge router reads it (cached, invalidated over the existing
  `graphden_events` / SSE channel). Two shapes:
  - **L7 edge** (Envoy/nginx/Knative-Istio) routing `(Host, path) → upstream`.
  - **Internal forward**: keep the edge org-coarse; a pod that receives a cell it
    doesn't hold forwards to the holder (one hop, service-mesh style) instead of
    `421`. Simpler edge, extra hop. Likely start here, add L7 later.

### 6.2 Dynamic membership (per executor)

- The `:executor-orgs` predicate becomes a **live cell set** (an atom fed by the
  controller), not a startup constant.
- **Load a cell**: compute the root's ref-closure, delta-compile it into the
  registry. Reuses the shipped delta path (SCALING.md § invalidation); the
  closure BFS already exists.
- **Evict a cell**: drop its fns from the registry *iff* no other held cell needs
  them (reference-count the shared fns). This is the one genuinely new executor
  capability.
- The `org-in-shard?` gate generalises to `cell-held?`; the compile filter
  loads closures of held roots + public instead of all-rows-of-org-X.

### 6.3 Placement controller

- **Metrics.** `fn-count` per cell from the graph (static-ish). `load` from: the
  per-org pending-execution counter we already keep, request rate, and pod CPU
  (Prometheus). `weight = w1·fn-count + w2·load` (memory proxy + activity).
- **Placement.** Greedy/bin-pack cells onto pods under a per-pod weight budget;
  spread an org's cells across pods for load levelling, not for isolation.
- **Rebalance.** Trigger on sustained imbalance (hysteresis, not instantaneous).
  Pick the move set that most reduces variance while moving the fewest cells.
  A move = target loads the cell → routing epoch bumped → source evicts. Graceful:
  target ready **before** the map flips; in-flight requests drain on the source.
- **Safety.** Advisory-lock the controller itself (singleton, reusing the
  reconciler's lock pattern) so two controllers can't fight.

## 7. Runtime substrate

- **k8s: yes.** Today we run a single `docker-compose` container — fine for one
  node, not a fleet. k8s gives us scheduling, rollouts, health, HPA.
- **Knative / HPA: below the brain, not instead of it.** Once the controller has
  composed a shard (a set of cells), k8s HPA / Knative can autoscale that shard's
  **replica count** on CPU/concurrency, and Knative's activator can buffer during
  cold start. What they can't do is decide *which cells go where* — that's the
  fine-grained, cross-tenant, load-aware packing this RFC owns.
- **Knative scale-to-zero** is attractive for a large *idle* tail; it fits a
  future "cold cells park at zero, the controller wakes them on first request"
  mode. Deferred until the base footprint is small enough to make cold start
  acceptable (the GraalVM track).
- **Managed serverless (Cloud Run/Fargate)** — same per-service model as Knative,
  less cluster ops, more lock-in. Viable substrate for the *slim* path; doesn't
  change the control-plane design.

## 8. Phased plan (incremental — each phase ships value alone)

- **Phase 0 — foundation.** Live routing map + dynamic membership (load/evict a
  cell without restart) + the internal-forward router. Assignment still manual.
  Value: `421` becomes rare; assignments change with no restart. Prerequisite for
  everything.
- **Phase 1 — metrics + assisted moves.** Weight collection; a controller that
  *executes* a move on command (target loads → map flips → source evicts).
  Rebalance decisions still human/heuristic.
- **Phase 2 — automatic placement & rebalance.** The packing + hysteresis +
  churn-minimising mover, running as a locked singleton.
- **Phase 3 — substrate integration.** k8s operator; HPA/Knative under shards;
  optionally scale-to-zero for the idle tail (gated on the slim-down track).
- **Parallel track — footprint.** GraalVM native-image (or aggressive
  base-package trimming / class-data sharing) to cut the ~640 MB base and the
  ~35 s cold start, making load/evict/rebalance cheap.

## 9. What we reuse from today

- **Delta-invalidation** → cell load (incremental compile).
- **`421` misdirected** → the backstop when the map is stale.
- **Per-org pending-execution counter** → a ready-made load signal.
- **`reject-cross-org-refs!`** → guarantees a cell's closure ⊆ `org ∪ public`,
  which is what makes a cell self-contained and movable.
- **Advisory-lock singleton** (reconciler) → the controller's own leader lock.
- **SSE / `graphden_events`** → routing-map + registry invalidation transport.
- **`:executor-orgs` predicate** → generalises to the live cell set.

## 10. Open questions / risks

- **Edge vs internal-forward routing** — start with internal forward (simpler
  edge), measure the hop cost, add L7 if it hurts.
- **Cell discovery** — how do we enumerate an org's independent entry points
  (routes, services) to cut cells? Probably: services are already roots; app
  routes need a declared entry set. Needs design.
- **Overlap accounting** — shared fns compiled on multiple pods inflate memory;
  reference-count on evict, and let the packer prefer co-locating cells that
  share large closures.
- **Move cost vs benefit** — a move pays a compile + cache-warm on the target;
  the hysteresis thresholds must exceed that cost. Needs the Phase-1 measurements.
- **GraalVM feasibility** — Clojure + heavy reflection/`eval` paths may resist
  native-image; needs a spike before committing the slim path.

## 11. Decision log

- 2026-07: direction = **own control plane, cell-granular placement**, with pod
  slim-down (GraalVM) as an independent parallel track. Distributed execution of
  a single call is a **non-goal**. Substrate = k8s; Knative/HPA sit *below* the
  controller, not in place of it. First deliverable = this RFC.
