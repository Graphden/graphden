# Graphden Roadmap

> This document tracks implementation status and future plans.
> For technical architecture, see [ARCHITECTURE.md](ARCHITECTURE.md).

## Implemented

The implemented substrate lives in the code — read it there rather than
tracking per-item status in this doc. It covers: the slot/binding schema +
GraphConstraints, StorageCRUD + PostgreSQL (recursive-CTE) + VersionedStorage,
the compile-at-startup executor, the full base-fn set (arithmetic / logic / HOF
/ collections / strings / system / web: http, reitit, html, crud, ring-adapter,
http-client), the type system (refinements / records / lists / unions /
variants + rich-type registry), the visual graph editor, the REST API
(`/api/graph/*`, `/api/entities/*`, `/api/sequence/*`, `/api/execute*`), branch
versioning, the tenancy addon (orgs / users / grants / RLS / effect-gate / FaaS
— the policy ships from the private `graphden-tenancy` repo over the core
seams in [TENANCY_SEAM.md](TENANCY_SEAM.md)), the package registry
(the `registry` package), the storage-swap path (storage base-fns `:pg-query` /
`:pg-execute` / `:pg-tx`; API routes are graph fn-defs; the storage protocol is
injected at the web-server via a `:storage-query` free arg — former Block 1),
`:fix` recursion ([RECURSION.md](RECURSION.md)), the accounts module
(open opt-in identity — [ACCOUNTS.md](ACCOUNTS.md)), the 2026-08
editor redesign (rail surfaces + right inspector + sidebar lens +
workspaces), and the packages-registry redesign (⬆ publish on the
namespace, Build-surface install chip, org-private registry +
`publish-packages` capability, Organization governance view —
[PACKAGE_DISTRIBUTION.md](PACKAGE_DISTRIBUTION.md)).

Unbuilt primitives that never made it into a block below: a **file-I/O**
base-fn set and **WebSocket** live-updates.

## Roadmap by Blocks (current plan)

