# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Are you a pooled feature agent? (read before editing)

Parallel feature work happens in **isolated git worktrees** behind a serialized
merge queue (`dev/wtq/`). An agent's operating contract is delivered as its
launch *message* — so `/clear` and context compaction **destroy it, while this
file survives**. If you have no memory of a contract, re-orient here before you
touch a single file.

**Am I a pooled agent?** Yes if either holds:

- your working directory is under `graphden-wt/<name>/` (one worktree per agent), or
- `git branch --show-current` prints `feature/<name>` and `bb wt list` shows `<name>` in the pool.

If neither holds, you are in the **main checkout on `develop`** — you are *not*
claimed, and the first rule below still binds you.

**Full contract: [dev/wtq/AGENT.md](dev/wtq/AGENT.md)** — read it before editing. The rules most
often violated after a context loss:

| Rule | Why it matters |
|------|----------------|
| **Claim before you edit** — `bb wt claim <name> "<summary>"`, then `cd` to the printed WORKTREE and work only there | Editing the main checkout on `develop` corrupts the shared baseline every other agent branches from |
| **Stay in your worktree** — never `cd` into another agent's worktree, never edit `develop`, never touch another agent's branch | Agents change unrelated files in parallel; your view of the repo is your branch only |
| **`bb ci` is your only local *test* command; `bb wt up` is your only live *instance*** | `bb rebuild` / `bb deploy` / `bb test-integration` / `bb test-e2e` / `bb coverage` drive the SHARED stack (`graphden-executor` on :9002) and the shared image tag that `bb test-e2e` boots — from a worktree they steal the demo and make another agent's suite test your binary. They belong to the landing gate, behind its lock. `bb wt up` gives you an isolated stack (own containers, volumes, image, ports) to see your change run |
| **Finish the job yourself** — a complete, `bb lint`-green feature goes through `bb wt merge`, then `bb wt drop`, without asking | Neither step can lose work: the gate cannot advance `develop` on a red result, and `drop` refuses an unmerged branch. Asking to merge a finished feature is ceremony, and it stalls a serialized queue on a human's reply. (A full local `bb ci` before queueing is optional solo — the gate re-runs it on the merged result; go `bb ci`-green first only when `bb wt list` shows other claimed agents.) Stop and ask only when a real decision is yours and the answer changes what you build |

**Recovering the contract and your place in it** — the branch, the worktree and
the task spec all live on disk, so nothing but the *prompt* is lost with the
context:

```bash
bb wt list             # every agent: branch, drift vs develop, last gate RESULT
bb wt status           # same, plus the recent gate runs
bb wt task <name>      # the task spec you were handed
bb wt log <name>       # full transcript of your last gate run
bb wt watch <name>     # follow a running gate: 60s ticks until RESULT, then print it
bb wt bootstrap        # reprint the discussion-phase (nameless-agent) launch prompt
bb wt kickoff <name>   # reprint the launch prompt for an already-claimed agent
```

## Design Principles (MUST READ)

**Every change must improve at least one principle without violating others.**

| # | Principle | Description |
|---|-----------|-------------|
| 1 | **Correctness first** | No feature justifies bugs. Comprehensive tests required. |
| 2 | **Minimal entities** | Resist adding new entity types, fields, or edge types. Each addition increases complexity everywhere. |
| 3 | **Explicit over implicit** | Behavior must be visible in graph structure. No magic, no context-dependent semantics. |
| 4 | **DRY** | Never define the same thing twice. Use inheritance (parent-id) and result caching for reuse. |
| 5 | **Expressiveness parity** | Can do everything classical languages can. No "sorry, you can't do that." |
| 6 | **No unnecessary expressiveness** | Don't add features just because we can. |
| 7 | **Locality of changes** | Changing one node shouldn't require changes elsewhere. |
| 8 | **Incrementality** | Adding features shouldn't require rewriting existing ones. |

**Before making changes, ask:**

- Which principle does this improve?
- Does it violate any other principle?
- Is there a simpler way?

See [docs/PHILOSOPHY.md](docs/PHILOSOPHY.md) for full rationale and module mapping.

## Project Overview

Graphden is a visual functional programming environment where functions and their compositions are stored as a graph in a database.

**Key concepts:**

- **Code = Graph in DB** — functions and arguments stored as entities
- **Lazy Execution** — delay-based evaluation, only computes what's needed
- **Storage backend** — PostgreSQL with recursive CTE for graph traversal

**Core entities** (slot/binding model):

- `fn` — function entity OR type-row. Inheritance via `parent-ids` (many-to-many).
  - empty `parent-ids` + `return-type-fn-id` set → base-fn (Clojure impl)
  - empty `parent-ids` + no `return-type-fn-id` + slots/refine/list → type-row
  - non-empty `parent-ids` → composed fn
  - `name=nil` → anonymous (deduped via `anonymous-hash`)
  - `branch-local?` — identity-level monotonic-OR flag. When set on a fn
    OR any ancestor in the `parent-ids` closure, the fn's version rows
    DO NOT propagate across branches on merge (runtime-config
    semantics — web-server port, vault path, cron schedule).
    Sync-time guard rejects descendant `:branch-local? false` when an
    ancestor is true. Seeds: `:http-server`, `:secret-leaf`, `:schedule`,
    `:env`. See [docs/VERSIONING.md § branch-local](docs/VERSIONING.md).
- `slot` — atomic `(name, type-fn-id)` pair, immutable post-create. Shared across fns through `fn-slot`.
- `fn-slot` — junction `(fn-id, slot-id, position)`. "Which slots does this fn expose, in what order."
- `binding` — per-`(fn-id, slot-id)` customization: `value`, `ref-fn-id`, `rename-to`,
  `type-override-fn-id`, `terminal`, `list-append`, `list-closed`, `description`.
- `binding-list-item` — sequence content under a list-typed binding, ordered by `position`.
- `service` — desired-state row "keep THIS fn running". `branch-id` scopes
  it to a per-branch `ExecutionContext` so the same fn can run on dev +
  prod simultaneously. NOT versioned. See [docs/SERVICES.md](docs/SERVICES.md).

## Core Concept: Inheritance Model

A composed fn inherits its parents' slots through `parent-ids` BFS closure. Each
slot in that closure is exposed once at the descendant; bindings overlay closer-fn-wins.

**Base function (no parents, has impl):**

```
fn: add
  parent-ids: []
  return-type-fn-id: number   ; base-fn marker (always set; defaults to :any)
fn-slot: {fn-id: add, slot-id: nums, position: 0}
slot: {id: nums, name: "nums", type-fn-id: sequence}
```

**Composed function (parent-ids set):**

```
fn: add-10
  parent-ids: [add]     ; inherits add's :nums slot
binding: {fn-id: add-10, slot-id: nums, list-append: true}
binding-list-item: {binding-id: ..., position: 0, value: 10}
```

**Using the composed function:**

```clojure
;; add-10 inherits :nums and seeds it with [10]; caller may
;; extend the chain or override.
(execute ctx add-10-id {})  ;; → 10
```

**Key insight:** Slots are global identities (one-shot creation, immutable). Bindings
overlay them per `(fn, slot)` pair. Sequence content lives in dedicated rows so the
chain can be queried/indexed independently of scalar bindings.

## Documentation Map

