# RFC: Dynamic fleet — load-based placement & rebalancing

**Status: Phases 0-3 CORE SHIPPED + verified end-to-end on a kind cluster (§8,
§12; ops guide in [FLEET_DEPLOY.md](FLEET_DEPLOY.md)).** Still open: T4.5 overlap-
accounting + per-route split (evidence-gated), T5.3 scale-to-zero (Knative/CRaC-
gated), and the CRaC checkpoint CI bake. The as-built STATIC fleet (org sharding,
`421` backstop, per-org quota, advisory-lock singletons, SSE invalidation, BYO)
is the base layer in [SCALING.md](SCALING.md); this document designed — and the
branch then built — the *dynamic* step on top: automatic placement and
rebalancing of tenant graphs across a fleet of executors by load and size, and
the runtime substrate to run it on.

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
  **~655 MB RSS per pod, zero tenants** — 2026-07) across many tenant pieces.

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
   Today it only loads its shard at startup. *(See §6.2 — most of this machinery
   already exists.)*
3. **The placement controller (the brain).** Computes each unit's weight from
   `fn-count` (from the graph) and `load` (from live metrics), packs units onto
   executors, and moves units when the fleet drifts out of balance — with
   hysteresis so it doesn't thrash, and churn-minimisation so a rebalance moves
   the fewest units.

None of the three exist yet; all three are prerequisites for rebalancing.

## 3. Unit of placement: the **cell**, not the org

The as-built shard key is the whole org. This RFC generalises it: the unit of
placement is a **cell** = *a root fn plus its transitive forward ref-closure*. An
org has **one or more** cells; a pod holds a **mix** of cells from **many** orgs
(a sleeping admin endpoint of org A packed next to a hot checkout endpoint of org
B). "Whole org = one cell" is the trivial case we start from.

- **Org = addressing / tenancy boundary.** The request still names its org in the
  Host; RLS and effect-gating stay per-org.
- **Cell = scheduling / placement boundary.** What gets assigned, loaded,
  evicted, weighed, and moved.

**Indivisibility (the §1 non-goal made concrete).** A cell is atomic: its whole
closure must live on the pod that runs it, because a single call can't span pods.
A cell can only be split off at an **independent entry point** — a route,
endpoint, or service whose closure doesn't call into another cell's private fns.
A cell's closure never leaves `org ∪ public` — already guaranteed by
`reject-cross-org-refs!`.

### 3.1 What is already a cell (verified against the code)

- **Services ARE cells, today.** A `:service` row names a `:fn-id` that MUST have
  zero free arguments (enforced at create-time — `schema/services/schema.clj`),
  and the reconciler runs it standalone via `cr/execute ctx fn-id {}`. A
  zero-free-arg root the executor compiles independently *is* a cell. `:web-server`
  ships as the baseline service. **So every service row already names a cell root
  — these are the first placeable units, no new work.**
- **An org's web app is ONE cell today.** `app_router` reads a single
  `:org.handler-fn-id` and executes it with `{:request request}`. Routes exist as
  *data* (each route is a fn-def parented from `:get-route`, gathered into one
  list fed to one compiled reitit router), but they are **not independently
  addressable** — every request enters the one top handler.
- **Splitting an app into route-cells is pure fn-def rewiring** (no new base-fn):
  split the route list into N sub-lists, give each its own router-root fn-def
  (`:router-or-nil` already exists for fall-through composition), and point a
  per-cell `handler-fn-id` at each. Deferred — see §3.2.

### 3.2 Overlap reality → default granularity

Independent route-cells **heavily overlap**: within an org they all converge on
the same shared library (`web/reitit`, `web/html`, `web/response`, `web/crud`,
`:pg-query`, the whole `_app-ring-response` wrap chain). Splitting one app across
pods duplicates that shared closure on each pod. So per-route splitting pays off
**only** under genuine hot/cold asymmetry with a large per-cell *unique* closure
relative to the shared base.

