# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

**How Graphden is built.** Graphden is developed by a human engineer who uses AI
coding agents as an accelerator. The engineer sets the direction, owns the design
decisions, and reviews every change before it lands. This file and the `dev/wtq/`
workflow describe how that human-directed work is organized so one developer can
move fast — they are not a claim that the software writes itself. AI involvement
is deliberate and not hidden.

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
- `binding` — per-`(fn-id, slot-id)` customization: `value`, `ref-fn-id`,
  `type-override-fn-id`, `terminal`, `list-append`, `list-closed`, `description`,
  `resolver-fn-id`. (Renames are rename-view SLOTS via `slot.source-slot-id`,
  not a binding column — `rename-to` is dropped.)
- `binding-list-item` — sequence content under a list-typed binding, ordered by `position`.
- `service` — desired-state row "keep THIS fn running". `branch-id` scopes
  it to a per-branch `ExecutionContext` so the same fn can run on dev +
  prod simultaneously. NOT versioned. See [docs/SERVICES.md](docs/SERVICES.md).
- `resource-override` — versioned `path → content` row shadowing a shipped
  frontend asset (the editor's own JS/CSS), served through
  `:read-resource-overridable`; every save rolls the effective `?v=` asset
  hash. Edited via Operate → Assets (self-host; cloud writes are
  system-only — tenant-forbidden as a stored-XSS surface).

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
| [docs/PHILOSOPHY.md](docs/PHILOSOPHY.md) | Design principles, rationale, module mapping | Before architectural decisions |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Data model, execution model, examples | When implementing features |
| [docs/PACKAGES.md](docs/PACKAGES.md) | Package system, module format, composition best practices | When adding base-fns or fn-defs |
| [docs/TYPES.md](docs/TYPES.md) | Type-system semantics — hierarchy, inference, narrowing, refinements, author `:type` sites | When working with arg types |
| [docs/TYPE_SYSTEM_DECISIONS.md](docs/TYPE_SYSTEM_DECISIONS.md) | Type-system ADR — sweep-at-zero state + the rejected alternatives (α/β/γ, #170 v2) with rationale | Before proposing ANY type-system change — avoids retrying closed paths |
| [docs/adr/ADR-identity-model.md](docs/adr/ADR-identity-model.md) | fn identity ADR — ids are identity, names are per-namespace labels; the two authoring worlds; the id-keyed rich-types registry | Before keying any new registry/cache/dispatch by fn NAME or changing id derivation |
| [docs/adr/AUDIT-name-vs-id-resolution.md](docs/adr/AUDIT-name-vs-id-resolution.md) | Closure audit of internal name-vs-id resolution + the `id_resolution_guard_test` CI guard | Before making an internal mechanism resolve/match/dispatch by NAME instead of id |
| [docs/adr/ADR-parent-set-identity.md](docs/adr/ADR-parent-set-identity.md) | Why `:parent-ids` stays an UNVERSIONED identity junction (guarded by `reparent-cross-branch-rej`); the copy-on-write fork door | Before touching `reparent-cross-branch-rej` or proposing versioned parent-ids / branch-local inheritance |
| [docs/adr/ADR-versioning-vs-offtheshelf.md](docs/adr/ADR-versioning-vs-offtheshelf.md) | Why the branch/versioning system stays bespoke on Postgres — not Dolt / Datomic / XTDB / temporal tables | Before proposing an off-the-shelf versioned DB or replacing `VersionedStorage` |
| [docs/PERF_BUDGETS.md](docs/PERF_BUDGETS.md) | The perf regression gate — budgets structural COUNTS (full-clears, SQL round trips), not timings; `bb perf` / `perf/budgets.edn` | Before adding any perf assertion, or when `bb perf` fails |
| [docs/PERF_NOTES.md](docs/PERF_NOTES.md) | Executor hot-path investigation — two failed point-fixes, real-fix sketch held in reserve | Before allocating perf work — re-benchmark first |
| [docs/LAYOUT.md](docs/LAYOUT.md) | Graph-editor layout pipeline (Stages 1–7) | When touching layout impl or editor frontend |
| [docs/EDITOR_MODULES.md](docs/EDITOR_MODULES.md) | Per-module map of the editor frontend + JS load order | Before touching any `editor-*.js` / `web/runtime/*.js` |
| [docs/EDITOR_ROW_ACTIONS.md](docs/EDITOR_ROW_ACTIONS.md) | As-shipped row-actions partial contract (4 contexts, query-param matrix) + why the other popovers stay JS | When extending the row-actions partial or considering another popover migration |
| [docs/PARTIALS.md](docs/PARTIALS.md) | Graph-native HTML partials at `GET /partials/*` — HTMX wiring, recipe, gotchas | When wiring a new server-rendered popover/panel |
| [docs/CONSTRAINTS.md](docs/CONSTRAINTS.md) | Graph constraint specifications | When working with GraphConstraints |
| [docs/ERROR_CODES.md](docs/ERROR_CODES.md) | Canonical error `:type` keywords | When handling errors |
| [docs/EXTENDING.md](docs/EXTENDING.md) | HOF semantics, custom storage, schema extensions | When extending below the package layer |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Shipped vs planned, by block | For project planning |
| [docs/AI_CONTEXT.md](docs/AI_CONTEXT.md) | The guide served to EXTERNAL AI authors at `graphden://ai-context`; its classpath copy is kept byte-identical by `mcp-doc-sync-test` | When changing what the AI is taught — edit BOTH copies |
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | Integrant config, Aero tags | When configuring the system |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Docker, uberjar, environment | When deploying to production |
| [docs/EXECUTION.md](docs/EXECUTION.md) | `/api/execute*` schema, cancel/TTL, Run popover | When touching execution API or its UI |
| [docs/MONITORING.md](docs/MONITORING.md) | Usage rollups, error viewer, both alerting paths (Prometheus + built-in alerter) | When touching stats/error-log/counters or adding a metric |
| [docs/SERVICES.md](docs/SERVICES.md) | `:service` registry — schema, cardinality, reconciler, supervisor, HTTP API | When touching `services/` or `:exec/service-reconciler` |
| [docs/SCALING.md](docs/SCALING.md) | Static fleet — org-keyed shards, NOTIFY invalidation, `:executor-orgs`, `421`, per-org quota, BYO executor | Before touching reconciler / branch-router invalidation / `storage/remote` / `system/sse` / `byo.clj`, or proposing distribution work |
| [docs/FLEET_RFC.md](docs/FLEET_RFC.md) | Dynamic fleet design — cell placement, rebalancing controller, CRaC track, tenant-service resource isolation; what is shipped vs evidence-gated | Before ANY autoscaling / placement / hot-reload / native-image work or touching `fleet/*` |
| [docs/FLEET_DEPLOY.md](docs/FLEET_DEPLOY.md) | Fleet ops — Helm chart, SRV discovery, controller tuning, dedicated-tenant-shard runbook | When deploying/operating a multi-pod fleet or provisioning a dedicated tenant |
| [docs/VERSIONING.md](docs/VERSIONING.md) | Branches surface — per-branch ctx routing, HTTP API, editor UI, known gaps | When touching branch code (`branch_router`, `crud/branches`, `editor-branches.js`, …) |
| [docs/CLOSURE_CAPTURE.md](docs/CLOSURE_CAPTURE.md) | Closure capture — call-site vs captured args, wrap-time capture contract, checker propagation | Before touching `hof-wrap` / `hof-lambda-params` / `ref-free-args` / `free-arg-slot-map` |
| [docs/RECURSION.md](docs/RECURSION.md) | Graph recursion — `:fix` (shipped) vs lazy ref resolution (road not taken) | When considering recursion-related work |
| [docs/SECRETS.md](docs/SECRETS.md) | `:secret` taint marker — asymmetric subtyping, propagation rules, hide-at-sink, Secrets-panel UX | When touching secret-type code or adding a base-fn that handles user data |
| [docs/PACKAGE_DISTRIBUTION.md](docs/PACKAGE_DISTRIBUTION.md) | Module kinds (fns-only / impl+fns / core-swap), registry lifecycle, export, § 15.1 as-built repo map | When touching `registry` / `packages/{loader,export}` / `executor-packages.edn`, or deciding what belongs in its own repo |
| [docs/TENANCY_SEAM.md](docs/TENANCY_SEAM.md) | The core seams the tenancy addon plugs into — context, auth, addon manifest, storage/schema, route collection, effect gate, execute guard | When touching `tenancy/context.clj`, the effect gate, an admission/quota seam, or any `:tenancy/*` init-key |
| [docs/ACCOUNTS.md](docs/ACCOUNTS.md) | The open opt-in identity module — account/identity/session model, social providers, email verification, TOTP, the `/auth/*` + `/login` surface | When touching `src/graphden/accounts/` or wiring auth for a deployment |
| [docs/SECURITY_MODEL.md](docs/SECURITY_MODEL.md) | The trust model — what each principal may do and which layer enforces it | Before touching an authz gate, or when reasoning about a tenant-facing surface |
| [docs/OPERATIONS.md](docs/OPERATIONS.md) | Day-2 ops runbook | When operating a deployment (incidents, upgrades, backups) |
| [docs/PLANS.md](docs/PLANS.md) | Cloud tiers/quotas reference — what each plan grants | When touching tier ceilings, quota, or the demo flow |
| [docs/FAQ.md](docs/FAQ.md) | Honest positioning Q&A about the project | When writing outward-facing copy about graphden |
| [docs/README.md](docs/README.md) | The reader-facing doc index (composes with this map) | When adding/renaming a doc — keep both indexes current |
| [docs/TUTORIAL_API_POLL.md](docs/TUTORIAL_API_POLL.md) | End-to-end worked example: scheduled API poller built from fn-defs | After tutorial lessons 01–10, or as a template for a real integration |
| [docs/RUNTIME_SLOT_ID_REFACTOR.md](docs/RUNTIME_SLOT_ID_REFACTOR.md) | The name→slot-id key-space refactor ledger — which runtime spaces are id-keyed vs name-keyed and why the remainder stays hybrid | Before re-keying any runtime map keyed by arg NAME |
| [docs/adr/ADR-free-arg-slot-map-perf.md](docs/adr/ADR-free-arg-slot-map-perf.md) | Why `free-arg-slot-map` is cached the way it is (VERIFIED) | Before touching free-arg caching |
| [docs/adr/ADR-slot-id-keyed-type-checker.md](docs/adr/ADR-slot-id-keyed-type-checker.md) | Slot-id-keyed checker — EVALUATED + REJECTED; closes TYPE_SYSTEM_DECISIONS § β | Before proposing to re-key the type checker |
| [docs/devtour/README.md](docs/devtour/README.md) | The developer code-tour — symbol-anchored read of the codebase; `bb devtour` bake + `bb devtour-check` drift guard | When onboarding, or when a toured top-level form is renamed/moved/deleted (CI goes red) |

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

The editor frontend is split into ~70 modules. The per-module map (what each
`editor-*.js` / `web/runtime/*.js` file owns) and the load order live in
[docs/EDITOR_MODULES.md](docs/EDITOR_MODULES.md) — read it before touching
editor JS. Platform-shared runtime files (`web/runtime/graphden-*.js`) are
bundled into BOTH the editor bundle and the standalone
`/assets/graphden-runtime.js` served to user-composed pages.

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
`:_rtr-narratives` in `app/editor-provenance/fns.edn`, covered by the Clojure
test `graphden.packages.app.rule-narratives-test`.)

Each `*.test.js` file is a standalone Node script — exit code 0 = PASS,
1 = FAIL. Run individually or via `./run-edit-tests.sh`.

## Architecture Overview

Classical Clojure monorepo. Top namespace: `graphden`. Where a module exposes an `interface.clj` (today: `executor/`, `system/`, `executor_runtime/`) that is its public API; most modules have none yet and are reached through their `core.clj` / named namespaces directly. The boundary is a convention, not lint-enforced.

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
the executor passes the fn-id directly instead of executing it. There is no
separate flag; effective slot type drives the dispatch.

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
- More than ~20 LOC of actual logic (excluding validation) — the hard
  ceiling; the **1-2 line ideal** above is the target, not the limit

**Key rule: base-fn MUST NOT call another base-fn.** If impl A calls impl B, and both are registered base-fns, the composition A→B is hidden in code instead of being visible in the graph. Fix: either compose via fn-def, or make the shared logic a private helper (not a base-fn).

**Acceptable exceptions for longer impls:**

- Input validation / size limits (e.g., `range` validates step≠0 and max-size)
- Library adapter boilerplate (e.g., `http-server` builds Ring handler format)
- These are part of safely wrapping the library, not business logic

**Example: Graph Editor should be fn-defs:**

```clojure
;; CORRECT approach
{:name :editor-styles, :parent :const, :args {:value "CSS here..."}}
{:name :editor-body, :parent :const, :args {:value [:div ...]}}
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
   recursion through a bare graph CYCLE is structurally impossible
   (`docs/ARCHITECTURE.md § Part 3` for the empirical demo). Recursion
   is instead provided by the shipped `:fix` combinator base-fn
   (`core/recursion/`, covered by `recursion_test`, used in prod by
   `storage/branches` `:branch-chain`) — see [docs/RECURSION.md](docs/RECURSION.md).
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

- Public API through `interface.clj` **where a module has one** (today 3 of the
  modules — `executor/`, `system/`, `executor_runtime/`); the rest expose no
  façade and are reached through `core.clj` directly. Not lint-enforced.
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
src/graphden/<module>/interface.clj    # Public API (where present — not every module has one)
test/graphden/<module>/                # Tests
docs/                                  # Documentation
```

### Namespace Structure

```
src/graphden/
├── packages/           # Package loader + package→storage sync (loader.clj, sync.clj)
├── executor/           # Executor, registry, compile pipeline, composition
├── crud/               # Entity/branch/secret CRUD, type-check, request parsing
├── types/              # Type system — core (subtype/unify/narrow) + check
├── schema/             # Protocol, malli, graph, versioned, traits, fields
├── storage/            # Protocol, postgres, remote (RemoteStorage + SSE source — BYO)
├── versioning/         # Storage decorator, merge protection
├── layout/             # Graph-editor layout pipeline (docs/LAYOUT.md)
├── services/           # Service registry — reconciler + supervisor (docs/SERVICES.md)
├── fleet/              # Dynamic fleet — placement, rebalance, control loop (docs/FLEET_RFC.md)
├── tenancy/            # ONLY context.clj — the seam core reads (docs/TENANCY_SEAM.md);
│                       #   the multi-tenant POLICY lives in the private graphden-tenancy
│                       #   repo, pulled into graphden-cloud as a git-dep
├── auth/               # Pluggable auth-provider seam
├── accounts/           # Open opt-in identity module — accounts/identities/sessions,
│                       #   social providers, TOTP, /auth/* routes (docs/ACCOUNTS.md)
├── monitoring/         # Built-in domain alerter (docs/MONITORING.md)
├── clients/            # Vault/OpenBao client + SSRF egress guard (egress.clj)
├── web/                # Shared web helpers (errors.clj, route_shape.clj)
├── util/               # Small shared helpers
├── system/             # Integrant lifecycle — config, init/* (per-concern init-keys),
│                       #   branch_router (per-branch ctx + dispatch; also serves the
│                       #   optional registry/mcp per-branch handlers), route_collection
│                       #   (the addon's fall-through router seam), sse (BYO relay)
├── executor_runtime/   # Main entry point (-main, shutdown hooks)
├── byo.clj             # BYO executor assembly (docs/SCALING.md § External / BYO)
└── crac.clj            # CRaC checkpoint integration (development/crac/README.md)

resources/packages/     # First-party packages (fns.edn + impls.clj per module)
├── core/               # Core primitives (arithmetic, logic, hof, collections, strings, system)
├── storage/            # Storage primitives (pg, protocol, versioned, branches)
├── web/                # Web primitives (http, reitit, html, crud, graph, runtime JS)
├── app-base/           # Route-building vocabulary shared by app + addon/registry (no app dep)
├── registry/           # OPTIONAL — in-graph publish/install/fork/export; routes served
│                       #   per-branch via branch_router; drop from :package-names to omit
├── mcp/                # OPTIONAL — the /mcp JSON-RPC AI endpoint; same per-branch serving
└── app/                # Application server — editor UI + JS/CSS, the editor partial
                        #   modules (editor-row-actions / editor-provenance /
                        #   editor-execute / editor-edit-forms / editor-branches /
                        #   editor-panels), lookups / execution / branches / secrets,
                        #   auth-pages (accounts page/email presentation) + forms
                        #   (value-form structure templates),
                        #   routes + route-groups, server (the handler chain)

external-packages/      # Kept OUT of the prod resources tree: mathx (Type-2 impl+fns,
                        #   also its own repo), examples (dev/test only)
resources/executor-packages.edn   # Operator manifest of EXTERNAL Type-2 packages;
                                  #   build.clj bundles, :app/packages loads
                                  #   (docs/PACKAGE_DISTRIBUTION.md § 5)
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

Names add the **last bit of distinction** — namespace, parent, and arg names convey the rest. Drop affixes the context already says; keep affixes that disambiguate vs a sibling. Verb-at-end (`entity-create`) when prefix-form clashes with a base-fn. Extract a sub-NS when ≥ ~5 fn-defs share a long prefix. Names are unique **per `(namespace, name)` pair** (ADR-identity-model stage 5) — base-fn names stay globally unique (name-keyed impls registry); a bare ref to a name defined in several namespaces must be qualified (`:other.ns/name`) or sync throws `:packages/ambiguous-ref`.

Before renaming, grep:

```bash
grep -rE ":name :the-target-name\b|defbase the-target-name\b" resources/packages/
```

See [docs/PACKAGES.md § Naming Guidelines](docs/PACKAGES.md#naming-guidelines) for the full rationale, decision matrix, and worked examples.

## Tutorial Maintenance

The tutorial lives in `docs/tutorial/` ([index here](docs/tutorial/README.md)).
It is a per-block, growing set of text lessons — Block 0 in
[ROADMAP § Roadmap by Blocks](docs/ROADMAP.md#roadmap-by-blocks-current-plan).

**Lessons are maintained LIKE CODE (user decision 2026-08-05).** When
a change alters user-visible behaviour that a lesson describes — or
unlocks a ⏳ planned lesson — update/add the lesson **in the same
landing**, through the gate, no proposal or sign-off step. The user
reviews the live lessons post-hoc, the same way they review any other
landed change.

**A change maps to a tutorial lesson if:**

- It introduces a concept a new user would encounter in the editor
  (e.g. a new entity kind, a new arg type, a new editor affordance,
  a new system shape like branches / services / executions).
- It changes the observable behaviour of an existing
  user-facing concept in a way that contradicts a written lesson.
- It is one of the explicit ⏳ planned lessons in
  `docs/tutorial/README.md` that just became writable because the
  feature shipped.

**Don't touch lessons for:**

- Internal refactors that don't change user-visible behaviour.
- Bug fixes (unless the fix changes a documented behaviour).
- Performance work, caching, infra.
- Anything happening only behind a feature flag or only in tests.

**The hard bar stays:** every lesson step must be paste-into-the-editor
correct against the tree it lands with. Never document a **partially**
landed feature — keep its lesson ⏳ planned and write it only when the
feature is complete enough for the lesson to be verified end-to-end.

## Developer Tour Maintenance

The **developer code-tour** lives in [docs/devtour/](docs/devtour/README.md) —
a navigable, symbol-anchored walkthrough of the *host codebase* for a new
contributor, organised by block (executor, storage, versioning, types, crud,
packages, web, layout, services, platform, accounts). It is the developer-facing counterpart to
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
