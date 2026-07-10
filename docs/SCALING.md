# Scaling the Executor Fleet

How graphden runs on more than one executor process, and what is still
missing. Companion to [SERVICES.md](SERVICES.md) (what a service is),
[VERSIONING.md](VERSIONING.md) (per-branch contexts) and
[PLATFORM_PLAN.md](PLATFORM_PLAN.md) (orgs, RLS, the effect gate).

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
| A write on pod A reaches pod B | `NOTIFY graphden_events` → delta invalidate | `storage/postgres/notify.clj`, `system/core.clj` `on-notify` |
| Only one pod runs a cron | `:cardinality :singleton` + `pg_try_advisory_lock` | `services/reconciler.clj` |
| Every pod runs the HTTP listener | `:cardinality :per-pod` | `resources/packages/app/package.edn` |
| A pod dies mid-service | its PG session ends → advisory lock auto-releases → a sibling takes over on the next reconcile | `storage/postgres/advisory_lock.clj` |
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

## What is NOT built

**Fleet-wide quotas.** `*max-concurrent-executions-per-org*` (default 32)
is a per-process counter. With N pods a tenant gets N×32. Honest per-tenant
limits need shared state; today the value should be read as "per pod".

**Semi-self-hosted / external executor.** The design is the org shard plus
two missing pieces:

1. A `StorageCRUD` / `ExecutionGraph` implementation over HTTP, so an
   executor outside our network can read the graph without Postgres
   credentials. The protocol seam already exists (see
   [EXTENDING.md](EXTENDING.md)); `GET /api/export/graph` is most of the
   bulk read.
2. An invalidation stream to replace LISTEN/NOTIFY across the network
   (SSE on the storage API).

Plus an `:org` field saying where that org runs, so shared pods refuse to
serve it and it isn't scheduled onto them.

**Advisory-lock reconnect** — DONE. The lock connection lives behind a
reconnecting holder; each reconcile pass calls `advisory-lock/ensure-live!`,
and a reconnect (DB restart / network blip) triggers
`reassert-lock-ownership!` — the pod re-takes every `:singleton` it was
running and stops any a sibling grabbed during the outage. See
SERVICES.md § Roadmap.

## Rolling upgrade note

An old pod holds the `:web-server` advisory lock until it exits; a new pod
reads `:cardinality :per-pod` and ignores the lock. So a rolling restart
works, and the first new pod serves traffic immediately. The seeder
backfills `:cardinality` on boot, before the reconciler's first pass.
