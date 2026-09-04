# Graph lint — structural linters for the graph

The type checker asks "does this binding fit its slot". The graph
lint asks the questions a code reviewer asks of a *composition*: is
this fn-def a copy of one that already exists, is this private helper
still used, is this the same graph as that one once the helpers are
inlined. It runs over EDN-shape fn-defs — the form the package loader
produces and `crud/type-check/reconstruct-fn-def` rebuilds from DB
rows — so one engine serves both authoring worlds.

| Where | What | Since |
|-------|------|-------|
| `src/graphden/lint/core.clj` | the pure engine — `lint` over a fn-def seq | 2026-09-03 |
| `src/graphden/lint/corpus.clj` | `bb graph-lint` — the first-party fns.edn corpus, no DB, ~10 s | 2026-09-03 |
| `src/graphden/lint/graph.clj` | the live branch — graph snapshot → fn-defs → `lint`, memoised per snapshot | 2026-09-03 |
| editor diagnostics drawer, **Lint** tab | `GET /partials/lint`; "Not an issue" / "Restore" write the branch's `lint-suppressions` const | 2026-09-03 |

## Rules

| Rule | Fires when | Severity |
|------|-----------|----------|
| `:duplicate-definition` | ≥ 2 named composed fn-defs have the same *shallow signature* — parents, canonical args (refs resolved to identities, literals as written), `:return-type`, `:lambda-params`, effects, `:branch-local?`. Names, namespaces and every `:description` are ignored. | warning at weight ≥ 3, info below |
| `:duplicate-after-expansion` | the same, over the *deep signature* — every ref to a `_`-private fn-def is replaced by that fn-def's own signature. Catches the same graph factored through differently-named helpers, or spread over two namespaces. Groups already equal shallowly are not repeated. | same weighting |
| `:unreferenced-private` | a `_`-private composed fn-def no fn-def references (parents, args, list items, type-row fields; string names count — the value-form / repr registries hand names out as strings). | warning |

**Weight** is the number of bound values a shared structure carries:
a ref or a non-nil literal counts one, an inline fn-def counts one
plus its own args, a rename (`{:as …}`), a type pin and `:default
nil` count nothing. `graphden.lint.core/warning-weight` (3) is the
line between "a copied graph" and "two accessors that happen to read
the same key" — `{:parent :get :args {:coll {:as :row} :key {:value
:id} :default nil}}` written twice is the let-rule's separate child
per code path, not copy-paste, and stays info.

Only warnings reach the editor — the info tier is calibration
output for the corpus gate, not a problem to put in front of an
author. There is no `private-alias` rule: a private fn-def that only
renames its parent is the let-rule's "separate child per code path",
and listing it would be noise dressed as a finding.

What the duplicate rules deliberately do **not** treat as findings:

- **Generated rows.** `_anon-<hash>` / nameless fn-defs are
  per-use-site by design (`graphden-fn-design` § 2).
- **Type-rows.** Two record types with the same shape are nominal
  types; the shape-dedup for inline composites is `anonymous-hash`.
- **Pure aliases** (`{:name :_merge-body :parent :parse-json-body}`
  ten times over) — weight 0. Naming a parent per handler is the
  sanctioned way to give each code path its own child.

## Reading a finding

```text
warning duplicate-after-expansion  3 fn-defs are the same graph once their
  private helpers are expanded (17 bound values): app.editor/_pstats-day-avg-cell,
  app.editor/_pstats-fn-avg-cell, app.editor/_pstats-org-avg-cell — extract a
  shared parent and inherit it
