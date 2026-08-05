# PLATFORM_PLAN.md — Users, organizations, packages, permissions

> **🔧 Internal design plan — not the user-facing tenancy
> reference.** This is the ORIGINAL design/ROADMAP document for the tenancy +
> packages work; it predates the shipped code and is not kept in step with it.
> The behaviour that actually shipped lives in the code (`graphden.tenancy.*`)
> and in the reader docs indexed from [docs/README.md](README.md). Read this
> only for original design intent and rationale.
>
> Status: **DESIGN / ROADMAP**. This is a plan, not a description of existing code.
> Implementation branch: `feature/platform-packages-tenancy`.
> The goal of this document is to capture a coherent picture of the four intertwined
> features (packages, organizations, users, permissions), the sequence of their
> rollout, and a list of architectural risks, before writing code.

## 0. Summary for the busy

The four features are related but **separable**. Recommended order:

1. **Packages** (the most self-contained, we start with it) — registry + publish/install +
   round-trip export + package versions on top of the existing graph branches +
   splitting the distribution channel into impl/non-impl.
2. **Tenancy / organizations** — `org-id` modeled on `branch-id`, two layers
   of isolation (app-decorator + Postgres RLS), subdomains and custom domains.
3. **Users and permissions** — a user model instead of a shared password +
   a minimal primitive `grant(subject, capability, ns-scope)`.
4. **Breaking up the monolith** (future) — via protocols + deps.edn git-deps, NOT via
   Polylith as a mandatory condition.

**The key insight that reframes the whole task:** the cloud security boundary is
**NOT "packages without impl"**, but **effect gating in the executor's runtime**,
which does not exist today (see §1 and risk R1). "Packages without impl" is a
necessary but insufficient condition.