This is the active forward plan, agreed with the author. See
[PHILOSOPHY § Positioning](PHILOSOPHY.md#positioning) for
context. The plan supersedes the per-item entries in § Future Work
below; those entries remain for historical context and as
deeper-design references.

**Path to MVP launch with external users**: launch STAGING (weeks,
go-to-market, monetization, legal) is tracked separately in a private
planning repo; this doc stays the authority on feature blocks and their
sizes. The remaining
critical path is Blocks 4 → 9-Launch, with Blocks 3 / 6 parallelizable.
(Blocks 1, 2 and 5 are done — see § Implemented.)

Launch-order refinements agreed 2026-07-20:

- The AI-launch piece is **MCP-first**: 9.1 + 9.3 are on the critical
  path (users co-edit through their EXISTING subscription clients —
  Claude Code / Cursor — at zero token cost to us); **9.2 "Ask AI"
  (BYOM API key) moves to immediately-post-launch**. Growth pieces
  (9.4–9.6) stay post-MVP.
- **Block 4.1 (sidecar `:python-call`/`:go-call`) is deferred to the
  future reserve** — the launch integration packages (4.2) are pure
  HTTP and don't need it.

### Block 0 — Tutorial framework (continuous)

- Initial framework + first lessons in `docs/tutorial/` — text only
  for now; UI integration is a later decision
- One new lesson added per feature block as that block ships
- **Initial scope**: ~1 week; **ongoing**: ~0.5 week per block

### Block 3 — Personal QoL

1. **Tests via `tests/` namespace** convention + UI filter in the
   sidebar — ~3-4 days
2. **Workspaces** (namespace M:N self-link + UI scoping) — SHIPPED
   2026-08-13: delivered as the ctxbar workspace chip's project
   checklist (scope the Explorer to picked root namespaces +
   personal ⊘ hide, per-browser), not a separate rail surface —
   the rail-surface variant was tried and retired in the redesign.
3. **Error tolerance** (type mismatches as derived diagnostics, not
   silent swallow) — SHIPPED, all phases (structured diagnostics +
   per-branch store + non-blocking user CRUD writes with
   `:type-warnings` + the editor surfaces + execute refusal +
   `:forbid-invalid?` branch merge policy + the ctx-build recompute).
   See § Future Work → Error Tolerance.
4. **Debug/observability** with the PHILOSOPHY § Debugging
   constraints (per-fn opt-in, sampling, `:secret` auto-skip,
   size/TTL limits) — **P1 shipped**: execution-path capture — the
   opt-in `trace?` submit flag + the runtime-only per-fn traced set
   (`compile-eager/set-traced-fn-ids!`); per-`:ref` entries
   `{fn-id, cache-hit?, duration-ms}` with capture-time `:secret`
   skip, snapshotted into `:fn-execution.path-trace` (256 KB cap,
   oldest-first truncation). **P2 shipped**: editor path-view —
   the "Trace path" checkbox on the execute popover (a `trace?`
   submission binds the execution-scoped `trace-all` sentinel, so
   the run's own traversal is the explicitly-selected subtree of
   constraint 1); history rows with a trace get a "path" button,
   result panes a "Show path on canvas" affordance —
   `editor-path-view.js` highlights the traversed fn cards (accent
   ring + per-fn aggregate badge: count × total/max duration,
   cache-hit, `[hidden — secret]`), dims the rest, and lists
   off-canvas fns in a bottom-centre panel with ✕ clear; the view
   is transient (any overlay rebuild clears it). **P3 shipped**
   (item complete): full intermediate-VALUE capture behind the
   `capture-values?` submit flag — the editor's "+ capture values"
   second-step checkbox unlocks only with Trace path checked and
   requires an explicit confirm dialog with an estimated cost line
   (constraint 3); values ride the trace entries through the same
   streaming-JSON safety machinery as `:result` persistence (4 KB
   per-entry cap with `:value-truncated?`, 16 MB total in-memory
   budget with oldest-first drop + `:values-dropped?`,
   secret-touching fns never read — `{:hidden :secret}` stays);
   path-view badges gain an "= value" chip opening a per-fn value
   popover. Plus ambient session sampling (constraint 2):
   `compile-eager/set-trace-sampling!` (runtime-only atom, default
   0.01, resets on restart, a 100% rate requires
   `{:confirm-full true}`) samples fns in the selective traced set
   once per top-level execution at bind time — DELIBERATELY
   backend/REPL-only for now: no ambient-sampling UI ships until a
   real need arises, since the constraint is about protecting the
   user from capture cost, not about UI completeness
5. ~~**Free-arg aliases**~~ — SHIPPED (the `slot.source-slot-id`
   rename model; see § Future Work entry, kept as the design record)
(The routes-API + static-lint-against-drift item shipped as `window.API` +
a sync-time drift validator — done, see § Implemented.)

Block total (sole remaining item, #1 tests-namespace): **~3-4 days**

### Block 4 — Ecosystem (after Block 1)

Implements the MVP launch bar.

1. **Sidecar pattern** — `:python-call`, `:python-script`,
   `:go-call` base-fns for cross-language reach — ~1.5 weeks
2. **Integration packages** — `telegram-bot`, `postgres-client`,
   `openai-client` (or `http-client` if `openai-client` deferred), plus
   social clients (`discord` webhook, `bluesky` AT-proto). A `social-post`
   fan-out fn-def over them makes one post reach every channel — the
   project's own announcements then run through a graph (dogfooding) —
   ~1 week each

Block total: **~4-5 weeks**

### Block 6 — UI Step 1 (any window)

- **Inline `:const` editor** for JS/CSS in the running editor +
  rebuild trigger — ~1.5 weeks. See
  [PHILOSOPHY § UI as Graph](PHILOSOPHY.md#ui-as-graph--two-step-roadmap).

### Block 7 — Horizontal scaling foundation

**Largely SHIPPED.** See [SCALING.md](SCALING.md) for the as-built
picture; this entry now only tracks what remains.

Sub-block A — **Multi-process executors over shared Postgres** —
DONE. Each JVM builds its own `:compiled-registry` at startup; the
seeded `:web-server` service is `:cardinality :per-pod` so every
container binds its own port behind a load balancer. (Before that
field existed, the advisory lock made `:web-server` a cluster
singleton and only one pod ever served HTTP.)

Sub-block B — **Cross-process invalidation via Postgres
LISTEN / NOTIFY** — DONE. Channel `graphden_events`, payload
`fn:invalidate:<fn-id>|<branch-id>`, delta-recompiled through the
reverse-deps index. The branch-id is load-bearing: a cached branch
that inherits from the written branch must recompile, and one that
doesn't must not.

Also shipped alongside: per-service `:cardinality`, advisory-lock
ownership for `:singleton` services, cross-pod `execution:cancel`
routing, and `:executor-orgs` — an org-shard predicate that lets a
pod compile only the tenants it serves.

Requests that reach a pod outside their org's shard get a
`421 Misdirected Request` from both entry points (editor/API
request-scope and the FaaS app-router), so a shard-unaware load
balancer degrades to a retry rather than to a wall of missing fns.

The fleet-wide per-org quota, advisory-lock connection-drop reconnect,
the periodic reconcile tick, and the external / BYO executor
(`graphden.byo` + `RemoteStorage` + SSE relay/source) all landed — see
[SCALING.md](SCALING.md) and SERVICES.md § Roadmap.

**Remaining**: optionally an LB rule that routes by subdomain so the 421
stays a backstop rather than a hot path, and a real BYO executor on a
second physical machine end-to-end (proven in one JVM today — see
SCALING.md § Still open).

### Block 8 — Hot-reload of impls (optional)

**Dev-velocity feature, NOT required for Cloud-Shared launch.**
Today changing a `defbase` body requires `bb rebuild` (docker
image rebuild + restart). Hot-reload would let the author push a
new impl into a running executor without restart.

Approach sketch: nREPL channel into each executor; the package
loader re-syncs an individual `impls.clj` and the registry rebuild
picks up the new impl-fn map. Risk class: ClassLoader hygiene,
in-flight requests during swap, security (treats any nREPL caller
as trusted code-pusher — needs auth gating).

Estimated: **~2 weeks**.

Scheduled any time after Block 7 lands (sharing the cross-process
invalidation channel). Will not block any other block.

### Block 9 — AI Integration

**Launch piece (9.1–9.3) is on the MVP critical path; growth piece
(9.4–9.6) is post-MVP.** Implements the AI co-author surface so users
can drive graphden with the AI model of their choice (their own API
key) and so the editor presents AI proposals as graph-diffs rather
than text-diffs.

#### Motivation

1. **Author pain.** Reviewing AI-proposed changes in text-based
   codebases costs attention to whitespace, line-breaks, formatting
   noise rather than to behaviour. Graphden's entity model lets a
   proposal surface as "added 3 fn-defs, changed 2 bindings, deleted
   1 ref" against the current graph — the existing branch-diff UI
   already renders this shape.
2. **Launch-time differentiation.** "Co-edit your graph with the AI
   of your choice, review without text-diff noise" is part of the
   first-launch story for both users and investors.
3. **Hypothesis under test.** The small vocabulary (5 entity types,
   ~150 base-fns) should give an AI a tighter problem surface than
   an arbitrary 50 kLOC text codebase. Unproven — the MCP server +
   AI-context resource is the experiment that tests it.

#### Launch piece (~4 weeks, critical path)

1. **MCP server.** Graphden exposes its primitives (list / read /
   create / update / delete fn-def, execute, run tests, query
   branches, read history, get AI context) over the Model Context
   Protocol. Any MCP-capable client (Claude Code, Cursor, Claude
   Desktop, future clients) can drive graphden against the user's
   own model + API key. Open-source, ships as a base-fn-graph
   (`:mcp-server`) wired into the editor process. Per-user auth on
   every tool call. **~1.5 weeks.**
2. **Editor "Ask AI" flow.** Button in the editor that prompts for
   instructions + a target branch (defaults to a fresh `ai/<slug>`
   branch), spins up an AI session against the user-configured
   model, lets the AI execute graph mutations against that branch
   via the same MCP tools, and on completion routes the user to the
   existing branch-diff UI (`editor-branch-diff.js`) for accept /
   reject. **BYOM (bring-your-own-model)**: per-user model + API
   key config UI, encrypted at rest via the existing `:vault-*`
   surface (cloud-shared) or kept client-side (self-hosted).
   **~2 weeks.**
3. **AI-context resource — SHIPPED.** `docs/AI_CONTEXT.md` teaches an
   external AI the entity model, the fn-def map, slot/inheritance/free-args,
   control-flow-as-data, the tool workflow, naming/style, and the cloud
   effect budget. The MCP server serves it verbatim at the
   `graphden://ai-context` resource by reading the classpath copy
   (`resources/packages/mcp/mcp/ai-context.md`), kept byte-identical by
   `mcp-doc-sync-test`. Distinct from `CLAUDE.md` (developer guidance) —
   this teaches *user* fn-def authoring.

#### Growth piece (~4–5 weeks, post-MVP)

1. **Managed-model gateway.** Control-plane proxy for users who
   don't want to manage their own API keys; per-token markup on top
   of upstream cost. Closed source, lives with the cloud control
   plane. Self-hosted users keep BYOM. **~1.5 weeks.**
2. **Dedicated proposal panel.** Richer per-fn-def diff cards with
   inline reject-with-feedback and conversational follow-up;
   successor to the branch-diff-modal reuse from 9.2. **~2 weeks.**
3. **Persistent AI sessions.** Store conversation transcripts +
   tool-call traces per branch, resume / share. **~1 week.**

#### Dependencies

- 9.1 needs Block 2 (auth — every tool call is per-user). Storage
  base-fns from Block 1 are not required but simplify implementing
  the tools as graph fn-defs rather than Clojure shims.
- 9.2 needs Block 2 (per-user secret storage for BYOM API keys).
  Block 6 (inline `:const` editor polish) is helpful but not
  required — the launch UX uses the existing branch-diff modal.
- 9.3 is standalone.
- 9.4 needs the cloud control plane (closed-source track, parallel
  to open-core blocks).
- 9.5–9.6 follow 9.2.

**Block total launch: ~4 weeks. Growth: ~4–5 weeks.**

### Deprioritized (do when there's a slot, no critical path)

- **Graph → Clojure export** — credibility / REPL escape hatch.
  Useful but not on the daily-use path.
- **Clojure → graph import** — needs a real migration target first.

### Not planned

- **Datomic** storage backend
- **Executor rewrite** in another host language (Go / Python). See
  PHILOSOPHY § Trade-off Sovereignty — would force two impls of
  every base-fn forever.
- **Multi-language fine-grained execution** — sidecar pattern
  (Block 4) covers the use case at coarse granularity, which is the
  only level where it's practical.
- **UI Step 2** (full graph-described UI structure) — far future.
- **Distributed execution** — kept on the existing § Future Work
  list as a separate research thread, not part of this block plan.
- **Popularity-based fn-set distribution** — the "smart" horizontal
  scaling where each executor holds only the hot subset of fn-defs
  and a routing layer dispatches by fn-id popularity. Requires
  millions-of-fns scale + heavy per-impl resource cost to pay back
  the orchestration complexity. We are not there for the next ~24
  months minimum. Still not planned — and note that the pressure it
  was meant to relieve (a cloud pod compiling every tenant's fns) is
  answered instead by **org sharding** (`:executor-orgs`, see
  [SCALING.md](SCALING.md)), which needs no fn-level router because a
  request already names its org.

---

## Future Work

### Package Interface Declaration (packages-spec §6, reframed)

> **Deferred by user decision 2026-08-13**; the packages epic shipped to
> prod without it. **Reframed 2026-08-13 after design discussion**: the
> feature is NOT "hide a package's internals" — it is "declare the
> package's update contract". This entry supersedes the "visibility
> marker / hide internals" wording in
> `.cursor/rules/packages-and-dependencies.mdc` §6.

**Goal**: a graph-native declaration of a package's public interface —
the fns the author promises to keep across versions — shown first in
the install browser, machine-readable by update tooling.

**Why "hiding" is the wrong frame** (cross-language survey):

- Python `__all__` / `_`-prefix, Clojure `defn-` / `impl.*`-namespace
  layering — advisory contract signals, not concealment.
- Rust `pub` / Elm `exposing` / npm `exports` enforce visibility only
  to protect the author's right to refactor internals across semver
  updates (Hyrum's law) — the consumer-side hazard is *update time*.
- Go's capitalization rule is name-based — the class of rule P4 forbids.

In Graphden, install materializes ALL of a bundle's fns into the user's
graph, and the §7 customization flows (inherit-override, fork-to-fix)
*require* reading internals. Real concealment is therefore impossible
and undesirable: **enforced privacy is a permanent non-goal** (not
"until enforcement exists"), and any implementation must stay
visual-only (collapse, never a read barrier).

**Real value, in priority order:**

1. **Update contract.** Update = repoint pin + rewrite the project's
   refs old→new; a ref into a non-exported fn can dangle when v2
   renames/removes it. An exports list gives a machine-checkable
   interface diff v1→v2 → update tooling can warn "you depend on
   internals of P" instead of silently breaking.
2. **Install-browser UX.** Show the public few; collapse the private
   many (packages with 40 fns and a 3-fn interface).
3. Retires the last cosmetic `_`-prefix use (`displayLabel`).

**Form (design lean, recorded 21890fa2)**: an **exports construct** — a
per-namespace fn-def `:parent :exports` whose binding-list-items
reference the public fns. Ordinary versioned binding content:
branch-scoped, mergeable, toggle = add/remove one list item; list order
doubles as display order; classification is structural (rows whose
parent is the `:exports` base-fn), never a name. Preferred over
marker-inheritance because `parent-ids` is an unversioned identity
junction (ADR-parent-set-identity) — visibility-as-a-parent-edge would
make every public/private toggle a cross-branch identity edit.

**Rejected simpler alternative — namespace layering** ("root ns =
interface, sub-namespaces = internals"): P4-clean, zero new constructs,
and adequate for most small packages — but it conflates organization
with visibility (a large public API can't be organized into
sub-namespaces; a private helper can't sit beside its one consumer) and
turns a visibility toggle into a structural fn move instead of a list
edit. Keep it in mind as the fallback if the exports construct ever
feels too heavy in practice.

**Implementation trigger** — not "the design is ready" but the first
real pain: a user's package update breaks refs into internals, or the
install browser is unusably noisy on a real package. Until real
cross-org package traffic exists, this stays deferred.

---

### Graph-level Recursion

Shipped (`:fix`, Approach A). Design + the rejected Approach B (lazy ref
resolution) live in [RECURSION.md](RECURSION.md).

**Two viable approaches**, both fully specified in
[RECURSION.md](RECURSION.md):

| | Approach A — `:fix` | Approach B — Lazy ref resolution |
|---|---|---|
| New entities | 1 base-fn | 0 (optional `:recursive?` flag on fn-row) |
| Cycle invariant | Preserved | Relaxed for opt-in fns |
| Mutual recursion | Tag-dispatch convention | Natural |
| Effort | ~3 hours | ~1-2 days |
| Risk | Minimal | Touches compile pipeline + delta-recompile |

**Recommended order**: ship A first (leverages already-shipped
closure-capture; covers ~80% of practical use cases). Revisit B
only if A's mutual-recursion ergonomics prove insufficient. After
A lands, `exec/execute-by-name` from inside an impl moves from
"escape hatch" to explicit anti-pattern.

**See**: [RECURSION.md](RECURSION.md) for the full design + impl
sketches + open questions, [ARCHITECTURE.md § Part 3](ARCHITECTURE.md#part-3-recursion-and-cycles)
for the current-state writeup.

---

### Distributed Execution

**Goal**: Automatic parallelization and distribution of computations across multiple executors.

**Why it's possible**: Graph structure explicitly represents dependencies. Independent subgraphs can be identified and computed in parallel without manual annotation.

**Phases:**

| Phase | Description | Complexity |
|-------|-------------|------------|
| 6.1 Local Parallelism | Execute independent args in parallel threads (same JVM) | Medium |
| 6.2 Worker Pool | Offload to worker processes on same machine | Medium |
| 6.3 Distributed Workers | Remote executors with network transport | High |
| 6.4 Smart Partitioning | Cost-based optimizer for graph partitioning | High |

**Key decisions needed:**

- Data transfer strategy between executors (direct, via coordinator, via storage)
- Granularity of distribution (coarse vs fine-grained)
- Handling side effects and ordering guarantees
- Failure handling and retry strategy

**See:** [ARCHITECTURE.md - Distributed Execution](ARCHITECTURE.md#part-8-distributed-execution-future)

---

### Free Argument Aliases (UI-friendly names)

> **Shipped** (Phase 6+ / 6c — verified in code 2026-08-04: the
> `slot.source-slot-id` FK model below is live, `slot-by-fn-source-slot`
> index in `executor/compile/lookups.clj`, rename popover in
> `editor-overlay-edge-label.js`). Kept here as the design record.

**Goal**: Human-readable names for free arguments in execution forms.

**Problem**: When executing a function with free arguments via UI,
users see slot's primary name (or its inherited form). For
domain-specific UI, we want a per-fn display alias.

**Solution (Phase 6+, current model)**: a per-fn rename creates a new
slot row owned by F whose `slot.source-slot-id` FK points at the
inherited ancestor slot. F's `fn-slot` junction exposes the new
slot under the alias name; bindings still resolve through the
source-slot id, so the rename is purely a display-side rewrite.

The retired `binding.rename-to` text column is no longer used —
Phase 6c migrated every callsite (CRUD, compile, editor) to the FK
link. `slot-by-fn-source-slot` (in
`executor/compile/lookups.clj`) is the O(1) index that lets renamers
be located without walking the inheritance chain.

**Lifecycle:**

- Created via the inline rename popover (edit the edge label) — the
  CRUD impl emits `slot.source-slot-id` + a new `fn-slot` junction.
- Cleared by saving an empty value through the same popover; the
  renamed-view slot row is deleted, exposing the source slot's
  original name again.
- Per-fn: a slot inherited at fn F may be renamed at F without
  affecting siblings (each rename owns its own slot row).

**UI Usage:**

```
Execute function: calculate-report
┌─────────────────────────────────────┐
│ Sales Region: [_______________]     │  ← alias for arg
│ Start Date:   [_______________]     │  ← alias for arg
│ Currency:     [USD v]               │  ← alias for arg
└─────────────────────────────────────┘
```

**Complexity**: Low-Medium.

---

### Type System (Type Algebra)

**Goal**: Static type checking, UI hints, automatic type inference.

**What's needed:**

- Types for functions (input types -> output type)
- Parametric polymorphism (List[T], Map[K,V])
- Type inference for compositions (Hindley-Milner or subset)
- Types for HOF: `map : (a -> b) -> List[a] -> List[b]`

**Complexity**: High. This is a separate large project.

---

### Error Tolerance (type errors as visible diagnostics)

> Now scheduled in **Block 3**.

**Goal**: A graph with type errors can be saved and iterated on — sketch
the structure first, fix details later — without errors being silent.

**Current state — ALL phases SHIPPED (0–5 plus the ctx-build
recompute). Validity stays a DERIVED fact: the store is in-memory, and
both policy gates below judge what is RECORDED (absence = allow). The
package sweep re-records first-party fns at boot; the branch router's
ctx-build recompute re-checks a branch's editor-authored fns (async,
bounded at 500, off-switch `branch-router/*recheck-user-fns?*`) so the
store repopulates after a JVM restart.**

- *Phase 0 (done)*: the CRUD check guards return structured
  diagnostics — `type-check-fn-after-mutation!` /
  `type-check-binding-direct!` carry a cleaned `:diagnostic` map
  (`:expected` / `:actual` / `:arg-name` / `:message`) alongside the
  human `:reason`.
- *Phase 1 (done)*: `graphden.types.diagnostics` — a per-branch
  in-memory store; the post-mutation guard records failures and
  clears them again when a later write fixes the fn.
- *Phase 2 (done)*: the behaviour flip. A USER CRUD write that fails
  the TYPE check KEEPS the row — no rollback, no 400: the create /
  update apply-cores and the tighten commit record the diagnostic
  and the success envelope carries `:type-warnings
  [{…diagnostic…}]` additively (a 200 JSON `{message,
  type-warnings}` body when warnings exist; the legacy HTML body
  otherwise). The pre-write single-binding guard is unhooked from
  the create/update validation chains (kept as the on-demand
  `:type-check-binding-rej` validator). SECURITY CARVE-OUT:
  secret-flow subtype violations (`[:secret …]` laundered into a
  plain slot) stay HARD save-time rejects — write rolled back, 400,
  no store record; the guarantee must not rest on the derived
  diagnostics store (see SECRETS.md § Flow protection vs Error
  Tolerance). Structural gates — cycles,
  name collisions, terminal / list-closed, MI, reparent-cross-branch
  — still hard-reject, and the package corpus is still gated at sync
  time by `assert-sweep-failures-match-allowlist!` (a broken
  first-party fn still blocks boot/CI).
- *Phase 3 (done)*: the editor surfaces. `GET /partials/type-errors`
  (the `:_pterr-*` family + the `:type-diagnostics-list` graph
  reshape over the `:branch-diagnostics-flat` join base-fn) renders
  the current branch's recorded diagnostics as a sidebar section
  (`editor-type-errors.js`, error-log pattern); the `:subtree` graph
  payload carries `:type-error-count` per fn (⚠ badge on the card
  root row) and the `:tree` counts carry it per namespace (⚠ chip on
  ns rows). Two write-path gaps closed with it: the `/api/sequence/*`
  append/update cores run the same post-write check (+ additive
  `:type-warnings`), and a binding / binding-list-item DELETE re-runs
  the owner's check (an fn delete drops its stored entry).
- *Phase 4 (done)*: execute refusal. `apply-execute` consults the
  store for the resolved fn on the current branch BEFORE acquiring an
  execution slot; recorded diagnostics → the standard rejection
  envelope (`400`, `:error-data {:reason :unresolved-type-errors
  :diagnostics […]}`, message naming the fn + first error) instead of
  a runtime crash. See ERROR_CODES.md.
- *Phase 5 (done)*: branch merge policy. The `:branch` row gained a
  nullable `:forbid-invalid?` boolean (non-versioned entity — no
  mirror work; POST /api/branches accepts it). When the merge TARGET
  has it set, `versioning.merge.core/validate-branch-policy!`
  (called from `validate-merge!` AND from the live `:merge-branch!`
  base-fn after the target switch) refuses while recorded diagnostics
  exist on either side — `:merge-protection-violation`, 409 envelope
  naming the fns. Backend only; the editor branch popover may expose
  the flag later.
- *Ctx-build recompute (done)*: closes the former restart caveat —
  see the current-state note above for the mechanism and bounds.
- *Still true*: a failing fn's computed rich type drops from the
  registry (its effect strip / computed return go missing in the
  editor).
- NO `is_draft` flag — a branch IS the unit of work-in-progress. A WIP
  branch holds WIP fns; "draft-ness" is branch policy, not a per-fn
  column. Validity is a derived fact, not stored state.

---

### Git-like Versioning

Shipped — VersionedStorage + branches + HTTP API + editor UI + per-branch
executor routing. Design and known gaps live in [VERSIONING.md](VERSIONING.md).

### User and Permission System

Shipped as capability **grants** — `(subject, capability, namespace)` with
the seven `:read`/`:view-impl`/`:write`/`:execute`/`:admin`/`:bind-args`/`:append-list`
caps and namespace-prefix coverage (deliberately minimal — no deny rules),
plus org-management roles on top. The shipped code
(`graphden.tenancy.grant` / `authz`) lives in the private `graphden-tenancy`
repo; the core seams it plugs into are in [TENANCY_SEAM.md](TENANCY_SEAM.md).
