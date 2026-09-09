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
| editor **⚐ lint** lens + Inspector **Lint** section | `GET /api/lint` primes the lens; `POST /partials/inspector-lint/{suppress,restore}` — "Not an issue" / "Restore" write the branch's `lint-suppressions` const | 2026-09-04 |

## Rules

| Rule | Fires when | Severity |
|------|-----------|----------|
| `:duplicate-definition` | ≥ 2 named composed fn-defs have the same *shallow signature* — parents, canonical args (refs resolved to identities, literals as written), `:return-type`, `:lambda-params`, effects, `:branch-local?`. Names, namespaces and every `:description` are ignored. | warning at weight ≥ 3, info below |
| `:duplicate-after-expansion` | the same, over the *deep signature* — every ref to a `_`-private fn-def is replaced by that fn-def's own signature. Catches the same graph factored through differently-named helpers, or spread over two namespaces. Groups already equal shallowly are not repeated. | same weighting |
| `:unreferenced-private` | a `_`-private composed fn-def no fn-def references (parents, args, list items, type-row fields; string names count — the value-form / repr registries hand names out as strings). | warning |
| `:unreachable-private` | a `_`-private fn-def that IS referenced, but only from fn-defs no live root reaches — the rest of a dead cluster whose head is the `:unreferenced-private` finding. Live roots: every public fn-def, type-row and base-fn declaration, the by-name entry points, the platform's own rows. Deleting the head no longer leaves a trail of one new finding per round. | warning |
| `:shadowed-override` | a fn-def re-binds an arg to exactly the value its closest ancestors already bind (closest-fn-wins over the parent closure; two parents that disagree leave nothing to restate). A bare ref, a `{:value …}` / `{:ref …}` spec or a scalar literal counts; a type pin, a rename, a doc-only spec or a list binding (which appends to the chain) says something new and does not. One finding per fn-def, naming the args. | warning |
| `:fan-in-extract-parent` | ≥ 2 named fn-defs with the same parents bind ≥ 1 identical value — [PACKAGES.md § 1](PACKAGES.md#1-use-inheritance-to-eliminate-duplication-dry)'s extraction rule. Each fn-def's shared bindings (those a sibling repeats) are a candidate parent; the group is every sibling that binds all of it, reported once under its heaviest candidate. A group that is the same definition outright is `:duplicate-definition`'s. | same weighting as the duplicate rules |
| `:deep-hierarchy` | a chain of composed fn-defs `deep-hierarchy-depth` (6) or more levels above its base-fn, reported at the chain's TIP only — [PACKAGES.md § 4](PACKAGES.md#4-hierarchy-depth-guidelines): 6+ needs a justification. The MCP surface's tool envelopes sit at 6–7 with a concept per level, so the info tier starts there and a warning needs `deep-hierarchy-warning-depth` (8). | info; warning at 8 |

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

What the lint deliberately does NOT re-check, because another layer
already rejects or records it — a rule here would be a second copy of
the same verdict:

- **a service whose fn has a start-blocking free argument** — refused at
  service-create time (`schema/services`, [SERVICES.md](SERVICES.md));
- **effects the closure produces that `:expects-effects` does not
  declare** — `:types/expects-effects-drift`, a hard reject at package
  sync and a recorded diagnostic (the ⚠ lens) on a user write;
- **a secret flowing into a plain-text sink** — the asymmetric subtyping
  of `[:secret T]` ([SECRETS.md](SECRETS.md)), a type error, not a style
  finding.

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

The 2026-09-09 sweep that shipped the fan-in rule found 24 warning
groups (0 after): mostly `:zipmap` record constructors written per
site with the same `:keys` (error envelopes in branches / secrets /
prefs / registry, the execute and value-form parse records shared by
the HTTP body parse, the editor previews and the MCP tools — now
`:execute-parsed-record`, `:value-form-record`, `:create-branch-record`),
six hand-built `{:status 200 :headers …}` responses that were
`:json-ok-response`, an `:if` test repeated across two branches
(`_dispatched-merge`, `_mk-on-executor`, `_mcp-with-read-fn`), two
`div` popover bodies that became `:plain-div` children, and the diff
routes' guard chain, which the two routes now `{:append …}` their apply
clause to. `:shadowed-override` and `:unreachable-private` fired
nothing on the corpus; `:deep-hierarchy` files the MCP tools as info.

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
- **Per-branch, on read.** `lint.graph/lint-branch` lints the per-ctx
  graph snapshot and memoises the result **per branch** (`branch-id →
  snapshot object + suppression set + findings`, a small LRU): a read
  recomputes only when that branch's snapshot object was replaced by a
  write, and two branches open side by side — or two orgs on one
  executor — no longer evict each other on every read. Incremental
  re-signaturing (a write to F re-signatures F and its referrers) is
  the next step if per-tenant graphs grow an order of magnitude; today
  a full pass is sub-second and the trigger for it has not arrived.
- **Not a write-time SQL check.** The write-time guards that exist —
  the cycle CTE, the resolved-view name / position collisions under
  advisory locks — are Clojure over SQL, and they exist for what must
  be *rejected*. The lint is advisory: a copied fn-def is the normal
  first step of "copy, then change the copy", a fresh `_helper` is
  unreferenced until the next write points at it, so refusing the row
  would break ordinary editing. Its subject is also not a row: a
  signature is the fn-def *after* branch resolution (version rows,
  soft-deletes, merge-on-read) and *after* the whole write unit landed —
  a per-row check sees half a fn-def and every branch's rows at once.
  One engine over EDN fn-defs serves the corpus (no DB) and the live
  branch alike; a SQL twin would be a second implementation of the
  same rules.

## The editor: lens + Inspector

Findings reach the author as an Explorer **lens** and an Inspector
**section** (the Lint tab shipped 2026-09-03 in a drawer under the
canvas and was retired the next day, once the lenses landed; the Tests
and Debug panels followed the same day and the drawer is gone).

- **⚐ lint lens** — `GET /api/lint` is the JSON read (same base-fn as
  the section), cached client-side (`editor-problems.js`)
  and re-primed per graph load, after runs and after an Inspector
  action; the chip counts findings, a namespace row counts its fns
  with findings, a fn row and its card carry `⚐N`. The response carries
  a weak `ETag` over the JSON and `Cache-Control: no-cache`, so a
  re-prime after a run that changed nothing is a conditional request
  answered `304` (`api-lint-handler` — an `:if` over `:header-get
  "if-none-match"`, `:not-modified-response` on a match).
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