```

The fix is the DRY rule from [PACKAGES.md § 1](PACKAGES.md#1-use-inheritance-to-eliminate-duplication-dry):
keep one definition and either reference it (when the use sites are
different requests / different `:map` callbacks — the value is not
shared at runtime, only the definition is) or extract it as a parent
and inherit it (when each site must stay its own entity). A
cross-namespace duplicate gets a **public** name in the lower-level
namespace — a `_`-private referenced from another namespace is no
longer private (`graphden-fn-design` § 10).

The 2026-09-03 sweep that shipped the lint brought the corpus from
131 warnings to zero. The shapes it found, for calibration:

- the per-day / top-fns / by-org usage tables each carried their own
  copy of the 15-fn row-cell subtree (`_pstats-{day,fn,org}-*`);
- the asset editor's three handlers each re-derived `?path=`, the
  known-path check and the classpath baseline (238 bound values twice);
- form parsers (`_parse-{bli,bnd,fn-slot,ns}-form-*-fragment`) and the
  update handler's apply stage re-deriving what its parse stage
  already had;
- five `<span hidden>` placeholders, two `deploy-config :hub-url`
  reads, three "`(str (:name body))`" wrappers per handler.

## Gate

`bb graph-lint` is in `bb ci` (group `:clj`, diff-scoped to
`fns.edn` / the engine / the reachability registry). Warnings fail
it unless listed in `graphden.lint.corpus/allowed-warnings` with a
reason; an allowlisted finding that stops firing fails it too — the
same two-way contract as the type-check sweep's allowlist, so the
list cannot rot. Exemptions for `:unreferenced-private` come from
`tools/graph-reachability.edn` (`:roots`, `:registry-fns`,
`:vocabulary`) — the fn-defs `src/` runs by name.

The lint is local (each rule reads one fn-def and its direct
references); the *global* dead-code question — "is this reachable
from any root at all" — stays with `tools/reachability_audit.clj`,
which BFS-walks from the same registry.

## Feasibility notes (why it is shaped this way)

- **Size.** The whole first-party graph is ~4.3k fn-defs / ~6.6k fn
  rows in a full DB; both signatures are one memoised DAG pass (ref
  edges cannot cycle — the write-time constraint guarantees it), so a
  full lint is sub-second after load. Nothing here needs a background
  worker, a separate instance, Postgres full-text search or `pg_trgm`
  — those buy fuzzy *name* similarity, which is not a structural
  question. (Both ARE available on the managed Postgres the cloud
  runs, should a "similar description" hint ever be wanted.)
- **No stored procedure, no stored verdicts.** A stored flag drifts
  from the graph it describes; `types.diagnostics` already sets the
  rule — derived, in-memory, recomputed on write. The lint follows it.
- **Per-branch on the DB (planned).** `reconstruct-fn-def` rebuilds one
  fn-def from rows; the branch-wide input is the four resolved-view
  reads (fn, fn-slot + slot, binding, list-item) the sync sweep
  already performs. Store findings next to the type diagnostics
  (`{branch-id {fn-id [finding …]}}`), recompute on the same triggers
  (package sync sweep; the CRUD post-mutation hook, incrementally: a
  write to fn F re-signatures F and its referrers, and re-buckets those
  signatures), never persist. Cost per write is the size of F's
  referrer set, not the graph.

## The editor: lens + Inspector

Findings reach the author as an Explorer **lens** and an Inspector
**section** (the Lint tab shipped 2026-09-03 in a drawer under the
canvas and was retired the next day, once the lenses landed; the Tests
and Debug panels followed the same day and the drawer is gone).

- **⚐ lint lens** — `GET /api/lint` is the JSON read (same base-fn as
  the section), cached client-side (`editor-problems.js`)
  and re-primed per graph load, after runs and after an Inspector
  action; the chip counts findings, a namespace row counts its fns
  with findings, a fn row and its card carry `⚐N`.
- **Inspector › Lint** — the rows naming the selected fn: rule, member
  fns as `#hash` links, the engine's message, **Not an issue**; the
  branch's hidden entries naming the fn with **Restore**. The two
  actions POST to `/partials/inspector-lint/{suppress,restore}` which
  render the section back.

**Suppression lives in the graph.** "Not an issue" POSTs the finding's
key (`rule` + the sorted member fn-ids); the handler appends
`{:rule :fn-ids}` to the value of the root fn `lint-suppressions` — a
`:const` created on first use through the ordinary CRUD write unit, so
it is versioned per branch, merges with the branch, and is visible and
editable on the canvas like any fn. The key is the member *ids*:
renaming a member keeps the suppression, adding a third copy is a new
finding. No new entity, no new table, no derived state persisted —
only what the author explicitly said.

The flow is graph composition (`app/editor-panels/fns.edn` `_plint-*`
for the store and the entry, `app/editor-provenance/fns.edn`
`_insp-lint-*` for the section) over one base-fn,
`:branch-lint-warnings`, whose impl is a single
`lint.graph/lint-branch` call. The section is parametrised by
`:suppressed` so the POST handlers render from the list they just
wrote — the store's own thunk was forced before the write
(ADR-thunk-once). Every reader lints the per-ctx graph snapshot: a
write splices it inline before its response returns, and a load-on-miss
that a write outran is discarded rather than installed
(`executor.context/fill-graph-cache!`, epoch-guarded), so a read right
after an edit is the post-edit graph.
