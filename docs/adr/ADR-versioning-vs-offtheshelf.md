# ADR — Bespoke branch/versioning on Postgres vs. an off-the-shelf tool

**Status:** Decided — keep the bespoke system on Postgres. Off-the-shelf
(Dolt / Datomic / XTDB / temporal tables) rejected. Two follow-through
findings from the investigation resolved (one fixed, one confirmed
deliberate).

**Date:** 2026-07-12.

## Question

An early skeptical review quipped that Graphden's branch/history/diff/merge
system is "reinventing git — surely there's a ready-made tool that's simpler
and better." This ADR investigates that seriously and records the decision so
it does not resurface.

## What the system actually is (three layers, ~2876 LOC)

`Versioned(OrgScoped(Postgres))` — tenancy (RLS) is a **separate layer beneath**
versioning, not part of it (`system/core.clj:211-219`).

| Layer | LOC | Can an off-the-shelf tool own it? |
|---|---|---|
| Version-row store + branch-chain resolution | **~750–800** | Only this — and **only by swapping the DB engine** |
| Diff (resolved-view entity comparison) | ~125 | No — domain-specific |
| Generic merge core | ~140 | Partially (Dolt) |
| **Domain merge policy** — `branch-local?` monotonic-OR + merge-protected secrets | **~325** | No — exceeds the generic merge core |
| **Live per-branch execution routing** (`branch_router.clj`: a compiled registry + `ExecutionContext` per branch, HTTP dispatch, cache invalidation) | **635** | No — no data tool provides this |

The decisive point: Graphden branches are **not "checked-out" one at a time**.
Every branch is live and serves traffic simultaneously — each holds its own
compiled executor registry bound to a `VersionedStorage(branch-id)`, and HTTP
requests route by `X-Graphden-Branch`. This is "code = a live graph in a DB,"
not "source text at rest." Git — and every data-versioning tool — has no
equivalent.

## Off-the-shelf survey

Crucial distinction: **branches** (a mutable named divergence axis) ≠
**time-travel** (an immutable historical axis). Almost every "temporal DB"
gives time-travel, not branches.

| Tool | Provides | Real data branches like ours? |
|---|---|---|
| **Dolt** ("git for data", MySQL-compatible) | branch/merge/diff/history at row level | **Yes** — the only true one |
| **Datomic** | immutable + `as-of`/`since`/`with` | No — time-travel; branches emulated by an attribute + filter = what we already do, on another engine |
| **XTDB** | bitemporal (valid + tx time) | No — bitemporal, not branched |
| **Postgres system-versioned / temporal tables** | single-table history | No — branches are an orthogonal axis |

So the only genuine "git for data" is **Dolt**, and adopting it means **leaving
Postgres** (Dolt is a separate MySQL-flavored engine). There is no ready-made
row-level branching that stays on Postgres.

## Why leaving Postgres is not worth it

Postgres is not incidental here — it is load-bearing for everything the
versioning tools do **not** provide, and which the rest of the platform depends
on:

- **`DISTINCT ON`** latest-per-group — the resolver's working-set bound
  (`storage/postgres/crud.clj:297-347`, consumed by `resolution.clj:380`).
- **advisory locks** — singleton services across the fleet
  (`storage/postgres/advisory_lock.clj`; inline at `storage/core.clj:266-276`).
- **LISTEN/NOTIFY** with a **branch-scoped wire format** — cross-pod delta
  invalidation (`storage/postgres/notify.clj`, `branch_router.clj:363-378`).
- **RLS** — multi-tenant isolation (`tenancy/rls.clj`), a whole separate layer.
- **JSONB** — versioned `:constraint`/`:value`/`:expects-effects` columns.

The trade would be: give up the most-tested ~26% of the versioning code (~750
LOC of store+resolve), **rebuild RLS / NOTIFY / advisory-locks / DISTINCT-ON on
a far less mature engine, inherit a new class of operational risk — and STILL
hand-write the remaining ~1100 LOC of domain versioning plus the 635-LOC
per-branch execution router**, which no tool provides. Net-negative.

People reach for Dolt/Datomic when versioning **is the product** (dataset
lineage, audit). Here versioning is a feature of a code-execution platform whose
substrate is Postgres for independent reasons. Swapping the substrate to get one
feature "for free" inverts the priority.

**Decision: keep the bespoke system on Postgres.**

## Follow-through findings from the investigation

The investigation was not wasted — a subagent map of the implementation
surfaced two "reimplemented-standard" smells. On close reading:

### 1. Fork-point was not a true merge-base — FIXED

`storage/merge.clj` `fork-point` approximated the merge-base by branch-creation
time, and its `:else` fallback (used whenever neither branch is a direct child
of the other) picked the **source** branch's created-at. For two **siblings**
off main where the **target** was created and edited **before** the source
existed, the target's edit predated that fork-point, so `detect-conflicts`
silently **missed the overlap** — a merge would clobber the target with no
prompt. That is a false **negative**, the dangerous kind (false positives are
safe — the user resolves them). Sibling merges are reachable via
`POST /api/branches/:ref/merge` and the demo seeder creates siblings, so the bug
was latent-but-real.

Fixed with a true lowest-common-ancestor fork over the `:base-branch-id` chains
(reusing the existing `collect-branch-chain`): each side's divergence branch is
the deepest chain element the other doesn't share; the fork is the **earlier**
divergence when both sides diverged (siblings), or the sole descendant's
divergence when one is an ancestor of the other (feature↔main — unchanged
behavior). Reproducing test:
`sibling-merge-detects-conflict-regardless-of-creation-order-test`.

**Residual (documented, not solved):** `detect-conflicts` inspects only the two
endpoints' own version rows, so a change on an *intermediate* branch of a
multi-level merge (grandchild→grandparent) that is inherited but not re-stamped
on an endpoint is still not compared. Fixing that needs examining inherited
rows — a larger change than the fork-point, and only relevant to deep branch
topologies. Deferred.

### 2. Branch-chain walk is app-code, not a `WITH RECURSIVE` CTE — DELIBERATE, kept

`resolution.clj` `collect-branch-chain-impl` walks parent branches with one
`sp/read-entity` per level in a Clojure loop, where a Postgres `WITH RECURSIVE`
CTE would do it in one round-trip. This looked like a reimplemented-standard
smell — but it is **deliberate and correct to keep**:

- It goes through the **storage protocol** (`sp/read-entity`), so it works on
  **any** backend — including the **BYO `RemoteStorage`** executor that reads the
  graph over HTTP (`docs/SCALING.md`). A raw CTE would Postgres-lock branch
  resolution and break BYO.
- It is **process-wide cached** (`global-chain-cache`), so the per-level reads
  happen only on a cold chain.
- Branch chains are **short** (typically 1–3 levels).

Converting to a CTE would trade portability for a micro-optimization on a cached,
short walk. Not done.
