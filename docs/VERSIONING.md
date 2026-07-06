# Versioning

Branches, history, diff, and merge for the fn graph. The storage decorator
that backs all of this (`VersionedStorage`) was the original deliverable
of the versioning effort — this document describes what landed on top
of it in `feat/versioning`: the HTTP surface, the per-branch executor
routing, the editor UI, and the dev-loop ergonomics.

For the underlying schema + branch resolution algorithm, see
[ROADMAP.md § Git-like Versioning](ROADMAP.md#git-like-versioning-done).

## Mental model

The bare `VersionedStorage` decorator already knew how to:

- write version rows scoped to a branch,
- resolve any versioned entity along a branch chain
  (`branch.base_branch_id → branch.base_branch_id → …`),
- surface cross-branch versions via `branch_merge` records.

What was missing was anyone HOLDING a `VersionedStorage` pointed at the
right branch when an HTTP request came in. The executor's compiled
closures close `ctx` in at compile time (`(impl args ctx)` —
`executor/compile_eager.clj`), and that `ctx` carries one specific
`VersionedStorage`. So serving a request on branch X requires its
OWN compiled registry, bound to a `VersionedStorage` whose `branch-id`
is X's.

`feat/versioning` adds the routing layer that builds + caches those
per-branch contexts on demand, and the HTTP / editor surface that
exercises it.

```
HTTP request
  │
  │ X-Graphden-Branch: feature-x        (or ?branch=feature-x, or absent)
  ▼
:_branch-routed-handler (compiled main fn-graph)
  │
  ▼
graphden.system.branch-router/dispatch
  │
  │ resolve branch-id from header/query → atom lookup
  ▼
per-branch Ring callable
  │
  │ re-reads (registry branch-ctx) on every call so
  │ post-write invalidation propagates without re-attach
  ▼
per-branch compiled closure
  │ ctx baked in → reads + writes go through
  │ VersionedStorage bound to feature-x
  ▼
PG storage
```

## Per-branch routing

### Lifecycle

1. `:exec/branch-router` init-key runs after `:exec/compiled-registry`,
   so main's registry is already populated.
2. Construction seeds the atom with the default-branch entry
   (`{branch-id → {:ctx :handler :built-at}}`). Reuses the existing
   base-ctx — no extra compile pass for main.
3. On a request, `dispatch` extracts the branch ref (header wins over
   query param; both must be non-blank to count), resolves to a
   branch-id via base storage.
4. If the branch-id has a cached entry, use it. Otherwise call
   `build-and-cache!`:
   - Construct a fresh `ExecutionContext` with
     `(VersionedStorage. base-storage branch-id)` and FRESH atoms for
     `:compiled-registry`, `:graph-cache`, `:compile-deps`.
   - Call `cr/rebuild!` — full compile pass against the branch's
     resolved view.
   - Build a Ring callable that re-reads
     `(registry branch-ctx)` on EVERY invocation, so a subsequent
     write that clears the registry atom is visible to the next
     request without our cache having to be invalidated too.

### Invalidation

The standard `invalidate-graph-cache!` flow still applies — writes
land on the per-branch ctx via the normal `defbase` path, and the
post-write invalidation only touches that branch's atoms. main stays
warm; other unrelated branches stay warm. Only the just-written
branch pays a rebuild on the next read.

### Cost model

- First request on a branch: full compile pass (~100ms on the
  current package set).
- Subsequent requests: O(1) lookup in the atom.
- Write on branch X: invalidates X only.
- Memory: one ExecutionContext per active branch. For a workflow
  with a handful of branches this is fine; multi-tenant prod will
  want LRU eviction (`invalidate!` exists, the caller side is
  missing).

### Why singleton atom, not on ctx

`graphden.system.branch-router/active-router-global` is a process-wide
atom set by the init-key (read through `active-router-atom`, which prefers
the per-thread `*active-router-override*` when a test binds one for
isolation). The wrap base-fn impl (`web.branch-router/branch-routing-wrap`)
runs inside a compiled closure that already closed over a fixed ctx — the
wrap CAN'T reach into the running router via ctx, because the router didn't
exist when the closure was built.

## Branch selection

Per request:

1. `X-Graphden-Branch: <name>` header (wins)
2. `?branch=<name>` URL query param (URL-decoded)
3. neither → default branch (main)

Both name and stringified UUID are accepted; the router normalises
via the base storage's `:branch` table. Unknown ref → 400 with an
`{"ok":false,"error":"Unknown branch: <ref>"}` body (caught at the
dispatcher, never reaches the per-branch handler).

The editor's `window.fetch` is wrapped at module load
(`editor-branches.js`) so every `/api/*` call picks up the current
branch header automatically — individual call sites stay
unaware. The branch state lives in three coordinated places:

- URL `?branch=` is the shareable truth,
- localStorage persists across reloads,
- the chip in the menu-header shows the current value (filled
  style when off main).

Switching branches mutates BOTH localStorage and the URL, then
calls `location.reload()` — the simplest "invalidate every cached
view" strategy.

## HTTP API

All endpoints are JSON. Auth is the standard bearer-token middleware;
read endpoints sit behind it too (matches `/api/services`).

| Verb   | Path                                     | Body                                  | Returns                              |
|--------|------------------------------------------|---------------------------------------|--------------------------------------|
| GET    | `/api/branches`                          |                                       | `{ok, count, branches: [{id, name, base-branch-id, created-at}]}` |
| GET    | `/api/branches/:ref`                     |                                       | `{ok, branch}` or `{ok: false, error}` |
| POST   | `/api/branches`                          | `{name, base-branch-id?}`             | `{ok, branch}`                       |
| DELETE | `/api/branches/:ref`                     |                                       | `{ok, id, name}` or `{ok: false, reason, error, child-branch-ids?}` |
| GET    | `/api/branches/:ref/diff?against=<ref>`  |                                       | `{ok, target, source, count, diffs}` |
| GET    | `/api/branches/:ref/conflicts?source=…`  |                                       | `{ok, target, source, fork-point, count, conflicts}` |
| POST   | `/api/branches/:ref/merge`               | `{source, conflict-resolutions?}`     | `{ok, merge}` or `{ok: false, reason: :merge-conflict, conflicts}` |
| GET    | `/api/fns/:fn-id/versions`               |                                       | `{ok, fn-id, count, versions}` — each entry carries `:execution-count` (runs that anchored to that exact version row) |
| GET    | `/api/executions?fn-id=X`                |                                       | `{ok, executions}` — runs of X **as it resolves on the current branch** (not all-versions). Defaults to 20 rows, capped at 100 |
| GET    | `/api/executions?fn-version-id=Y`        |                                       | `{ok, executions}` — runs of the SPECIFIC version row (drives the `⌛` panel's per-version expand) |

### Diff shape

Each `diffs[]` entry:

```jsonc
{
  "entity-name": "fn",
  "entity-id": "<uuid>",
  "change": "added-in-source" | "added-in-target" | "modified",
  "source-version": { … } | null,
  "target-version": { … } | null
}
```

Filters out identity-only rows so a fn that exists on one branch
but not the other reports `:added-*` rather than spurious
`:modified`. created-at is dissoc'd before comparison.

### Conflict resolution shape

The merge endpoint's `conflict-resolutions` is an array (JSON can't
key by `[entity-name entity-id]`):

```jsonc
[
  {"entity-name": "fn", "entity-id": "<uuid>", "choice": "source"},
  {"entity-name": "binding", "entity-id": "<uuid>", "choice": "target"}
]
```

Coerced to the Clojure-side `{[entity-name entity-id] :source|:target}`
internally. Unknown choices are silently dropped — matches the merge
core's `case` matcher.

## Editor UI

| Affordance | Where | What it does |
|------------|-------|--------------|
| Branch chip | menu-header (between prefs + auth) | Shows current branch. Inverted style when off main. |
| Branch popover | click chip | Branch list + inline create + Δ diff + ⇢ merge + × delete |
| Diff modal | click Δ in row | Full-viewport list of differences (`:added-in-source` / `:added-in-target` / `:modified`); :fn rows are clickable → navigate |
| Conflict modal | merge fails with `:reason :merge-conflict` | Per-entity source/target radio, retry merge with `:conflict-resolutions` |
| Fn-card ⌛ action | per fn-card row-actions | Version timeline + per-version `(N runs)` badge; click a row → inline-expand its executions (lazy fetch); `switch` button jumps to that version's branch |

## Demo seeder

`:exec/demo-branches` init-key (in `system-{dev,prod}.edn`) pre-bakes
a small set of branches every startup so the UI has something to
demo without manual setup. Idempotent — already-existing branches
are left untouched.

### Toggle

```bash
# enable (default in dev system + local docker via .env)
GRAPHDEN_DEMO_BRANCHES_ENABLED=1

# disable (default in real prod)
GRAPHDEN_DEMO_BRANCHES_ENABLED=      # unset / empty / 0 / false / no / off
```

Accepts `1 / true / yes / on` case-insensitive. The init-key logs
`[demo-branches] disabled — set GRAPHDEN_DEMO_BRANCHES_ENABLED=1 …`
when off so the silence isn't mysterious.

### Re-seed

The seeder skips a branch whose `:name` already exists. To re-seed:

- Delete the branch via the editor's popover or
  `DELETE /api/branches/<name>`, then restart the pod.
- OR `bb deploy` (clean DB) — sync re-runs, branches reappear.

### Mutation vocabulary

Each branch declaration has a `:mutations` vector. Currently
shipped:

- `:update-fn-description` — set the fn's `:description` on this
  branch. Looks the fn up by `:fn-name`.
- `:create-fn` — create a composed fn (`:parent`-based, optional
  `:description`, no bindings yet). Names the new fn via `:name`.

Adding more is one `defmethod` of
`graphden.system.demo-branches/apply-mutation!`.

## Files

| Path | What |
|------|------|
| `src/graphden/system/branch_router.clj` | Per-branch ctx registry + Ring dispatcher + singleton hook |
| `resources/packages/web/branch-router/` | `branch-routing-wrap` base-fn (the wrap installed in front of `_app-ring-response`) |
| `src/graphden/crud/branches.clj` | List / get / create / delete / merge / diff / history orchestration |
| `resources/packages/app/branches/` | HTTP fn-defs + impls bridging the API endpoints to `crud.branches` |
| `src/graphden/system/demo_branches.clj` | Idempotent demo seeder + `:apply-mutation!` multimethod |
| `resources/packages/app/editor/editor-branches.js` | Branch chip + popover + fetch wrap + conflict-modal overlay chrome & radio/apply lifecycle |
| `resources/packages/app/editor/fns.edn` (`:_partial-mc-*`) | `POST /partials/merge-conflicts` — the conflict-modal card rendered as hiccup (route `:partial-merge-conflicts`) |
| `resources/packages/app/editor/editor-fn-versions.js` | `⌛` history popover for fn-card |

## Subtleties worth knowing

### `:fn-version` ≠ "functional behaviour"

Only the `:fn` row itself is anchored when you make a change to its
top-level fields (name, description, return-type,
constraint, anonymous-hash, expects-effects). Edits to **bindings**
(values, refs, type-overrides, list items, fn-slot positions)
create separate `:binding-version` / `:binding-list-item-version` /
`:fn-slot-version` rows; the `:fn` row's `:fn-version-id` is
UNCHANGED.

Consequence: two `:fn-execution` rows pointing at the same
`:fn-version-id` can have produced different results if a binding
changed in between. The history filter ("show me runs of the
current version") groups by `:fn-version-id`, so binding-only
edits don't visually split the timeline — they all share one
bucket.

This is an intentional MVP trade-off: a per-execution hash over
`{fn-version, all binding-versions, all list-item-versions}` would
make the timeline more accurate but adds significant schema +
compute cost. If a tight forensic answer to "what exactly was
running when this row was produced" becomes critical, expand the
anchor scope; for now, the resolved-view on the run's branch at
the run's timestamp is the closest the system gets.

### Services CAN now run per-branch (`:service.branch-id`)

`:service` is still NOT in `versioning.storage.resolution/
entity-config` — the row is global — but each row carries a
`:branch-id` ref that the reconciler routes against. The
production reconciler (`graphden.services.reconciler/reconcile-
once!`) groups enabled services by `:branch-id`, asks
`branch-router/ctx-for` for each branch's `ExecutionContext`, and
starts the service against THAT ctx. Same fn-id can run with
branch-specific bindings (dev port + prod port live side-by-side).

- Creating a `:service` via the editor's ⚙ popover offers a
  branch picker (default = the editor's current branch). The
  reconciler picks up the row immediately and starts it inside
  the chosen branch's ctx.
- A bug fix on branch X immediately re-rolls the X-scoped service
  via `recon/restart-services-on-branch!` (wired into
  `merge-branch!` as well, so post-merge cron loops pick up the
  fresh closures).
- Legacy rows without `:branch-id` fall back to the reconciler's
  base ctx (= main behavior), so the migration is transparent.

Open gaps: port allocation is OS-level (two branches binding
`8080` fight; the loser records `:start-failed-at`); cron
collision detection across branches isn't implemented; the
running-atom is a single in-process map (no multi-pod
coordination beyond the existing advisory locks).

### `:branch-local?` — runtime-config that doesn't propagate on merge

Some fn-defs encode environment-specific runtime config (web-
server port, vault path, cron cadence, env-var indirection). For
those, an `:branch-local?` identity-level flag on `:fn`
short-circuits the cross-branch overlay: foreign-branch version
rows are dropped from `merge-candidates` (online + batch paths)
so a sticky-local fn version stays scoped to the branch that
produced it.

The filter applies to MERGE propagation, NOT to parent-branch
INHERITANCE. A branch B that was forked from A (`:base-branch-
id = A`) inherits A's branch-local fn versions as part of normal
inheritance — that's the user's choice when they forked B from A.
The asymmetry is intentional: merge says "fold sibling's history
in", inheritance says "I'm a child of this branch, give me its
state". Branch-local blocks the first but not the second.

The flag is **monotonic-OR over `:parent-ids`**: any ancestor
true ⇒ effective true forever. Sync-time type-check rejects
descendant `:branch-local? false` when an ancestor is true (mirror
of the `:required` widening guard).

Seeded defaults: `:http-server`, `:secret-leaf`, `:schedule`,
`:env`. NOT seeded: `:pg-query` (admins may want portable
queries), `:future` (transitively reaches `:schedule`).

Implementation:

- Walker + per-storage cache: `graphden.versioning.branch-local`
  (`effective-branch-local?` + `build-branch-local-set`).
- Resolution filter: `versioning.storage.resolution/merge-
  candidates(-from-cache)` drops foreign-branch candidates when
  `effective-branch-local?` is true for the fn-id; `resolve-
  version`'s parent-branch recursion is also gated.
- Type-check guard: `types.check/check-branch-local-monotonicity!`
  throws `:types/branch-local-widening-forbidden` on widening.
- Editor: 📍 strip on the fn-card (walks parent-ids via
  `lookups.fnMap` + the diff payload's `source-version` as a
  seed for cross-branch fns); 📍 badge + dimmed row in the
  branch-diff modal.
- Merge response surfaces a `:skipped-as-branch-local` list with
  entity-ids (see "Merge audit log" below) so API consumers can
  see what didn't propagate.

### Merge audit log

`POST /api/branches/:ref/merge` returns the merge record plus a
`:skipped` block enumerating entities the resolver kept scoped to
their origin branch:

```json
{
  "ok": true,
  "merge": { /* :branch-merge row */ },
  "skipped": {
    "branch-local": [
      {"entity-name": "fn", "entity-id": "uuid…", "fn-name": "my-server"}
    ]
  }
}
```

The shape is forward-compatible: new categories
(`:conflict-deferred`, `:protected-by-trait`) can land alongside
`:branch-local` without breaking existing consumers. The editor's
post-merge alert summarises the skipped count; the diff modal
keeps its inline 📍 badge on the same rows.

## Known gaps

- Per-branch ctx cache has no eviction — fine for the dev workflow
  (handful of branches), needs LRU for multi-tenant prod.
- `resolve-branch-id`'s ref-cache has a narrow TOCTOU: a branch deleted
  concurrently with the FIRST (uncached) resolution of its name can
  leave the just-deleted id cached (the resolve's DB read raced ahead
  of the delete's `forget-ref-cache-for-branch!`). A later request for
  that name then builds a ctx for a dead branch (chain resolves to
  `[dead-id]`, falling back to main's versions / entity-not-found).
  Admin-triggered + recoverable (cache invalidate / restart); an
  airtight fix needs resolve↔delete coordination — deferred with the
  LRU work above.
- The `:exec/branch-router` is unit-tested at the dispatcher level
  (`branch-router-test`: header / query parsing, default fallback,
  unknown-ref rejection, invalidate) but there's no end-to-end
  Clojure test exercising the full middleware-through-storage path.
  Manual curl verification + the
  `list-executions-isolates-by-branch-version-test` integration
  test cover the per-branch storage round-trip; a single test that
  sends a real HTTP request through the wrap into per-branch ctx
  is still missing.