Therefore the default granularity for Phase 0/1:

- **cell = the whole org app** (one cell), plus
- **cell = each service** (one cell each, already standalone).

Per-route splitting is a Phase 2+ refinement, gated on evidence that route-level
asymmetry justifies the overlap cost. The cell abstraction is designed to *allow*
it; we don't build it early. (This matches the "combine sleeping + hot pieces"
intent — but the pieces should be genuinely independent, like separate services
or apps, not routes of one small app.)

### 3.3 Routing consequence

Addressing becomes `(org, entry)`, not just `org` — needs either L7 (path-aware)
edge routing, or an internal forward hop between sibling pods (§6.1). This is the
price of fine granularity, called out as a first-class trade-off.

## 4. Two orthogonal axes (correcting a false coupling)

Pod **footprint** and placement **model** are independent — do both:

| Axis | Options | Note |
|------|---------|------|
| **Footprint / start** | fat JVM (~655 MB, slow start) ↔ faster-start options (§5.1) | Worth doing under *any* model; shrinks the base tax and the cold-start that makes evict/rebalance/scale-to-zero cheap. |
| **Placement model** | own control plane (this RFC) ↔ delegate to Knative-per-service | Independent of footprint. |

Even a much smaller base doesn't remove the packing problem: at 100–200 orgs it
is the same order of magnitude, and we deliberately pack *pieces* of orgs together
for load levelling. The footprint work is a **cost/latency** optimisation, not a
substitute for the control plane.

## 5. Chosen direction

**Own control plane (grouped cells) + a footprint/start track in parallel.**

- The brain is ours: our packing is finer-grained and load-aware than a
  per-service autoscaler expresses (Knative packs *replicas of one service*, not
  *mixed cells of many tenants*).
- Knative / k8s HPA still sit **below** the brain: autoscaling the replica count
  of a shard the controller has already composed (§7).

### 5.1 Footprint / start track — GraalVM is OUT, CRaC is the candidate

**GraalVM native-image is NOT feasible without a package-layer rearchitecture**
(verified). The blocker is fundamental, not incidental: base-fn implementations
are shipped as Clojure **source** (`resources/packages/**/impls.clj`, 32 files)
and `eval`'d at boot **with macroexpansion** (`packages/loader.clj`
`load-impls-via-eval`) — runtime Clojure compilation, which native-image forbids,
on the production startup path. Going native would mean converting all 32 impls to
AOT namespaces, killing eval-load, force-AOTing ~20 `requiring-resolve` targets,
dropping the runtime-`require` addon manifest, and authoring reflection config for
5 native-unfriendly deps (postgres, next.jdbc, HikariCP, http-kit, hiccup). That
is a rewrite of the package layer — not a spike. **Shelved.**

Candidates that are **compatible with the eval-load model**, in payoff order:

- **CRaC (Coordinated Restore at Checkpoint)** — snapshot the warm JVM *after*
  package-load, restore in a fraction of the boot time. Sidesteps the eval
  blocker entirely (the eval already ran before the checkpoint) and directly
  kills the cold-start (~35 s documented; **~113 s measured here** on a container
  restart, likely inflated by dependency reconnect — needs a clean re-measure)
  that makes scale-to-zero and frequent rebalance painful. Requires a CRaC-enabled
  JDK (Azul Zulu / OpenJDK CRaC) + `Resource` handlers to close/reopen the DB pool
  and sockets across the checkpoint, and privileged CRIU at restore.
  **Highest-payoff realistic path; architecturally a fit.**
- **AppCDS / dynamic CDS** — share class metadata across pods; modest RSS + start
  win, trivial to enable.
- **JVM tuning for small pods** — heap cap, GC choice, tiered-stop → RSS control.

**Environment feasibility CONFIRMED in this sandbox (2026-07).** Both pieces are
obtainable and functional here:

