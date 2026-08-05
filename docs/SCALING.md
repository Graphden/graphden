# Scaling the Executor Fleet

How graphden runs on more than one executor process, and what is still
missing. Companion to [SERVICES.md](SERVICES.md) (what a service is),
[VERSIONING.md](VERSIONING.md) (per-branch contexts) and
[PLATFORM_PLAN.md](PLATFORM_PLAN.md) (orgs, RLS, the effect gate).

This document is the STATIC layer — fixed `:executor-orgs` shards + the `421`
backstop. The DYNAMIC layer built on top of it (automatic load-based placement,
cell load/evict, a leader-locked rebalancing controller, and forward-hop routing
to a cell's holder instead of `421`) is [FLEET_RFC.md](FLEET_RFC.md), with the
ops guide in [FLEET_DEPLOY.md](FLEET_DEPLOY.md).

## The one idea

Four separate-looking problems — "don't compile every tenant's fns",
"let a customer run the executor on their own hardware", "sell a faster
tier", "route load across pods" — are the same problem: **which orgs does
this executor serve?**

Organization is the only closed dependency boundary in the graph. A
tenant's reads are filtered to `{own-org, public}`
(`tenancy/storage.clj` `visible?`), so no edge a tenant can write leaves
that set — and since 2026-07 `reject-cross-org-refs!` enforces it instead
of merely implying it. Therefore a pod that holds `own-orgs ∪ public` can
resolve every ref inside the fns it compiled, and nothing else needs to be
present.

That is why the sharding key is the org, not fn popularity. ROADMAP's
"popularity-based fn-set distribution" (a routing layer that dispatches by
fn-id heat) stays [Not planned](ROADMAP.md) — it needs a router in front of
every call and pays back only at millions of fns. Org sharding needs no
router at all: a request already names its org.

## What multi-pod means today

Run N containers against one Postgres, put a load balancer in front.

| Concern | Mechanism | Where |
|---|---|---|
| Each pod compiles its own registry | `rebuild!` at boot | `executor/compile_runtime.clj` |
| A pod never runs two full compiles at once (each holds the whole graph + lookups + a fresh registry live — two working sets OOM'd prod on 2026-08-05: cold-branch build racing the epoch heal) | process-wide `full-compile-semaphore`, 1 permit (`GRAPHDEN_MAX_CONCURRENT_COMPILES` widens); queued compiles count `:registry/compile-queued`. Held only across the pure read+compile section — never across a lock (`call-with-compile-permit`'s deadlock-freedom contract) | `executor/compile_runtime.clj` |
| A write on pod A reaches pod B | `NOTIFY graphden_events` → delta invalidate (LATENCY optimization) | `storage/postgres/notify.clj`, `system/init/services.clj` `on-notify` |
| A skipped/lost invalidation self-heals (client abort mid-request, a write path with no NOTIFY, a dropped NOTIFY) | graph EPOCH: a PG sequence bumped BEFORE every graph-shaped write; the branch-router validates its cached ctxs against it on fetch (TTL ≈1s) and full-invalidates stale ones. Eager invalidate + NOTIFY stay as the fast path; correctness no longer depends on them | `storage/postgres/graph_epoch.clj`, `system/branch_router.clj` `validate-graph-epoch!` |
| Only one pod runs a cron | `:cardinality :singleton` + `pg_try_advisory_lock` | `services/reconciler.clj` |
| Every pod runs the HTTP listener | `:cardinality :per-pod` | `resources/packages/app/package.edn` |
| A pod dies mid-service | its PG session ends → advisory lock auto-releases → a sibling re-takes it on the periodic reconcile tick (~15s; the crash emits no NOTIFY, so the level-triggered pass — not an event — heals it) | `storage/postgres/advisory_lock.clj`, `services/reconciler.clj` |
| Cancel reaches the pod actually running the execution | `execution:cancel:<id>` NOTIFY fan-out | `crud/fn_execution.clj`, `persist/cancel-local!` |

### Cardinality is the thing people get wrong

A `:service` is "keep this fn running". Two service shapes want opposite
placement, and the difference is not inferable from the fn:

- a `:schedule` loop must run **once per cluster**, or every tick fires N times;
- an `:http-server` must run **once per pod**, or only the advisory-lock
  winner binds a port and the load balancer sees a single backend no matter
  how many pods you started.

Hence the explicit `:cardinality` field. `nil` reads as `:singleton` so
rows written before the field keep their old behaviour; the package seeder
backfills it from `package.edn` on boot, which is what flips a pre-existing
`:web-server` row to `:per-pod`.

### Invalidation is per-branch, not per-pod

Each branch has its own compiled registry. A branch with no version rows
of its own gets the base registry's `{fn-id → closure}` map **by
reference** (`instantiate-from-templates!`), so branches are nearly free in
memory — but that shared map is a *value*, held in the branch ctx's own
atom. Recompiling `main` therefore does **not** update it.

So a write invalidates every cached branch ctx that inherits from the
written branch — `written-branch ∈ (collect-branch-chain … C)` — and
nothing else. Locally that is
`branch-router/invalidate-affected-ctxs!`; across pods the branch-id rides
in the NOTIFY payload (`fn:invalidate:<fn-id>|<branch-id>`) and the
receiving pod answers the same question about its own cache. It never
*builds* a branch ctx it hasn't served: an unbuilt branch has no stale
state, and compiling it on a NOTIFY would make a pod pay for a branch
nobody asked it about.

## Sharding by org

`:executor-orgs` on the ExecutionContext is a membership predicate over
org-ids. `read-graph` filters the graph to `org-id ∈ predicate ∪ NULL`
before compiling. `nil` (the default, and the only value a self-hosted
deployment uses) compiles everything, exactly as before.

```
# a pod serving two tenants plus the platform packages
GRAPHDEN_EXECUTOR_ORGS=public,acme,beta
```

A set is already a predicate in Clojure, so a hash-sharded fleet can pass a
function instead and never enumerate its tenants:

```clojure
{:executor-orgs (fn [org] (= my-ordinal (mod (hash org) pod-count)))}
```

Rows with a NULL `:org-id` are un-owned — the platform graph from
`resources/packages/` — and are in every shard. Name the public org
explicitly: once the tenancy decorator writes the platform packages, they
carry `org-id = "public"`, not NULL.

What this buys:

- **Memory / compile time** scale with the orgs a pod serves, not with the
  tenant count. A shared pod used to compile every tenant's fns into one
  registry (`compile-storage` reads org-agnostically, PLATFORM_PLAN §4
  "Design B").
- **Dedicated capacity** for a paying org = a pod set whose predicate is
  `#{"public" "that-org"}`. No new mechanism.
- **Bring-your-own executor** = the same, on the customer's hardware.

### Reaching the wrong pod: 421

A load balancer that doesn't know about shards will eventually send a
request for org `acme` to a pod that doesn't hold `acme`. That pod answers
**`421 Misdirected Request`** — the status HTTP defines for exactly this:
the server cannot produce an authoritative response for this authority,
and the client should retry on another connection.

Both entry points check, because both execute graph fns:

| Path | Where | Response |
|---|---|---|
| Editor / API (`/api/*`) | `tenancy/addon.clj` `:tenancy/request-scope` | `421 {"error":"misdirected-request"}` |
| Tenant app (FaaS subdomain) | `tenancy/app_router.clj` | `421 text/plain` |

Two ordering decisions worth keeping:

- The **cross-org 403 runs first**. A caller spoofing a `Host` to reach
  another org gets a security answer, not a routing hint.
- The shard check runs **before** the public-org short-circuit. A shard
  that forgot to list the public org — where the platform packages live —
  then fails loudly on its first request instead of 404'ing every fn on
  the editor.

`421` is not a security response. A tenant that reaches the wrong pod did
nothing wrong; the balancer sent it there. Routing by subdomain at the LB
(which the addon's `Host`-based org resolution already makes natural)
avoids the round trip; the 421 is the backstop that keeps a misrouted
request honest.

### Routing by subdomain at the load balancer

To keep the 421 a backstop rather than a hot path, route each tenant's
subdomain to the pool of pods that hold its shard. Since an org's slug IS
its subdomain (`<org>.<base-domain>`), the map is direct. An nginx sketch,
one upstream per shard:

```nginx
# pods serving orgs acme + beta (GRAPHDEN_EXECUTOR_ORGS=public,acme,beta)
upstream shard_a { server pod-a1:8080; server pod-a2:8080; }
# pods serving the rest (GRAPHDEN_EXECUTOR_ORGS=public,gamma,delta)
upstream shard_b { server pod-b1:8080; }

map $host $shard {
    ~^(acme|beta)\.        shard_a;
    ~^(gamma|delta)\.      shard_b;
    default                shard_a;   # apex / editor → any hosted pod
}

server {
    server_name ~^.+\.example\.com$;
    location / { proxy_pass http://$shard; }
}
```

A **BYO** org points its subdomain (or custom domain) straight at the
customer's own executor instead — that pod runs with
`GRAPHDEN_EXECUTOR_ORGS=<their-org>` + `GRAPHDEN_BYO_EXECUTOR=true`, and
hosted pods 421 it anyway if the LB ever misroutes. The `map` is the only
thing that changes as shards are rebalanced; the pods need no LB awareness.

## Fleet-wide per-org quota

`*max-concurrent-executions-per-org*` (default 32,
`GRAPHDEN_MAX_CONCURRENT_EXECUTIONS_PER_ORG`) is enforced **fleet-wide for
tenants**: `acquire-execution-slot!` counts a tenant's non-terminal
(`:pending`) `:fn-execution` rows in shared storage, so N pods enforce ONE
budget instead of N×budget. It is self-healing — the pending rows are the
source of truth, so a crashed pod leaks no counter (the zombie/TTL sweeper
reaps its rows), unlike a durable counter table would.

The **global** cap (`*max-concurrent-executions*`, default 128) stays
per-pod: it protects each JVM's unbounded soloExecutor from thread
exhaustion, which is a per-process safety property, and it remains the exact
bound. The per-org fleet count has a bounded TOCTOU slack (two pods can both
admit before either row exists), which is fine for a fairness limit — the
global per-pod cap is the hard safety net.

The **public** org (platform / single-tenant) keeps the per-pod atom for its
per-org cap: it isn't a metered tenant, and its executions are the hot editor
path we don't add a query to. So single-tenant self-hosted is unchanged.

## Tenant isolation: shared vs dedicated (resource-isolation caveat)

On the **shared** tier many orgs are co-located on one JVM/pod (the packer
spreads for load, not isolation). Those tenants are isolated by *capability*
and bounded for *fairness*:

- **Data** — `OrgScopedStorage` + Postgres RLS (strict by default; see
  [DEPLOYMENT.md § non-superuser DB role](DEPLOYMENT.md)) confine every row to
  its org.
- **Capability** — the two-layer effect gate bounds what a tenant graph may
  *do* (a free tenant can't touch `:network` / `:process` at all).
- **Fairness** — the fleet-wide per-org execution quota, the per-org egress
  rate-limit, and the response byte-cap bound how *often* and how *much*.

What the shared tier does **not** give is a hard **resource** boundary: the
co-located tenants share the pod's CPU, heap, and threads, so a heavy or
misbehaving neighbour can degrade others (a classic *noisy neighbour*), and a
per-tenant out-of-memory can restart the pod and drop co-tenants' in-flight
executions. The wall-clock execution deadline and the per-org quotas blunt
this, but they do not partition CPU or heap.

A tenant that needs a **hard** resource boundary uses the **dedicated** tier:
its own `:executor-orgs` shard with cgroup CPU/memory limits, so its load can
never touch another tenant — see
[FLEET_DEPLOY.md § Dedicated tenant shard](FLEET_DEPLOY.md). This is why safe
always-on tenant services are the dedicated (paid) line, not the shared one.

## External / BYO executor

A customer runs the executor on their OWN hardware, but the graph stays in
our Postgres. Built in these pieces:

- **Pod role + BYO refusal — DONE.** An org carries `:org.execution-mode`
  (`"hosted"` default, `"byo"`). A `"byo"` org runs on the customer's own
  executor, so a HOSTED pod refuses to run it: both request boundaries (the
  editor/API request-scope and the FaaS app-router) answer `421` when
  `tenancy.context/byo-org?` is true and the pod isn't a BYO executor
  (`:byo-executor?`, from `GRAPHDEN_BYO_EXECUTOR`). A BYO executor sets
  `GRAPHDEN_EXECUTOR_ORGS` to its own org and `GRAPHDEN_BYO_EXECUTOR=true`, so
  it serves that org and 421s everything else. `byo-org?` reads `:org` in the
  public context (before the tenant org is bound — `:org` is tenant-hidden
  once scoped) with a ~5s memo.
- **Storage-over-HTTP — DONE.** `graphden.storage.remote.core/RemoteStorage`
  is a read-only leaf implementing the minimal read surface (`query-entities`,
  `read-entity(s)`, and an `ExecutionGraph` satisfy-gate; see
  [EXTENDING.md](EXTENDING.md)). It bootstraps the whole graph over HTTP from
  the new `GET /api/export/graph-rows` (the RAW five-table rows the compiler
  wants — `/api/export/graph` emits fn-def maps, the migration shape), which
  is org-scoped so a BYO executor authenticated as its org gets exactly its
  org + public rows. The rows live in an in-memory index, so compile + execute
  read from memory with no per-read round-trip (the compiled-registry model).
  Writes throw `:remote-storage/read-only` — this executor serves the graph,
  it doesn't author it; the FaaS app path is read-only and works, the
  `/api/execute` persistence path stays on the hosted editor. `refresh!`
  re-fetches (called by the SSE source below).
- **SSE invalidation — DONE.** The BYO executor has no PG connection, so it
  can't `LISTEN`. Invalidation is INFRASTRUCTURE here (the same as the
  dedicated `graphden_events` LISTEN connection), so it's a second consumer of
  that stream, NOT an app route:
  - **Hub** — `system.sse/start-relay!` registers a callback on the
    `:db/notify-listener` (like the reconciler does) and forwards each event
    over SSE to subscribed executors. It runs on its OWN httpkit server /
    port, parallel to the app server — keeping the async SSE channel out of
    the graph-composed router + tenancy request-scope, which expect ordinary
    response maps. Wired as the `:sse/relay` integrant component, opt-in via
    `GRAPHDEN_SSE_PORT` (unset ⇒ no-op). Bearer-gated.
  - **Remote** — `storage.remote.sse/start-source!` holds an SSE connection to
    the hub (`java.net.http.HttpClient`, which streams on the response head —
    the httpkit client buffers the whole body and can't do SSE), parses each
    frame with the shared `notify/parse-payload`, and calls `on-event` — the
    same parsed `{:kind :op :id :branch-id}` map a local pod gets. Reconnects
    with backoff.
  - Fan-out is per-org: `crud.entities/notify-after-write!` tags each
    `fn:invalidate` with the writing org (`:org-id`, read straight off the
    stamped row — no tenancy dependency in that core code), the relay
    registers each subscriber under its authenticated org, and an event goes
    only to that org's subscribers. A nil-org event (a public / platform /
    single-tenant write — shared rows every bundle holds) goes to everyone. So
    a BYO executor is woken only by changes it actually holds.

### Running one (`graphden.byo`)

The pieces are assembled by `graphden.byo/start-byo!`, and `-main` reads the
config from the environment — so a customer runs the same jar with:

```bash
GRAPHDEN_HUB_URL=https://hub.example.com \
GRAPHDEN_SSE_URL=https://hub.example.com:8081 \
GRAPHDEN_EXECUTOR_TOKEN=<bearer> \
GRAPHDEN_EXECUTOR_ORG=acme \
GRAPHDEN_EXECUTOR_BRANCH=main \
GRAPHDEN_APP_HANDLER_FN=<org-app-handler> \
GRAPHDEN_PORT=8080 \
  clojure -M -m graphden.byo
```

It loads the packages LOCALLY for their base-fn impls (no DB sync — the graph
lives on the hub), reads the graph over HTTP into a `RemoteStorage`, compiles
it, serves the org's handler fn over HTTP directly (not via the PG-backed
service registry), and refreshes on each SSE push. `GRAPHDEN_HUB_URL` is the
hub's APP url (`/api/export/graph-rows`); `GRAPHDEN_SSE_URL` is the relay's
separate port — omit it for a bootstrap-only executor with no live refresh.

Provisioning a BYO customer, operator-side (platform-only, `:org` is
tenant-forbidden): create the org, mint its executor token, point the org at
its handler, then flip it with
`POST /api/orgs/execution-mode {name, execution-mode=byo}`
(`tenancy-admin/registration`), which drops the byo memo so hosted pods start
421'ing it at once.

### Boundaries (what a BYO executor does NOT do)

The read-only, one-org shape is deliberate:

- **Serves the app path, not `/api/execute` with history.** `RemoteStorage`
  writes throw, and `/api/execute` persists `:fn-execution` rows. So a BYO
  executor runs the org's APP (FaaS handler, no persistence); the editor's
  Run-with-history stays on the hosted hub.
- **Pinned to one branch.** A `RemoteStorage` bootstraps one branch
  (`GRAPHDEN_EXECUTOR_BRANCH`, default main). Serving several branches on one
  BYO executor means several RemoteStorages — out of scope for the single-org
  serve case.
- **The fleet per-org quota doesn't apply.** It counts `:fn-execution` rows,
  which a BYO executor doesn't persist, so the count is always 0. That's
  intended — a BYO customer runs their own compute on their own hardware, so
  the platform's fairness cap isn't theirs to enforce.
- **Effects are NOT clamped.** The cloud effect gate
  (`default-cloud-allowed-effects` — no env/io/network/process) protects the
  SHARED platform from untrusted co-located tenant code. A BYO executor runs
  the customer's OWN graph on their OWN hardware — the same trust posture as a
  self-hosted deployment, which has no gate — so its handler runs with
  `:allowed-effects` unset (an app that calls external APIs is the point). It
  is still bounded by a per-request wall-clock timeout (`byo/default-timeout-ms`,
  30s) with cooperative cancellation, so one runaway handler can't pin the pool.

## Advisory-lock reconnect

The lock connection lives behind a reconnecting holder; each reconcile pass
calls `advisory-lock/ensure-live!`, and a reconnect (DB restart / network
blip) triggers `reassert-lock-ownership!` — the pod re-takes every
`:singleton` it was running and stops any a sibling grabbed during the outage.
See SERVICES.md § Roadmap.

## Still open

Nothing about CORRECTNESS. The multi-node software topology — separate JVMs,
real TCP between them, a BYO executor that reaches the graph over HTTP
(`RemoteStorage`) instead of touching Postgres directly — needs no special
hardware: it's separate processes + a config boundary, reproducible with
containers, VMs, or even two ports on one host. That loop is proven three ways:
`storage.remote.e2e-test` runs the BYO half over REAL http-kit servers
(bootstrap, SSE live-refresh, reconnect); `fleet.two-container-e2e-test` boots the executor
image TWICE as a real two-pod fleet over a shared Postgres (testcontainers) and
drives the token-gated `/internal/fleet/*` control plane over real HTTP
(`bb test-fleet-e2e`); and the fleet was also run on a real two-pod kind cluster
(forward-hop, leader election, cross-pod transport, DNS-SRV — 2026-07).

What remains is purely OPERATIONAL and not a test: a live demonstration of a
customer running a BYO executor on their own infrastructure (their hardware /
another cloud / another region). That's a deployment showcase — reproducible with
a second cloud VM or container, never literally a second physical machine — not a
correctness gap. The boundaries above are intentional scope.

## Rolling upgrade note

An old pod holds the `:web-server` advisory lock until it exits; a new pod
reads `:cardinality :per-pod` and ignores the lock. So a rolling restart
works, and the first new pod serves traffic immediately. The seeder
backfills `:cardinality` on boot, before the reconciler's first pass.
