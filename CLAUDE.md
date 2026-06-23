# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

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
  - empty `parent-ids` + `impl-hash` set → base-fn (Clojure impl)
  - empty `parent-ids` + `impl-hash=nil` + slots/refine/list → type-row
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
  impl-hash: "sha256..."   ; links to Clojure impl
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
| [docs/TYPES.md](docs/TYPES.md) | Type system design & semantics | When working with arg types |
| [docs/LAYOUT.md](docs/LAYOUT.md) | Graph-editor layout pipeline (Stages 1–7) | When touching layout impl or editor frontend |
| [docs/CONSTRAINTS.md](docs/CONSTRAINTS.md) | Graph constraint specifications | When working with GraphConstraints |
| [docs/ERROR_CODES.md](docs/ERROR_CODES.md) | Error types reference | When handling errors |
| [docs/EXTENDING.md](docs/EXTENDING.md) | HOF semantics, custom storage, schema extensions, impl-hash | When extending below the package layer |
| [docs/ROADMAP.md](docs/ROADMAP.md) | Implementation status, future plans | For project planning |
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md) | Integrant config, Aero tags | When configuring the system |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | Docker, uberjar, environment | When deploying to production |
| [docs/EXECUTION.md](docs/EXECUTION.md) | Function execution feature: schema, HTTP API, cancel/TTL/UI | When touching `/api/execute*` or the editor's Run popover |
| [docs/SERVICES.md](docs/SERVICES.md) | Phase 1 service registry: `:service` schema, reconciler, supervisor, HTTP API, legacy fallback + displacement, roadmap to cron + multi-pod | When touching `services/`, `:exec/service-reconciler`, or anything that needs to know what services are |
| [docs/VERSIONING.md](docs/VERSIONING.md) | Branches surface — per-branch ExecutionContext routing, HTTP API (`/api/branches`, diff, merge, conflicts), editor UI (branch chip + popover + ⌛ history + conflict modal), demo seeder + env toggle, known gaps | When touching `system/branch_router`, `crud/branches`, `web.branch-router`, `app.branches`, `editor-branches.js`, `editor-fn-versions.js`, or the demo seeder |
| [docs/CLOSURE_CAPTURE.md](docs/CLOSURE_CAPTURE.md) | Closure-capture extension to the fn-graph model: call-site vs captured args, wrap-time capture contract, type-checker propagation, as-shipped commit map | Before touching `hof-wrap` / `hof-lambda-params` / `ref-free-args` / `free-arg-slot-map`; needed to understand why `:schedule` works |
| [docs/RECURSION.md](docs/RECURSION.md) | Design space for graph-level recursion (approaches A `:fix` vs B lazy ref resolution), per-approach impl sketches, recommended order. Roadmap — neither approach implemented yet | When considering recursion-related work; current state of recursion is in ARCHITECTURE.md § Part 3 |
| [docs/SECRETS.md](docs/SECRETS.md) | `:secret` information-flow type-marker — asymmetric subtyping (`T ⊆ [:secret T]` but NOT `[:secret T] ⊆ T`), per-base-fn `:return-type-rule` propagation (`taint-with-secret-if-tainted` / `wrap-with-taint`), executor-side `/api/execute` hide on `:secret`-marked return, editor "Result hidden" pane + history badge, current Secrets-panel admin UX (creating fn-defs with `parent :vault-get`), audit of which base-fns propagate vs not | When touching `types/core` secret-type code, adding a new base-fn that handles user data, marking a sink's slot as `[:secret …]`, or wiring secret-flow protection in a new place |
| [docs/PARTIALS.md](docs/PARTIALS.md) | Graph-native HTML partials: how editor popovers / panels get content from fn-defs returning hiccup at `GET /partials/*`; HTMX 2.x wiring (auth bridge + post-swap process); recipe for adding a new partial; list of common gotchas (`:parse-uuid` slot, JSONB keyword roundtrip, inline-anon limits, `fn-ref` in `:value` literal) | When migrating an editor JS module to server-rendered hiccup, OR when wiring a new popover from scratch |
| [docs/EDITOR_HTMX_MIGRATION_PLAN.md](docs/EDITOR_HTMX_MIGRATION_PLAN.md) | As-shipped reference for the row-actions partial: per-context (`col-header` / `cell` / `use-site-arg` / `root-row`) query-param matrix + JS dispatcher contract. Documents what shipped in Phase A (8 commits) and why Phase B/C/edge-label were deferred (server has no data the client doesn't). | When extending the row-actions partial OR considering another graphData-backed popover for migration |
| [docs/USER_SITES_PLAN.md](docs/USER_SITES_PLAN.md) | Roadmap for enabling users to build their own sites end-to-end in the graph — Block 1 (runtime + DSL) and Block 2 (starter component library, built-in handlers, `/demo/contact` end-to-end) shipped 2026-06-23. Remaining: Block 3 `:custom-script` escape hatch, Block 4 user-site routing + tutorial. | When working on `editor-runtime.js` / `editor-actions-builtin.js`, the `web/runtime` or `web/components` packages, adding a new component, or extending the user-site demo |

## Common Commands

```bash
bb repl         # Start REPL with dev profile
bb ci           # Full CI: clj linters + biome + tests + coverage (parallel, live progress)
bb test         # Run all tests
bb coverage     # Tests with coverage report (open target/coverage/index.html)
bb check        # Clojure linters only (clj-kondo, splint, cljstyle in parallel)
bb fix          # Auto-fix Clojure formatting (cljstyle)
bb biome        # Lint editor JS (resources/packages/app/editor/**/*.js)
bb biome-fix    # Apply safe biome autofixes
bb stylelint    # Lint editor CSS — enforces design tokens for color/background/fill/stroke
bb stylelint-fix # Apply safe stylelint autofixes
bb visual       # Playwright visual-regression diff against committed baselines
bb visual-update # Refresh visual baselines after intentional UI changes

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
| `editor-popover-base.js` | Shared singleton-popover scaffolding — `anchorBelowClamped` viewport-clamped positioner + `installPopoverDismiss` outside-pointerdown/Esc handler |
| `editor-busy.js` | Visible feedback for multi-step user actions (reparent / extend / delete cascades) — `withBusy(opKey, label, fn)` helper + bottom-centre banner |
| `editor-prefs.js` | Theme + sidebar-collapsed prefs (localStorage) |
| `editor-auth.js` | `authFetch`, `isAuthenticated`, login popover |
| `editor-branches.js` | Branch-aware fetch wrap (every `/api/*` call carries `X-Graphden-Branch`), current-branch state (URL `?branch=` + localStorage), top-bar branch chip + popover (list / create / switch / Δ diff / ⇢ merge / × delete), `switchToBranch` reloads to invalidate caches, conflict-resolution modal for `:reason :merge-conflict` merge responses |
| `editor-branch-diff.js` | Full-viewport modal opened from the Δ button. Fetches `GET /api/branches/:current/diff?against=:other`, groups entries by `:change` (added-in-source / added-in-target / modified), renders per-entity-type previews; rows where entity-name is `:fn` are clickable and navigate to that fn via `selectFn` |
| `editor-create.js` | Inline-input row helper, fn / namespace creation |
| `editor-create-type.js` | Type-row creation popover (refinement / record / union / variant / list) |
| `editor-data.js` | Data utilities, lookups, inheritance, free-args |
| `editor-layout.js` | Grid layout algorithm, positioning |
| `editor-literal-types.js` | Type-validation helpers shared by edit popovers (mirrors `graphden.types.check`). Also exports `refinementConstraintText` / `resolveRefinementAlias` — pure utilities for unwrapping refinement chips, used by stacked refinement rendering in `createTypeChip` |
| `editor-value-form.js` | Type-aware value-edit forms — generic hiccup→DOM renderer + collect/fill/validate/save runtime, fetches `POST /api/value-form`; union branch-swap, Tier-2 widget hydration, singleton read-only value viewer |
| `editor-widget-rating.js` | Tier-2 custom value-form widget — a 1-5 slider, registered on `window.GraphdenFormWidgets`; reference example for adding widgets |
| `editor-tooltips.js` | Description-tooltip + full-name popover singletons |
| `editor-icons.js` | Sidebar / edge-label icon factories (`createDescriptionBadge`, `createOpenInNewTabButton`, `createMoreActionsTrigger`, `applyActionIconBox`). The in-card badge factories (`createNamespaceBadge`, `createPinnedIconButton`, `createEditPencilButton`, `applyIconDisabledReason`) were retired in the HTMX Phase-A migration — their work moved into `:partial-row-actions`. |
| `editor-runtime.js` | Platform-shared client primitives — `registerActionHandler(action, fn)` / `bindActionDispatch(host)` / `loadPartial(host, url, opts)`. Extracted from `editor-row-actions.js` in user-sites Block 1 (`docs/USER_SITES_PLAN.md`) so future components + user sites use the same dispatch / fetch-and-swap surface. Sandbox-tested via `tools/runtime-test/runtime.test.js` (no browser). |
| `editor-actions-builtin.js` | Two platform-provided action handlers (user-sites Block 2.4): `navigate` (sets `window.location.href` from `data-href`) and `submit-form` (finds the nearest `<form>` ancestor, POSTs via fetch, swaps the response into `data-target` or back into the form). Registered via `editor-runtime.js`'s `registerActionHandler`. Sandbox-tested in `tools/runtime-test/actions-builtin.test.js`. |
| `editor-row-actions.js` | Singleton popover anchored OUTSIDE the card (right of the `⋯` trigger). Server-rendered content via `:partial-row-actions` for 4 contexts (`col-header` / `cell` / `use-site-arg` / `root-row` — see `docs/EDITOR_HTMX_MIGRATION_PLAN.md`); JS owns the lifecycle (hover-show, click-pin, fade-out, cy.zoom/pan re-anchor). Consumes `editor-runtime.js` — registers its 10 `data-action="…"` handlers via `registerActionHandler` at load time; `loadRowActionsContent` is a thin URL-builder over `loadPartial`. Also holds the `_rowActionsUseSiteArgs` registry that lets the dispatcher recover rich `useSiteArg` objects by `binding-id`. |
| `editor-drag.js` | Drag handle for any overlay |
| `editor-fn-picker.js` | Type-aware fn-picker popover |
| `editor-namespace-picker.js` | Namespace picker popover (Phase 5 ns-move) |
| `editor-edit-validation.js` | Structural pre-checks: `wouldCycle`, `miCollisionCheck` |
| `editor-edit-modes.js` | Inline edit popovers (arg-value / rename / type / free-arg-bind / sequence add-remove / namespace-move) |
| `editor-edit-reparent.js` | Phase 3 re-parent cascade + parent-set editor popover |
| `editor-execute-result.js` | Pure render helpers shared by the execute popover — scalar / list / record / pending / error / oversize JSON panes. No state. |
| `editor-execute-history.js` | Execute popover history panel — `/api/executions` fetch, summary row builder, Repeat re-fill via the orchestrator's `argFormHosts`. |
| `editor-execute.js` | Execute popover orchestrator — ▶ button entry, free-arg lookup, value-form mount, polling state machine, Run / Cancel, effects gate + persist toggle. |
| `editor-fn-versions.js` | `⌛` history popover anchored to the fn-card root row. Fetches `GET /api/fns/:fn-id/versions`, renders a per-branch timeline (latest first), each row has a `switch` button that jumps the editor to that branch via `switchToBranch`. |
| `editor-secrets.js` | Admin Secrets-panel CRUD — collapsible section at the top of the sidebar listing every fn-def whose parents are exactly `[:vault-get]`. Per-row rotate (↻) + delete (×) actions, header `+` opens the New-secret form (name + path + value + description, value is write-only). Exposes `isSecretFn(fn)` so `editor-sidebar.js` can put a 🔒 badge on the SAME fn when it appears in the namespace tree. Backed by `/api/secrets/*`. |
| `editor-mismatch-explainer.js` | Singleton popover shown on click of an arg-overlay-mismatch indicator (expected/actual/reason + Edit-value action) |
| `editor-effect-explainer.js` | Singleton popover shown on click of an effect-chip — plain-English description of a tracked side-effect (db / env / io / network / time / random) + the canonical effect tag |
| `editor-overlay-type-expand.js` | Inline `▸/▾` expansion of a type-chip — body-level floating panel with constituent mini-chips (refine→base+constraint, list→element, union→branches, record→fields, fn→args+ret), recursive; persistent in `expandedTypePaths`, re-anchored on cy pan/zoom. Fn-type panels include a read-only `eff: pure / <chips>` row (`makeEffectsReadOnly`) showing the slot-level effect constraint — separate from the editable tightening widgets below. Exports `appendResolutionSection(host, prov, opts?)` — the shared 4-tier + inheritance-chain renderer; `opts.onNavigate(fnId)` makes ancestor / source-fn labels clickable links |
| `editor-provenance-popover.js` | Click-driven singleton popover anchored to the `↳` provenance badge on an arg-overlay's type-chip. Calls `slotTypeProvenance` for data + `appendResolutionSection` for rendering (with `onNavigate: selectFn`), so every ancestor / source-fn row navigates on click. Surfaces the FULL narrowing chain without forcing the user to open the inline `▸/▾` type-expand panel |
| `editor-overlay-arg.js` | Arg-value overlay (in-place edit click target, type-chip, mismatch indicator, type-narrowing `↳` provenance badge). Column-flex outer: inline row of value+chip+trigger+mismatch sits over a drag-handle docked below. Exports `createTypeChip` (stacks base+constraint for refinements), `getTypeNarrowingInfo` (detects both `:type-override` and ref-return narrowing), and `createProvenanceBadge` (the `↳` glyph reused by edge-label overlays) |
| `editor-overlay-edge-label.js` | Edge-label overlay (rename click, type-chip + inline-expand trigger, stacked type-narrowing chain, description badge, sequence add/remove, `↳` provenance badge for ref-binding narrowing). Anchored AFTER the taxi-bend so the shared part of a branching edge stays visible |
| `editor-overlay-fn.js` | Fn-overlay renderer — ancestor rows, MI cells, paint state machine, `createFnOverlay` |
| `editor-overlay-strips.js` | Bottom-of-card metadata strips — return-type (refinement variant stacks `→ base` over `(constraint)`) / effects / parents / ns / optional-args (each entry on the wire is `{:name :slot-id}`; the strip emits one span per `?name`, title carries the arg-type from rich-types + the declaring ancestor via `findSlotDeclaringFn`) / HOF-captured-args, sign-in CTA |
| `editor-overlay-manager.js` | Base `createOverlay` factory, placeholder-overlay binder, `createNodeOverlays` / `updateOverlayPositions` lifecycle |
| `editor-sidebar.js` | Namespace tree + entity list + filter |
| `editor-expansion.js` | spec→state→preview machine for ancestor row click/hover |
| `editor-ui.js` | Selection + navigation controls + the shared `previewDebounceTimer` |
| `editor-cytoscape.js` | Cytoscape initialization, rendering, theme/zoom |
| `editor-main.js` | Entry point, init |

**Load order** (in `app/editor/fns.edn` `_editor-script-paths`): state → popover-base → busy → prefs → auth → branches → branch-diff → create → create-type → data → layout → literal-types → value-form → widget-rating → tooltips → icons → runtime → actions-builtin → row-actions → drag → fn-picker → namespace-picker → edit-validation → edit-modes → edit-reparent → execute-result → execute-history → execute → fn-versions → service-popover → mismatch-explainer → effect-explainer → overlay-type-expand → provenance-popover → overlay-arg → overlay-edge-label → overlay-fn → overlay-strips → overlay-manager → secrets → sidebar → expansion → ui → cytoscape → main

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
# refinementChain, typeKindLabel, closedEnumOf, formatTypeHumanReadable,
# shortTypeLabel — pure type helpers from editor-literal-types.js +
# editor-overlay-type-expand.js. No DOM construction asserted here.
node type-system-ui-types.test.js

# ruleNarrators dispatch table — coverage check + per-rule prose
# spot-checks. Trips when a new return-rule is registered without a
# matching narrator template.
node type-system-ui-narrators.test.js

# appendResolutionSection (incl. multi-override visualization +
# onNavigate spy), appendClosedEnumSection, appendEffectConstraintSection,
# appendPopoverSection — DOM rendering of the provenance popover.
node type-system-ui-resolution.test.js
```

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

### Base Function impl-hash

Each base function has an `impl-hash` stored in the `fn` entity for version tracking:

- SHA-256 hash of canonical form (args, return-type, impl-source)
- Detects: body changes, arg changes, return-type changes
- Ignores: whitespace, comments, map key ordering

See [docs/EXTENDING.md](docs/EXTENDING.md) for details.

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
2. **Schema-level uniqueness** — `UNIQUE` keys on `fn.name` (NULL allowed),
   `fn-slot(fn-id, slot-id)`, `binding(fn-id, slot-id)`, and
   `binding-list-item(binding-id, position)`.

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
├── executor/           # Executor, registry, composition
│   ├── interface.clj
│   ├── registry/
│   └── composition/
├── schema/             # Protocol, malli, graph, versioned, traits, fields
│   ├── protocol/
│   ├── malli/
│   ├── graph/
│   ├── versioned/
│   ├── traits/
│   └── fields/
├── storage/            # Protocol, postgres
│   ├── protocol/
│   └── postgres/
├── versioning/         # Storage decorator, merge protection
│   ├── storage/
│   └── merge/
├── logging/            # Structured logging with MDC
│   └── interface.clj
├── system/             # Integrant lifecycle management
│   ├── interface.clj   # start!, stop!, read-config
│   ├── config.clj      # Aero config loading
│   └── core.clj        # ig/init-key implementations
└── executor_runtime/   # Main entry point
    └── core.clj        # -main, shutdown hooks

resources/packages/     # Package definitions (EDN + Clojure impls)
├── core/               # Core primitives (arithmetic, logic, HOF, etc.)
│   ├── package.edn     # Package metadata + dependencies
│   ├── arithmetic/     # {fns.edn, impls.clj}
│   ├── logic/
│   ├── hof/
│   ├── collections/
│   ├── strings/
│   └── system/
├── web/                # Web primitives (http, routing, html)
│   ├── package.edn
│   ├── http/
│   ├── reitit/
│   ├── html/
│   ├── crud/
│   └── graph/
└── app/                # Application server (editor, routes)
    ├── package.edn     # Has startup-fn: :web-server
    ├── common/         # Shared fn-defs (routes, responses)
    ├── editor/         # Editor UI fn-defs + impls
    └── server/         # Server composition fn-defs
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

## CI Workflow

**Run once, save output:**

```bash
bb ci 2>&1 | tee /tmp/ci-output.txt
```

Check final line for PASSED/FAILED. All information is in the single run — don't run multiple times.