- **CRIU works**: `apt-get install criu` (v3.16.1), `criu check` → "Looks good".
  Kernel **5.15** has `CAP_CHECKPOINT_RESTORE`, and the process already holds
  `CAP_SYS_ADMIN` / `CAP_SYS_PTRACE` / `CAP_CHECKPOINT_RESTORE`. In prod a pod
  needs those caps (`--privileged`, or `--cap-add=CHECKPOINT_RESTORE,SYS_PTRACE,SYS_ADMIN
  --security-opt seccomp=unconfined`).
- **CRaC JDK works**: Azul Zulu `zulu21.50.19-ca-crac-jdk21.0.11` runs and exposes
  `CRaCCheckpointTo` / `CRaCRestoreFrom`. A checkpoint of a **dirty ~300 MB heap
  succeeded** → a ~330 MB image; restore is functional (criu "Restore successful!").

So the gate is **no longer "is the environment capable"** — it is the CRaC
integration itself (§8 track).

**Integration PoC run** (`development/crac/`, 2026-07): a warm graphden JVM that
has done the expensive `load-packages` (eval of 32 `impls.clj` → 251 base-fns,
**4484 ms**) checkpointed to a **201 MB** image and **restored in ~41 ms** —
>100× vs the load, >800× vs a cold boot. That is the value, demonstrated. The PoC
also surfaced the two blockers a production path must solve:

1. **Native-library temp-mmap — FIXED.** `brotli4j` (`deps.edn`, the
   `:brotli-bytes` base-fn) extracted `libbrotli.so` to a random
   `${tmpdir}/com_aayushatharva_brotli4j_<nanoTime>/` dir marked `deleteOnExit`
   and mmapped it; CRIU restore then failed once that file was gone
   (`Cannot open mapped file …/libbrotli.so`) — so only the very first restore
   worked. Resolved in `web/http/impls.clj`: extract the `.so` once to a
   deterministic path (`-Dgraphden.native-lib.dir`, default
   `${tmpdir}/graphden-native`) and point brotli4j at it via its supported
   `brotli4j.library.path` property → `System.load` of a stable, image-resident
   file, no temp dir, no `deleteOnExit`. Restore is now **reproducible**
   (3/3 restores OK, ~30–38 ms). General rule for any future extract-and-mmap
   native lib: pin it to a stable path the checkpoint image ships.
2. **Live external connections.** A full-system checkpoint needs CRaC `Resource`
   handlers to close-before / reopen-and-**rewire**-after every live resource
   (Hikari→Postgres pool, http-kit listener, vault/openbao client, notify +
   advisory-lock connections, SSE). CRIU won't snapshot live sockets without app
   cooperation; the executor context holds a reference to the pool-wrapping
   storage, so reopening means re-threading it.

So CRaC is feasible and high-value, but the integration is a **real feature**, not
a config flip — see `development/crac/README.md`.

Corollary: since the ~655 MB base can't shrink cheaply, that **reinforces**
grouping (Path A) — the base is paid once per pod and shared by all cells the pod
holds. The base duplication *across* pods (cells overlap heavily on `web/*`,
§3.2) is exactly why packing many cells per pod wins.

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

- Source of truth: a `:placement` entity in the shared Postgres —
  `(org, entry-fn-id) → executor-id`, epoch-versioned so a move is atomic to
  readers.
- The edge router reads it (cached, invalidated over `graphden_events` / SSE).
  Two shapes:
  - **L7 edge** (Envoy/nginx/Knative-Istio) routing `(Host, path) → upstream`.
  - **Internal forward**: keep the edge org-coarse; a pod that receives a cell it
    doesn't hold forwards to the holder (one hop) instead of `421`. Simpler edge,
    extra hop. **Start here**, add L7 later.

### 6.2 Dynamic membership — most of the machinery already exists

Verified against the executor:

- **The compiled registry is already a live, per-context atom** `:compiled-registry`
  = `{fn-id → closure}` (`executor/context.clj`). `delta-recompile!`
  (`compile_runtime.clj`) already does a **surgical `swap!`**: it dissocs pruned
  fn-ids and re-inserts only a recompiled subset via `ce/compile-subset` on top of
  the existing map. So per-cell add/evict is a `swap!` on an atom that already
  supports it — **not** a wholesale rebuild.
- **Loading a cell = `compile-subset` over the FORWARD closure of its root.** The
  `:forward-deps` index is already built (`compile/deps.clj build-deps-state`);
  `compile-subset` already compiles an arbitrary dependency-ordered subset. The
  one missing named piece is a **forward-closure walk** (`transitive-blast`
  already does this walk, but over `:reverse-deps` for invalidation; we need the
  same walk over `:forward-deps`).
- **Evicting a cell** needs the one genuinely new abstraction: a **loaded-roots**
  set + reference counting. Evict `forward-closure(root) \ ⋃ forward-closure(other
  loaded roots)` — the `:forward-deps` index gives the per-fn sets to compute the
  difference.
- **Concurrency is already handled**: the `:invalidation-lock` ReentrantLock +
  CAS `swap!` mean per-cell add/evict under the lock is safe with no new
  synchronization.
- **The shard predicate is trivially made live**: `:executor-orgs` already accepts
  a *fn* (not just a set); pass a fn closing over a mutable atom. The real work is
  not the predicate read — it's keeping the compiled registry coherent as the set
  changes, which *is* the load/evict problem above.

### 6.3 Placement controller

- **Metrics.** `fn-count` per cell from the graph (static-ish). `load` from the
  per-org pending-execution counter we already keep, request rate, and pod CPU.
  `weight = w1·fn-count + w2·load`.
- **Placement.** Greedy/bin-pack cells onto pods under a per-pod weight budget;
  spread an org's cells for load levelling, not isolation.
- **Rebalance.** Trigger on *sustained* imbalance (hysteresis). Pick the move set
  that most reduces variance while moving the fewest cells. A move = target loads
  → routing epoch bumped → source evicts; target ready **before** the map flips,
  in-flight drains on the source.
- **Safety.** Advisory-lock the controller itself (reuse the reconciler's leader
  lock) so two controllers can't fight.

## 7. Runtime substrate

- **k8s: yes.** Today we run a single `docker-compose` container — fine for one
  node, not a fleet.
- **Knative / HPA: below the brain.** Once the controller composes a shard, HPA /
  Knative can autoscale that shard's **replica count** and Knative's activator can
  buffer during cold start. They cannot decide *which cells go where* — that's the
  brain's job.
- **Scale-to-zero** fits a future "cold cells park at zero, controller wakes on
  first request" mode; gated on the start-time track (§5.1) making cold start
  acceptable.
- **Managed serverless (Cloud Run/Fargate)** — same per-service model, less
  cluster ops, more lock-in; doesn't change the control-plane design.

## 8. Phased plan (incremental — each phase ships value alone)

- **Phase 0 — foundation.** Live routing map + dynamic membership + internal-forward
  router. Assignment still manual. Value: `421` becomes rare; assignments change
  with no restart. Concrete tasks:
  - **T2.1** `forward-closure(root)` — transitive walk over `:forward-deps`
    (`compile/deps.clj`). Defines a cell's contents. Small; building blocks exist.
  - **T2.2** `load-cell!(ctx, root)` — forward-closure → `compile-subset` on top of
    the existing registry → `swap!` under `:invalidation-lock`. Reuses the
    `delta-recompile!` plumbing.
  - **T2.3** `loaded-roots` bookkeeping + refcount + `evict-cell!` — the one new
    abstraction (§6.2).
  - **T2.4** Live shard predicate — pass a fn over a mutable cell-set; generalise
    `org-in-shard?` → `cell-held?`. (Decision: keep `read-graph` org-coarse at
    first, or move it to "load closures of held roots + public" — smaller step
    first.)
  - **T2.5** `:placement` routing table `(org, entry) → executor`, epoch-versioned;
    populated manually.
  - **T2.6** Internal forward-hop router (miss → forward to holder, not `421`).
  - **First sprint = T2.1 → T2.2 → T2.3**: pure in-JVM executor work, fully
    testable without k8s, delivers load/evict of individual closures at runtime.
