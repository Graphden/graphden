# PACKAGE_DISTRIBUTION.md — Distributable packages: the three module kinds

> Status: **DESIGN / IN-PROGRESS** on branch `feature/distributable-packages`.
> This document is the plan-of-record for splitting graphden into
> distributable modules. It is the concrete, decision-fixed successor to
> [PLATFORM_PLAN.md § 2 (Packages)](PLATFORM_PLAN.md) — read PLATFORM_PLAN
> first for the wider org/tenancy framing. The business/license framing is
> open-core AGPL: no app-store commission, and cloud-shared users cannot ship
> custom Clojure impls.
>
> **Nothing here starts from zero.** The registry (`:package-version` +
> publish/list/fetch/install), the round-trip exporter, the tenancy addon
> (orgs / users / grants / RLS / effect-gate / FaaS), and branch versioning
> are already shipped. This document records what those give us, the exact
> gaps, the architectural decisions that close them, and the task plan.

## Table of contents

1. [The three module kinds](#1-the-three-module-kinds)
2. [What already ships](#2-what-already-ships)
3. [Architecture decisions (ADRs)](#3-architecture-decisions)
4. [Type 1 — fns-only packages: full lifecycle](#4-type-1--fns-only-packages)
5. [Type 2 — impl+fns packages: full lifecycle](#5-type-2--implfns-packages)
6. [Type 3 — core-swap modules: seams and honest limits](#6-type-3--core-swap-modules)
7. [Schema deltas](#7-schema-deltas)
8. [API + editor surface](#8-api--editor-surface)
9. [Moderation + cloud→self-hosted export](#9-moderation--cloudself-hosted-export)
10. [Task plan + checkpoints](#10-task-plan--checkpoints)
11. [Risks](#11-risks)
12. [Getting started (self-hosted): Track A vs Track B](#12-getting-started-self-hosted)
13. [Self-hosted install by package type](#13-self-hosted-install-by-package-type)
14. [Swap recipes: storage, core-impls](#14-swap-recipes)
15. [Repository strategy & dev workflow](#15-repository-strategy--dev-workflow)
16. [Cloud assembly = self-hosted core + private addons](#16-cloud-assembly--self-hosted-core--private-addons)

---

## 1. The three module kinds

These map 1:1 onto the two distribution *channels* in PLATFORM_PLAN § 2.1 plus
the "swap a core component" case, and onto the DISTRIBUTION.md package tiers.

| Kind | Contents | Distribution channel | Who installs in cloud | Security boundary |
|---|---|---|---|---|
| **1. fns-only** | only `fns.edn` (compositions over existing impls) | our registry — EDN over HTTP → graph rows | **the tenant themselves** (it's data, not code) | effect-gate (a fns-package can only compose base-fns; what it may *do* is bounded by `:allowed-effects`, not by the absence of impl) |
| **2. impl+fns** | `impls.clj` (Clojure primitives) + optional `fns.edn` | deps.edn git-dep / Maven coordinate → classpath | **nobody** — we build it into the cloud image; tenants may only add its *fns* as a Type-1 reference | the impl is *our* code, compiled into *our* executor |
| **3. core-swap** | Clojure code replacing a core component (storage / auth / schema / executor / type-system / versioning) | deps.edn git-dep + Integrant addon config (`GRAPHDEN_ADDON_CONFIGS`) | n/a — self-hosted / cloud-operator only | it *is* the security/enforcement layer; cannot itself be a graph-package |

**Load-bearing invariant (PLATFORM_PLAN § 2.1):** every fns-package
transitively depends on at least one impl-package, because the eldest ancestor
of any fn is a base-fn with an impl. That transitive dependency becomes a
*declared* package dependency (see § 4.4).

---

## 2. What already ships

Verified against code on this branch. File references are anchors for the
implementation tasks.

### 2.1 Registry — immutable published artifact

- **`:package-version` entity** — `src/graphden/schema/packages/schema.clj`.
  Immutable, content-hashed EDN snapshot of a namespace subtree's fn-defs +
  declared dependencies. Columns: `name`, `version`, `ns-root`, `fns` (jsonb
  bundle), `dependencies` (jsonb), `content-hash`, `published-at`. NOT
  versioned (immutable by contract — the publish path rejects re-publishing an
  existing `(name, version)`).
- **Base-fns + HTTP routes** — `resources/packages/registry/registry/{fns.edn,impls.clj}`:
  `export-namespace`, `publish-package`, `list-package-versions`,
  `fetch-package-version`, `install-package`, wired to
  `POST /api/packages/publish`, `GET /api/packages`,
  `GET /api/packages/:name/:version`, `POST /api/packages/install`.
- **Round-trip exporter** — `src/graphden/packages/export.clj` (`export-namespace`):
  serialises a live namespace subtree back into a publishable `fns.edn`-shaped
  bundle. This is the reverse of the EDN→rows sync and is the core of both
  publish and self-hosted extract.

### 2.2 Loader — package discovery + external seam

- `src/graphden/packages/loader.clj` reads packages from the classpath
  (`io/resource "packages/<name>/package.edn"`), topo-sorts by
  `:dependencies`, loads each module's `fns.edn` (+ optional `impls.clj` via
  eval).
- **The external-package seam already exists**: `:app/packages` init-key
  (`src/graphden/system/core.clj:204`) concatenates `:package-names` with
  `:extra-package-names`. A package placed on the classpath under
  `packages/<name>/` and named in either list is loaded — this is the Type-2
  hook (§ 5).
- Config list today: `resources/system-dev.edn` → `["core" "storage" "web" "app-base" "app" "registry" "mcp" "examples"]`.
- **fn-ids are deterministic** on `(namespace, name)` via
  `graphden.packages.records.ids/fn-id`. This is the linchpin for versioned
  materialization (§ 4.2): the same fn name under a different namespace gets a
  different, stable UUID with no bookkeeping.

### 2.3 Tenancy addon — isolation, workspace, effect-gate

- **Two-layer isolation**: `OrgScopedStorage` decorator (own+public read,
  own write, stamps `org-id`) under `VersionedStorage`, plus Postgres RLS as
  the belt-and-suspenders lower layer. `src/graphden/tenancy/{storage.clj,rls.clj}`.
- **Workspace = union of `:read`-granted namespaces** —
  `src/graphden/tenancy/grant.clj` (`workspace`). Grants are
  `(subject, capability, namespace)`; capabilities
  `#{:read :write :execute :admin :bind-args :append-list}`.
- **`public` org** — the shared tenant; `OrgScopedStorage` returns
  own-org + public rows, so a public-org package is visible to every tenant
  **without copying**. This is the foundation of reference-install (§ 4.2).
- **Effect-gate** — `record-effect!` throws when an effect is outside the
  request's `:allowed-effects`. Cloud-forbidden set:
  `#{:env :io :network :process :raw-sql}`. This — not "no impl" — is the
  real cloud security boundary (PLATFORM_PLAN § 5).

### 2.4 Branch versioning — the staging + rollback engine

- Per-branch `ExecutionContext`, per-branch version rows, merge via
  `:branch-merge` records (no row copy — source versions become visible via
  resolution), `branch-local?` for runtime-config fns. `docs/VERSIONING.md`.
- **This is the package-upgrade staging engine** (PLATFORM_PLAN § 2.4): test
  a new package version on a branch, merge to main, revert to roll back. We
  reuse it wholesale (§ 4.3) rather than inventing package-version staging.

### 2.5 The gaps this branch closed

All shipped except moderation (below). Kept as a record of what the branch set
out to close.

1. ✓ Version **constraints** in `:dependencies` (Task 2) — package.edn accepts
   constraints; the loader resolves them (§ 4.4).
2. ✓ Reference-install (§ 4.2, Tasks 3+4) — `install-package` now installs by
   REFERENCE (materialize-once under `<ns>@<version>` + a `:package-install`
   pin), NOT by copying rows — the PLATFORM_PLAN § 2.8 "install = grant of
   visibility" model.
3. ✓ `:package-install` **pin** entity + update/rollback ref-rewrite (Tasks 3+4).
4. ✓ Type-2 external-package **manifest** + docs (Task 5) — proven by `mathx`
   via git coord (§ 5.1).
5. ✓ Type-3 swap **seams**: documented + one proven (Task 6, § 6.3 —
   `extension_seam_test`).
6. ✓ Cloud→self-hosted export (`GET /api/export/graph`, § 9).
   Moderation queue is **deferred** (a cloud-control-plane / public-registry
   concern; self-hosted↔self-hosted needs none — § 9).

---

## 3. Architecture decisions

### AD-1 — Reference install is materialize-once + reference, NOT per-tenant copy

**Decision.** A published package version is materialised **once** into the
`public` org under a **version-qualified namespace** (`<ns-root>@<version>`,
e.g. `web.components@1.3.0`). Every tenant sees those rows via
`OrgScopedStorage` (own+public read). A tenant that "uses" a package fn holds a
**reference** (`ref-fn-id`) from their own fn into the public-org fn — they do
**not** copy the package's rows into their own graph.

**Why.** This is the only model that satisfies the stated requirement — "no
copying package data into each project's DB, just references" — while keeping
per-project independent versions. It reuses the already-shipped org-scoping:
public-org rows are *already* visible cross-tenant without duplication.
Deterministic `fn-id(ns, name)` makes each version's fns distinct and stable
for free.

**Rejected — per-tenant copy (today's `install-package`).** Simple and makes
per-project versions "free" via branches, but duplicates every package's rows
into every project and is "fork by default." Kept only as the explicit
copy-on-write *fork* path (§ 4.5), not as install.

### AD-2 — Version switch rewrites the project's own refs (variant B), NOT compiler late-binding (variant A)

**Decision.** Changing a project's pinned package version rewrites **only that
project's own package-referencing bindings** (on the current branch) to point
at the new version's namespace fn-ids — computed with the deterministic
`fn-id(<ns-root>@<new-version>, fn-name)`. Package rows are never touched. The
rewrite runs on a branch, so update/rollback reuse branch-staging and
merge/revert.

**Why.** The compiler resolves references as concrete UUIDs looked up in
`child-callables` (`compile_eager.clj:238-240,431-436`); the compile cache key
(`compile_eager.clj:729-741`) is graph-shape + base-fns with **no** per-request
pin dimension. True name-late-binding (variant A) would add a symbolic-ref
edge type, a compiler pre-pass, and a pin dimension to the cache key — a
hot-path change that violates principle #2 (minimal entities) and #7 (locality)
to buy O(1) rollback that branch-revert already provides. Variant B needs **no
compiler change and no new edge type**: it is a bounded, branch-scoped rewrite
of the user's own data (which must change semantics on a version switch anyway).

**Rejected — variant A (symbolic `{:pkg :fn}` ref resolved through a per-branch
pin map in a `compile-all*` pre-pass).** Cleaner in the abstract (Unison-style
content-addressed indirection, pin-only version switch), but the seam analysis
shows it touches parse + compile + cache-key + type-check + cycle-constraints.
Recorded here so a future reader does not re-attempt it without weighing the
hot-path cost. If per-fn version *coexistence within a single project* is ever
needed (two versions of the same package live in one graph), revisit A — B
cannot express that.

### AD-3 — Type-2 distribution is deps.edn git-deps + a data manifest, NOT Polylith / submodules

**Decision.** An impl-package is an ordinary Clojure library: its own git repo
with `packages/<name>/` resources (`package.edn` + `fns.edn` + `impls.clj`) and
a `deps.edn`. Self-hosters add its coordinate. A data manifest
`executor-packages.edn` (list of `{:coord … :package "name"}`) is spliced by
`build.clj` into `deps.edn :extra-deps` **and** the loader's package-name list,
so the operator edits one data file and never touches executor code.

**Why.** The loader already reads from the classpath and already has the
`extra-package-names` seam; git-deps is the Clojure-native distribution. This
manifest is distinct from the tenancy addon manifest (`GRAPHDEN_ADDON_CONFIGS`,
which injects Integrant *config* — a different concern, § 6). Polylith is a
monorepo-organisation tool, not required for swappability (protocols + Integrant
already provide it); submodules are strictly worse than git-deps.

### AD-4 — Type-3 swappability is protocol + Integrant + `extend-builder`; storage-schema extension is already the answer

**Decision.** Core components are swapped by implementing their protocol
(`StorageCRUD` / `ExecutionGraph` / `data-schema-protocol` / `AuthProvider`)
and wiring the implementation through Integrant via the addon config manifest.
The user's specific worry — "a new storage backend must know about every index
and extra table other modules added" — is **already solved** by
`data-schema-protocol` + `extend-builder`: each module contributes its
entities/fields to a shared schema *builder* (that is how `:package-version`,
`:org`, `:grant` were added), and a storage backend consumes the *same* builder
output to create its tables/indexes/equivalents. There is therefore a single
storage interface through which modules add fields/tables/indexes. We document
the seams and prove one swap; we do **not** commit to making every core
component swap-clean now (principle #6 — no unnecessary expressiveness), and we
honestly mark the tightly-coupled trio (executor ↔ storage ↔ editor) as
"documented coupling," not "clean swap."

---

## 4. Type 1 — fns-only packages

### 4.1 Storage of the artifact (unchanged)

The distributable unit is the immutable `:package-version` bundle (§ 2.1). It is
the same artifact whether it lives in our registry (cloud) or is written to an
`.edn` file (self-hosted). Content-addressed by `content-hash` over the fn
bundle. This is the "download as EDN" format the user asked about — a single
self-describing EDN map, not a directory of files (nested namespaces are carried
as `:namespace` on each fn-def, reconstructed on install).

### 4.2 Publish

- **Cloud user, no files by hand**: `POST /api/packages/publish {name, version, ns-root}`
  → `export-namespace` serialises the live subtree → `publish-package` hashes +
  inserts the `:package-version` row. Already shipped.
- **Self-hosted, registry-independent**: the same `export-namespace` returns the
  bundle; the user writes it to their git repo as `fns.edn`. Registry optional.
- **Making a version available in cloud (new — Task 3)**: a publish (or an
  admin "release" step) **materialises** the bundle once into
  `public` org under `<ns-root>@<version>`, syncing its fn-defs with
  `sync-fns-to-storage!` against the public-org, versioned-ns namespace. Rows
  live once, globally. The unqualified `<ns-root>` may carry a "latest" alias
  (a thin convenience, not required for correctness).

### 4.3 Install / update / rollback (reference + pin — Tasks 3, 4)

- **Install** `POST /api/packages/install {name, version}` (branch-scoped, as
  today) becomes:
  1. resolve dependencies (§ 4.4); reject on missing/unsatisfiable;
  2. ensure the requested version is materialised in public-org (§ 4.2);
  3. write a **`:package-install` pin** row `(branch-id, package-name, version)`;
  4. grant `:read` on `<ns-root>@<version>` so the package enters the workspace.
  No fn rows are copied into the project.
- **Reference a package fn**: when the user composes with a package fn (drops it
  into their graph in the editor, or writes a fn-def referencing it), that
  stores a `ref-fn-id` into the public-org fn — a reference, not a copy.
- **Update** `POST /api/packages/install` with a newer version (or a dedicated
  `PUT /api/packages/pin`): materialise the new version, repoint the pin,
  **rewrite the branch's own bindings that reference the old version's ns** to
  the new version's fn-ids (AD-2), re-grant. Do it on a test branch, verify,
  merge to main.
- **Rollback**: repoint the pin to the older version and rewrite refs back, or
  `git revert` the merge on main. Reuses branch/merge/revert wholesale.
- **Notifications**: registry stores latest; a periodic check compares each
  pin's version against registry-latest per org (later checkpoint).

### 4.4 Dependencies + versions

- **Loader-side constraints (shipped, Task 2):** `package.edn :dependencies`
  accepts version constraints — `{"core" ">=1.5.0", "web" "~>2.1"}` alongside
  legacy bare names (bare ≡ any). The loader validates the classpath version
  against every constraint at boot (`graphden.packages.semver` +
  `validate-dep-constraints!`), throwing `:packages/version-conflict` early.
- **Install-time version selection (shipped, 3d-4):** `install` / `fork` /
  `materialize` accept an exact version, a semver constraint (`>=1.1`, `~>1.2`,
  `*`), or `latest`; `resolve-version` picks the highest published match and
  pins/materialises that concrete version. Unsatisfiable → `not-found`.
- **Install precondition (shipped):** every declared FN dependency of a bundle
  must already be present, else `missing-dependencies` before any write.
- **Recursive PACKAGE pull (shipped):** `install` auto-resolves a package's
  *package* dependencies. Publish now records them: `export-namespace` reverse-
  maps each external dep fn's namespace — the versioned ones (`<ns-root>@<v>`,
  materialised by another installed package) → the published
  `:package-version` (name+version) that owns them — into a new
  `:package-dependencies` field on the row (`{:name :version}` list;
  platform-only packages get `[]`). `install-package` then pulls those
  packages FIRST, depth-first with a `[name version]` cycle guard — since the
  graph decomposition it is a `:fix` worklist loop of graph fn-defs (the
  `:_inst-*` chain in `registry/registry/fns.edn`) over the base-fn primitives
  (`:resolve-package-version` / `:missing-package-dependencies` /
  `:package-version-materialized?` / `:materialize-package-fns` /
  `:package-upsert-pin`) — so a package's cross-package refs
  resolve without manual ordering. Best-effort on ns-root sharing: if several
  packages publish the same `(ns-root, version)`, the first registry match is
  recorded (they materialise the same rows). The name-based
  `missing-dependencies` precondition still guards genuinely-absent platform
  fns after the package pull.

### 4.5 Fork (copy-on-write)

Intentional modification of a public package = copy its subtree into the user's
own namespace (this is where today's copy-`install-package` logic is retained,
renamed to a fork operation). Only then do rows get duplicated, and only into
the forker's own org — a deliberate act, not the default.

---

## 5. Type 2 — impl+fns packages

### 5.1 Storage + distribution (shipped)

- The package is a git repo / Maven artifact carrying `packages/<name>/`
  resources on the classpath. No registry row — distribution is the Clojure
  dependency graph, versioned by git tag / Maven version.
- **`resources/executor-packages.edn` manifest** — the operator's single data
  file. Each entry `{:name "telegram" :lib com.acme/graphden-telegram :coord {…}}`
  (`:coord` is a `deps.edn` git-dep or `:mvn/version`). Read by
  `graphden.packages.manifest`; two consumers so the operator edits ONE file:
  - `build.clj` merges `extra-deps` into the uberjar basis → the external
    package's resources are bundled;
  - `:app/packages` appends `package-names` to the loaded list → the loader
    picks them up at runtime.
- **Dev classpath** is Clojure-native and separate: while developing an external
  package, add its coordinate (or a `:local/root` override, § 15) to `deps.edn`
  / a gitignored `deps.local.edn`. The manifest drives the *build* + *load*;
  the dev CLI classpath is driven by `deps.edn` as usual.
- **Proven end-to-end via a real git coord (`mathx`, Track B):** the standalone
  repo [`graphden/graphden-mathx`](https://github.com/graphden/graphden-mathx)
  (`@a99354d1`) is a real out-of-tree Type-2 package — a `:gcd` base-fn
  (`ops/impls.clj` `defbase` + the `impls` var linking it) plus a
  `:gcd-with-12` composed fn-def (`ops/fns.edn`). It is pulled in by a **git
  coord**, not a local path:
  - `executor-packages.edn` + `deps.edn` root `:deps` list
    `{:git/url "git@github.com:graphden/graphden-mathx.git" :git/sha "a99354d1…"}`.
    `bb rebuild` **clones the package straight from GitHub** into the uberjar
    (build.clj merges the manifest coord into the basis). The running instance
    loads it (base-fn count 249 → 250, `mathx.ops` synced) and
    `POST /api/execute` of `:gcd-with-12 {b 18}` returns `6`.
  - **Hermetic dev/test:** the `:test` + `:dev` aliases carry
    `:override-deps {mathx/mathx {:local/root "external-packages/mathx"}}`, so
    `bb test` / `bb dev` resolve the identical in-tree copy — **no repo access,
    no ssh-agent, offline**. `external-packages/mathx/` is kept in-tree as the
    override source (and matches the pushed repo at `@a99354d1`).
  - **Access requirement:** none — the repo is **public** and the coord is an
    https URL, so `bb rebuild` / `bb check` / any non-`:test`/`:dev` invocation
    resolves it anonymously, no credentials or ssh-agent. (If you fork it into a
    private repo, add the build host key as a read-only deploy key.)
  - Tests: `manifest-test/external-mathx-package-loads-impl-and-fn-def` (loader
    layer, offline via override).
  - **`defbase` gotcha proven here:** the macro rewrites every occurrence of an
    arg symbol into a `resolve-arg` call, so an impl must not shadow its arg
    names in a `loop`/`let` (mathx's gcd loops on `x`/`y`, not `a`/`b`).

### 5.2 Install / update

- **Self-hosted**: add the coordinate to the manifest, rebuild. `bb rebuild`
  loads the new package's base-fns + fn-defs. Update = bump the git-sha/version
  in the manifest.
- **Cloud**: tenants **cannot** install Type-2 (it is Clojure code on our
  executor — would breach isolation). We vet and bake approved impl-packages
  into the cloud image. A tenant's only path to an impl-package's capability is
  to add its **fns** as a Type-1 reference (§ 4) — the impls then run inside our
  trusted, effect-gated executor.

### 5.3 Dependencies

Permissive: an impl-package may depend on other packages (impl or fns) — the
loader already topo-sorts. We do **not** forbid mixed impl/fns packages up
front (per the stated preference); the minimalism guidance (one impl OR one
composition concern per package) stays a *recommendation* in PACKAGES.md, not a
hard rule. Revisit only if a concrete problem appears.

---

## 6. Type 3 — core-swap modules

### 6.1 What is already swappable

- **Storage backend** — implement `StorageCRUD` + `ExecutionGraph`; wire via
  Integrant. `VersionedStorage` / `OrgScopedStorage` prove the decorator stack.
- **Schema extension** — `data-schema-protocol` + `extend-builder`: a module
  adds entities/fields/indexes to the shared builder; the storage backend
  materialises them. **This is the "single storage interface that lets modules
  add tables/fields/indexes" the design question asked for.** A new backend
  (e.g. a non-Postgres store) consumes the same builder and provides its own
  equivalents (indexes → its indexing primitive, etc.).
- **Auth** — `graphden.auth.provider/AuthProvider` protocol + `:auth/provider`
  Integrant key (default `SingleTokenAuthProvider`; tenancy addon overrides).
- **Tenancy / org isolation** — the whole addon proves the "optional
  Integrant addon injected via `GRAPHDEN_ADDON_CONFIGS`" pattern.

### 6.2 What is NOT cleanly swappable yet (honest limits)

- **Executor core / type-system / versioning-decorator** are protocol-bounded
  but no one has replaced them; treat them as "swap seam documented, not
  proven." Swapping the type system in particular reaches into the editor
  (rich-types, overlays) — a documented coupling, not a clean seam.
- **The executor ↔ storage ↔ editor trio** is intentionally coupled: storage
  carries indexes/extra tables that many modules rely on; the editor renders
  storage-shaped data. `extend-builder` keeps *storage* swap-clean despite this;
  the editor coupling is accepted, not abstracted (principle #6).

### 6.3 Swap-seam matrix (status + how)

The honest answer to the original design question ("can we replace storage /
executor / types / versioning / permissions?"). "Proven" = shipped code + a
test exercises the seam.

| Component | Seam | Injected via | Status | Proof |
|---|---|---|---|---|
| Storage **decorator** | wrap the `:app/storage` chain | Integrant key + addon config | **proven** | `VersionedStorage`, `OrgScopedStorage` (tenancy addon tests) |
| Storage **backend** | `StorageCRUD` + `ExecutionGraph` + `Constraints` | override the backend init-key | **partial** | protocol exists; only Postgres implements the recursive-CTE `ExecutionGraph`. Swapping to a non-Postgres store = real work (see § 14) |
| **Schema extension** | `data-schema-protocol` `extend-builder` | chain an `extend-builder` fn | **proven** | `extension-seam-test` (arbitrary third-party entity accepted) + `:package-install` storage round-trip (registry-test) |
| **Auth** | `AuthProvider` protocol | `:auth/provider` Integrant key | **proven** | `SingleTokenAuthProvider` default; tenancy `TokenAuthProvider` override |
| **Tenancy / org isolation** | optional addon | `GRAPHDEN_ADDON_CONFIGS` | **proven** | the whole tenancy addon (orgs / RLS / effect-gate) |
| **Executor core** | `ExecutionGraph` result contract | — | **documented, unproven** | protocol-bounded; nobody has replaced it |
| **Type system** | — | — | **coupled** | reaches into the editor (rich-types, overlays) — a documented coupling, not a clean seam |
| **Versioning decorator** | it's a storage decorator | omit `VersionedStorage` from the chain | **documented, unproven** | mechanically a decorator, but the branch-router assumes it — untested to remove |

**"Prove one" (fresh):** `test/graphden/schema/extension_seam_test.clj` — a
deliberately third-party `extend-builder` adds a novel `:widget` entity chained
after the core graph schema; the built schema carries it with its declared
fields AND still carries the core `:fn`. Combined with the `:package-install`
storage round-trip (a builder-added entity that a real Postgres backend
materialises + CRUDs), this proves the "a new module adds its own tables
without touching the storage backend" claim end-to-end. We deliberately do NOT
attempt to make every core component swap-clean (principle #6) — the matrix
above says exactly which are proven vs documented vs coupled.

---

## 7. Schema deltas

| Entity | Change | Versioned? | Rationale |
|---|---|---|---|
| `:package-version` | unchanged | no | immutable artifact (shipped) |
| `:package-install` | **new** — `(id, branch-id, package-name, version, org-id)`; UNIQUE `(branch-id, package-name)` | no | desired-state pin, same class as `:service`; per-branch so it lands in the branch's compile context with no new cache dimension |
| `package.edn :dependencies` | value may be `{"name" "constraint"}` | — | version constraints (Task 2); bare string still accepted |

`:package-install` is **not** versioned (like `:service`, `:fn-execution`,
`:package-version`) — it is runtime desired-state, not graph semantics. It
carries `org-id` and is a **tenant-forbidden**-adjacent decision: a tenant may
read/write their *own* org's pins (to install/update packages in their project)
but not others' — so it is org-scoped, not in `tenant-forbidden-entities`.

No change to `:binding` / `:binding-list-item`: references stay `ref-fn-id`
UUIDs (AD-2). No symbolic-ref column.

---

## 8. API + editor surface

**API (extends the shipped `/api/packages/*`):**

- `POST /api/packages/publish` — unchanged (+ optional materialise-to-public step).
- `GET /api/packages` — registry index (+ latest-version surfacing).
- `POST /api/packages/install` — now writes a pin + grant (reference model).
- `PUT /api/packages/pin` (or reuse install) — change pinned version (update/rollback).
- `POST /api/packages/fork` — copy-on-write into the user's namespace (§ 4.5).
- `GET /api/packages/installed` — this branch's pins (for the editor).
- `GET /api/packages/:name/:version` — full bundle (export / self-hosted pull) — shipped.

**Editor (shipped):** a "Packages" sidebar section (parallels the Secrets /
Grants admin sections) lists the current branch's installed pins with their
version, a per-row update/rollback version input (`↑`) + uninstall (`×`), the
registry as a nested `<details>` browse (Install / Fork per version), and a
"Publish a namespace" form. Server-rendered via `GET /partials/packages-panel`;
`editor-packages.js` owns the collapsible-section lifecycle only. (The only
piece NOT built is a proactive "update available" indicator — the manual
version input covers update/rollback.)

---

## 9. Moderation + cloud→self-hosted export

- **Moderation** (self-hosted → our public registry): a published version enters
  a `:pending` moderation state; an admin review promotes it to `:public`
  (visible/installable by cloud tenants). Model as a `status` field on
  `:package-version` (or a small `:package-moderation` row) — decide at that
  checkpoint. Self-hosted → self-hosted sharing needs no moderation (direct
  git/EDN exchange).
- **Cloud → self-hosted export** — the open-core piece **shipped**:
  `GET /api/export/graph` (auth-required) returns the WHOLE graph (current
  branch/scope) as an EDN migration bundle `{:fns [fn-def …] :namespaces
  [dotted …]}` — the "download my whole project" artifact. It reuses
  `export/export-graph` (the same records→fn-defs machinery as
  `export-namespace`, just unscoped) via the `:export-graph` base-fn +
  `:edn-ok-response`. **EDN, not JSON**, so fn-def keywords (`:parent :add`,
  refinement chips, …) round-trip faithfully. **Import is idempotent-additive:**
  because fn-ids are deterministic from `(namespace, name)`, syncing the bundle
  onto a booted self-hosted install re-writes the platform fn-defs as no-ops and
  only adds the caller's own fns — so no platform-namespace filtering is needed.
  Refs that can't be spelled as readable keywords - a duplicated name
  in a version-materialized `@`-ns or the ROOT (nil) ns - ride the
  bundle as `#graphden/ref "lib@1-2-0.sub/name"` / `#graphden/ref
  "/name"` tagged literals (`records.wire`); the package loader and
  the remote-bundle reader decode them back to the same qualified
  keywords. Tests: `export-test/export-graph-bundle-shape`,
  `export-test/{roundtrip-unspellable-ns-duplicates,wire-edn-text-roundtrip}`,
  `registry-test/export-graph-base-fn-and-handler`.
  - The paywall/billing **gate** stays a cloud-control-plane concern (closed
    source, per DISTRIBUTION.md) — the open-core executor just exposes the
    capability; the control plane decides who may call it.
- **Secret-path policy** (both export AND publish): vault paths
  (`:override-kind :secret-path` bindings) are **stripped by default** and
  manifested in `:secrets` + `:secret-paths-included?` on the bundle — the
  publisher sees a stripped-secrets notice, the installer gets the manifest
  back as `:needs-definition` in the install envelope (persisted on the
  `:package-version` row's nullable `:secrets` column) plus a Packages-panel
  notice. `?include-secret-paths=true` on `GET /api/export/graph` opts in for
  org-internal migration. Full rationale + round-trip form:
  [SECRETS.md § Sharing / export policy](SECRETS.md).
  Tests: `export-test/{roundtrip-secret-path,strip-secret-paths-policy}`,
  `registry-test/publish-carries-secrets-manifest-install-reports-needs-definition`.

---

## 10. Task plan + checkpoints

Tracked in the session task list. Order chosen for
"cheapest self-contained value first, deepest last," each a commit checkpoint
(rebuild + verify + smoke after every backend change, per the rebuild rule).

1. **This design doc** — plan-of-record. ← current
2. **Version constraints** (Task 2) — self-contained loader change; unlocks
   correct dependency resolution for every later step.
3. **Reference install: public-org materialization + `:package-install` pin**
   (Task 3) — schema + materialise step + pin CRUD; install writes a pin+grant
   instead of copying.
4. **Pin-change ref-rewrite + resolver** (Task 4) — update/rollback via
   branch-scoped ref rewrite; fork = the retained copy path.
5. **Type-2 external loading + manifest** (Task 5) — `executor-packages.edn` +
   `build.clj` splice + docs.
6. **Type-3 swap seams: document + prove one** (Task 6).
7. **Publish graphden as a consumable artifact** (Task 7) — **git-dep flavour
   shipped:** the first semantic release tag `v0.1.0` is the consumable
   coordinate a downstream `deps.edn` pins (`:git/tag "v0.1.0" :git/sha …`) —
   `graphden-cloud` does exactly this. The editor/`app` is already an *opt-in*
   package chosen via `:package-names` (drop `"app"` for headless — proven by
   `headless_boot_test`). Does **not** split the monorepo — it emits the
   artifact; app/editor stays as an optional package. **Remaining polish
   (credential-gated):** a Clojars `com.graphden/graphden-core` jar via
   `b/jar` + `deps-deploy` — a versioned Maven coordinate that ships built
   classes (not the whole repo). Not needed to consume graphden today.
8. **Shipped:** editor Packages panel (§ 8) + cloud→self-hosted **export**
   (`GET /api/export/graph`, § 9). **Deferred:** moderation queue (a
   cloud-control-plane / public-registry concern — § 9).

Each backend checkpoint: `bb rebuild` → `bb verify` all-match → smoke all-✓,
and `bb ci` green before it is called done. Roll back the branch if the whole
shape proves wrong — one branch, no proliferation.

---

## 11. Risks

- **R-copy-to-reference migration**: existing demo data installed via the
  copy path must not break when install switches to reference. Mitigation:
  keep copy logic as `fork`; migrate demo seeders to the pin model on a clean
  DB (declarative-sync-needs-clean-baseline rule).
- **R-pin-rewrite correctness**: rewriting a branch's package-refs on version
  switch must be exact and reuse the type-check/conflict path so a breaking
  package upgrade surfaces as a conflict, not a silent mismatch. Mitigation:
  route the rewrite through the same validation `sync-fns-to-storage!` uses.
- **R-public-org bloat**: materialising every version of every package into
  public-org grows unboundedly. Mitigation: materialise lazily (on first
  install of a version) + a GC for versions no pin references.
- **R-effect-gate is the boundary, not "no impl"**: a fns-package can compose
  effectful base-fns; the gate (`:allowed-effects`) is what actually protects
  tenants. Any new install path must run under the tenant's gated request scope.
- **R-cache-key untouched (AD-2)**: because we chose variant B, the compile
  cache key stays graph-shape-only; do not accidentally introduce a per-request
  pin dimension — that would silently multiply compiled registries.

---

## 12. Getting started (self-hosted)

Two consumption models. **A** works today (graphden is an application you run).
**B** is the target for anyone customising the core — it is the only fork-free
way to swap storage / auth / core-impls, and it is how our own cloud is
assembled (§ 16). Both are supported; they differ in *who owns the build*.

### Track A — "run graphden as it ships" (works today)

1. `git clone <repo> && cd graphden`
2. Point at Postgres: `export GRAPHDEN_JDBC_URL=jdbc:postgresql://host:5432/graphden`
3. `bb rebuild` (or `docker compose up -d`)
4. Editor + API on `:8080`.

Customising here means editing the checkout / building a custom image — a mild
fork with a rebase burden. Fine for a one-off; for a product, use Track B.

### Track B — "graphden as a dependency in your own project" (works via git-deps today)

The developer creates a thin project that depends on graphden as a **git-dep**
plus whatever they swap in. No fork, and — for git-deps — **no published
artifact needed**: the Clojure CLI clones the repo at a sha and puts its
`:paths` (`src` + `resources`) on the classpath. The dependency key is an
arbitrary local identifier (it need not match any published coordinate).

1. `mkdir my-graphden && cd my-graphden`
2. `deps.edn` — graphden (git-dep) + your addon(s):

   ```clojure
   {:paths ["src" "resources"]
    :deps {com.graphden/graphden {:git/url "https://github.com/you/graphden"
                                  :git/sha "<sha>"}                    ; graphden itself
           acme/graphden-sqlite  {:git/url "…" :git/sha "…"}}}         ; your backend
   ```

   During active dev of the addon, override to `{:local/root "../graphden-sqlite"}`
   in a gitignored `deps.local.edn` (§ 15) — edit-in-place, no push/pull loop.
3. `resources/my-config.edn` — an Aero fragment overriding the seam + choosing
   packages. **Omit `"app"` to run headless** (no editor / default web-server —
   its resources ride the classpath but are not loaded):

   ```clojure
   {:app/storage    {:base #ig/ref :sqlite/backend}
    :sqlite/backend {:db-path "/data/graphden.db" :schema #ig/ref :db/schema}
    :graphden/require [acme.graphden.sqlite]
    :app/packages   {:package-names ["core" "storage" "web"]}}   ; drop "app" for headless
   ```

4. Run — reuse graphden's `-main`:

   ```bash
   GRAPHDEN_ADDON_CONFIGS=my-config.edn clojure -M -m graphden.executor-runtime.core
   ```

   (or your own three-line `-main` calling `graphden.system/start!` for full control.)
5. A graphden engine running on **your** storage, no Postgres, no fork.

Headless boot (`["core" "storage" "web"]`, no `app`) is verified end-to-end by
`test/graphden/system/headless_boot_test.clj` — the packages load + sync and the
executor evaluates a fn with the editor absent.

**Caveat (git-deps pulls the whole repo).** A `:git/url` on this repo brings the
editor's `resources/packages/app/` along; omitting `"app"` from `:package-names`
leaves them present-but-unloaded. A cleaner core-only dep (`:deps/root` on a
`core/` subdir) or a Clojars **library-jar** (a `com.graphden/graphden-core`
Maven coordinate via `b/jar` + `deps-deploy`) is a later polish — needed only
for a versioned public artifact, not to consume graphden today.

**Live example of the reverse direction (an external package pulled INTO a
graphden build).** [`graphden/graphden-mathx`](https://github.com/graphden/graphden-mathx)
is exactly a Track-B-style thin repo, consumed the other way round: this repo
lists it by git coord in `deps.edn` + `executor-packages.edn`, and `bb rebuild`
clones it into the uberjar. See § 5.1 "Proven end-to-end via a real git coord"
for the full wiring (git coord for build/prod, `:override-deps` local for
hermetic `bb test`/`bb dev`, and the private-repo ssh-agent access note).

---

## 13. Self-hosted install by package type

| Type | Channel | How self-hosted installs | Rebuild? |
|---|---|---|---|
| **1 fns** | data | boot-bundle under `resources/packages/`, **or** `POST /api/packages/install` (EDN bundle / registry pull) | no — runtime, on a branch, then merge |
| **2 impl+fns** | code (git-dep) | add coordinate to `executor-packages.edn` → `bb rebuild` | yes |
| **3 core-swap** | code + Integrant | add dep + config fragment in `GRAPHDEN_ADDON_CONFIGS` → restart | yes |

**Type-1 sources:** (a) bundled in the build (`resources/packages/<name>/`,
loaded at boot like core/web/app); (b) an `.edn` bundle imported at runtime via
`POST /api/packages/install` (the exporter's format *is* the install format);
(c) pulled from a remote registry over HTTP (`fetch-package-version`). All sync
onto a branch → test → merge; update = install newer version + merge; rollback
= revert / re-install older.

**Ordering rule:** a fns-package transitively depends on an impl-package (§ 1).
So install impl-dependencies first (Type-2, rebuild), then the fns-package
(Type-1, runtime). Missing deps are rejected before any write.

---

## 14. Swap recipes

Two different mechanisms, because storage and core-impls live at different
layers. Concrete steps in § 12 (Track B) and § 5 (Type-2); the decision matrix:

| Motivation | Mechanism |
|---|---|
| different DB / storage engine | **Integrant seam** `:app/storage` — protocol impl + `:db/schema` builder (§ 12 Track B, § 6.1) |
| a base-fn impl is written badly, interface is fine | **override package** — a small `impls.clj` redefining specific base-fns, declaring `:overrides ["core"]`, loaded after core (§ 5 / below) |
| rewrite a whole graph subsystem | **replace a module** — your fns in the same namespaces (same deterministic `fn-id`) |
| fork graphden wholesale | edit the monorepo checkout (AGPL-native) |

### Override a core base-fn impl (Type-2, targeted — the recommended path)

`register-base-fn!` is a last-wins `assoc`, and declaring a dependency on the
package you override places you after it in the topo-sort — so an override is
expressible today. What we **add** (Task 6): make it *explicit* — a package
declares `:overrides ["core"]` (or per-fn), the loader applies defined
precedence and **logs** `core/add impl overridden by my-core/add` (principle #3;
silent last-wins is the current footgun). Example:

```clojure
;; packages/my-core/arithmetic/impls.clj
(defbase add [nums] (my-faster-sum nums))   ; same contract, better impl
(def impls {:add add})
;; packages/my-core/package.edn
{:name "my-core" :dependencies ["core"] :overrides ["core"] :modules ["arithmetic"]}
```

You keep upstream's `fns.edn` (graph structure) — only the Clojure closure
diverges, so graph-shape updates still flow from upstream.

### Swap storage — what our impl does when overridden

Integrant only instantiates keys reachable from the system config. Override
`:app/storage` → our `:db/postgres` is simply never built. It is an unselected
default (a few KB of unused classes in the jar), **not** dead code — most
self-hosters keep Postgres and only stack decorators. The real work in a swap is
implementing `ExecutionGraph` (Postgres uses recursive CTEs) in the new backend
and providing/omitting the Postgres-specific `NOTIFY` invalidation +
advisory-locks — all in the *addon's* lib, our repo untouched.

---

## 15. Repository strategy & dev workflow

**We do NOT split the monorepo.** "Distributable packages" means giving
*third parties* a clean boundary to publish into and giving self-hosters a way
to *add* packages — not breaking up our first-party code. First-party packages
(`core`, `web`, `storage`, `app`/editor) stay in the monorepo and ship as the
build artifact (Task 7 publishes it; the monorepo can emit multiple artifacts).

- **Extract only** genuinely-independent things: third-party packages (external
  by nature) and noise/domain/example packages (PLATFORM_PLAN § 2.9). Candidate
  first extraction as a mechanism proof: the `examples` package.
- **Inner-loop for co-developed external packages:** Clojure `:local/root`
  override in a gitignored `deps.local.edn` — edit the package's working copy in
  place, `bb rebuild`, no push/pull/SHA-bump. The SHA bump in the manifest is
  the *release* step, not the dev loop. This replaces git submodules (rejected).
- **Bug-fix workflow by origin:** first-party bug → fix in monorepo, `bb rebuild`
  (unchanged from today, the common case). External package you own → `:local/root`
  override, fix locally, then push + bump. Third-party you don't own → fork +
  `:local/root`, PR upstream. Only the *release* has multi-repo friction.
- **Polylith:** not adopted. It organises a monorepo (the opposite of "extract"),
  and swappability already comes from protocols + Integrant. `:local/root` +
  the manifest solve the multi-repo-pain the design question raised. Revisit
  only if internal component sprawl becomes a real problem. (PLATFORM_PLAN § 2.2.)

### 15.1 As-built repo map

The rule above, made concrete. A separate repo is paid ONLY where independence
is real; modularity itself comes from packages + protocols/Integrant, in-tree.

| Repo | Kind | How it relates to the monorepo | Access |
|------|------|--------------------------------|--------|
| `graphden/graphden` (this) | monorepo | core / web / storage / **app-base** / app-editor / **registry** / **mcp** / **tenancy-admin** — all co-evolving first-party, in ONE tree. Emits the uberjar; can emit a `graphden-core` artifact (Task 7). | private |
| `graphden/graphden-mathx` | external Type-2 pkg | pulled IN by git coord (`deps.edn` + `executor-packages.edn`); in-tree copy at `external-packages/mathx` for offline test (§ 5.1). | public |
| `graphden/graphden-examples` | extracted dev pkg | the pedagogical `examples` package moved OUT; in-tree at `external-packages/examples`, on the classpath only via the `:dev`/`:test` `:extra-paths` (never prod). | private |
| `graphden/graphden-cloud` | thin consumer | depends on graphden as a **git-dep**, turns the tenancy addon on, adds cloud modules (`usage-metering` …). NOT a fork (§ 16). | private |
| *future* private cloud modules | closed addons | attach to `graphden-cloud` via `GRAPHDEN_ADDON_CONFIGS` (billing / metering sinks / at-scale routing). | proprietary |

What's NOT extracted, on purpose: the Postgres storage impl (a swap *seam*
exists — § 6.3 — but the default first-party backend co-evolves with schema /
versioning / executor, so it stays in-tree); a non-Postgres backend is external
work in the *consumer's* addon, not a monorepo split.

**In-tree first-party PACKAGE split (still one repo).** The `app` monolith was
decomposed into cohesive packages *without* a repo split — modularity from
packages, not repos:

- **`app-base`** — the reitit route-building vocabulary (`:route` /
  `:route-with-middleware`, the per-method + auth route templates, `app.common`
  helpers). Deps `core`+`web`, NO `app` dep, so the tenancy addon AND `registry`
  reach the templates without dragging in the editor. `tenancy-admin` now depends
  on `app-base`, not `app`.
- **`registry`** (in-graph publish/install/fork/export) and **`mcp`** (the `/mcp`
  JSON-RPC AI endpoint) are OPTIONAL top-level packages: drop either from
  `:package-names` and the app still boots (the editor hides its Packages panel
  via a `window.API` probe; the endpoints 404). `app`'s router no longer
  references their routes.
- Identity is preserved by keeping every fn's `:namespace` string
  (`app.common` / `app.registry` / `app.mcp`) — identity is `uuid-v5(namespace,
  name)`, so only the package DIRECTORY moved; ids + refs are byte-identical.
- **How the optional routes are served** — NOT the route-collection seam (that
  boot-frozen, branch-agnostic router bakes constant-arg data reads and threads
  no `:request`, which cannot serve app HTTP routes; it's tenancy-only). Each
  optional package carries a `:_registry-ring-response` / `:_mcp-ring-response`
  handler that `graphden.system.branch-router` resolves TOLERANTLY and serves
  PER-BRANCH (fresh + invalidation-aware) alongside the main handler.

Also deliberately kept in the `app` package (NOT moved to `examples`, despite
looking like demo content): **`app/contact-demo`** — the `/demo/contact` page.
It is the canonical NON-TRIVIAL app-namespace FIXTURE the distribution machinery
is tested against end-to-end: `packages/export_test` + `packages/registry_test`
export / publish / install / fork `app.contact-demo` through the shared
`core+web+app` golden; the `edit-packages-panel` e2e publishes it from the
browser; and `tools/browser-test/contact-demo-smoke.js` is the live-route smoke
for the components + `submit-form` dispatcher pipeline. It ships as a small demo
AND doubles as that cross-pyramid fixture — moving it to `examples` would fork
the fixture from the golden those tests build and drag the whole (heavy)
examples package into their bootstrap. So the demo route is intentional and the
smoke depends on it; it stays.

---

## 16. Cloud assembly = self-hosted core + private addons

Our cloud is not a fork — it is the **same self-hosted core + our addons +
a restriction policy** (PLATFORM_PLAN § 3.0 ADR: "Cloud vs self-hosted = режим,
не код. Один бинарник."). Internally we operate the cloud as self-hosted admins
(platform-admin behind a VPN). Our cloud build is literally the best dogfood of
Track B (§ 12): a thin project depending on `graphden-core` + the addons.

Two layers of "modules for cloud" — only the second is private:

| Layer | What | Where | Openness |
|---|---|---|---|
| Tenancy primitives | orgs / users / RLS / effect-gate / FaaS | **this repo** | AGPL — self-hosted teams use them too; **not** "private" |
| **Private cloud modules** | billing / metering / at-scale routing / admin dashboards / managed-model gateway | **separate closed repos** | proprietary; we don't publish them |

Private cloud modules attach via the **same** mechanism as any addon
(`GRAPHDEN_ADDON_CONFIGS` + `:graphden/require` + Integrant-seam overrides) —
there is no special "cloud" machinery; they are ordinary addons we keep private.

**After this plan** the assembly model is fully real: consumable core (Task 7) +
tenancy addon (shipped) + swap seams (Tasks 5–6) → cloud = `core + tenancy +
[private modules] + policy`. What remains beyond this plan is *writing* the
private modules (billing etc.); they plug into the socket unchanged.

**Honest asterisks.** (1) `org-id` is a core column (default `public`) present
in both modes — the one place "core knows nothing about tenancy" blurs;
accepted (§ 3.0 nuance 2). (2) The open/closed line holds by **discipline**, not
automation: only genuine cloud-business (billing, our infra orchestration) goes
private; anything a self-hoster needs for their own multi-user (orgs/RLS) stays
open, or we break "no feature-gating between shapes." Billing hooks must never
land in `resources/packages/` or open `src/`.