**The second insight, which simplifies the model:** the axes are not three ("cloud / self-hosted /
mixed"), but **two independent ones**: where the graph lives and where the executor runs.
The mixed mode then decomposes into already-understood parts (see §1).

**The third insight (ADR §3.0), which shapes phases 2–3:** organizations/users/permissions
and their enforcement are an **optional Integrant addon** (impl channel, deps.edn),
not a graph-package (a security boundary cannot be implemented by the thing it
restricts) and not a fork. The system works with the addon (multi-tenant) and without it
(single-tenant). "Cloud" = the same binary + addon + restriction policy +
admin access behind a VPN. The organizations product (UI/admin panel) is an fns-package on top of the addon.

**Status:** phase 1 (packages: exporter + publish/install registry) and phase 1.5
(effect-gate mechanism + audit) are **done** (see §8). Phases 2–3 = building
the tenancy addon, captured by this document.

---

## 1. Three types of users → two axes

Instead of three "types", it is more convenient to think in terms of a 2×2 matrix:

|                          | Executor: ours                    | Executor: user's                   |
| ------------------------ | --------------------------------- | ---------------------------------- |
| **Graph (fn-rows): ours**| **Cloud** (type 1)                | **Mixed** (type 3)                 |
| **Graph: user's**        | (no point — we skip it)           | **Self-hosted** (type 2)           |

What this gives us:

- **Cloud:** all impl is *our* code. The user never sends Clojure.
  But they **compose existing base-fns**, some of which perform effects
  (`:env`, `:read-resource`, network, `:pg-query`). So security ≠
  "no impl", but "the executor does not let a forbidden effect run" → **effect
  gating** (R1).
- **Self-hosted:** the executor is *theirs*. Any impl is their responsibility. Our
  surface is zero.
- **Mixed** = *our storage API (scoped by the organization key)* +
  *their executor (self-hosted style)*. That is, the mixed mode is **not a new
  entity**, but a composition: "org-scoped storage API" (phase 2) + "their executor"
  (phase 4). Effect gating in the mixed mode is their concern, not ours; our concern is
  that the storage API **never** hands over someone else's data even on a bug (→ RLS).

Conclusion: the mixed mode is **feasible** and nearly free if phases 2 and 4 are done
correctly. Almost no dedicated code is needed for it.

---

## 2. PHASE 1 — Packages

> **Implementation of distributable packages** (three kinds of modules: fns-only /
> impl+fns / core-swap) — the concrete, decisions-locked plan-of-record
> is in [PACKAGE_DISTRIBUTION.md](PACKAGE_DISTRIBUTION.md), branch
> `feature/distributable-packages`. It elaborates §2 below: reference-install
> (materialization into the public-org + `:package-install` pin), rewrite-on-pin-change
> instead of compiler-late-binding, version constraints, the impl-package manifest,
> core-swap seams.

### 2.1. Two distribution channels (the key split)

| Channel                     | What it distributes                                 | Mechanism                             | Who may install it in the cloud |
| --------------------------- | --------------------------------------------------- | ------------------------------------- | -------------------------- |
| **Impl channel**            | packages with `impls.clj` (Clojure code, new primitives) | git repository / Maven (deps.edn)     | **nobody** (this is code on our executor) |
| **Fns channel**             | packages with only `fns.edn` (compositions over impl) | our registry, EDN over HTTP → graph rows | **yes** (this is data, not code), subject to effect gating |

Why:

- impl-package = new Clojure code → can execute anything on the executor
  where it is compiled. In the cloud **only we** build it, and make it available
  to everyone. A self-hosted/mixed user adds it to **their own** executor.
- fns-package = only a description of compositions (`fn/slot/binding` rows). Installation =
  sync of EDN into the graph. No code execution. Therefore a cloud user may
  install fns-packages themselves — but what they can *do* with them is limited by effect
  gating (R1), not by whether an impl exists.

Any fns-package transitively depends on at least one impl-package (every fn's
oldest ancestor is a base-fn with an impl). This becomes a **declared
package dependency** (see 2.3).

### 2.2. Distributing impl-packages: deps.edn git-deps, NOT Polylith, NOT submodules

Recommendation (answer to the Polylith question):

- **Distributing impl-packages** — the native Clojure mechanism: each impl-package
  is its own git repository with `package.edn` + `impls.clj` + `deps.edn`.
  A self-hosted user adds a git/Maven coordinate. This is exactly "ordinary
  git repositories and clojure libraries", as you assumed.
- **Automation of "add it in one place — the build picks it up"**: we introduce a
  manifest `executor-packages.edn` (a list of package coordinates). The build step
  (`build.clj`) splices it into `deps.edn` `:deps`/`:extra-deps` and into the list
  of packages loaded by the loader. The user edits **one data file**, without
  touching the executor code → less chance of accidentally breaking the executor. Exactly the
  automation you wanted.
- **Polylith** is a monorepo-organization tool (components/bases/projects).
  It *would help* structure our components, but is **not required** for
  swappability: the codebase is already protocol-oriented (`StorageCRUD`,
  `ExecutionGraph`, `data-schema-protocol`, integrant). Swappability comes from
  protocol + integrant, not Polylith. Verdict: **Polylith is nice later,
  not a blocker now.** We don't commit to it.
- **Git submodules** — do not use. deps.edn git-deps is the clojure-native
  equivalent, without the pain of submodules.

### 2.3. Package versions and dependencies

Today (verified): `package.edn` has `:dependencies ["core" ...]` — bare
names without versions; dependency topo-sorting already exists; `:version` is pure
metadata, not used at runtime.

What we add:

- **Version constraints** in `:dependencies`: `{"core" ">=1.5.0"}`.
  `resolve-dependencies` (loader.clj) checks them and pulls from the registry.
- **Check at install time**: you cannot install an fns-package whose nodes reference
  missing ancestors. The resolver checks that dependency packages exist in the
  target system and, if needed, pulls and installs them (recursively).

### 2.4. Three version systems and how they relate

| System                          | What it versions            | Exists?     |
| ------------------------------- | --------------------------- | ----------- |
| **Graph branches** (git-like)   | per-entity versions of fn-rows | yes      |
| **Package versions** (semver)   | snapshot of the EDN bundle  | metadata    |
| **Git-repo versions** (impl)    | git tag / Maven version     | standard    |

**The main synergy (important):** "test a new package version on a test branch
before prod" = **install the new package version onto a graph branch**, run the tests,
then merge into `main`. Rollback = revert the merge or reinstall the old version.
That is, the existing graph-branch system **already is** the staging ground for
package upgrades. Nothing new needs to be invented — we reuse branches.

- Importing a version "from dev/another branch" = installing onto the corresponding graph branch.
- Notifications about new versions = the registry stores latest; a periodic comparison
  of installed-version vs registry-latest per organization.
- "A package may change only its impl" → this is about the impl channel: a new version
  of the package's Maven/git coordinate, signaled by semver/git-tag.

### 2.5. Registry (PyPI-like) and publishing

- **The fns-package registry** is a service: a package = a named versioned bundle of
  `fns.edn` (+ nested namespaces as a folder structure). Public or
  accessible by the organization key. Browsing descriptions/versions — like pypi.org.
- **Publishing by a cloud user WITHOUT creating a file by hand**: the API
  `POST /api/packages/publish` accepts a root namespace → the server
  **serializes the graph subtree into EDN** (see 2.6) → stores it as a package version.
  The user does not fiddle with git/files.
- **Publishing by a self-hosted user independently of our registry**: the same
  exporter emits `fns.edn` (or a set with folders for nested ns) → the user
  puts it into their own git. The registry is optional.

### 2.6. Round-trip exporter `graph → fns.edn` — BUILT

Implemented in phase 1: `packages/export` (export-namespace for publish,
`GET /api/export/graph` for the whole graph — see R3 and
PACKAGE_DISTRIBUTION § 12). The historical text of this item ("there is no
serialization at all") has been removed as outdated.

### 2.7. Packages ↔ namespaces (accounting for the real schema)

**A correction to your question:** in the current schema the fn↔namespace relationship is
**one-to-one** (`fn.namespace-id` is the only FK), and `:ns` is a tree
(`parent-id`). The M2M you remember ("one fn in several namespaces") is
**not implemented** in the schema (see R-NS). So there is no ambiguity today about "which namespace
to assign at packaging time".

The model:

- **A package = a named versioned export of a namespace subtree** (one
  root ns + its children). The closure of fns under the root + their dependencies from
  other namespaces → become the **declared dependencies of the package**.
- If/when m2m for namespaces appears (for your interface-gathering
  case — "gather all the places where secrets are needed"), then such an **assembled
  namespace view is NOT a package**, but a view. There is no point packaging it
  (you noted this yourself). For packaging you need the notion of a "home namespace"
  (primary membership); secondary memberships are not included in the package.

### 2.8. "Installation" for the cloud = a visibility grant, not row duplication

A cloud user **should not** duplicate the files/rows of a public
package on their side. Therefore:

- Public fns-packages live once in the **public organization** (the shared tenant).
- "Installing a package" by a cloud user = a **read grant** on that package's
  namespace in their **workspace** (see phase 3, §4.4). Rows are not copied.
- This directly answers your hunch: "installing a package is related to configuring
  namespace visibility". Packages, workspaces, and permissions are **unified through
  ns-scoped grants**.
- Forking/modifying a public package = copy-on-write into your own namespace (then
  the rows are copied, but only on a deliberate fork).

### 2.9. What to extract from the current repository

Once the package system exists, we keep in the core the **bare minimum**:
executor, storage, versioning, packages-loader, web primitives, the server skeleton,
the base-fn core. Candidates for extraction into separate repositories/packages:
domain/example packages, non-standard impl-packages, possibly the editor itself
(it is a product, but technically an app-package). The goal is to reduce the size and noise of the core.

---

## 3. PHASE 2 — Organizations and tenancy

### 3.0. ADR: tenancy = optional Integrant addon, NOT a fork and NOT a graph-package

**Decision.** Organizations, users, permissions, and all their **enforcement**
are packaged as an **optional impl-addon** (a Clojure dependency, deps.edn
git-dep — this is the impl channel from §2.1), wired via Integrant. The system
works **with the addon and without it**: without it — single-tenant (one implicit
org `public`, `:allowed-effects nil`, auth = the current single-token); with it —
multi-tenant + effect-gate + permissions. The product part (organization admin panel,
user management, grants UI, workspace view) is an **fns-package on top of the addon**.

**Why exactly this (and not a graph-package or a fork).** A security boundary
cannot be implemented by the thing it restricts: the user-graph executes
*inside* the executor, and if the isolation/gate were themselves a graph-package —
they could be circumvented by composition. So enforcement must live below the graph, in
the core. But it must neither fork "cloud vs self-hosted" nor bloat the base
binary. **An optional core-level addon** removes both: one codebase, the difference is
the presence of a dependency in the config. This reuses graphden's already-existing
bones — storage decorators (`VersionedStorage` is already one), Integrant,
`extend-builder` (this is how `:package-version` was added), the two-channel package
model — that is, it is architecturally "free".

**What's in the addon (impl), what's in core, what's in the graph:**

| Layer | Where | Why |
|------|-----|--------|
| `OrgScopedStorage` decorator (org filter) | **addon** (impl) | wraps the storage chain: `OrgScoped(Versioned(Postgres))` |
| Postgres RLS (`SET LOCAL app.current_org`) | **addon** (DB policies) | the lower safety net — if the app code "surfaced past it", the DB cuts off other tenants' rows |
| auth-middleware (session/JWT → org/user) | **addon** (impl) | overrides the core auth seam |
| `extend-builder`: `org`/`user`/`grant` | **addon** (impl) | extends the schema like `pkgs/extend-builder` |
| Setting `:allowed-effects` on the per-request ctx | **addon** (impl) | the gate mechanism is already in core (§5); the addon sets the policy |
| Host→org resolver | **addon** (impl) | extends `branch_router/dispatch` |
| `org-id` column (default `public`) | **core** | a cross-cutting field; the addon merely enables scoping (see nuance 2) |
| The gate mechanism (`record-effect!` throws) | **core** (done) | the boundary cannot be an addon that removes it |
| The `grant` primitive + check at the request boundary | **core** | enforcement is not composable by the user |
| Product: org admin panel, user management, grants UI | **graph (fns-package)** | these are compositions → they belong in a package; they depend on addon entities |

**Cloud vs self-hosted = a mode, not code.** One binary. "Cloud" = the addon is
wired + a restriction policy (org users are forbidden `:env`/`:io`/
`:network`/`:process`, impl-packages are unavailable) + platform-admin access behind a VPN
(we operate the cloud as a self-hosted admin). The minimal self-hosted = without
the addon, single-tenant. A self-hoster who needs multi-user **also installs
the addon** — they are not "cloud".

**5 nuances/preconditions (in decreasing order of importance):**

1. **The decorator must be impenetrable.** The code has `vs/unwrap`
   (branch-router gets the base storage) — any such path bypassing
   `OrgScopedStorage` = a hole in the isolation. That is why RLS is mandatory as the second
   layer (even if the app code surfaced past it, the DB cuts off). RLS policies are the addon's
   schema → enabling/disabling = a migration.
2. **`org-id` is a core column (default `public`), not something that "appears with
   the addon".** So that core queries work in both modes. "Fully
   optional" gets slightly blurred at the data level: the column is always present,
   the addon enables enforcement. This is a legitimate cross-cutting field (ADR R5).
3. **The injection mechanism into Integrant.** The config is a static EDN map; we need
   an addon manifest (analogous to `executor-packages.edn` from §2.2) that boot
   reads and **splices the addon's Integrant keys** into the storage/router chain,
   without hardcoding them into the core config.
4. **Auth must first be extracted into a swappable seam.** Today auth is a hardcoded single
   token. For the addon to *replace* it, the core needs auth as an injectable
   component (protocol/Integrant key). **Precondition #1** — without it
   the rest cannot be wired in cleanly.
5. **The product's dependency on the addon.** The "org-admin" graph-package references
   the `org`/`user` entities → a transitive fns→impl dependency (§2.1);
   checked at install (already implemented for install in phase 1).

**Implementation order (refines §8):** auth seam (precondition) → addon
manifest (the injection mechanism) → `OrgScopedStorage` + RLS (isolation, both layers)
→ wiring the effect-gate (the addon sets `:allowed-effects`) → the product
fns-package (org-admin UI). Before the auth seam and the manifest — the rest cannot be wedged in.

### 3.1. Data isolation: `org-id` modeled on `branch-id`, two layers

The existing `branch-id` is an exact analog of tenant scope (verified:
`VersionedStorage` wraps storage and auto-filters by `branch-id`).
We mirror it:

- **Layer A (app): the `OrgScopedStorage` decorator** (like `VersionedStorage`) —
  injects the `org-id` filter into every query. Ergonomics and perf.
- **Layer B (DB): Postgres RLS** — `SET LOCAL app.current_org = ?` at the start of
  each transaction (HikariCP pools connections, hence specifically `SET LOCAL`
  inside the transaction / `set_config(...,true)`, not a session-level onConnect).
  This is the **hard guarantee "even if there is a bug in our code"** that you want.

**We do both.** RLS is the lower safety net (see R6: today reads are completely
anonymous, the surface is huge, the app layer will let something slip). We put org-id as a
column on `fn` and the versioned tables; for public packages — a special organization
`public`. This is **in tension with principle #2** (minimal fields) — an explicit ADR is needed
(R5): a tenant is a legitimate cross-cutting field, not graph semantics.

**The hook point for the effect-gate (see §5):** the per-org `ExecutionContext` —
this is exactly the restricted-ctx for the effect gate. When creating an org-ctx for
user-graph execution: `(create-context {:storage org-storage :allowed-effects
compile-runtime/default-cloud-allowed-effects})`. The platform-ctx remains
unrestricted. The gate mechanism is already ready and verified (Phase 1.5) — here we only
need to thread the value through.

### 3.2. Organization subdomains and custom domains (Heroku-style)

Today (verified): the `Host` header is not inspected; branch routing goes
through `X-Graphden-Branch` / `?branch=`. The seam is `branch_router/dispatch` +
`extract-branch-ref`.

- **Auto-subdomain on organization registration**: `<org>.graphden.app` → the
  organization's tenant. We extend context extraction: parse the subdomain from `Host` →
  resolve it to `org-id` (a `subdomain → org` table). The route is created
  automatically when the organization is created.
  - **resolution** `tenancy.subdomain` — `extract-subdomain` (Host →
    label; strips port, rejects apex/multi-level) + `OrgResolver` protocol.
    **Default — `identity-org-resolver`: the subdomain label IS the org-id, NO
    table** (org is a slug string; an unknown slug → an empty tenant-view via
    OrgScoped, nothing to validate). `static-org-resolver` (map) — ONLY for
    vanity aliases (subdomain ≠ org-id). **SECURITY: the token is the org authority
    (single-membership), the subdomain is a GUARD, not a source.** A member on someone else's
    subdomain → 403; the subdomain does NOT widen access (a spoofed `Host` won't read
    someone else's org, since reads are not grant-gated and OrgScoped scopes by `*current-org*`);
    anonymous → public. `:tenancy/org-resolver` init-key (identity by default;
    `:subdomains` for aliases) + `:base-domain`. Inert without a resolver. Proven
    (5 tests / 29 assertions, incl. cross-org 403 + anonymous-no-leak).
    *Remaining:* auto-creating an org on first visit to its
    subdomain (if needed); the table is only for **custom domains** (R10,
    `hostname → org`, not derived from org) + DNS-TXT.
- **Custom domains**: a `hostname → org` table. The user adds a domain;
  **ownership confirmation** is a DNS TXT record that we verify once.
  - **built** `tenancy.domain`: `HostResolver` + `static-host-resolver`
    (`{hostname org}` of verified domains) → `org-from-request`; wired into
    request-scope AFTER the subdomain, **the same guard semantics** (the token is the authority,
    the host only forbids). `verify-domain-ownership` (DNS-TXT
    `graphden-verify=<token>`, injectable lookup + JNDI default) — the ownership
    check. Proven (3 tests, incl. verify + member→org + foreign→403 +
    anon→public). **Built out later:** storage-backed `hostname → org`
    (`tenancy.domain` — a HostResolver over the `:domain` entity, provisionable
    without a redeploy) + a provisioning flow (verify-domain via
    `tenancy.deploy`: add domain → token → DNS-TXT verify → row).
- **Important (R10):** a DNS check is a network **effect**, which is forbidden to cloud
  users. So the domain-check fn is a **privileged
  platform fn**, not a user composition. "Through the graph, but not
  by the user" is a subtlety that breaks the naive "preferably through the graph".
  **honored:** `verify-domain-ownership` is an ordinary Clojure fn in the addon
  (platform), NOT a graph-fn; the tenant cannot reach it.
- **Important (R11) — ⛔ SUPERSEDED §3.4 (FaaS):** the distinction "(a) subdomain → tenant
  vs (b) hostname → user-`:service`" is RESOLVED by the FaaS model: the tenant has no
  `:service` of their own — their application is a handler-fn, and both routes (app vs
  editor) are separated by ONE app-router by host+context (an app request on the subdomain →
  org context/handler; editor/API on the apex → token authority). There is no
  separate hostname→`:service` mapping.

### 3.3. User web servers on a subdomain — ⛔ SUPERSEDED §3.4 (FaaS)

> This section described the PaaS variant (the tenant OWNS `:service`). It is superseded
> by ADR §3.4: a cloud tenant does not own a server — they provide a handler-fn, which
> is executed by the platform app-router (FaaS). The section is kept for history;
> tenant-`:service` remains platform-only (write/read-deny). Per-tenant
> runtime-config via `:branch-local?` and the per-branch service model
> are still relevant FOR PLATFORM services (the hidden web servers).

The service model already supports per-branch (verified: `:service.branch-id` +
per-branch `ExecutionContext`, reconciler). ~~The user makes an fn-def
`:parent :http-server` with their own `:handler`, sets up a `:service`~~ (→ §3.4: instead
the tenant sets a handler-fn via `POST /api/my-app/handler` / ⌂). Per-tenant
runtime-config (port/path) is isolated via the already-existing `:branch-local?`.

**A concrete bug risk (R8):** the LRU cache of per-branch handlers (`max 16`) must
become **per-org**, otherwise branches/handlers leak between organizations.

**🔴 SANDBOX precondition (found + a temporary risk closed):** an `:http-server`
service binds ITS OWN port, and its handler executes in the reconciler's ctx, which has NO
`:allowed-effects` → a tenant-deployed service would execute **outside the effect
gate** (env/io/network/process), bypassing the entire cloud sandbox. Plus `:service`
is not org-scoped, and the per-namespace write-guard let non-`:fn` through. That is, a tenant with
any write grant could `POST /api/entities/service` and stand up
an un-sandboxed web server. **Closed (storage layer):**
`tenant-forbidden-entities #{:service :grant :domain :org :token :user}` in
OrgScopedStorage — a tenant (org ≠ public) can neither write nor read
privileged entities (`:service` → escape, `:grant` → escalation/
authz enumeration, `:domain` → routing hijack, `:org`/`:token`/`:user` →
the platform registry); the platform (public) — freely. authz does not break,
since the grant-store reads `:grant` from the BASE storage, not from the decorator.
Proven (storage-test).

**`:branch` and `:execution` — NOW ORG-SCOPED (§4), no longer forbidden:**

- `:execution`: the original deferral was outdated — the completion-future inherits
  `*current-org*` via binding conveyance (`record-completion!`'s `(future …)`
  plus `run-future`'s `bound-fn*`), so the terminal UPDATE from the future passes
  the own-guard. `:fn-execution` gained `:org-id` + was added to
  `default-scoped-entities`. Proven by `faas-app-test`.
- `:branch` (**Design B**): resolution now happens INSIDE request-scope (so
  `*current-org*` is bound when reading an org-scoped `:branch`); the per-branch
  compiled ctx remains **org-AGNOSTIC** — `:compile-storage` (raw
  PG under OrgScoped) reads the STRUCTURE unprivileged (the registry holds fns of all
  orgs), isolation is on the runtime `:storage` + the `resolve-fn` / execute-guard gates.
  This way there is no per-org compilation and no shared-`main` leak. The ref-cache is keyed
  `[org ref]`. As a side effect it fixes a latent app-router bug (its registry was
  public-only). Proven by `faas-app-test` (27/91), branch-router+lifecycle+rls.
  **Clarification (2026-07):** "the registry holds fns of all orgs" is a default, not an
  invariant. `:executor-orgs` on the ctx narrows compilation to the pod's shard
  (`org-id ∈ predicate ∪ NULL`), which is what closes off the registry's growth by the number of
  tenants, the dedicated pod for the paid tier, and the external executor. Isolation
  is still on the runtime `:storage` + the gates — a shard is about RESOURCES, not about
  security. Shard correctness rests on `reject-cross-org-refs!`
  (`tenancy/storage.clj`): previously "no cross-org ref" was an emergent
  consequence of the read filter, now it is a write-time check. See
  [SCALING.md](SCALING.md).
- **`:graph-cache` reads are org-SLICED (2026-08).** The Design-B ctx's
  `:graph-cache` is primed org-agnostically (`prime-graph-cache!` off
  `:compile-storage`), and the editor's read paths (`cached-or-load-graph` →
  sidebar `:tree`/`:search`, `/api/types*`, layout) served that atom
  DIRECTLY — OrgScopedStorage never saw a cache hit, so a tenant could
  enumerate every org's fn names/namespaces/binding values. Closed
  read-side: `types-api/org-visible-slice` filters each cache read to
  {own, public/un-owned} rows (mirrors the decorator's `visible?`);
  the cache itself keeps the FULL graph (a tenant's miss-fill goes
  through `:compile-storage` so it can't poison the shared cache with a
  narrow slice). Platform tier / single-tenant = identity pass-through.
  Same class of fix on the org-dimensionless type-diagnostics store:
  the panel, the `:tree`/`:subtree` error counts and the
  `:forbid-invalid?` merge gate all drop diagnostics whose fn the
  org-scoped read doesn't return. Pinned by
  `crud/org-visible-graph-test`, `packages/app/editor-panels-test`,
  `merge/core-test/forbid-invalid-ignores-invisible-fns-test`.
- **Follow-ups:**
  - **per-org branch-names** — `UNIQUE (org-id, name) NULLS NOT DISTINCT`
    (PG 15+). Different orgs reuse a name without cross-org collision/leak;
    single-tenant (NULL org) remains unique by name.
  - **per-org type-registries (§4 Risk 2) — CLOSED on ALL 3 surfaces.**
    Design A (org-filtered view, reuses the existing `*…-override*`, we do NOT touch
    CORE):
    - **type-alias check** — a per-org slice `{org → {name → body}}` in
      `compile_runtime`; tenant type-check binds `*type-aliases-override*` to
      `org-alias-snapshot` (`crud/type-check/with-org-alias-view*`).
    - **alias read-display** — the same view on tenant `:read` requests
      (request-scope), so value-form / types-api render their own `Foo`.
    - **rich-types** — a per-org slice in `registry/core`; `rich-type-of` is
      org-aware (tenant slice OR global), tenant-only — the compile/public/sync
      path is byte-for-byte unchanged. `record-rich-types-raw!` mirrors
      tenant records. `current-tenant-org` via `resolve` (like branch).
    All three with parallel-isolation overrides + tests.

> **⛔ SUPERSEDED §3.4 (FaaS):** the idea of "a sandbox for a tenant-OWNED `:service`"
> is CANCELED. In the FaaS model the tenant does not own a server/`:service` — their
> "web server" is a handler-fn, which is executed by the platform app-router
> (org-scoped + effect-gated + timed, proven by `faas-app-test`). Therefore
> a sandbox-for-tenant-`:service` is NOT needed; write/read-deny of `:service` for
> tenants remains (service deployment is platform-only, tenants use FaaS).

---

### 3.4. ADR: tenant applications = FaaS (handler-only), NOT PaaS (own server)

> **How it is enabled (as-built).** App-routing is no longer baked into the base tenancy
> addon: it is in a separate fragment `resources/graphden/tenancy/faas.edn` (app-
> router + `:org` schema), wired via
> `GRAPHDEN_ADDON_CONFIGS=graphden/tenancy/addon.edn,graphden/tenancy/faas.edn`.
> In a sharded fleet the app-router, before `421`, consults the seam
> `:fleet-forward` (`app_router.clj`) — a forward-hop to the executor holding the org's
> cell (docs/FLEET_RFC.md §6.1). See [FLEET_DEPLOY.md](FLEET_DEPLOY.md).

**Decision (replaces the "service-sandbox" approach from the §3.3 note).** A cloud
tenant does NOT own a `:service`/web server (the Heroku model). Instead —
the **FaaS / Cloudflare-Workers model**: the platform owns the web servers
(ports, number of instances, scaling, org→instance routing) — all in graphs
HIDDEN from the tenant; the tenant provides ONLY a **handler function** (request→response,
with internal path routing). For Graphden this is the native model: "code = a graph
of functions", so "a tenant's application = a function, and the platform calls it".

**The FaaS/PaaS duality (falls out of the design, no fork).** FaaS is the mode of the
CLOUD TENANT. Self-hosted (without the addon) and the cloud operator (public org)
remain **PaaS** — full access to `:http-server`/port/`:service`. This is exactly the
"addon on/off + public/tenant" axis: the `tenant-forbidden` guard fires
ONLY for `org ≠ public`, so one codebase gives PaaS to the self-hoster +
the operator and FaaS to the cloud tenant — the §3.0 goal of "works WITH the addon and WITHOUT".

**Operator = a capability, not a magic org (Track A).** The platform-tier
predicate is unified (`tenancy.context/platform-tier?`), and operator
authority is a `:platform-admin` grant (`tenancy.grant`), NOT "you are the
public org". The `:platform-admin` holder may run the admin console
(`:user`/`:org`/`:domain`/`:plan` writes + cross-org read/stats) from a
NORMAL tenant org, while the effect gate, per-namespace `:fn` authz, and
quotas still apply to them — so the vendor org (`graphden`, seeded by
`:tenancy/operator-bootstrap` on the `network` plan) is "just tenant #1",
sandboxed like any other, and CANNOT read/edit/execute another tenant's
graph. The boot seed (`GRAPHDEN_OPERATOR_PASSWORD`, create-if-absent)
mints the operator org + user + grant so the operator logs in through the
normal `/login` form. `"public"` reverts to meaning only the shared
read-only stdlib tier. (Slack multi-org identity + nested app subdomains
are the follow-on tracks B/C.)

**Why it's better than a service-sandbox.** In the PaaS variant a tenant-`:service` binds
its own port BYPASSING request-scope → you'd have to invent a `:service-scope` seam +
a per-request wrap across the port/async boundary. In FaaS the tenant's handler
executes INSIDE the platform's already-sandboxed request path → the effect-gate +
org-scoping apply automatically. The hardest part disappears. Plus:
principle #2 minimal entities (the tenant edits one `:binding`, does not create
a `:service`), principle #3 explicit (one lever — the handler; ports/instances are
platform decisions).

**Mechanics.**

- **The `:org` entity** (orgs registry, platform, tenant-forbidden):
  `name` (= slug = subdomain = `*current-org*`), `handler-fn-id`, metadata.
  The registration endpoint (a platform graph) creates the row + the subdomain.
- **The handler slot:** a per-org provisioned "locked" fn (cannot be deleted/
  inherited, only the handler-binding can be edited) OR a shared fn with an
  org-scoped binding (then binding uniqueness → `(fn,slot,org)`).
  The per-org locked-fn variant is cleaner. "Locked" is expressed by a grant: a capability
  only on that slot, not on the fn's structure.
- **Two routings (resolves R11):**
  - **App** (the tenant's handler, public visitors) → the org context =
    the **subdomain/domain** (anonymous OK). Safe: handler X is itself the gatekeeper,
    OrgScoped won't let it past X's data. Within-org exposure is the app's responsibility.
  - **Editor/API** (`/api/*`,`/partials/*`) → the org authority = the **token**
    (guard §3.2: someone else's subdomain → 403).
- **The web server is hidden:** `:http-server`/`:service` — platform-only (already
  enforced write-deny). The port is not a fixed literal, but comes from a function (the platform
  decides the count/instances).
- **Custom domains** — via the same app-routing: a verified
  `hostname → org` (`tenancy.domain`, DNS-TXT already exists) → the org's handler.

**Preconditions/subtleties (honestly):**

- **R8 (the per-org compiled-handler cache)** goes from a "scale optimization" to a
  **performance precondition** — otherwise the handler is compiled on every request.
- **Resource limits** (timeout/memory on the tenant-handler) — the effect-gate does not
  limit CPU/memory (noisy neighbor; true for any shared model).
- The app path must bind `*allowed-effects*` for the tenant-handler (the same
  mechanics as in request-scope).

**Bounded steps (implementation plan):**

1. `:org` entity + schema (`tenancy/org_schema`), tenant-forbidden, addon-wiring.
2. The org registration endpoint (`POST /api/orgs`, platform-only).
3. The app-routing seam (`tenancy/app-router` + `:app-router` on the ctx + dispatch). **⚠ Track C model A (2026-08-03) reshaped this:** apps now live on a SEPARATE apps-domain (`graphden.app`), addressed by a GLOBALLY-unique label — `<label>.graphden.app` → the `:app-route` for `label` → its owning org + handler (`app-route/route-by-label`); a verified custom `:domain` routes the same way. A `graphden.dev` subdomain is the org's EDITOR (`<org>.graphden.dev`), NOT an app, so it falls through. This supersedes the original "`<org>.<base>` subdomain→org→`:org.handler-fn-id`" (kept for custom-domain default only). Rationale: DNS has no `*.*` wildcard (nested subdomains need per-org DNS), and tenant app code must be isolated from the editor's origin/token (GitHub/Vercel pattern).
4. Executing the handler org-scoped + effect-gated (in app-router: `with-org` + `:allowed-effects` + `:execute-guard nil`).
4a. The set-handler mechanism: the `set-org-handler` base-fn (update `:org.handler-fn-id` by name, str→uuid) + `POST /api/orgs/handler` (platform-only). Now the app-router serves the real handler when it is set.
4b. self-serve — the tenant sets THEIR OWN handler.
    - **seam** `tenancy.deploy/set-org-handler!`: validates (org = token; the fn is readable by the tenant via OrgScoped = own/public), then a single controlled-write of `:org.handler-fn-id` under a temporary public escalation (only its own org row by name = token). Proven (forbidden for public + someone else's fn; update for its own). A security-critical core.
    - **backend** the `:set-org-handler` seam on the ctx (create-context + system/core + build-branch-ctx inherit + `:tenancy/set-org-handler` init-key) + the core base-fn `invoke-set-org-handler` (calls the seam, str→uuid) + the route `POST /api/my-app/handler`. sync+type-check+boot green (13 tests).
    - **editor-JS** ⌂ "set as app handler" in the row-actions popover (root-row): `data-action="set-app-handler"` → `registerActionHandler` POSTs `/api/my-app/handler`. CSS-gated by `body.gd-tenancy` (set on the first capability-header) → hidden in single-tenant. Playwright: the partial emits the button, hidden without tenancy / shown with tenancy. **§3.4 FULLY CLOSED.**
5. R8 per-org handler-lookup: the app-router reads `:org.handler-fn-id` fresh on every app request — one indexed unique-name lookup, negligible against the subsequent graph-handler `execute`, yet a handler change is visible immediately. (There used to be a 5s TTL cache — removed as sacrificing correctness for a negligible saving; the compiled handler is in the shared registry by fn-id anyway.)
6. (timeout) Resource limits: `run-with-timeout` in the app-router (future + deref-timeout → 504; `future-cancel` + interrupt-aware `*cancel-check*` → the graph-handler cancels cooperatively, no thread leak). `:timeout-ms` is configurable (default 10s). *Remaining:* memory limits (the JVM does not cap per-thread memory — a separate mechanism is needed; a tight CPU-loop in an impl is not cancellable, but tenants write only graphs → each execute-step checks cancel).
7. Custom domains in app-routing: `resolve-app-org` = `(or subdomain host-resolver)`, the app-router accepts a `host-resolver` → a verified custom domain → the org's handler by the same path.

### Critical path to a "live cloud" (what remains BEFORE launch)

The FaaS core + isolation + sandbox are proven (`faas-app-test`, 12 tests). The critical path
to prod onboarding is CLOSED (code+tests):

1. **Real token-source** — the `:token` entity + `storage-token-provider`
   (bearer hash-match) + the `create-token` base-fn / `POST /api/tokens`. Onboarding =
   inserting a row, no redeploy. Round-trip test (mint → authenticate).
2. **Storage-backed domains** — the `:domain` entity + `storage-host-resolver`
   (resolves only verified) + `create-domain` / `POST /api/domains` (the operator
   registers, vetted) + **self-serve DNS-verify** (`deploy/verify-domain!`
   seam + the `:verify-domain` ctx + `invoke-verify-domain` / `POST /api/my-app/
   verify-domain` — the tenant proves ownership themselves via DNS-TXT). The app-router
   accepts any `HostResolver` — the integration is free.
3. **Non-superuser DB role** — the code has long been ready (`FORCE ROW LEVEL SECURITY` +
   policy + the `org-aware-datasource` wrap + `rls-test` under a non-superuser `SET ROLE`).
   Only the infra remains: run the process under a non-superuser role —
   documented in `DEPLOYMENT.md § Multi-tenancy: non-superuser DB role`.
4. *(scale, not a launch blocker)* multi-instance orchestration — **mostly
   shipped**: multi-pod + org sharding (`:executor-orgs`) + `421` +
   the fleet quota + the external/BYO executor. See `SCALING.md`. Only open is
   a real BYO executor on a second physical machine (proven in a single JVM).

The rest (Phase-1 packages R4/versions, type-R2, the user model §4.x, addon-active
e2e) is not on the launch critical path.

---

## 4. PHASE 3 — Users and permissions

### 4.1. Authentication: replace the shared password

> **SHIPPED (2026-07-31), state differs from the snapshot below:** auth is
> provider-driven — blank `AUTH_TOKEN` = NO provider = the instance runs OPEN
> (self-hosted no-login mode); a set token or the tenancy addon turns auth ON,
> and then **reads are gated too** (the anonymous graph view is removed; the
> auth-required middleware is provider-aware). The addon serves a public
> `GET /login` page (sign-in + create-org, confirm-password + strength meter);
> `signup!` creates org + first user (8-char password minimum), grants the
> creator `:admin` on the root namespace (org-scoped by RLS), and auto-logs-in.
> The paragraph below is kept as the historical starting point.

Today (verified): a single `AUTH_TOKEN` (env) as a bearer in localStorage,
no user model at all; **reads are entirely anonymous**; secrets — everyone sees everyone's.
This is a full auth rework: a `user` table, sessions/JWT, extraction of the
user context in middleware (`ring-adapter`), threading it into `ExecutionContext`.

**Per ADR §3.0 — this is precondition #1 of the tenancy addon.** First, extract auth from
the hardcoded token into an **injectable seam** (protocol / Integrant key): the core keeps
a default implementation (single-token), and the addon *overrides* it with its own
(session/JWT → org/user). Without this seam the addon cannot wedge in cleanly. That
is, "the user table + sessions" goes into the addon, and the core gets only a replacement point.

### 4.2. Permissions: minimal primitives, NOT a big system (answer to your question)

The graphden ethos (#2, #6) requires a minimum of entities. **One
primitive** suffices:

```
grant(subject, capability, scope)
  subject    = user | group
  capability = :read | :view-impl | :write | :execute | :admin
             | :bind-args | :append-list
  ;; as-shipped closed set of seven (grant.clj); :view-impl gates
  ;; seeing a fn's internal composition (:write / :admin subsume it).
  ;; the once-sketched
  ;; :publish/:install never became capabilities — package ops
  ;; gate on :write of the target namespace. subject: the panel
  ;; handles user subjects; groups remain a sketch.
  scope      = subtree of a namespace (or a specific fn)
```

- **User groups** = sets of users (one junction table).
- **Function groups** = the already-existing **namespaces** (free, a tree).
- Complex cases are covered by a **combination** of grants, not by new entities.

### 4.3. Restricted editing (the "per-user spam filter" case)

"The user changes only some arguments / adds-removes list
items in the same place where they added them, not through inheritance" decomposes as follows:

- `:bind-args` (scope=ns) — can change `binding.value`, cannot change
  `ref-fn-id`/`type-override`/do a full replacement.
- `:append-list` (scope=ns) — can add/remove items of one's own
  `binding-list-item`, without touching the parents'.
- **The `:terminal` flag** (seal a slot from being overridden by descendants) —
  restricts what **cannot** be touched.
- "Does not see other people's spam filters" = `:read` scope + **a personal namespace per
  user** (their filters live there; read grants are per-namespace).

**R2 — CLOSED.** The investigation showed that the plan's premise was half
outdated: `:list-append`/`:list-closed` + personal namespaces + workspaces
are **already fully built** (field + read + enforcement). What was actually missing:

- **`:terminal`** — an explicit seal flag on a binding (`validation/terminal-rej`,
  generalizes the auto-seal `value-override-rej` to not-yet-valued template slots).
- **`:bind-args` / `:append-list`** — two narrow capabilities + enforcement. The
  write-authz seam is extended from `:fn`-only to `:binding`/`:binding-list-item` AND to
  **deletes** (`guard-write!` now receives the entity-id; `delete-entity`
  is guarded). The required cap is narrowed per edit: `:bind-args` for value-only,
  `:append-list` for list-item, `:write` for structural/create/delete.
  `:write`/`:admin` subsume the narrow ones (`grant/cap-implies?`). own = by-namespace.

Proven: grant-test, authz-test, storage-integration (end-to-end via
the real `sp/*` — Risk 1). §4.3 done.

### 4.4. Workspaces = the union of visible namespaces

A user's/context's workspace = the set of namespaces for which there is a
`:read` grant. "Installing a package" (2.8) = adding a read grant on its
namespace. Thus packages + workspaces + permissions are one mechanism.

---

## 5. Effect gating (cross-cutting, a cloud precondition) — the most important item

Today (verified): base-fns call `(record-effect! :env)` and so on — but this is
**purely informational** (type-checker + chips in the UI). There is **no** runtime
gate/sandbox: `ctx` is available in every base-fn, but nobody asks it "is this allowed".

Design (two layers, belt-and-suspenders):

- **Runtime gate:** add `:allowed-effects` to `ExecutionContext`.
  `record-effect!` checks `(contains? allowed eff)` and throws
  `:execution/forbidden-effect`. For cloud org-contexts, `:env`, `:io`, `:network`
  are excluded from allowed (or `:network` is narrowed to an egress-allowlist).
  For self-hosted/mixed, allowed = all.
- **Registry visibility (edit/sync-time):** build-time filtering of the base-fn registry
  — forbidden primitives are simply not registered/not visible in the cloud
  context (the fn-picker won't show `:env`, sync will fail on "unknown fn").

Only this makes "a cloud user does not read env/files, does not reach the internal
network" **real**, not declarative. "Standing up your own DB in our cloud/private
network" is solved by **kubernetes/network policies**, not by a service (you're right); at the
app level, the effect gate + egress-allowlist is enough.

### Implementation status (updated)

**Done (Phase 1.5):**

- **Runtime gate — IMPLEMENTED.** `ExecutionContext :allowed-effects` (set;
  nil = unrestricted) → `compile-runtime/execute` binds `*allowed-effects*` →
  `record-effect!` throws `:execution/forbidden-effect`. Perf: unrestricted =
  zero overhead. Test + smoke green.
- **Coverage audit — PASSED.** All 275 base-fns checked: every
  security-sensitive one (`:env`/`:io`/`:network`/`:process`) calls `record-effect!`
  → **0 holes in the sandbox**. `:process` = thread/server/cancel (control), there is no
  shell/RCE primitive. One `:db` gap (layout cache) was fixed.
- **Artifacts:** `compile-runtime/cloud-forbidden-effects` `#{:env :io :network
  :process :raw-sql}` + `default-cloud-allowed-effects` `#{:db :time :state :random}`
  (computed as `known-effects − forbidden`). `:raw-sql` was added because
  `:db` is allowed (the tenant needs org-scoped storage), but raw `:pg-query` /
  `:pg-execute` / `:pg-tx` (arbitrary HoneySQL over the platform pool) ride
  on `:db` bypassing org-scope + RLS — the `:raw-sql` tag blocks them separately, without
  touching the safe `:query-entities` path.

**Wiring — DONE (tenancy-addon, per-request org-ctx).** Previously the plan
deferred wiring to Phase 2, because a global flag on the default-ctx breaks
the platform (the default ctx executes THE PLATFORM ITSELF: web-server `:network`/`:process`,
vault `:network`, config `:env`, asset-reads `:io`). The "platform vs user
execution" boundary = the tenancy boundary, and the gate is **ctx-based by design**.

Implemented in TWO LAYERS. A naive single blanket binding around the whole
handler broke the platform handler itself — it reads storage via `:pg-query`
(which records `:raw-sql`), so almost any tenant request that
read storage got 403'd. Therefore:

1. **Request level** — `tenancy.addon` binds `cr/*allowed-effects*
   cr/cloud-request-allowed-effects` (= the safe set PLUS `:raw-sql`, so that
   a trusted handler can read storage for the tenant's benefit); the external
   `:env/:io/:network/:process` are still blocked (defense-in-depth).
2. **The tenant's own graph** is gated more strictly — WITHOUT `:raw-sql`:
   `crud.fn-execution/apply-execute` puts `:allowed-effects
   cr/default-cloud-allowed-effects` on the exec-ctx (only for a non-public org),
   which the executor applies in `cr/execute`. This way the tenant cannot call the raw-SQL
   escape hatch in THEIR OWN graph, but the handler serving it can.

The platform-ctx (`public-org`) remains unrestricted. The restriction is a property
of the EXECUTION of the user graph (`default-cloud-allowed-effects` is a value
of the *ctx*), not a wrapper around the platform handler.

### 5.1. Shipped beyond the effect gate (baseline hardening) — updated 2026-07-27

Three subsystems are **already implemented** (not a "future egress-allowlist", as in places
earlier in this section's text):

- **SSRF-egress-guard (baseline, for ALL network tenants).** `clients/egress.clj`:
  `internal-address?` classifies loopback / RFC1918 / CGNAT (100.64/10) /
  IPv6-ULA (fc00::/7) / link-local (incl. cloud-metadata 169.254.169.254) /
  multicast / any-local; `resolve-public-ips` fail-closed (an unresolvable host or
  ANY internal resolution → block — this also closes DNS-rebind); `check-target!`
  is called in `web/http-client` before dial, when `*allowed-effects*` is restricted
  (a tenant). This is a **deny-internal baseline**, not a tier allowlist — it applies to
  any tenant that has `:network` at all (a paid plan). **The DNS-rebind TOCTOU
  is CLOSED** (not a residual): the restricted tenant HTTP client is built with
  `egress/validating-dns` as its OkHttp `Dns` (`web/http-client/impls.clj` —
  `.dns egress/validating-dns`), which re-runs `resolve-public-ips` AT CONNECT
  TIME and dials exactly those verified public addresses while keeping the
  hostname for SNI / Host / cert verification. There is no second, unvalidated
  resolution between check and dial, so a name that is public at `check-target!`
  and internal at dial is rejected by the connect-time lookup.

- **Row-cap (protecting the shared DB from abuse), TWO ceilings.** `tenancy/plan.clj`
  `plans` = `{:effects :max-fns :max-list-items}` (free 500/50000, network
  5000/500000, nil = no limit). A tenant controls TWO independent growth
  vectors: `:fn` (slots/bindings scale with fns) and `:binding-list-item`
  (sequence content — one row (+version row) per append, NOT
  bounded by the number of fns). `OrgScopedStorage.create-entity` gates the creation of
  both; exceeding → `:quota/entity-limit` (HTTP 429, user-facing text).
  **Fail-open:** a check failure (a DB blip) does not block a legitimate write — the quota is
  a soft abuse defense, not an invariant. The public/platform org is never capped.

- **Ephemeral demo-org + TTL reaper (`tenancy/demo_gc.clj`).** A demo/trial org
  carries `:org.expires-at`; `:tenancy/demo-gc` (an opt-in scheduler, default 1h)
  **HARD-deletes** expired orgs and ALL their graph rows in one FK-safe
  transaction (version tables via a subquery on identity.org_id → identity → org).
  **Invariant (important — destructive):** a permanent org = `expires-at` NULL,
  the reaper NEVER selects it (real tenants + public are eternal).

---

## 6. Monetization (through the package system — yes, feasible)

The package system + organizations are a natural substrate for pricing tiers:

| Tier             | What it unlocks                                                     |
| ---------------- | ------------------------------------------------------------------- |
| Free             | public fns-packages, our base-fns, one organization, a compute/storage limit |
| Paid             | private packages, the right to publish, custom domains, more storage (graph rows), more service slots/compute, egress-allowlist, third-party impl-packages (mixed), SSO for organizations |
| Self-hosted/Mixed| payment for the volume of graph storage on our side + storage API + egress |

The package registry is a natural paywall point (a gate on publish/install of private
and premium packages). The mixed mode (bring-your-own-executor) is itself
a paid feature; **the mechanics are shipped** (`graphden.byo` + `:org.execution-mode`,
see `SCALING.md`), only billing remains to be hung on it. This **does not kill the project**,
but on the contrary — gives several orthogonal monetization levers, without breaking
the free self-hosted path.

---

## 7. Risks and inconsistencies (what you asked to highlight)

| #      | Risk / inconsistency                                                                                                                          |
| ------ | --------------------------------------------------------------------------------------------------------------------------------------------- |
| **R1** | CLOSED. The runtime effect gate is built (two-layer ctx-based, see §5 "Wiring — DONE"): `cloud-request-allowed-effects` on the request + `default-cloud-allowed-effects` on the tenant's exec-ctx; the `:raw-sql` tag separately blocks raw pg over the platform pool. |
| **R2** | CLOSED. `:list-append`/`:list-closed` + personal NS were already built; added `:terminal` seal + `:bind-args`/`:append-list` caps + extension of the write-authz seam to `:binding`/`:binding-list-item`/deletes. §4.3 done. |
| **R3** | CLOSED. `export-graph` base-fn + `GET /api/export/graph` (+ `/api/export/graph-rows` for the BYO bootstrap); publish/export go through it. |
| **R4** | There is no automatic breaking-change detection between package versions — we rely on the package's semver/git coordinates. |
| **R5** | `org-id` on every table — **in tension with principle #2** (minimal fields). An ADR is needed. |
| **R6** | CLOSED. Reads are org-scoped via `tenancy/storage` (`default-scoped-entities`) + RLS as the lower safety net (`tenancy/rls.clj`, policy SELECT own+public / write own-only). |
| **R7** | Mixed mode: our storage API must not hand over someone else's data on a bug → the API is also under RLS, not only app checks. Their executor runs their effects — that is their zone; our zone is only the scope of the storage API. |
| **R8** | The LRU cache of per-branch handlers (`max 16`) **will leak between organizations** if it is not made per-org. A concrete bug. |
| **R9** | CLOSED (org-scope). A secret = a `:fn` with parent `:secret-leaf`, and `:fn` is already org-scoped (`default-scoped-entities`) → other tenants' secrets are invisible; rotate/delete are additionally protected by org-guards (`crud/secrets.clj`). Remaining, if desired, is to namespace the vault paths themselves and add a user-level (not only org-level) scope. |
| **R10**| Domain ownership verification is a network **effect**, forbidden to cloud users. So the check fn is privileged/platform, not a user's. Breaks the naive "through the graph". |
| **R11**| Two different routings get mixed: the organization subdomain → tenant (editor/API) and the hostname of a deployed web server → `:service`. These are different mappings. |
| **R12**| **Name uniqueness** today is `UNIQUE(namespace-id, name)` + globally-in-batch (a validator). Multi-tenant → the sync validator must scope per-org, otherwise two organizations cannot have an fn with the same name even in their own namespaces. |
| **R-NS**| **M2M for namespaces is not implemented** (contrary to memory — `fn.namespace-id` is the only FK, `:ns` is a tree). This simplifies packaging, but your interface-gathering case will require a NEW entity (violates #2) and does not exist now. |
| **R13**| CLOSED (per-user built). A `:user` table + tokens (SHA-256 + bcrypt-12 + TTL) + grants + threading the org-ctx (`tenancy/users.clj`). The shared `AUTH_TOKEN` remains only as a single-tenant fallback outside the tenancy addon. |

---

## 8. Implementation sequence

Updated per ADR §3.0: phases 2–3 (organizations + users/permissions) are
reconceived as **building the optional tenancy addon**, not "features in
the core".

- **Phase 0 (preparation):** the ADR on `org-id` vs #2 (R5) — **fixed in §3.0**;
  the namespace model (R-NS) — confirmed single-membership; the effect gate
  (R1) — standalone, done in parallel.
- **Phase 1 (packages) — done:** the round-trip exporter (R3) → scoped
  `export-namespace` + dependency extraction → the `:package-version` registry +
  **publish/list/fetch/install** (graph-composed) → 6 layering fixes
  (core/web publish independently). **Remaining:** version constraints (R4),
  splitting the impl/fns channels, the `executor-packages.edn`
  manifest (which is also the addon injection mechanism, see §3.0 nuance 3), extracting
  domain packages (§2.9), hot-recompile on install.
- **Phase 1.5 (effect gate, R1) — MECHANISM DONE:** `:allowed-effects` in the
  ctx + the check in `record-effect!` + the coverage audit (0 holes) +
  `cloud-forbidden-effects`/`default-cloud-allowed-effects`. **Wiring
  deferred to the addon** (§3.0, §5): a global flag on the default-ctx breaks
  the platform. Build-time filtering of the registry — the second layer, a follow-up.
- **Phases 2–3 (tenancy addon, per §3.0):** strictly in the order of preconditions —
  1. **auth seam** (R13) — done (branch `feature/auth-seam`):
     the `graphden.auth.provider/AuthProvider` protocol + the default
     `SingleTokenAuthProvider`; the Integrant key `:auth/provider`; the ctx option
     `:auth-provider`; the graph seam `:authenticate-request` →
     `:request-authenticated?`. The addon overrides `:auth/provider`.
  2. **addon manifest** — done: `config/read-config` deep-merges
     the Aero fragments of the addons from `GRAPHDEN_ADDON_CONFIGS` (env, comma-sep
     classpath resources) on top of the core config; an addon fragment overrides
     indirection keys (e.g. `:auth/provider`) + adds its own; the directive
     `:graphden/require` loads the addon's init-key namespaces. Without addons —
     a no-op (single-tenant). (Replaces the `executor-packages.edn` sketch.)
  3. **`OrgScopedStorage` + RLS** (R6, both layers) — **BUILT AND
     TESTED** (B1–B5, branch `feature/auth-seam`):
     - *storage-seam* `:app/storage` (Integrant identity-passthrough) UNDER
       versioning, so that `vs/unwrap` preserves the tenant filter (nuance 1);
     - **B1** the `OrgScopedStorage` decorator (own+public read, own write,
       stamps org, delegates 8 protocols);
     - **B2** the `org-id` column on all 5 graph entities (identity-level,
       NULL≡public, not versioned);
     - **B3** the `:org/scoped-storage` init-key + addon fragment → `Versioned(
       OrgScoped(Postgres))` via the manifest (real-Postgres integration);
     - **B4** the `:request-scope` seam → `dispatch` binds `*current-org*` from
       the auth-principal per-request;
     - **B5** Postgres RLS — own+public/own policy + a `set_config` setter,
       enforcement proven by a raw-query test through `SET ROLE` (non-superuser);
     - **provider** `tenancy.auth/TokenAuthProvider` resolves the bearer →
       `{:user :org}` (hashed-token lookup); the chain `token → org →
       *current-org* → OrgScoped + RLS` is proven end-to-end.
     - **ops: datasource-wrap** `rls/org-aware-datasource` wraps
       the `:db/postgres` pool (seam `:datasource-wrap`), sets
       `graphden.current_org` from `*current-org*` on every borrow (tenant →
       org, public/admin/unbound → '' = sees everything); proven by a test.
     - **ops: enable-rls! auto-run** the `:tenancy/rls-enabler` Integrant
       component (depends on `:db/postgres` → the tables are already created) sets
       the policies at boot; proven (policy on all 5 scoped tables).
     *Remaining is clean deploy/infra:* the application under a non-superuser role
     (superuser bypasses RLS); a real token-source (storage/secret instead of
     a static-map). Next in the plan: per-org LRU (R8) → subdomains (R10, R11).
     (The org-scope of secrets R9 — already done, see §7.)
  4. **wiring the effect-gate** — done: the request-scope wrap for
     a real tenant (org ≠ public) binds `cr/*allowed-effects*
     default-cloud-allowed-effects` around the handler → the cloud graph cannot
     do env/io/network/process. `execute` re-binds `*allowed-effects*` only
     from the ctx (the branch-ctx does not have it), so the ambient binding carries through to
     `record-effect!`. Platform (public/admin) — without restrictions.
     Proven: a tenant env/network → throw, db/time → ok; public —
     unrestricted; the binding is restored (no leak). *(Implemented via
     a per-request dynamic var, not ":allowed-effects on the ctx" — the ctx is shared
     per-branch, the org is per-request.)*
  5. **the `grant` primitive** (§4.2) — **PRIMITIVE READY**:
     `tenancy.grant` — `(subject, capability, namespace)`, `can?`
     (default-deny; `:admin` ⇒ the other capabilities; an ns-grant covers
     descendants by dot-path; root = blank ns), the `GrantStore` protocol +
     a static-map impl + the `:tenancy/grant-store` init-key + the `authorized?`
     bridge from the auth-principal (`:user`). Proven (5 tests / 17 assertions).
     - **enforcement (opt-in)** the request-scope wrap, when a
       `:grant-store` is present, gates the tenant's write/execute: `request->capability`
       (POST/PUT/PATCH/DELETE→write, `/execute`→execute, else read) +
       `request-permitted?` → 403 if there is no right on the org; reads are open
       (OrgScoped governs visibility); platform is not gated. Proven.
     - **UI signal** request-scope attaches `X-Graphden-Capabilities`
       (comma-list: `write,execute` / a subset / empty) to every
       non-403 response — the contract the editor reads to hide
       affordances. Proven (a tenant with `:write` → "write", without grants →
       "", platform → "write,execute").
     - **editor-JS gating** the fetch-wrap reads `X-Graphden-Capabilities`
       from every /api response → `graphdenCapabilities` + the body classes
       `gd-no-write`/`gd-no-execute`; CSS hides `.more-actions-trigger`
       (the entry to ✎/+/✕/▶) when `gd-no-write`. Without the addon the header is absent →
       the classes are not set → the editor is unchanged. **Verified with Playwright
       on localhost:9002:** the editor loads cleanly (the fetch-wrap is safe),
       `graphdenCanWrite()` = true without the header, the CSS gate hides the affordance.
     - **per-target-namespace** request-scope is now a coarse gate
       (`has-capability?` — "a writer/executor at all?"), and the PRECISE
       check is done by the storage layer (`tenancy.authz/authorize-writer`):
       resolves the ns-path from the `:ns` tree (name+parent-id), checks the grant
       against the real target-namespace, throws `:authz/forbidden` →
       request-scope catches → 403. `*current-principal*` is bound in
       request-scope. Proven: unit (ns-path/writable?/guard) + **real
       Postgres** (alice with a grant on acme.team writes there, but not into acme;
       admin/public is not gated). Scope: `:fn` writes with a `:namespace-id`;
       other entities/updates without an ns-id — coarse-gate + RLS (follow-up).
     - **storage-backed store** a new seam `:db/schema {:extensions […]}`
       (the addon adds entities without editing the core) → the `:grant` entity
       (`graphden.tenancy.grant-schema`) + `StorageBackedGrantStore` reads
       the `:grant` rows (capability text→keyword), `:tenancy/grant-store` is
       polymorphic (`:storage` → storage-backed, `:grants` → static).
       Proven: unit (extend-builder, seam, store) + **real-Postgres**
       (the `:grant` table, roundtrip, `can?`); boot backward-compat. Grants
       are now ordinary entities → CRUD via `/api/entities/grant`.
     - **per-action gating** CSS by `data-action` in the popover: `gd-no-write`
       hides write actions (rename/extend/delete/mi-parent/ns-move/use-site/
       service-settings), `gd-no-execute` — `run-fn`; read actions
       (i/↗/⌛) are always visible; full read-only (no write AND execute) hides
       the `⋯` entry itself. **Verified with Playwright:** execute-only hides write +
       shows run; write-only hides run; read-only hides the entry.
     - **per-namespace execute** an injectable `:execute-guard` on the ctx;
       `cr/execute` consults it ONCE at top-level (the recursion flag
       `*execute-authorized*` keeps it out of the hot sub-fn path), throws
       `:authz/forbidden` → 403-bridge. `authz/authorize-executor` resolves
       the fn's namespace (read :fn → namespace-id → ns-path) + checks
       `:execute`; skip for public/admin + system (no principal). Proven:
       unit (guard by namespace, fires-once, denial/skip) + execute_http
       (a real /api/execute via dispatch) + boot backward-compat.
     - **personal namespaces** `grant/with-personal-namespaces` —
       a GrantStore decorator: each user implicitly holds `:admin` on
       `<prefix>.<user>` (e.g. `users.alice`), without a grant row;
       composes with the static/storage-backed store; `:personal-ns-prefix`
       in `:tenancy/grant-store`. Proven: a user owns their ns +
       descendants (all capabilities), does not own someone else's, base grants
       are preserved. *(Provisioning the `:ns` entity itself — a follow-up.)*
     - **workspaces (backend)** `grant/workspace` — the union of named
       namespaces from the user's grants (+ personal via the decorator; root/blank
       and public excluded); a sorted set. Surfaced via
       the `X-Graphden-Workspace` header in request-scope (tenant → their
       namespaces, platform → empty). Proven: pure + the header via
       dispatch. **editor-frontend** the fetch-wrap reads the header →
       `graphdenWorkspace` + `window.graphdenInWorkspace(path)`; the sidebar
       attaches `.ns-in-workspace` to an ns-header in the workspace → CSS left-accent.
       Without the addon — a no-op. **Verified with Playwright:** the helper exists, a no-op without
       a workspace, the CSS accent 2px, matching (exact+descendant yes, parent/
       sibling no).
     *R2 (`:terminal` seal / `:list-append`) — done, see §7.*
  6. **the product fns-package** "org-admin" (UI for organizations/users/grants),
     depends on the addon's entities.
     - **fns-channel seam** `:app/packages {:extra-package-names […]}` —
       the addon adds its own fns-packages via the manifest, without rewriting
       the core list; they are loaded ONLY when the addon is active (§2.1). Proven:
       extra loads together with core, no-extra is unchanged, boot ok.
       *This unblocks the org-admin UI as the `tenancy-admin` fns-package.*
     - **route-collection seam** The problem: core `:all` cannot reference
       the routes of a conditionally-loaded addon package (an fn-def name collision =
       a hard error; an unresolved arg-ref → a silent literal, garbage in the list
       of routes). The solution mirrors branch-router: a new core-package
       `web/reitit` primitive `:router-or-nil` (a reitit router that returns
       nil on no-match, for fall-through) + a singleton
       `graphden.system.route-collection` (later renamed from
       `tenancy-router`; now an ORDERED collection of fall-through routers).
       The addon package
       `tenancy-admin` compiles its routes into `:tenancy-router`
       (`:router-or-nil` over `:tenancy-routes`); the init-key
       `:tenancy/router-install` puts it into the singleton AFTER `:exec/context`.
       `branch-router/dispatch` consults the singleton INSIDE request-scope
       (like the app-router alongside) — the control-plane panels run org-scoped
       (`*current-org*` is bound), otherwise a tenant read of `:grant` would leak across
       all orgs. Without the addon the singleton is nil → a transparent pass-through,
       single-tenant byte-for-byte unchanged. Proven (faas_app_test):
       the seam serves `/partials/grants-admin`, falls through on `/health`,
       org-gating (public sees a grant, tenant — an empty table).
     - **grants-admin** Migrated from core `app/admin` into `tenancy-admin`
       (panel + `:list-grants`/`:create-grant` base-fns + `POST /api/grants`),
       removed from core `:all`; the editor JS is untouched (the same `/partials/*` path,
       `/api/grants` is marked `api-url-drift-allow`).
     - **users-admin** Migrated by the same pattern (panel + `:list-users`/
       `:invoke-create-user` + `POST /api/users`). Along the way a shared
       `tenancy-admin/router` module was extracted: `:tenancy-routes` aggregates the routes of all
       panels, `:tenancy-router` compiles them (each panel module just
       declares its own routes). `/api/users` is marked `api-url-drift-allow`.
     - **provisioning + my-app** Migrated as the modules `registration`
       (create-org/token/domain + set-org-handler, platform-only) and `my-app`
       (set-my-app-handler + verify-my-domain, self-serve). Removed from core
       `:all`; not called from the editor JS (except `/api/my-app/handler`).
     - **auth** login/signup/logout/logout-all migrated into
       `tenancy-admin/auth`. After this **`app/admin` was deleted** (the package is empty).
       Single-tenant authentication (a static bearer + GET `/api/auth/check`,
       a CORE route in `app/routes`) is untouched; the editor calls the auth routes only in
       multi-tenant (`loginIsTenant()`). The auth handlers consume the platform
       response templates `:text-unauthorized-response` (401) and
       `:text-too-many-requests-response` (429) from `web.response` — they remain
       a reusable HTTP vocabulary in core, even though only the addon calls them now
       (otherwise the core-only reachability audit flags them as "unreachable").
     - **window.API carries the tenancy routes too (frontend decoupling preserved).**
       NO hardcoded literals/`api-url-drift-allow` in the editor JS: editor-auth/
       grants/users/row-actions address the tenancy routes via `window.API.api_*`
       (like the core routes). The addon contributes its paths into `window.API` — this is
       the frontend half of the route-collection seam: `:tenancy/router-install` after
       `:exec/api-routes-js-cache` (an ig dependency) regenerates the cache from
       `:_router ∪ tenancy-router` via the new `api-routes-js/install-from-
       routers!`. Single-tenant: the tenancy keys are absent from `window.API`, but they are also
       not called (panels render only when the addon is active; auth — only
       `loginIsTenant()`). We do not touch the drift check — `window.API.x` is a
       property access, not an `/api/*` literal, there is nothing for it to catch.
     *Summary of §6:* the entire tenancy control-plane (panels + provisioning + my-app +
     auth) lives in the addon-only package `tenancy-admin`, loaded via the
     route-collection seam BOTH on the backend (tenancy-router) AND on the frontend (window.API);
     `app/admin` is gone; the frontend auto-picks-up the tenancy routes from the routing
     graph, there is no hardcoding of paths. Single-tenant without the addon does not have them.

     **Update (2026-07-28):** the route-collection seam was generalized to an ORDERED
     collection (`system/route_collection`, renamed from `tenancy_router`) and
     remains ONLY for tenancy. The optional first-party packages `registry` /
     `mcp` do NOT use this seam: the boot-frozen branch-agnostic router
     bakes in constant reads and does not thread `:request`/`:storage-query`,
     so it cannot serve app-HTTP routes. They are served **per-branch**
     via `branch_router` (`:_registry-ring-response` / `:_mcp-ring-response`,
     resolved tolerantly). window.API is now regenerated via
     `install-base-routers!` (the main `:_router` ∪ registry/mcp paths) +
     `rebuild-window-api!` (∪ the addon's collection). Details are in the memory
     `project_optional_packages_seam_vs_perbranch`.
     - **HTMX forms (grants/users)** The client-JS fetch layer of the panels was removed:
       create — a real `<form hx-post>`, delete — `hx-delete` +
       `hx-swap="delete"` (the `<tr>` row disappears, the response does not matter), both directly in
       the server-rendered hiccup. The POST-handler via `:do [create render-panel]`
       returns the UPDATED panel → HTMX swaps it into `[data-*-panel]` (the form
       clears, the new row is visible). The bearer rides through the `htmx:configRequest`
       bridge. editor-grants/users-admin.js shrank to a gated-mount + `hx-get`
       lazy-load; `wire*`/`refresh*` were removed. The path is single-sourced: both the route and
       `hx-post` reference a shared `:const` (`:_grants-api-path`/
       `:_users-api-path`) — the form does not drift from the route. The panels' JS has NEITHER
       paths NOR `API.*` — the paths live only in the graph. Covered by integration
       tests (the partial render contains hx-*; no `data-act`).
     *Next:* by the same pattern the remaining editor popovers can be migrated
     from JS-fetch to HTMX (EDITOR_HTMX_MIGRATION_PLAN). ~~One known gap: a real
     HTMX submit in the browser is tested only in multi-tenant~~ — CLOSED:
     `tools/browser-test/edit-packages-publish-form.test.js` drives the packages
     panel's "Publish a namespace" `<form hx-post>` end-to-end on the plain
     single-tenant stack (fill → submit → HTMX form-encode + Authorization
     bridge → outerHTML swap → server-side row asserted); runs in every e2e
     gate. The multi-tenant grants/users panels remain covered by their own
     integration render tests.
- **Phase 4 (breakup/mixed mode):** protocols + deps.edn git-deps;
  the mixed mode = a composition of "org-scoped storage API" + "their executor".
  Polylith — optional, not a blocker.

> **Phase 1 + 1.5 — closed in this session** (16 package commits + the gate). Phases
> 2–3 are **one tenancy addon** per the §3.0 plan; start strictly with the auth seam
>
> - the manifest, otherwise the rest cannot be wedged in. Self-hosted without the addon works
> already (single-tenant) — the addon is optional by design.