| Document | Purpose | When to read |
|----------|---------|--------------|
| [docs/PHILOSOPHY.md](docs/PHILOSOPHY.md) | Design principles, rationale, module mapping | Before making architectural decisions |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Technical details, execution model, examples | When implementing features |
| [docs/PACKAGES.md](docs/PACKAGES.md) | Package system, module structure, loading | When adding base-fns or fn-defs |
| [docs/TYPES.md](docs/TYPES.md) | Type system design & semantics — type hierarchy, inference, narrowing, refinements, variants, control-flow narrowing (Phase #170 v1), canonical author `:type T` assertion sites | When working with arg types |
| [docs/TYPE_SYSTEM_DECISIONS.md](docs/TYPE_SYSTEM_DECISIONS.md) | ADR for the type system — current sweep-at-zero state, the root architectural tension (slots are global identities vs structurally-different flows), three alternatives (α/β/γ), why β was attempted + reverted, why #170 v2 + γ were rejected, outcome table | Before proposing a type-system change — read this first to avoid retrying paths that already closed |
| [docs/TYPE_CHECK_BACKLOG.md](docs/TYPE_CHECK_BACKLOG.md) | Historical ledger of per-failure closures during the sweep-to-zero work | When tracing the history of a specific type-check fix |
| [docs/adr/AUDIT-name-vs-id-resolution.md](docs/adr/AUDIT-name-vs-id-resolution.md) | id-vs-name resolution audit — the closure ledger: which internal name-resolution is FRAGILE (all fixed: root-branch, call-cache key, authz-subject P1, layout synth-slot) vs BENIGN / deliberate-by-design (type-alias registry, hybrid `fa`, anon-hash, export serialization) across executor/crud/storage/types/tenancy/layout; the CI-guard (`id_resolution_guard_test`) that regression-proofs it; and why re-keying the type checker was NOT done | Before proposing that an internal mechanism resolve/match/dispatch by NAME instead of id, auditing id-vs-name usage, or touching authz-subject / branch-root / sequence-slot / layout synth-slot identity |
| [docs/adr/ADR-versioning-vs-offtheshelf.md](docs/adr/ADR-versioning-vs-offtheshelf.md) | Why the bespoke branch/versioning system stays on Postgres and is NOT replaced by Dolt / Datomic / XTDB / temporal tables — the 3-layer decomposition (store+resolve vs domain merge policy vs live per-branch execution routing), the branches≠time-travel distinction, the Postgres coupling (DISTINCT ON, advisory locks, branch-scoped NOTIFY, RLS) a DB swap would forfeit, and the two follow-through findings (fork-point LCA fix; branch-chain-walk-kept-portable) | Before proposing to adopt an off-the-shelf versioned/temporal DB, replace `VersionedStorage`, or "just use git/Dolt/Datomic" for the branch model |
| [docs/PERF_BUDGETS.md](docs/PERF_BUDGETS.md) | The performance **regression gate** — why it counts structural events (registry full-clears, fixture bootstraps, SQL round trips) rather than timing them: every perf fix this repo shipped was a count that moved, while both attempts at a timing win measured as noise or worse. Covers `bb perf` / `bb perf-update` + the committed `perf/budgets.edn` reference set (the `bb visual` loop), what may be budgeted (ONLY counters invariant to test count) vs merely reported as trend, `pg_stat_statements` per-scenario SQL counting + why its warm-up and dbid filter are load-bearing, and the first per-NS fixture-vs-assertion readings (fixture cost is concentrated in 3 NSes, and the unit suite is ~1:1, not the 360:1 folklore) | Before adding any perf assertion, touching `graphden.util.counters` / `kaocha.plugin/perf` / `graphden.perf.*` / `scripts/perf.clj` / `perf/budgets.edn`, or when `bb perf` fails |
| [docs/PERF_NOTES.md](docs/PERF_NOTES.md) | Executor hot-path performance investigation — current measurements within budget, "smear not hot frame" diagnosis from 2026-05, two attempted point-fixes that didn't help (one made things slower), 4-step real-fix sketch held in reserve | Before allocating multi-commit performance work — re-benchmark first; the 2026-05 flame-graph predates eager-compile |
| [docs/LAYOUT.md](docs/LAYOUT.md) | Graph-editor layout pipeline (Stages 1–7) | When touching layout impl or editor frontend |
| [docs/CONSTRAINTS.md](docs/CONSTRAINTS.md) | Graph constraint specifications | When working with GraphConstraints |
| [docs/ERROR_CODES.md](docs/ERROR_CODES.md) | Error types reference | When handling errors |
| [docs/EXTENDING.md](docs/EXTENDING.md) | HOF semantics, custom storage, schema extensions | When extending below the package layer |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Implementation status, future plans | For project planning |
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | Integrant config, Aero tags | When configuring the system |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Docker, uberjar, environment | When deploying to production |
| [docs/EXECUTION.md](docs/EXECUTION.md) | Function execution feature: schema, HTTP API, cancel/TTL/UI | When touching `/api/execute*` or the editor's Run popover |
| [docs/SERVICES.md](docs/SERVICES.md) | Phase 1 service registry: `:service` schema (incl. `:cardinality` — `:singleton` advisory-lock-gated vs `:per-pod` listeners), reconciler (edge-triggered on CRUD/NOTIFY **plus** a level-triggered periodic tick for crash-failover + out-of-band drift), supervisor, HTTP API, legacy fallback + displacement, roadmap to cron | When touching `services/`, `:exec/service-reconciler`, or anything that needs to know what services are |
| [docs/SCALING.md](docs/SCALING.md) | Multi-executor fleet: why the shard key is the **org** and not fn-popularity, what multi-pod does (NOTIFY delta-invalidation, advisory-lock singletons + reconnect/reassert, per-pod listeners, cross-pod cancel, level-triggered reconcile tick), per-branch invalidation via `collect-branch-chain`, `:executor-orgs` predicate on the ctx, `421 Misdirected Request` off-shard, the **fleet-wide per-org quota**, and the full **external/BYO executor** (`graphden.byo` + `RemoteStorage` over HTTP + SSE relay/source + `:org.execution-mode`) — all SHIPPED; only a real BYO executor on a second physical machine (§ Still open) remains | Before touching `services/reconciler`, `system/branch_router` invalidation, `storage/{postgres/notify,remote/*}`, `system/sse`, `byo.clj`, `compile_runtime/read-graph`, or the `:executor-orgs` checks in `tenancy/{addon,app_router}`; and before proposing any distribution work |
| [docs/FLEET_RFC.md](docs/FLEET_RFC.md) | Dynamic fleet: load-based placement + rebalancing. **Phases 0-3 core SHIPPED** (`fleet.{placement,router,command,metrics,controller,packer,rebalance,control-loop,discovery}` + `:exec/fleet-controller` + Helm chart) — the fleet auto-places tenant cells + rebalances sustained imbalance under a leader-locked controller over a k8s StatefulSet with SRV membership. The **cell** (root fn + forward ref-closure) is the placement unit (services stay with the reconciler, NOT the fleet controller; an org app is one cell today; single-call distributed execution is a non-goal); load/evict reuses the live compiled-registry atom + `compile-subset`; footprint/start track = **CRaC-first, GraalVM shelved**; §9: graph **hot-reload is already shipped**, this adds placement not freshness. Still open: overlap-accounting + per-route split (T4.5, evidence-gated), scale-to-zero (T5.3, CRaC-gated) | Before proposing ANY dynamic-placement / autoscaling / rebalancing / hot-reload / native-image work, touching `fleet/*` or `:exec/fleet-controller`, or picking a k8s/Knative/serverless/CRaC substrate |
| [docs/FLEET_DEPLOY.md](docs/FLEET_DEPLOY.md) | Operational how-to for the dynamic fleet: `helm install deploy/helm/graphden`, the StatefulSet + headless-SRV-discovery + leader-locked controller model, HPA, controller tuning (`GRAPHDEN_FLEET_*`), and how a request forward-hops to its cell's holder | When deploying/operating a multi-pod fleet, editing the Helm chart, or wiring the `GRAPHDEN_EXECUTOR_ID` / `GRAPHDEN_FLEET_DNS` / `GRAPHDEN_INTERNAL_TOKEN` env |
| [docs/VERSIONING.md](docs/VERSIONING.md) | Branches surface — per-branch ExecutionContext routing, HTTP API (`/api/branches`, diff, merge, conflicts), editor UI (branch chip + popover + ⌛ history + conflict modal), demo seeder + env toggle, known gaps | When touching `system/branch_router`, `crud/branches`, `web.branch-router`, `app.branches`, `editor-branches.js`, `editor-fn-versions.js`, or the demo seeder |
| [docs/CLOSURE_CAPTURE.md](docs/CLOSURE_CAPTURE.md) | Closure-capture extension to the fn-graph model: call-site vs captured args, wrap-time capture contract, type-checker propagation, as-shipped commit map | Before touching `hof-wrap` / `hof-lambda-params` / `ref-free-args` / `free-arg-slot-map`; needed to understand why `:schedule` works |
| [docs/RECURSION.md](docs/RECURSION.md) | Graph-level recursion: Approach A (`:fix` Y-combinator) is SHIPPED (`core/recursion`, depth-guarded, used by `storage/branches` `:branch-chain`); Approach B (lazy ref resolution) is the road not taken. | When considering recursion-related work; runtime cycle/recursion model is in ARCHITECTURE.md § Part 3 |
| [docs/SECRETS.md](docs/SECRETS.md) | `:secret` information-flow type-marker — asymmetric subtyping (`T ⊆ [:secret T]` but NOT `[:secret T] ⊆ T`), per-base-fn `:return-type-rule` propagation (`taint-with-secret-if-tainted` / `wrap-with-taint`), executor-side `/api/execute` hide on `:secret`-marked return, editor "Result hidden" pane + history badge, current Secrets-panel admin UX (creating fn-defs with `parent :vault-get`), audit of which base-fns propagate vs not | When touching `types/core` secret-type code, adding a new base-fn that handles user data, marking a sink's slot as `[:secret …]`, or wiring secret-flow protection in a new place |
| [docs/PARTIALS.md](docs/PARTIALS.md) | Graph-native HTML partials: how editor popovers / panels get content from fn-defs returning hiccup at `GET /partials/*`; HTMX 2.x wiring (auth bridge + post-swap process); recipe for adding a new partial; list of common gotchas (`:parse-uuid` slot, JSONB keyword roundtrip, inline-anon limits, `fn-ref` in `:value` literal) | When migrating an editor JS module to server-rendered hiccup, OR when wiring a new popover from scratch |
| [docs/EDITOR_HTMX_MIGRATION_PLAN.md](docs/EDITOR_HTMX_MIGRATION_PLAN.md) | As-shipped reference for the row-actions partial: per-context (`col-header` / `cell` / `use-site-arg` / `root-row`) query-param matrix + JS dispatcher contract. Documents what shipped in Phase A (8 commits) and why Phase B/C/edge-label were deferred (server has no data the client doesn't). | When extending the row-actions partial OR considering another graphData-backed popover for migration |
| [docs/PACKAGE_DISTRIBUTION.md](docs/PACKAGE_DISTRIBUTION.md) | Distributing packages: the three module kinds (Type-1 fns-only / Type-2 impl+fns / Type-3 core-swap), the in-graph registry (publish / reference-install + pin / update-rollback ref-rewrite / fork), external Type-2 packages via `resources/executor-packages.edn` + a git coord, whole-graph export (`GET /api/export/graph`), the swap-seam matrix, and § 15.1 **as-built repo map** (what stays in the monorepo vs `graphden-{mathx,examples,cloud}`) | When touching `app/registry`, `packages/{loader,export}`, `executor-packages.edn`, `external-packages/`, or deciding whether something belongs in its own repo |
| [docs/PLATFORM_PLAN.md](docs/PLATFORM_PLAN.md) | Multi-tenant platform ADR — orgs / RLS / grants, the **two-layer tenant effect gate** (§5: `cloud-request-allowed-effects` at the request, `default-cloud-allowed-effects` on the exec ctx), the `:execute-guard` admission seam, monetisation via packages | When touching `tenancy/`, the effect gate, or an admission/quota policy |
| [docs/devtour/README.md](docs/devtour/README.md) | The **developer code-tour** — a navigable, symbol-anchored read of the *host codebase* by block (executor → storage → versioning → types → crud → packages → web → services → tenancy); how `tour.edn` + `bb devtour` bake real source into `docs/devtour/index.html`, the `:ns` / `:file` / `:dispatch` anchor forms, and the `bb devtour-check` drift guard. See **Developer Tour Maintenance** below | When onboarding to the codebase, or when you rename / move / delete a top-level form a tour step anchors (CI goes red) or add a subsystem worth a step |

## Common Commands

```bash
bb repl         # Start REPL with dev profile

# CI check hierarchy — one runner (scripts/ci.clj) over one registry
# (scripts/checks.edn); every task below delegates, no command is duplicated.
bb ci           # Full CI: lint (fail-fast) THEN unit tests. A lint slip skips the suite.
                #   --since <ref>  diff-scope: skip checks whose :relevant paths
                #                  (scripts/checks.edn) saw no change — skips are VISIBLE
                #   --skip <a,b>   force-skip by check/group name (gate: WTQ_CI_SKIP)
bb lint         # Every linter, NO tests — the fast pre-gate check (~1 min)
bb check        #   alias for `bb lint`
bb lint-clj     # Clojure only (kondo/splint/cljstyle) — after editing .clj
bb lint-web     # Editor JS/CSS (biome/stylelint)
bb lint-infra   # Scripts/Dockerfile/workflows (shellcheck/hadolint/actionlint)
bb lint-docs    # Docs (markdownlint/lychee/typos)
bb lint-sec     # Security (gitleaks/trivy/license-check)
bb kondo / bb cljstyle / bb biome / …   # any single check on its own
bb fix          # Auto-fix Clojure formatting (cljstyle)
bb test         # Run all tests
bb coverage     # Tests with coverage report (open target/coverage/index.html)
bb biome        # Lint editor JS (resources/packages/app/editor/**/*.js)
bb biome-fix    # Apply safe biome autofixes
bb stylelint    # Lint editor CSS — enforces design tokens for color/background/fill/stroke
bb stylelint-fix # Apply safe stylelint autofixes
bb visual       # Playwright visual-regression diff against committed baselines
bb visual-update # Refresh visual baselines after intentional UI changes
bb devtour      # Regenerate the developer code-tour docs/devtour/index.html from tour.edn
bb devtour-check # (in bb ci) fail if a tour anchor broke or index.html drifted

# Build & Deploy (Docker)
bb rebuild      # Rebuild jar + docker + restart (ALWAYS use this after code changes!)
bb deploy       # Full rebuild with DB truncate (for clean deployments)
```

**IMPORTANT:** After ANY backend code change (Clojure files, resources/packages/), ALWAYS run `bb rebuild` to apply changes. Never use raw docker commands.

### Running a Single Test

```bash
clojure -M:dev:test -m kaocha.runner --focus graphden.executor.core-test
clojure -M:dev:test -m kaocha.runner --focus graphden.executor.core-test/execute-test
```

### Deploy verification — `bb verify`

Every uberjar carries a `graphden-build-hashes.json` resource with
three SHA-256 digests, written by `build.clj`'s
`compute-section-hashes` step:

| Section | Files |
|---------|-------|
| `frontend` | `.js` / `.css` / `.html` / `.svg` under `resources/packages/` |
| `packages` | `.edn` / `.clj` under `resources/packages/` |
| `backend`  | `src/**/*.clj` plus non-package resources |

Three consumers read those hashes:

- `GET /version` → `{"frontend": "<hex>", "packages": "<hex>", "backend": "<hex>"}`
- `window.BUILD_HASH` — first 12 chars of the `frontend` hash,
  substituted into the `__BUILD_HASH__` placeholder in
  `editor-state.js` at bundle time. Exposed on `window` for
  on-demand readout (type `BUILD_HASH` in DevTools, or read it
  programmatically from a test). No auto-log to console.
- `bb verify [<base-url>]` — recomputes the same three hashes from
  the local checkout, fetches `<base-url>/version`, and reports each
  section's match/mismatch independently.

Workflow:

```bash
bb rebuild           # rebuild JAR + docker image
bb verify            # per-section ✓/✗ — tells you WHICH part of the
                     # deploy didn't ship: e.g. frontend matches but
                     # backend differs → docker image rebuilt with a
                     # stale jar
bb verify https://prod.example.com   # any URL
```

Exit codes: 0 (every section matches), 1 (at least one mismatch),
2 (`/version` unreachable).

`window.BUILD_HASH` and the `frontend` field of `/version` always
agree because they come from the same baked-in resource. If
`bb verify` reports backend match but the user's browser still
behaves like old code, compare `window.BUILD_HASH` (DevTools console)
against `fetch('/version').then(r=>r.json())` — a divergence is a
browser-cache issue, not a deploy issue (offer the in-app reload
button or Ctrl+Shift+R).

The placeholder is `__BUILD_HASH__` — it lives only in
`editor-state.js`. Don't delete it; the substitution step would have
nothing to replace and `window.BUILD_HASH` would be the literal
string `"__BUILD_HASH__"`.

### Frontend Module Structure

The editor frontend is split into modules for better maintainability:

| File | Purpose |
|------|---------|
| `editor-state.js` | Global variables, constants, `BUILD_HASH` placeholder |
| `web/runtime/graphden-popover.js` | Platform-shared popover primitives (NOT editor-specific) — `anchorBelowClamped` viewport-clamped positioner + `installPopoverDismiss` outside-pointerdown/Esc handler. Pure DOM (no cy/graphData/editor-state); bundled into BOTH the editor bundle and the standalone `/assets/graphden-runtime.js` (see `:_graphden-runtime-js-paths`) so user-composed pages can build info/form popovers too. |
| `editor-busy.js` | Visible feedback for multi-step user actions (reparent / extend / delete cascades) — `withBusy(opKey, label, fn)` helper + bottom-centre banner |
| `editor-prefs.js` | Theme + sidebar-collapsed prefs (localStorage) |
| `editor-auth.js` | `authFetch`, `isAuthenticated`, login popover |
| `editor-branches.js` | Branch-aware fetch wrap (every `/api/*` call carries `X-Graphden-Branch`), current-branch state (URL `?branch=` + localStorage), top-bar branch chip + popover (list / create / switch / Δ diff / ⇢ merge / × delete), `switchToBranch` reloads to invalidate caches, conflict-resolution modal for `:reason :merge-conflict` merge responses |
| `editor-branch-diff.js` | Full-viewport modal opened from the Δ button. Fetches `GET /api/branches/:current/diff?against=:other`, groups entries by `:change` (added-in-source / added-in-target / modified), renders per-entity-type previews; rows where entity-name is `:fn` are clickable and navigate to that fn via `selectFn` |
| `editor-create.js` | Inline-input row helper, fn / namespace creation |
| `editor-create-type-fields.js` | Per-kind form-field builders for the create/edit-type popover (refinement constraint builder, drag-reorderable pair-row lists for variant/record, prefill parsers) — split out of `editor-create-type.js` |
| `editor-create-type.js` | Type-row creation popover (refinement / record / union / variant / list) — lifecycle, shell, kind tabs, submit dispatch; server-fed name datalist via `GET /partials/type-name-datalist`; field builders live in `editor-create-type-fields.js` |
| `editor-data.js` | Data utilities, lookups, inheritance, free-args |
| `editor-layout.js` | Grid layout client + pixel positioning. Owns `taxiBendX` — the single source of truth for where a taxi edge turns (graph coords), called by the SVG edge path and the edge-label anchor alike; when they each kept their own copy, a dragged node put the label on the wrong side of the bend. Also `computeRowCenters`, shared by the estimated sizing pass and the measured reflow. **`calculateNodeSize` only estimates** — a card's real height is measured off the DOM by `reflowFromMeasuredHeights`, because the effects strip wraps to as many chip rows as it needs. |
| `editor-literal-types.js` | Type RESOLUTION + keystroke-VALIDATION half of the type helpers (`expectedSlotType` / `slotTypeProvenance` / `validateLiteralAgainstType` / nav-type walk; the validators mirror `graphden.types.check` deliberately — sub-100ms keystroke path) |
| `editor-type-format.js` | Type PRESENTATION half — `formatTypeHumanReadable` / `formatTypeHint` / `compactTypeChipText` / `shortTypeLabel` / `refinementConstraintText` / `resolveRefinementAlias` + the shared `appendResolutionSection` renderer. Split out of `editor-literal-types.js` |
| `web/runtime/graphden-forms.js` | Platform-shared form runtime (NOT editor-specific) — hiccup→DOM `renderHiccup` (createElement-only, no innerHTML), `collectFormValue`/`fillFormValue`, `initUnions`, `installTextareaEnterGuard`, `hydrateWidgets` (mounts `window.GraphdenFormWidgets`). Pure DOM + the widget registry; bundled into BOTH the editor and `/assets/graphden-runtime.js` so user pages can render server-sent forms. |
| `editor-value-form.js` | Editor-COUPLED half of the value-edit popover — fetches `POST /api/value-form`, live-validates against the slot type, orchestrates render (via `graphden-forms.js`), saves through the binding/sequence write helpers, singleton read-only value viewer. The generic render/collect/fill/widget core lives in `web/runtime/graphden-forms.js`. |
| `editor-widget-rating.js` | Tier-2 custom value-form widget — a 1-5 slider, registered on `window.GraphdenFormWidgets`; reference example for adding widgets |
| `editor-tooltips.js` | Description-tooltip + full-name popover + icon-reason popover singletons (the last is the disabled-with-reason surface the row-actions ✎/+/✕ buttons open) |
| `editor-icons.js` | Sidebar / edge-label icon factories (`createDescriptionBadge`, `createOpenInNewTabButton`, `createMoreActionsTrigger`, `applyActionIconBox`). In-card badge/action rendering lives in the server-rendered `:partial-row-actions`. |
| `web/runtime/graphden-runtime.js` | Platform-shared client primitives (NOT editor-specific) — `registerActionHandler(action, fn)` / `bindActionDispatch(host)` / `loadPartial(host, url, opts)`. Component fn-defs / partials / graph-composed pages reuse the same dispatch / fetch-and-swap surface. Bundled into both the editor JS bundle and `/assets/graphden-runtime.js` (the standalone user-page runtime — see `:_graphden-runtime-js-paths`). Sandbox-tested via `tools/runtime-test/runtime.test.js` (no browser). |
| `web/runtime/graphden-actions-builtin.js` | Three platform-provided action handlers: `navigate` (sets `window.location.href` from `data-href`), `submit-form` (finds the nearest `<form>` ancestor, POSTs via fetch, swaps the response into `data-target` or back into the form), and `custom` (evaluates `data-custom-handler` as `(btn, event, host) => …`). Registered via `graphden-runtime.js`'s `registerActionHandler`. Sandbox-tested in `tools/runtime-test/actions-builtin.test.js`. |
| `editor-row-actions.js` | Singleton popover anchored OUTSIDE the card (right of the `⋯` trigger). Server-rendered content via `:partial-row-actions` for 4 contexts (`col-header` / `cell` / `use-site-arg` / `root-row` — see `docs/EDITOR_HTMX_MIGRATION_PLAN.md`); JS owns the lifecycle (hover-show, click-pin, fade-out, cy.zoom/pan re-anchor). Consumes `graphden-runtime.js` — registers its 10 `data-action="…"` handlers via `registerActionHandler` at load time; `loadRowActionsContent` is a thin URL-builder over `loadPartial`. Also holds the `_rowActionsUseSiteArgs` registry that lets the dispatcher recover rich `useSiteArg` objects by `binding-id`. |
| `editor-drag.js` | Drag handle for any overlay |
| `editor-fn-picker.js` | Type-aware fn-picker popover |
| `editor-namespace-picker.js` | Namespace picker popover (Phase 5 ns-move) |
| `editor-edit-validation.js` | Structural pre-checks: `wouldCycle`, `miCollisionCheck` |
| `editor-edit-modes.js` | Inline edit popover SKELETON (`openInlineEditPopover`) + value / secret / arg-rename / sequence modes + the shared network helpers and `openLiteralVsRefChooser` |
| `editor-edit-modes-fn.js` | fn-level edit modes — extend / rename / declared-effects (server form via `GET /partials/expects-effects-form`) / return-type / namespace-move + `patchFnFieldInState`. Split out of `editor-edit-modes.js` |
| `editor-edit-modes-type.js` | type-level edit modes — compatible-type select (server option list via `GET /partials/compatible-type-options`), arg-type flip with picker-chaining rollback, free-arg binder. Split out of `editor-edit-modes.js` |
| `editor-edit-reparent.js` | Phase 3 re-parent cascade + parent-set editor popover |
| `editor-execute-result.js` | Pure render helpers shared by the execute popover — scalar / list / record / pending / error / oversize JSON panes. No state. |
| `editor-execute-history.js` | Execute popover history panel — mounts the server-rendered `GET /partials/execute-history` (+ `GET /partials/execute-result` per row-expand); JS owns row-expand toggling and Repeat re-fill via the orchestrator's `argFormHosts`. |
| `editor-execute.js` | Execute popover orchestrator — ▶ entry mounts the server shell (`GET /partials/execute-popover`: header, effects banner, free-arg hosts from the backend's `:free-arg-slot-map`, options, action bar); JS mounts `/api/value-form` widgets into the hosts and owns the run/poll/cancel state machine + branch pill. |
| `editor-fn-versions.js` | `⌛` history popover anchored to the fn-card root row. Fetches `GET /api/fns/:fn-id/versions`, renders a per-branch timeline (latest first), each row has a `switch` button that jumps the editor to that branch via `switchToBranch`. |
| `editor-service-popover.js` | Service-status popover anchored to a fn-card. Mounts the server-rendered `GET /partials/service-popover` (create / start / stop / delete a `:service` for the fn, plus `:enabled?` / `:restart-policy` / `:cardinality` / `:branch-id` controls); JS owns anchored positioning + dismissal only and collects the radio/checkbox values into the save `PUT`/`POST`. Holds a per-fn `_servicePopoverCache` Map, cleared via `invalidateServicePopoverCache()` on save/delete so the next open re-fetches fresh desired-state. |
| `editor-secrets.js` | Secret CRUD helpers integrated INTO the namespace tree (no separate section). Exposes `isSecretFn(fn)` (parents exactly `[:secret-leaf]`) for the sidebar's kind classification + 🔒 badge, `secretRecordForFn(fn-id)` (name + vault path from the primed `/api/secrets` list), and `buildSecretRowActions(actionsEl, fn)` (per-row rotate ↻ + delete × on secret tree rows, auth-gated). The New-secret form (name + path + value + description, value write-only) opens from `#secret-add-btn` in the filter bar via `openCreateSecretForm`. Backed by `/api/secrets/*`. |
| `editor-grants-admin.js` | Org-admin Grants sidebar section (PLATFORM_PLAN §6). Server-rendered via `GET /partials/grants-admin` (table of subject \| capability \| namespace); JS mounts the partial + owns the collapsible section lifecycle. Backed by `/api/grants`. |
| `editor-users-admin.js` | Users-admin sidebar section (PLATFORM_PLAN §4.1). Server-rendered via `GET /partials/users-admin` (table of username \| org; password hashes stripped server-side); JS mounts the partial + section lifecycle. Backed by `/api/users`. |
| `editor-packages.js` | Packages sidebar section (PACKAGE_DISTRIBUTION 3e). Server-rendered via `GET /partials/packages-panel`: the current branch's `:package-install` pins (per-row `×` uninstall + a `↑` update/rollback form: a version text input prefilled with the current version, submitting form-encoded `{name, version}` — accepts exact / `latest` / a semver constraint, symmetric so an older version rolls back with ref-rewrite) PLUS a native `<details>` "browse" of the registry index (each published version an Install button + a Fork button — Fork copies the fns into the graph copy-on-write and shows a transient notice, since fork writes no pin) PLUS a native `<details>` "Publish a namespace" form (name / version / ns-root → export the subtree + write an immutable `:package-version`; the handler `:do`s export+publish FIRST then renders — export-namespace's full-graph read returns empty if forced lazily inside the hiccup render). All mutations swap the refreshed `[data-packages-panel]` root (`hx-swap="outerHTML"`) — install/uninstall transition the installed table ↔ empty-state. JS mounts the partial + owns the collapsible section lifecycle only. NOT tenancy-gated (packages exist single-tenant). Backed by `/api/packages/{installed,uninstall,panel-install,panel-update,panel-fork,panel-publish}` (the panel's HTML-returning variants; the JSON `/api/packages/{install,update,fork,publish}` remain the programmatic API). |
| `editor-mismatch-explainer.js` | Singleton popover shown on click of an arg-overlay-mismatch indicator (expected/actual/reason + Edit-value action) |
| `editor-effect-explainer.js` | Singleton popover shown on click of an effect-chip — plain-English description of a tracked side-effect (db / env / io / network / time / random / process / raw-sql) + the canonical effect tag |
| `editor-type-expand-render.js` | Structural type-interpretation half of the inline `▸/▾` panel — per-kind constituent rows (refine→base+constraint, list→element, union→branches, record→fields, fn→args+ret), subtype-chain breadcrumb, and the type-grammar readers (`typeKindLabel` / `refinementChain` / `constraintToString`). Split out of `editor-overlay-type-expand.js` |
| `editor-overlay-type-expand.js` | Host lifecycle + edit affordances of the inline `▸/▾` panel — `position:fixed` hosts re-anchored on pan/zoom, `expandedTypePaths` persistence, effect-tightening widgets, promote-anonymous / rename-fn-type-arg actions, `makeEffectsReadOnly`. Rendering lives in `editor-type-expand-render.js` |
| `editor-provenance-popover.js` | Click-driven singleton popover for BOTH `↳` provenance badges. Slot-narrowing variant fetches `GET /partials/provenance?binding-id=…`; return-type-rule variant fetches `GET /partials/return-type-rule?fn=…` (rule-owner walk + `:_rtr-narratives` prose + Inputs table all server-rendered). JS owns the singleton lifecycle, anchoring, and post-swap binding of `[data-explainer-close]` + `a[data-fn-id]` → `selectFn` navigation |
| `editor-overlay-arg.js` | Arg-value overlay (in-place edit click target, type-chip, mismatch indicator, type-narrowing `↳` provenance badge). Column-flex outer: inline row of value+chip+trigger+mismatch sits over a drag-handle docked below. Exports `createTypeChip` (stacks base+constraint for refinements), `getTypeNarrowingInfo` (detects both `:type-override` and ref-return narrowing), and `createProvenanceBadge` (the `↳` glyph reused by edge-label overlays) |
| `editor-overlay-edge-label.js` | Edge-label overlay (rename click, type-chip + inline-expand trigger, stacked type-narrowing chain, description badge, sequence add/remove, `↳` provenance badge for ref-binding narrowing). Anchored AFTER the taxi-bend so the shared part of a branching edge stays visible |
| `editor-overlay-fn-rows.js` | The four fn-card row renderers (use-site header, column-below-MI / MI / single-fn rows). Split out of `editor-overlay-fn.js` |
| `editor-overlay-fn.js` | Fn-overlay assembly — paint state machine, hover wiring, `createFnOverlay` (row rendering lives in `editor-overlay-fn-rows.js`) |
| `editor-overlay-strips.js` | Bottom-of-card metadata strips — return-type (refinement variant stacks `→ base` over `(constraint)`) / effects / parents / ns / optional-args (each entry on the wire is `{:name :slot-id}`; the strip emits one span per `?name`, title carries the arg-type from rich-types + the declaring ancestor via `findSlotDeclaringFn`) / HOF-captured-args, sign-in CTA |
| `editor-overlay-manager.js` | Base `createOverlay` factory, placeholder-overlay binder, `createNodeOverlays` lifecycle. Owns `#graph-layer` — the single div that carries `translate(pan) scale(zoom)` for every overlay at once. Overlays are laid out in **graph coordinates**, so `applyViewportTransform()` (O(1), on `gv.onViewportChange`) is separate from `syncOverlayGeometry()` (O(n), only when a node moves or an overlay resizes). `updateOverlayPositions()` calls both; use it after a layout / animation frame / drag, never on pan-zoom. |
| `editor-sidebar.js` | Namespace tree + entity list + filter. **Lazy**: the tree paints from `?scope=tree` (namespaces + counts, O(namespaces)); each namespace's fn leaves load on expand via `loadNamespaceFns` → `?scope=namespace`; the filter box is a debounced server search (`?scope=search`), not a client scan. The client never holds a full-fns mirror — `graphData.fns` is an accumulating cache (subtree + expanded namespaces + searches), and reverse-ref delete-gate counts come from the server (`used-as-*-count` on each fn row + `:tree` counts). Name→id resolution (deep-link, type-override, secret-leaf, fn-picker pick) goes through `resolveFnByName`/`?scope=search`; the type-aware fn-picker pulls its compatible set from `/api/types/candidates`. |
| `editor-expansion.js` | spec→state→preview machine for ancestor row click/hover |
| `editor-ui.js` | Selection + navigation controls + the shared `previewDebounceTimer` |
| `editor-graph-model.js` | The graph itself — two Maps (`nodes`, `edges`) plus a RAF position tween. No library: this is all cytoscape had been reduced to. Exposes `window.graph` for browser tests. |
| `editor-viewport.js` | Pan / zoom, and the gestures that change them (wheel zooms about the cursor, background drag pans, one finger pans, two pinch). A press inside `#graph-layer` belongs to a card or an edge and never pans. |
| `editor-graph-view.js` | `gv` — the seam every other module reads the graph through. Element shape is `id()/data()/position()/width()/height()`; `fnNodes()` / `argNodes()` / `placeholderNodes()` replace cytoscape selectors. |
| `editor-edges-svg.js` | SVG edge layer inside `#graph-layer`. One visible path + one fat transparent one per edge, so the path IS its hit-zone and `elementsFromPoint` returns every edge under the cursor (overlapping vertical runs included). Stroke widths ride the groups, not the paths. |
| `editor-render.js` | Turns a backend layout into the graph: diff, add/remove, measured-height reflow, tween. `fitInVisibleArea` fits to the area the sidebar doesn't cover. |
| `editor-main.js` | Entry point, init |

**Load order** (in `app/editor/fns.edn` `_editor-script-paths`): state → graph-model → viewport → graph-view → graphden-popover (web/runtime) → busy → prefs → auth → branches → branch-diff → create → create-type-fields → create-type → data → layout → edges-svg → literal-types → type-format → graphden-forms (web/runtime) → value-form → widget-rating → tooltips → icons → runtime → actions-builtin → row-actions → drag → fn-picker → namespace-picker → edit-validation → edit-modes → edit-modes-fn → edit-modes-type → edit-reparent → execute-result → execute-history → execute → fn-versions → service-popover → mismatch-explainer → effect-explainer → type-expand-render → overlay-type-expand → provenance-popover → overlay-arg → overlay-edge-label → overlay-fn-rows → overlay-fn → overlay-strips → overlay-manager → secrets → grants-admin → users-admin → packages → sidebar → expansion → ui → render → main

### Browser Test Tool

Automated browser testing with Playwright in `tools/browser-test/`:

```bash
cd tools/browser-test

# View a function's graph
node check-editor.js web-server

# Expand root node ancestors
node check-editor.js web-server root:1

# Expand multiple nodes
node check-editor.js web-server root:1 router-fn:1
```

**Output:**

- Screenshot: `/tmp/editor-screenshot.png`
- Console logs printed to terminal
- Build timestamp verification

**Expand spec format:** `node-name:level` (use `root` for selected function)

The same directory also hosts e2e edit-flow tests (`edit-*.test.js`) and
the type-system UI helper smoke tests, split by concern:

```bash
# refinementChain, typeKindLabel (editor-type-expand-render.js),
# closedEnumOf (editor-literal-types.js), formatTypeHumanReadable,
# shortTypeLabel (editor-type-format.js) — pure type helpers.
# No DOM construction asserted here.
node type-system-ui-types.test.js

# appendResolutionSection (incl. multi-override visualization +
# onNavigate spy) — DOM rendering of the inline resolution section.
node type-system-ui-resolution.test.js
```

(The return-type-rule popover's prose table lives in the graph now —
`:_rtr-narratives` in `app/editor/fns.edn`, covered by the Clojure
test `graphden.packages.app.rule-narratives-test`.)

Each `*.test.js` file is a standalone Node script — exit code 0 = PASS,
1 = FAIL. Run individually or via `./run-edit-tests.sh`.

## Architecture Overview

Classical Clojure monorepo. Top namespace: `graphden`. Public API through `interface.clj` only.

### Three-Layer Architecture

```
┌─────────────────────────────────────────────────────────────┐
│                      EXECUTOR LAYER                          │
│  executor + base-functions + fn-registry                    │
│  Uses ExecutionGraph protocol (sp/resolve-execution-graph)  │
├─────────────────────────────────────────────────────────────┤
│                      STORAGE LAYER                           │
│  storage-protocol: StorageCRUD, ExecutionGraph, Constraints │
│  Decorators: VersionedStorage (composable)                  │
│  Backends: postgres-storage                                 │
├─────────────────────────────────────────────────────────────┤
│                    DATA SCHEMA LAYER                         │
│  data-schema-protocol + malli-data-schema + field-types     │
│  Schema extensions: versioned-data-schema, graph-data-schema│
└─────────────────────────────────────────────────────────────┘
```

**Key principle:** Each layer depends only on the layer below it. Executor calls `sp/resolve-execution-graph` which returns `ExecutionGraphResult`. Storage implementations implement this protocol using recursive CTEs for optimal graph traversal.

See [docs/PHILOSOPHY.md](docs/PHILOSOPHY.md) for full architecture rationale.

## Quick Reference

### Fn-def Syntax

```clojure
{:name :my-fn           ; unique function name
 :parent :base-fn-name  ; base function to use
 :args {:arg1 value     ; literal value
        :arg2 :other-fn}}  ; reference to fn
```

### Reference Types

Function references live in `binding.ref-fn-id` (or `binding-list-item.ref-fn-id`
for sequence elements).

| Syntax | Storage | Notes |
|--------|---------|-------|
| `:fn-name` | `ref-fn-id` on the binding/item row | Reference to another fn |

**Key principle:** A slot whose `type-fn-id` resolves to `:fn` IS the HOF marker —
the executor passes the fn-id directly instead of executing it. The legacy `:is-fn`
flag was retired in #15b; effective slot type drives the dispatch.

### Base Function Arg Types

| Type | Behavior |
|------|----------|
| `:fn` | Expects fn-id, auto-wrapped for HOF |
| `:any` | No processing (use for already-executed Clojure fns) |
| Others (`:int`, `:text`, `:jsonb`) | Auto-deref from delay |

For complete examples, see [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) Part 5.5.

### Transducers and Lazy Sequences

HOFs like `map`, `filter` support two modes via optional `coll` argument:

- **With coll**: `(map f coll)` — returns lazy sequence of results
- **Without coll**: `(map f)` — returns transducer

**Key functions for composition:**

- `comp` — composes functions/transducers
- `transduce` — applies transducer with reducing function in single pass
- `call` — invokes function with argument

**Example pipeline:**

```clojure
;; Efficient single-pass transformation
(transduce (comp (filter pred) (map transform)) + 0 coll)
```

### fn vs Runtime Function (CRITICAL)

**Understand the two levels of "function" in graphden:**

| Level | What it is | Where it lives | Examples |
|-------|------------|----------------|----------|
| **fn (graph)** | Composition description | Database | fn entity, arg entities |
| **Runtime fn** | Actual Clojure object | Executor memory | transducers, composed fns |

**Key insight:** The graph in DB is just a *description* of how functions compose. Actual Clojure functions (like transducers, composed functions) exist only at runtime in executor memory.

**No special-casing:** The executor must remain generic. Never add special handling for specific function names (anti-pattern). All functions execute uniformly through the same code path.

**Multi-arity for behavior:** Use optional arguments to control behavior (e.g., `coll` in `map`/`filter`). Clojure's multi-arity functions handle this idiomatically.

**Runtime objects can't be stored:** Functions returned by `comp`, transducers, etc. are Clojure objects — they exist only during execution. The graph stores *how to create them*, not the objects themselves.

### Base Function Philosophy (CRITICAL)

**Base functions MUST be minimal primitives wrapping a single Clojure/Java/library call.**

A base-fn impl should ideally be **1-2 lines** of actual logic: call the library, return the result. Everything else — composition, defaults, transformation — belongs in fn-defs.

✅ **GOOD base-fns** (atomic, generic, small):

- `add`, `sub`, `mul` — wrap Clojure arithmetic (1 line)
- `render-hiccup` — wrap hiccup2 `html` (1 line)
- `query-entities` — wrap storage protocol (1 line)
- `env` — get environment variable (1 line)
- `wrap-element` — `[(keyword tag) content]` (1 line)

❌ **BAD base-fns** (anti-patterns to avoid):

- Hardcoded HTML/CSS/JS content → use `:parent :const` fn-def
- Hardcoded defaults → use arg `:value` in fns.edn
- Calling another base-fn → hidden composition, must be fn-def
- Multi-step processing (parse → transform → format) → decompose into separate base-fns composed via fn-defs
- More than ~5 lines of actual logic (excluding validation)

**Key rule: base-fn MUST NOT call another base-fn.** If impl A calls impl B, and both are registered base-fns, the composition A→B is hidden in code instead of being visible in the graph. Fix: either compose via fn-def, or make the shared logic a private helper (not a base-fn).

**Acceptable exceptions for longer impls:**

- Input validation / size limits (e.g., `range` validates step≠0 and max-size)
- Library adapter boilerplate (e.g., `http-server` builds Ring handler format)
- These are part of safely wrapping the library, not business logic

**Example: Graph Editor should be fn-defs:**

```clojure
;; CORRECT approach
{:name :editor-styles, :parent :const, :args {:x "CSS here..."}}
{:name :editor-body, :parent :const, :args {:x [:div ...]}}
{:name :editor-page, :parent :html-page, :args {:title "Editor" :body :editor-body}}
{:name :editor-router, :parent :router, :args {:routes [...]}}
{:name :web-server, :parent :http-server, :args {:handler :editor-router :port 8080}}
```

See [docs/PHILOSOPHY.md](docs/PHILOSOPHY.md) "Base Functions Philosophy" for full details.

## Graph Constraints

Enforced at write time:

1. **No dependency cycles** — any cycle through `binding.ref-fn-id`
   - `parent-ids` + `type-override-fn-id` + `binding-list-item.ref-fn-id`
   edges is rejected. Two layers: the per-binding write-time
   `GraphConstraints` check, AND the sync-time topological-sort over
   the whole fn-def set. Bare self-ref (`owner == ref`) passes the
   per-binding layer but is rejected by topological-sort — so
   graph-level recursion is structurally impossible today
   (`docs/ARCHITECTURE.md § Part 3` for the empirical demo + planned
   `:fix`-based path forward).
2. **Schema-level uniqueness** — `UNIQUE` keys on `fn-slot(fn-id, slot-id)`
   and `binding(fn-id, slot-id)`. Two former keys were retired because the
   base identity row is cross-branch and soft-deleted identities persist,
   so uniqueness is a per-branch RESOLVED-VIEW property enforced by
   `VersionedStorage` instead: `binding-list-item(binding-id, position)`
   (`check-list-item-position-collision!`) and `fn(namespace-id, name)`
   (`check-fn-name-collision!` — a dead fn no longer blocks its name, and
   root fns, NULL namespace, are covered too; both advisory-lock-serialized).

These constraints are implemented in `storage-protocol` and enforced by storage implementations.

See [docs/CONSTRAINTS.md](docs/CONSTRAINTS.md) for detailed specifications.

## Code Conventions

- Public API through `interface.clj` only
- Internal namespaces: `core.clj`, `util.clj`, `constraints.clj`, etc.
- Error types use canonical `:type` keywords (see [docs/ERROR_CODES.md](docs/ERROR_CODES.md))
- Dynamic vars for configuration: `*query-timeout-ms*`, `*max-graph-iterations*`
- **Before modifying any `resources/packages/**/impls.clj`** — load
  the `graphden-packages-quality` skill. It catches not just
  oversized `defbase` bodies but also the bigger pitfall: private
  `defn-` helpers that quietly accrete composition (Ring wraps,
  cache orchestration, multi-step pipelines) which belongs in
  fn-defs over small base-fns. The canonical pattern is
  `:branch-routing-wrap` in `web/branch-router/fns.edn`.

## File Locations

```
src/graphden/<module>/interface.clj    # Public API
test/graphden/<module>/                # Tests
docs/                                  # Documentation
```

### Namespace Structure

```
src/graphden/
├── packages/           # Package loader for resources/packages/
│   └── loader.clj      # load-packages, load-module-fns, load-module-impls
├── executor/           # Executor, registry, compile pipeline, composition
│   ├── interface.clj
│   ├── registry/
│   ├── compile/        # deps, lookups, renames, bindings
│   └── composition/
├── crud/               # Entity/branch/secret CRUD, type-check, request parsing
│   ├── entities/       # apply-*-body/-core/-rollback write units
│   └── fn_execution/   # /api/execute* lookup + persist
├── types/              # Type system — core (subtype/unify/narrow), check
│   ├── core/
│   └── check/
├── schema/             # Protocol, malli, graph, versioned, traits, fields
│   ├── protocol/
│   ├── malli/
│   ├── graph/
│   ├── versioned/
│   ├── traits/
│   └── fields/
├── storage/            # Protocol, postgres
│   ├── protocol/
│   ├── postgres/
│   └── remote/         # RemoteStorage (read-only HTTP leaf) + SSE source — BYO
├── versioning/         # Storage decorator, merge protection
│   ├── storage/
│   └── merge/
├── layout/             # Graph-editor layout pipeline (Stages 1–7)
├── services/           # Service registry — reconciler + supervisor
├── fleet/              # Dynamic fleet (docs/FLEET_RFC.md) — placement table +
│                       #   forward-hop router, cell load/evict metrics, LPT
│                       #   packer, churn-min rebalancer, control-loop (sustained
│                       #   hysteresis), directed cell-command transport,
│                       #   DNS-SRV discovery. Driven by :exec/fleet-controller.
├── tenancy/            # Multi-tenant router, users, grants, RLS
├── auth/               # Pluggable auth-provider seam
├── clients/            # External clients (vault / OpenBao)
├── util/               # Small shared helpers (backoff — reconnect policy)
├── system/             # Integrant lifecycle management
│   ├── interface.clj   # start!, stop!, read-config
│   ├── config.clj      # Aero config loading
│   ├── sse.clj         # SSE invalidation relay (BYO freshness, per-org fan-out)
│   └── core.clj        # ig/init-key implementations
├── executor_runtime/   # Main entry point
│   └── core.clj        # -main, shutdown hooks
├── byo.clj             # BYO executor assembly (RemoteStorage + SSE source +
│                       #   direct http-server); `-main` for a customer-hosted
│                       #   executor. See docs/SCALING.md § External / BYO.
└── crac.clj            # CRaC checkpoint integration — quiesce!/resume! the pool
                        #   + LISTEN + advisory-lock + services around a
                        #   checkpoint; `-main` for the restore image. See
                        #   development/crac/README.md.

resources/graphden/tenancy/  # Addon config fragments (spliced via
                             #   GRAPHDEN_ADDON_CONFIGS): addon.edn (org-scoped
                             #   storage + RLS + request-scope) and faas.edn
                             #   (app-router + :org schema → FaaS app-routing +
                             #   the fleet forward-hop). See docs/PLATFORM_PLAN.md.

resources/packages/     # First-party package definitions (EDN + Clojure impls)
├── core/               # Core primitives (arithmetic, logic, HOF, etc.)
│   ├── package.edn     # Package metadata + dependencies
│   ├── arithmetic/     # {fns.edn, impls.clj}
│   ├── logic/
│   ├── hof/
│   ├── collections/
│   ├── strings/
│   └── system/
├── storage/            # Storage primitives (pg, protocol, versioned, branches)
├── web/                # Web primitives (http, routing, html)
│   ├── package.edn
│   ├── http/
│   ├── reitit/
│   ├── html/
│   ├── crud/
│   └── graph/
├── tenancy-admin/      # Org-admin fn-defs (auth, grants, users, registration)
│                       #   — loaded only when the tenancy addon is wired
└── app/                # Application server (editor, routes)
    ├── package.edn     # Has startup-fn: :web-server
    ├── common/         # Shared fn-defs (routes, responses)
    ├── editor/         # Editor UI fn-defs + impls
    ├── registry/       # Package registry: publish / install / fork / export
    └── server/         # Server composition fn-defs

external-packages/      # Packages kept OUT of the prod `resources` tree
├── mathx/              # External Type-2 (impl+fns) — also its own repo,
│                       #   pulled in by the git coord in the manifest below
└── examples/           # Pedagogical fn-defs — dev/test only (an :extra-paths
                        #   entry in the :dev/:test aliases), never in prod

resources/executor-packages.edn   # The operator's manifest of EXTERNAL Type-2
                                  # packages: {:name :lib :coord}. build.clj
                                  # bundles them; :app/packages loads them.
                                  # See docs/PACKAGE_DISTRIBUTION.md § 5.
```

## Packages System

Base functions and fn-defs live in `resources/packages/{pkg}/{module}/` as `fns.edn` (declarations) + `impls.clj` (Clojure impls). Dependencies in `package.edn` drive load order. See [docs/PACKAGES.md](docs/PACKAGES.md) for full format and workflow.

## Best Practices (CRITICAL)

The full rationale and worked examples live in [docs/PACKAGES.md § Composition Best Practices](docs/PACKAGES.md#composition-best-practices). The bullets below are the bare minimum for AI-assisted edits — read PACKAGES.md before larger structural changes.

### 1. DRY via inheritance

Extract a common parent when ≥ 2 fn-defs share an ancestor AND ≥ 1 bound arg with the same structure. Indicators: same parent, same bound args, repeated shape, can be named meaningfully. See [§ 1](docs/PACKAGES.md#1-use-inheritance-to-eliminate-duplication-dry).

### 2. Free-args propagation

Unbound args of a referenced fn-def surface as free args of the caller — that's how reusable templates (`:get-route`, `:json-ok-response`, …) work. Arg names propagate up; renames via `{:as :name}` swap the public name. See [§ 2](docs/PACKAGES.md#2-free-arguments-pattern-argument-propagation).

### 3. Named vs one-off

Name a fn-def when it's reused, has independent meaning, or represents a domain concept. Inline (no name) when used exactly once with no semantic identity. Heuristic: if you can't name it without describing wiring, it's one-off. See [§ 3](docs/PACKAGES.md#3-named-vs-anonymous-one-off-functions).

### 4. Hierarchy depth

2–3 levels is normal, 4–5 acceptable for route/response composition, 6+ needs justification. Each level should have a name, potential reuse, and a cohesive concept. See [§ 4](docs/PACKAGES.md#4-hierarchy-depth-guidelines).

### 5. Base-fn vs fn-def

Base-fn: has Clojure impl, wraps library, ≤ ~20 LOC body. Fn-def: pure composition, may carry hardcoded values, no impl. Base-fns MUST NOT call other base-fns — that's hidden composition. See [§ 5](docs/PACKAGES.md#5-base-function-vs-fn-def-decision-matrix) and [PHILOSOPHY § Base Functions](docs/PHILOSOPHY.md#base-functions-philosophy).

### 6. Naming (short names, context carries meaning)

Names add the **last bit of distinction** — namespace, parent, and arg names convey the rest. Drop affixes the context already says; keep affixes that disambiguate vs a sibling. Verb-at-end (`entity-create`) when prefix-form clashes with a base-fn. Extract a sub-NS when ≥ ~5 fn-defs share a long prefix. Names are validated for **global** uniqueness at sync time, **including locals** (`_*`).

Before renaming, grep:

```bash
grep -rE ":name :the-target-name\b|defbase the-target-name\b" resources/packages/
```

See [docs/PACKAGES.md § Naming Guidelines](docs/PACKAGES.md#naming-guidelines) for the full rationale, decision matrix, and worked examples.

## Tutorial Maintenance

The tutorial lives in `docs/tutorial/` ([index here](docs/tutorial/README.md)).
It is a per-block, growing set of text lessons — Block 0 in
[ROADMAP § Roadmap by Blocks](docs/ROADMAP.md#roadmap-by-blocks-current-plan).

**When you finish (or are about to finish) a feature that maps to a
tutorial lesson, propose to the user that the relevant lesson(s) be
added or updated.** Don't add or rewrite lessons unilaterally —
propose, get sign-off, then write.

**A feature maps to a tutorial lesson if:**

- It introduces a concept a new user would encounter in the editor
  (e.g. a new entity kind, a new arg type, a new editor affordance,
  a new system shape like branches / services / executions).
- It changes the observable behaviour of an existing
  user-facing concept in a way that contradicts a written lesson.
- It is one of the explicit ⏳ planned lessons in
  `docs/tutorial/README.md` that just became writable because the
  feature shipped.

**Don't propose lessons for:**

- Internal refactors that don't change user-visible behaviour.
- Bug fixes (unless the fix changes a documented behaviour).
- Performance work, caching, infra.
- Anything happening only behind a feature flag or only in tests.

**How to propose:**

- Name the specific lesson(s) by number/title from the index.
- Say what feature just landed and which concept that lesson would
  introduce or update.
- One-sentence sketch of what the lesson would walk through. Don't
  draft the full lesson until the user agrees.

Example:

> Block 1 storage base-fns landed. This unlocks Lesson 11
> (Packages — namespaces, fns.edn, impls.clj, deps) because users
> now have something concrete to call into. Lesson would walk
> through writing your own `:pg-query`-using fn-def from scratch.
> Want me to draft it?

If a lesson would document a feature that **partially** landed, do
NOT write the lesson yet — keep it ⏳ planned, propose again when the
feature is complete enough that the lesson can be paste-into-the-editor
correct.

## Developer Tour Maintenance

The **developer code-tour** lives in [docs/devtour/](docs/devtour/README.md) —
a navigable, symbol-anchored walkthrough of the *host codebase* for a new
contributor, organised by block (executor, storage, versioning, types, crud,
packages, web, services, tenancy). It is the developer-facing counterpart to
the user tutorial above: `docs/tutorial/` teaches *using* the editor; the tour
teaches *the code that runs it*.

Source of truth is [docs/devtour/tour.edn](docs/devtour/tour.edn) (blocks →
ordered steps, each anchored on a `{:ns :defn}` **symbol**, never a line
number). `bb devtour` bakes each anchored form's real source into the
self-contained `docs/devtour/index.html`.

**Two obligations when your change touches toured code:**

1. **Mechanical (CI-enforced).** `bb devtour-check` (in `bb ci`, `:docs` group)
   fails if any anchor stops resolving to a unique form, or if `index.html` has
   drifted from a fresh regeneration. So if your change edits the body of — or
   renames / moves / deletes — a form a step anchors on:
   - run `bb devtour` and commit the regenerated `index.html`, and
   - if you renamed / moved / removed the form, fix its step in `tour.edn`
     (re-point the anchor, or drop the step) so it resolves again.

   You cannot land red: the guard forces the tour to stay in sync with the
   code it points at.

2. **Editorial (judgement).** When a change introduces a significant new
   entry-point, subsystem, or concept in a toured block — or a whole new
   block-worthy subsystem — add a step (or block) so the tour still reads as a
   complete walkthrough; removing a subsystem removes its step. Keep each
   `:say` to the "what this form does + why it's the right next stop" style.
   See [docs/devtour/README.md](docs/devtour/README.md) for the anchor forms
   (`:ns` / `:file` / `:dispatch`) and how to add a block.

Unlike the user tutorial (propose lessons, get sign-off), developer-tour edits
are part of the change itself — no separate sign-off. Do **not** add a step for
an internal refactor that changes no entry-point, a bug fix, or perf work,
unless it changes what a newcomer should read.

## CI Workflow

**Run once, save output:**

```bash
bb ci 2>&1 | tee /tmp/ci-output.txt
```

Check final line for PASSED/FAILED. All information is in the single run — don't run multiple times.
