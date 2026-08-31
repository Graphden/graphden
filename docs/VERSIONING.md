# Versioning

Branches, history, diff, and merge for the fn graph. The storage decorator
that backs all of this (`VersionedStorage`) was the original deliverable
of the versioning effort — this document describes what landed on top
of it in `feat/versioning`: the HTTP surface, the per-branch executor
routing, the editor UI, and the dev-loop ergonomics.

For the underlying schema + branch resolution algorithm, see
[ROADMAP.md § Git-like Versioning](ROADMAP.md#git-like-versioning).

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
- Memory: one ExecutionContext per active branch, bounded by an **LRU
  cap** (`default-max-cached-branches`, default 16; tune per deployment
  via `GRAPHDEN_MAX_CACHED_BRANCHES`). Adding a branch beyond the cap
  evicts the least-recently-used non-default entry, so a multi-tenant pod
  churning through many branches keeps a bounded working set.

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
| GET    | `/api/branches`                          |                                       | `{ok, count, branches: [{id, name, base-branch-id, created-at, owner-id, write-policy}]}` |
| GET    | `/api/branches/:ref`                     |                                       | `{ok, branch}` or `{ok: false, error}` |
| POST   | `/api/branches`                          | `{name, base-branch-id?, forbid-invalid??, write-policy?, require-merge??}` | `{ok, branch}` — the creating principal's stable id is stamped as `owner-id` |
| POST   | `/api/branches/:ref/policy`              | `{write-policy}` ∈ `open`/`owner`/`admins` | `{ok, write-policy}` (`open` clears to null) — WHO may flip is the tenancy authorize-writer's call |
| POST   | `/api/branches/:ref/protect`             | `{require-merge}` boolean               | `{ok, require-merge?}` — the open-core "push only via merge" toggle; direct writes then 409 `:branch/merge-required`, merges still land |
| POST   | `/api/branches/:ref/propose`             | `{proposed}` boolean (absent ≡ true)    | `{ok, review-state}` — mark/withdraw the branch as a change proposal for review into its base (open-core); the reviewer list = branches with `review-state "proposed"` |
| POST   | `/api/branches/:ref/review-policy`       | `{required-approvals, allow-self-approval, approver-ids}` | `{ok, policy}` — set the branch's review requirements (on the merge target); enforced open-core by the merge gate |
| POST / DELETE | `/api/branches/:ref/approve`      | (empty)                                 | `{ok, approver}` / `{ok, removed}` — record / withdraw the caller's approval of a proposal; POST is `:authz/forbidden` (403) if the caller may not approve merges into the target |
| GET    | `/api/branches/:ref/approvals`           |                                         | `{ok, required, have, satisfied, approvers:[{approver-id, stale}]}` — the proposal's approval status |
| DELETE | `/api/branches/:ref`                     |                                       | `{ok, id, name}` or `{ok: false, reason, error, child-branch-ids?}`. Rejected when the branch has children (`:reason :branch-has-children`) or is a live **merge SOURCE** (`:constraint-violation/branch-is-merge-source` — deleting it would revert every target it merged into, since merge is by-reference; delete those targets first) |
| GET    | `/api/branches/:ref/diff?against=<ref>`  |                                       | `{ok, target, source, count, diffs}` |
| GET    | `/api/branches/:ref/conflicts?source=…`  |                                       | `{ok, target, source, fork-point, count, conflicts}` |
| POST   | `/api/branches/:ref/merge`               | `{source, conflict-resolutions?}`     | `{ok, merge}`, `{ok: false, reason: :merge-conflict, conflicts}`, or `{ok: false, reason: :merge-protection-violation, error, invalid-fns}` (409 — target's `forbid-invalid?` policy over recorded type diagnostics) |
| POST   | `/api/import/graph?target=<branch>[&create=true][&prune=true]` | `application/edn` bundle (`{:fns […]}` or a bare fn-def vector) | `{ok, branch, fn-ids, skipped-owned, pruned?}` — apply an exported bundle to a NAMED branch (registry package; `?target=` because `?branch=` is the request-scope selector; `create` forks it stamping the caller owner/`owner`-policy; `prune` = snapshot semantics; see PACKAGE_DISTRIBUTION § 13). The imported branch then rides this table's normal diff → merge flow |
| GET    | `/api/fns/:fn-id/versions`               |                                       | `{ok, fn-id, count, versions}` — each entry carries `:execution-count` (runs that anchored to that exact version row) |
| GET    | `/api/executions?fn-id=X`                |                                       | `{ok, executions}` — runs of X **as it resolves on the current branch** (not all-versions). Defaults to 20 rows, capped at 100 |
| GET    | `/api/executions?fn-version-id=Y`        |                                       | `{ok, executions}` — runs of the SPECIFIC version row (drives the `⌛` panel's per-version expand) |

### Diff shape (wire, v1) and the display model (v2)

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
`:modified`. created-at is dissoc'd before comparison. Note there is
no `removed` change kind: deletion is a tombstone, and a tombstoned
entity resolves to `nil` exactly like one that never existed on that
branch — so "deleted on source" surfaces as `added-in-target`. The
editor therefore labels these sections "Only in <branch>", which is
always true, instead of "Added in", which sometimes isn't.

The editor's diff modal does NOT render this flat wire shape. It
renders the GROUPED display model from
`graphden.versioning.storage.diff-view/diff-branches-view` (exposed
as the `:diff-branches-view` base-fn, consumed only by the
`/partials/branch-diff` partial):

```edn
{:count 3
 :groups [{:fn-id "<uuid>" :fn-name "web-server" :fn-label ":web-server"
           :change :modified          ; the OWNING fn's own change,
                                      ; or :modified when only parts moved
           :branch-local? false
           :entries [{:entity-name :fn :entity-id "<uuid>" :change :modified
                      :fields [{:field "description"
                                :source "new" :target "old"}]}
                     {:entity-name :binding :entity-id "<uuid>"
                      :change :modified :slot-name "port"
                      :fields [{:field "value" :source "9090" :target "8080"}]}]}]}
```

Bindings / fn-slots / list-items are grouped under the fn that owns
them (list-items chain through their binding); slot names and
fn-typed ref values (`ref-fn-id`, `type-override-fn-id`, …) are
resolved to names batch-wise. One-sided entries carry a `:preview`
string instead of `:fields`. The JSON API keeps serving the flat v1
shape above.

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
| Branch chip | context bar (`#gd-ctxbar` → `#branch-mount`, between the workspace chip and the packages chip) | Shows current branch. Inverted style when off main. |
| Branch popover | click chip | Branch list + inline create. Per row: Δ diff, ✅ approve (on proposed rows) and ⇢ merge inline (instant `data-tip` tooltips), everything administrative under the row's ⋯ menu with text labels — 📤 propose/withdraw, ⚙ Protection (require-merge + required-approvals 0…3 segmented control + count-self-approval), ⛨ write-policy (tenancy only), × delete; a 🔒 marks write-policy-protected rows, an accented ⋯ marks a proposed / protected row. Propose/approve/require-merge/required-approvals are all open-core |
| Diff modal | click Δ in row | Diff v2: per-owning-fn groups with `+`/`−`/`±` markers, per-field `old → new` pairs, slot-name labels. Rows are clickable → navigate; the changed args are then ringed `Δ` on the canvas (sessionStorage `graphden.diffFocus` hand-off). Hovering a row/entry reveals 💬 — an ANCHORED comment on that element (`entity-name`+`entity-id` on the `:branch-comment` row; unanchored = the general thread below). A Suggestions section lists proposed CHILD branches of the source (reviewer suggestions) with Δ-view + one-click apply (= merge into the proposal), plus "+ Suggest a change" (fork-and-switch) |
| Compare mode | inline ◐ on a branch row (lit on the picked one; click again = exit), or "◐ Compare mode" in the diff modal's header | The editor-wide diff lens (`editor-diff-mode.js`): Explorer rows badge +/−/± vs the compared branch (namespace headers aggregate counts), changed fn CARDS and their args ring on the canvas, a `◐ vs <branch>` chip marks the mode. The chip opens the review cockpit — full Δ diff, 📤 propose-current-for-review, ⇢ merge-compared-in, and the TYPE LENS (added / modified / only-there toggles + "substantive only" (hides name/description-only edits) + "effects touched only"; persisted as `graphden.diffLens`). Groups whose change wires an effect-carrying fn in/out are annotated "effects touched: +time −db" (derived from the changed refs — see the registry known-gap). Fns existing only on the compared branch render as dimmed GHOST ROWS in expanded Explorer groups (click = switch there); collapsed groups keep the aggregate badge. Mode persists across reloads (localStorage). Annotations only — reads/writes stay on the current branch |
| Conflict modal | merge fails with `:reason :merge-conflict` | Per-entity source/target radio, retry merge with `:conflict-resolutions` |
| Fn-card ⌛ action | per fn-card row-actions | Version timeline + per-version `(N runs)` badge; click a row → inline-expand its executions (lazy fetch); `switch` button jumps to that version's branch |

## Push branches (cross-install review, 2026-08-23)

A branch is also the unit of CROSS-INSTALL flow: a local (offline)
instance pushes a snapshot of its work to the hub as a branch named
`push/<name>` (`graphden.cli push` → `POST /api/import/graph?target=…`),
and review happens with everything in this document — diff against main,
resolve conflicts, merge, with `forbid-invalid?` and the write-policy
applying as usual. Note on slash-named branches (`push/<x>`): a name with `/` cannot ride
a `/api/branches/:ref/*` PATH segment — address such branches by their
UUID (every `:ref` accepts one; the editor's row ops do this
automatically via `data-branch-id`). The convention rests on machinery,
not new entities:

- the push branch is created with the pushing principal stamped as
  `owner-id` and the `owner` write-policy, so only its author updates it;
- a re-push is a fresh snapshot of the same branch (`?prune=true` —
  deletions travel);
- the reverse direction lands the hub's main locally as `hub/main`
  (`graphden.cli pull`), merged with the same local merge flow;
- the same flow is served in-editor: with `GRAPHDEN_HUB_URL` +
  `GRAPHDEN_HUB_TOKEN` set on the local instance, the branch popover's
  **Hub** section drives `POST /api/sync/push` / `POST /api/sync/pull`
  (registry package; `GET /api/sync/status` is the reveal probe). The
  hub coordinates are server config only — never caller-supplied, so
  the instance's hub bearer can't be pointed at an attacker's host;
- `branch-local?` fns (ports, cron, vault paths) never propagate through
  the merge, so local runtime wiring can't leak into the hub's main —
  exactly the same guarantee in-instance branches already have.

## Protected branches (Stage 1, 2026-08-15)

A branch may carry a `write-policy` (nullable text on the `:branch`
identity row, next to `owner-id` — the creating principal's stable
`:user-id`, stamped whenever one is bound):

- `nil` / `"open"` — anyone the ordinary namespace grants admit
  (the default; behaviour unchanged).
- `"owner"` — only the branch owner writes; the org's
  `:manage-grants` holders (and the org owner, via owner-implies-all)
  pass too, so a departed owner can't fossilize a branch.
- `"admins"` — `:manage-grants` holders only.

"Write" covers every content mutation ON the branch — the
version-plane rows all carry `:branch-id`, so edits, creates,
deletes AND merges INTO the branch hit the same check. Enforcement
lives in the tenancy addon's `authorize-writer` (violations throw
`:authz/branch-protected` → 403); core stores the fields, serves the
API and renders the UI, but without principals the policy is inert —
which is why the editor shows the ⛨ / Advanced affordances only under
`body.gd-tenancy`. Flipping a policy (or deleting a protected branch)
is itself gated on owner / `:manage-grants`.

## Protected branches (Stage 2 — push only via merge, 2026-08-23)

`write-policy` above answers **who** may write a branch and is
enforced by the tenancy addon. Its sibling `require-merge?` (nullable
boolean on the same `:branch` identity row) answers a different,
open-core question: **how** the branch may change at all.

- `nil` / `false` — direct writes allowed (the default; unchanged).
- `true` — the branch accepts changes **only via merge**, the
  GitHub-style "push only via merge requests" toggle. Any DIRECT
  content mutation (create / update / delete of a versioned graph
  entity through the editor, `/api/entities/*`, or the MCP upsert
  path) is refused with `:branch/merge-required` → **409** and the
  message *"This branch accepts changes only via merge…"*. A **merge
  into** the branch is exempt — that is the entire point — so the flow
  becomes: work on a child branch, then merge it in.

Unlike `write-policy`, this needs **no tenancy addon**: enforcement
lives in `VersionedStorage` itself (`assert-not-merge-protected!`,
armed per-request by `branch_router` via `*enforce-require-merge?*`;
merge runs outside that arming, so it is structurally exempt). The
editor surfaces it as an always-visible 🔀 toggle on each branch row
in the branch popover (dim = off, accented = on), hidden only for a
read-only viewer (`gd-no-write`). Flip it over HTTP with
`POST /api/branches/:ref/protect` `{require-merge: true|false}`, or at
create time with `POST /api/branches` `{require-merge: true}`.

> Merge invalidation footnote: the post-merge graph-cache invalidation
> targets the merge's TARGET ctx. It captures the active router on the
> request thread (dynamic/per-thread router state does not convey to
> the raw post-commit thread), so a merge into a NON-main protected
> branch becomes visible immediately — see
> `merge-post-commit!` in `app/branches/impls.clj`.

## Change proposals (Phase A — review handoff, 2026-08-23)

`require-merge?` above forces changes onto a *side* branch; a **proposal**
is how that side branch is handed to a reviewer. A nullable `:review-state`
on the `:branch` row (nil ≡ ordinary working branch; `"proposed"` ≡ its
owner submitted it for review into its `base-branch-id`) carries the async
handoff — no separate merge-request entity. The reviewer's proposal list is
simply the branches whose `:review-state` is `"proposed"`.

- Toggle over HTTP: `POST /api/branches/:ref/propose` `{proposed: true|false}`
  (body absent ≡ `true`); the state is surfaced by `as-json-branch` and in
  `GET /api/branches`.
- OPEN CORE — no principals needed; WHO may propose/withdraw is the same
  authorize-writer call as any branch-row write (its owner). Enforcement of
  the setter lives in `set-review-state!` (`app/branches/impls.clj`).
- Editor: an always-visible 📤 toggle per branch row in the branch popover
  (accented when proposed), hidden only for a read-only viewer.

## Review policy (Phase C — configurable approvals, 2026-08-23)

A proposal is *reviewed* by recording approvals against it and gating the
merge on their count — configurable per **target** branch, GitHub-style.

**Policy fields** (nullable, on the merge-TARGET `:branch` row;
`:required-approvals` / `:approver-ids` default off — but see the
`:allow-self-approval?` default below):

- `:required-approvals` (int) — how many valid approvals a proposal needs
  before a merge INTO this branch is allowed.
- `:allow-self-approval?` (bool) — DEFAULT (nil) is **TRUE**: the proposal
  author's own approval counts, so a solo user / small team isn't locked
  out. Set it explicitly **false** for genuine four-eyes review — then the
  author's own approval does not count (GitHub "require review from someone
  else"). (`merge.core/self-approval-allowed?` is the single source of the
  nil→true default, shared by the gate and the status projection.)
- `:approver-ids` (jsonb list of `:user-id`) — an explicit reviewer
  allow-list. When non-empty it is **RESTRICTIVE**, not additive: ONLY
  those users (∪ org-admins, as an escalation unlock) may approve —
  regardless of `:write-policy`. This is GitHub's "require review from
  these users", and the whole point of naming reviewers: an ADDITIVE list
  OR'd with the ⚙-menu's open write-policy default restricted no one, so a
  named-reviewer requirement was silently a no-op. With `:approver-ids`
  empty, "who may approve" falls back to the target's `:write-policy`
  roles (owner / org-admins / open).

**Approvals** are `:branch-approval` rows (one per approval; mirrors
`:branch-merge`): `{source-branch-id, target-branch-id, approver-id,
content-stamp, created-at}`. `content-stamp` is the source's content
fingerprint (`merge.core/branch-content-stamp` — count + max version
`created-at`) at approval time; a later edit advances it, so the approval is
**auto-dismissed as stale** (counted only while the stamp matches — GitHub
"dismiss stale approvals"). `target-branch-id` records the branch the
approval was AUTHORIZED for (the proposal's base at approval time); the
merge gate counts an approval only when this equals the ACTUAL merge target,
so approvals gathered on a proposal off an open decoy branch cannot be
redirected to satisfy a merge into a protected one.

**The merge gate** (`merge.core/validate-approval-policy!`, called on the
live merge path right after the `forbid-invalid?` gate) counts DISTINCT,
non-stale approvals that are **bound to the actual merge target**
(`target-branch-id` = current branch) and — when the target sets a
restrictive `:approver-ids` — **in that allow-list**, excluding the author
unless self-approval is allowed, and throws `:branch/approval-required`
(409) when short. WHO may approve is enforced when the approval is WRITTEN
(`approve-proposal!` — restrictive `approver-ids` else target write-policy),
and re-verified against target + allow-list at merge time, so a policy that
tightened after approvals were gathered isn't satisfied by a
now-unauthorized or wrong-target approval.

**HTTP**: `POST /api/branches/:ref/review-policy {required-approvals,
allow-self-approval, approver-ids}`; `POST|DELETE /api/branches/:ref/approve`
(record / withdraw the caller's approval); `GET /api/branches/:ref/approvals`
→ `{required, have, satisfied, approvers:[{approver-id, stale}]}`.

**Comments**: each proposal carries a review-comment thread —
`:branch-comment` rows (`{source-branch-id, author-id, body, created-at,
entity-name?, entity-id?}`, org-scoped like approvals, cascaded on branch
delete). `POST|GET|DELETE /api/branches/:ref/comments` (`{body}` to add,
optionally `{entity-name, entity-id}` to ANCHOR the comment to one diffed
element — fn / fn-slot / binding / binding-list-item; a half-anchor or
unknown kind is a 400; `{id}` to delete — the author's own only). The
editor renders anchored comments as inline threads under their diff
row/entry (💬), unanchored ones as the general thread below the diff —
the conversation lives next to the change it reviews.

**Editor** (branch popover, open-core): a ✅ **Approve** button on proposed
rows (with an `n/N` progress badge), a "N proposals awaiting review" inbox
header, and a ⚙ protection menu per row (require-merge / required-approvals
/ count-self-approval). `approver-ids` is the only policy field still
API/MCP-only (advanced, rarely changed); a partial `/review-policy` POST
(the ⚙ menu) leaves it untouched (`:keep` patch semantics). Without the
tenancy addon there are no principals, so on a solo self-host the flow
degrades to "propose → self-approve → merge". A merged proposal's
`review-state` clears automatically, dropping it off the review list.

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
| `src/graphden/crud/branches.clj` | Two read-side helpers only: `base-storage` + `resolve-branch-ref` (the list / create / delete / merge / diff / conflicts orchestration now lives in graph fn-defs over atomic base-fns) |
| `resources/packages/app/branches/` | HTTP fn-defs + impls over the atomic base-fns (`:create-branch!` / `:delete-branch!` / `:diff-branches` / `:detect-conflicts` / `:merge-branch!`) |
| `src/graphden/system/demo_branches.clj` | Idempotent demo seeder + `:apply-mutation!` multimethod |
| `resources/packages/app/editor/editor-branches.js` | Branch chip + popover + fetch wrap + conflict-modal overlay chrome & radio/apply lifecycle |
| `resources/packages/app/editor/fns.edn` (`:_partial-mc-*`) | `POST /partials/merge-conflicts` — the conflict-modal card rendered as hiccup (route `:partial-merge-conflicts`) |
| `resources/packages/app/editor/editor-fn-versions.js` | `⌛` history popover for fn-card |

## Subtleties worth knowing

### Parent-set edits require a converged fn (gated)

`:parent-ids` is a `:ref-many` junction on the IDENTITY row — it is NOT
versioned, so a re-parent is visible to EVERY branch the moment it
commits, while the re-parent cascade's binding migration writes version
rows on the request branch only. Ungated, a re-parent from a feature
branch left every other branch resolving NEW parents over OLD bindings
(bindings whose slots fell out of the new inheritance closure silently
unbind — prod behaviour changes with no prod-visible edit).

`validation/reparent-cross-branch-rej` therefore rejects a parent-set
change whenever any OTHER branch holds its own live `:fn-version` /
`:binding-version` / `:fn-slot-version` rows for this fn (the reject
names the diverging branches — merge or delete them first; off-root it
additionally points at the root branch as the place to converge). The
two allowed shapes are exactly the non-corrupting ones:

- the ROOT branch (`:base-branch-id nil`), every other branch converged
  on this fn;
- a fn BORN on the request branch that no other branch has ever
  versioned — nobody else resolves it, so nothing can desync. This is
  the branch-isolated tutorial's whole flow (create a fn on the
  `tutorial-*` scratch branch, then assign its parent).

Parent-PRESERVING updates and non-versioned storages are unaffected.
This is option (b) of the two coherent designs, now DECIDED as the
permanent semantic — the alternative (a), versioning the parent-set
itself, is rejected (the parent-set defines the slot closure, and the
closure is identity; branch-varying closures would re-architect
fn-slot/MI/free-args and put version resolution on the compile hot
path). Full rationale + the copy-on-write revisit door:
[ADR-parent-set-identity.md](adr/ADR-parent-set-identity.md).

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

### Asset overrides are branch-scoped (`:resource-override`)

`:resource-override` (an in-DB override of a shipped frontend
asset — `path → content`) IS in `entity-config`, so it resolves
per branch like the fn graph: a fork inherits the parent's
overrides, a merge carries them, and a soft-delete on a branch
reverts that path to the classpath baseline on that branch only.
It is the first versioned entity that is not part of the fn
graph. See [PHILOSOPHY § UI as Graph](PHILOSOPHY.md#ui-as-graph--two-step-roadmap).

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
  via `recon/restart-services-on-branch!` (wired into the merge's
  `:merge-post-commit!` step as well, so post-merge cron loops pick
  up the fresh closures).
- Legacy rows without `:branch-id` fall back to the reconciler's
  base ctx (= main behavior), so the migration is transparent.

Open gaps: port allocation is OS-level (two branches binding
`8080` fight; the loser records `:start-failed-at`); cron
collision detection across branches isn't implemented; the
running-atom is a single in-process map (no multi-pod
coordination beyond the existing advisory locks).

### `:forbid-invalid?` — branch merge policy (error-tolerance Phase 5)

The `:branch` row carries a nullable boolean `:forbid-invalid?`
(non-versioned entity — a plain column, no version mirror). When set
on the merge TARGET, `versioning.merge.core/validate-branch-policy!`
(run inside the live `:merge-branch!` atomic core after the target switch, and
by `validate-merge!`) refuses the merge while recorded type
diagnostics (`graphden.types.diagnostics`) exist on either the source
or the target branch — `:merge-protection-violation`, 409, message
naming the broken fns. The store is DERIVED (in-memory): the gate
judges what is recorded, absence = allow. Set at create time via
`POST /api/branches` `{forbid-invalid?: true}`; no editor UI yet
(backend only — the branch popover may expose it later).

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

- **The rich-types registry is process-global, not branch-scoped.** It
  is id-keyed and last-compile-wins: compile a branch's context and its
  entries land in the same registry every other branch reads, so
  `/api/types` (and the type/effect chips it feeds) can show a
  cross-branch union — a fn that exists only on a feature branch
  appears in `main`'s snapshot, and an effect set reflects whichever
  branch compiled last. Verified 2026-08-31 (fn created only on a
  branch is present in `/api/types` under `main`). Branch-scoping the
  registry (per-branch snapshots + `*rich-types-override*`-style
  binding at serve time) is a real subsystem change — deliberately NOT
  attempted alongside the diff-v2 work. Until then, cross-branch
  comparisons must not be built on `/api/types`; compare mode's
  "effects touched" therefore derives from the diff's changed REFS
  (whose targets' effect sets are branch-independent in practice), not
  from registry comparison.

- **Merge is one-hop (non-transitive) — and REFUSES rather than silently drops.**
  A merge of source `S` into target `T` transfers only the versions `S` OWNS
  (its own version rows since the last merge of `S` into `T`) — not content `S`
  merely *inherits* from an intermediate ancestor `R` (`S.base = R`) or a branch
  `S` itself merged, that `T` does not share. A by-reference merge (`branch-merge`
  record) surfaces only `S`'s own rows, so that inherited content cannot be
  carried. Rather than lose it silently, the merge is **blocked** with
  `:merge/inherited-content-not-transferable` (409): `untransferable-inherited-
  entities` (built on the resolved-view `diff-branches`) lists the entities that
  would be dropped, and the merge refuses. **Workaround: merge the intermediate
  branch (`R`) into `T` first, then merge `S`.** The common cases never trip it —
  a branch forked off `T`, or a sibling of `T` off a shared ancestor, shares all
  its inherited content with `T`, so nothing is dropped. Making merge
  *transitive* (walk the source's own ancestor/merge closure during resolution +
  detect conflicts on inherited rows) is a deliberate larger change to the
  resolution backbone, deferred as a future enhancement — the block makes the
  current one-hop model SAFE in the meantime (guard added 2026-08-25).
  (Re-merging the SAME source is also safe: a re-merge carries only the source's
  changes SINCE the prior merge, so an unchanged re-merge cannot revert a target
  edit made in between — `merge-candidates-from-cache`'s per-source eligible
  window, regression-tested by `re-merge-does-not-silently-revert-target-edit`.)
- Per-branch ctx cache is LRU-bounded (`default-max-cached-branches` = 16,
  `evict-lru-if-full` keyed on `:last-used`); tune via the
  `GRAPHDEN_MAX_CACHED_BRANCHES` env var (read by `:exec/branch-router`
  into `create-router`'s `:max-size`).
- `resolve-branch-id` re-reads AFTER its ref-cache write (one extra read
  per cache miss only). This closes a TOCTOU: without the recheck, a
  branch deleted between the first (uncached) resolution's DB read and
  its cache write leaves the dead id cached, because the delete's
  value-sweep has already run. A delete landing before the recheck is
  seen (entry dropped, nil/new id returned); one landing after it sees
  the now-present entry and sweeps it. Covered by the
  `ref-cache-toctou-*` tests in `branch_router_test`.
- The `:exec/branch-router` is unit-tested at the dispatcher level
  (`branch-router-test`: header / query parsing, default fallback,
  unknown-ref rejection, invalidate). The full middleware-through-storage
  path — a request through the wrap into a per-branch ExecutionContext and
  back through the branch's storage view — is covered end-to-end by
  `graphden.integration.branches-lifecycle-test`: it dispatches through
  `br/dispatch` (the same closure http-kit feeds real `/api` requests into)
  with explicit `X-Graphden-Branch` headers, creates a branch, writes a `:fn`
  on it, and asserts the **isolation split** (the fn resolves under the
  branch header yet is absent on `main`), then diffs + merges. The socket
  layer itself (http-kit request parsing over TCP) is exercised separately by
  the BYO / remote-storage e2e tests and is not a branch-router concern.