- **Phase 1 — metrics + assisted moves (core SHIPPED).** Weight collection; a
  controller that *executes* a move on command. Rebalance decisions still
  human/heuristic. Concrete tasks:
  - **T3.1 ✅** `fleet.metrics` — `cell-fn-count` (forward-closure size),
    `org-pending-load` (the quota's own `:pending` `:fn-execution` count),
    `cell-weight` folding the two with overridable weights.
  - **T3.2 ✅** `fleet.controller/move-cell!` — the load→flip-epoch→evict
    orchestration with its invariants (load-before-flip, abort-before-flip,
    evict-after-flip), cross-pod effects behind `load-on` / `evict-on` seams,
    serialized under a `move-monitor`. Fully in-JVM tested.
  - **T3.3 ✅** `fleet.command` — the directed, ACK-gated cross-pod transport
    that fills the seams: `POST /internal/fleet/cell/{load|evict}/{root}` on
    each pod (runs `load-cell!` / `evict-cell!`), gated by a shared internal
    token (`GRAPHDEN_INTERNAL_TOKEN`), NOT the tenant auth-provider (a move is
    cross-org platform authority). `execute-move!` is the ops/REPL entry.
  - **T3.4 (Phase 2)** an autonomous controller loop, elected by an advisory
    leader-lock (reusing the reconciler's), that *decides* moves. Phase 1's
    `move-monitor` orders a single controller's moves; the leader-lock makes
    ONE controller authoritative fleet-wide.
- **Phase 2 — automatic placement & rebalance (core SHIPPED).** Packing +
  hysteresis + churn-minimising mover as a locked singleton. Concrete tasks:
  - **T4.1 ✅** `fleet.packer` — LPT bin-pack (heaviest cell → least-loaded pod),
    the desired-placement baseline; budget-free (capacity is a separate read).
  - **T4.2 ✅** `fleet.rebalance` — churn-minimising steepest-descent (move the
    fewest cells that flatten load) + magnitude hysteresis (`:min-improvement`);
    surfaces `:unplaced` cells separately for initial placement.
  - **T4.3 ✅** `fleet.control-loop` — the pure `plan-tick` (initial placement
    every tick, rebalance only on SUSTAINED imbalance via `:sustain-ticks`) plus
    live-fleet discovery (`:org` app cells ∪ `:placement`, `:service` excluded)
    and `run-tick!` executing through a `move-fn` seam.
  - **T4.4 ✅** `:exec/fleet-controller` — the leader-locked periodic component
    (own advisory-lock conn, re-asserted per tick) that drives `run-tick!` via
    the directed cell-command transport. One controller fleet-wide.
  - **T4.5 (deferred, on evidence)** overlap-accounting on evict (co-locate cells
    sharing large closures) + per-route cell-splitting (§3.2) — both wait for
    evidence that a single-cell-per-org grain is insufficient.
- **Phase 3 — substrate (core SHIPPED).** Concrete tasks:
  - **T5.1 ✅** DNS-SRV membership discovery (`fleet.discovery`) — headless-
    Service SRV → live executor set, tracking StatefulSet/HPA scaling. Pure DNS,
    no k8s API / RBAC.
  - **T5.2 ✅** Helm chart (`deploy/helm/graphden`) — StatefulSet + headless +
    front-door Service + Secret/ConfigMap + opt-in HPA. Each pod's
    `GRAPHDEN_EXECUTOR_ID` is its FQDN; the controller runs in-app (no separate
    operator/CRD). See [FLEET_DEPLOY.md](FLEET_DEPLOY.md).
  - **T5.3 (Knative half DEMONSTRATED; CRaC-gated for production)** scale-to-zero
    for the idle tail. Verified on kind (2026-07-12, deploy/kind/knative/): the
    graphden app as a Knative Service scales to **0 pods** when idle and a request
    cold-starts one (the activator buffers it) — but the cold-start was **115 s**
    (the compute-bound boot). So the substrate works; making it PRACTICAL still
    needs the footprint/start track (§5.1) — CRaC restore (~178 ms) fronting the
    ksvc turns that 115 s into sub-200 ms (~650×). Wiring the CRaC restore image
    into the ksvc is the remaining step.
- **Parallel track — footprint/start (§5.1) — MEASURED + DECISION: ADOPT.**
  The full-system checkpoint now works end-to-end (three `graphden.crac`
  quiesce/resume blockers fixed — see `development/crac/README.md`). Measured on
  the real image: checkpoint 219 MB, **restore→serving ~178 ms vs ~141 s cold
  boot (~780×)**. Decision: **adopt CRaC** as the footprint track — it directly
  unblocks scale-to-zero (T5.3). AppCDS is DE-PRIORITISED: the boot is
  compute-bound (type-check + eager-compile), which AppCDS doesn't touch.
  Remaining is operational: bake the checkpoint in CI via `Dockerfile.crac`.
  NOT GraalVM.

## 9. Relationship to graph hot-reload (already shipped)

A likely question: "when the DB graph changes (package install, branch merge, user
edit), every executor using that graph must refresh, and requests must only reach
up-to-date executors — isn't that this task?" **Mostly it is already built, and it
is the foundation this RFC reuses — not new work.**

- **Keeping a held graph fresh is SHIPPED.** A graph write emits a
  `fn:invalidate:<fn-id>|<branch>|<org>` on `graphden_events`; every local pod's
  LISTEN applies the **delta** (recompiles just the affected fns via
  `delta-recompile!`), and remote/BYO executors get the same over the **SSE relay**
  (SCALING.md § SSE). Package install/update/fork/materialize and branch-merge were
  all made delta-invalidating. So "the graph changed → holders refresh" already
  works, fleet-wide.
- **This is the SAME machinery cell-load reuses.** `load-cell!` (T2.2) is the same
  `compile-subset`/`swap!` delta path pointed at a *forward* closure instead of a
  *reverse* (invalidation) one. Hot-reload and cell-load are two directions of one
  mechanism.
- **What this task genuinely ADDS is placement, not freshness**: deciding *which*
  executor holds *which* cell and *moving* it. The routing map (§6.1) is also what
  lets requests reach a *holder*.
- **One freshness gap is adjacent but separate: strict "only route to up-to-date"**
  is NOT built. Today invalidation is **eventually consistent** (~1 s after the
  NOTIFY). There is no fence holding a request until the target pod's registry has
  applied the write. If we ever need read-your-writes across the fleet, add a
  **version epoch**: stamp writes with an epoch, carry the required epoch on the
  request, and let a pod behind that epoch forward/park until it catches up. This
  is optional, layers on the §6.1 routing map, and is out of scope for Phase 0.

## 10. What we reuse from today

- **Delta-invalidation** (`delta-recompile!`, `compile-subset`, `:forward-deps`)
  → cell load, and the whole hot-reload story (§9).
- **`421` misdirected** → the backstop when the map is stale.
- **Per-org pending-execution counter** → a ready-made load signal.
- **`reject-cross-org-refs!`** → guarantees a cell's closure ⊆ `org ∪ public`.
- **Advisory-lock singleton** (reconciler) → the controller's leader lock.
- **SSE / `graphden_events`** → routing-map + registry invalidation transport.
- **`:executor-orgs` predicate** → generalises to the live cell set (it already
  accepts a fn).
- **Services** → already standalone cell roots (§3.1).
- **`:router-or-nil` + route fn-defs** → per-route cell split with no new base-fn.

## 11. Open questions / risks

- **Cold-start number** — RE-MEASURED cleanly (2026-07-12): ~141 s to serving,
  decomposed as fn-entities + type-check sweep ~55 s and eager-compile of 4085
  fns ~81 s. It is **compute-bound**, not dependency-reconnect or class-loading —
  so AppCDS (which only speeds class-load) barely helps, and CRaC (which captures
  the whole compiled registry) is the lever. The earlier ~35 s figure predates
  the graph's growth to 4085 fns.
- **CRaC feasibility** — substrate CONFIRMED (CRIU v3.16.1 + Zulu 21 CRaC JDK
  both work here, §5.1). Remaining risk is the integration: open resources (Hikari
  pool, http-kit listener, vault client, notify/advisory-lock connections, SSE)
  must be closed before checkpoint and reopened + rewired after restore.
- **Edge vs internal-forward routing** — start with internal forward, measure the
  hop cost, add L7 if it hurts.
- **Overlap accounting** — shared fns compiled on multiple pods inflate memory;
  refcount on evict, and let the packer co-locate cells that share large closures.
- **Move cost vs benefit** — a move pays a compile + cache-warm; hysteresis
  thresholds must exceed it. Needs Phase-1 measurements.
- **Cell discovery beyond services** — per-route entries need the app authored as
  independent entries or an auto-derivation from top-level routes.
- **LRU compile cache granularity** — `compile-all`'s cache is keyed on the whole
  graph shape; per-cell partial loads bypass it. Not blocking, but the cache is at
  the wrong granularity for per-cell.

## 12. Decision log

- 2026-07: direction = **own control plane, cell-granular placement**, with a
  footprint/start track in parallel. Distributed execution of a single call is a
  **non-goal**. Substrate = k8s; Knative/HPA sit *below* the controller.
- 2026-07: **GraalVM native-image shelved** — the eval-at-boot package model is a
  fundamental blocker; footprint/start track pivots to **CRaC-first** (+ AppCDS).
- 2026-07: **CRaC substrate confirmed working in-env** (CRIU v3.16.1 + Zulu 21
  CRaC JDK; checkpoint of a ~300 MB heap succeeds). The gate moves from
  environment feasibility to the `Resource`-handler integration + measuring the
  real ~655 MB image.
- 2026-07-12: **CRaC full-system checkpoint MEASURED end-to-end; DECISION =
  ADOPT.** The real warm image (4085 fns compiled + web-server up) checkpoints to
  219 MB and restores to serving in ~178 ms vs a ~141 s cold boot (~780×). Three
  `graphden.crac` quiesce/resume blockers were found + fixed (lazy class-init on
  the checkpoint thread, Hikari pool not draining to zero, http-kit
  listener/selector). Cold boot re-measured clean = compute-bound (type-check +
  eager-compile), so **AppCDS is de-prioritised** and CRaC is the footprint
  lever. Remaining is operational (CI checkpoint bake); this unblocks
  scale-to-zero (T5.3).
- 2026-07: graph **hot-reload is already shipped** and is the foundation cell-load
  reuses; this task adds **placement**, not freshness. Strict route-to-up-to-date
  (version epoch) is a separate, optional layer.
- 2026-07: **services are already cells**; **an org app is one cell today**, and
  per-route splitting is pure fn-def rewiring, deferred to Phase 2+ on evidence.
- 2026-07: **Phase 0 + Phase 1 + Phase 2 + Phase 3 core SHIPPED**
  (`fleet.{placement,router,command,metrics,controller,packer,rebalance,
  control-loop,discovery}` + `:exec/fleet-controller` + the Helm chart). The
  fleet auto-places new tenant cells and rebalances sustained load imbalance
  under a leader-locked controller, over a k8s StatefulSet whose SRV membership
  tracks HPA scaling. Remaining: overlap-accounting + per-route split
  (evidence-gated, T4.5), scale-to-zero (CRaC-gated, T5.3), and the CRaC
  footprint track's final measurement.
