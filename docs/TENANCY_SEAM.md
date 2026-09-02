# The tenancy seam

> 🔧 Internal engineering record — not user documentation.
> The reader-facing view of tenant isolation is
> [SECURITY_MODEL.md](SECURITY_MODEL.md); the tier/quota surface is
> [PLANS.md](PLANS.md).

Multi-tenancy is an **optional Integrant addon**, not a fork and not a
graph-package. This repo (the open core) ships every *mechanism* the addon
needs — the seams below — plus one namespace of shared context
(`graphden.tenancy.context`). The multi-tenant *policy* (OrgScoped storage,
RLS, grants/authz, plans, users, the app-router, domains, the `tenancy-admin`
package) lives in the private `graphden-tenancy` repo and plugs into these
seams via config. Without the addon the system is single-tenant: one implicit
org, unrestricted effects, single-token (or open) auth — every seam is an
identity pass-through.

**Why an addon (decision guard).** A security boundary cannot be implemented
by the thing it restricts: tenant graphs execute *inside* the executor, so if
isolation or the effect gate were themselves graph-packages, composition could
circumvent them. Enforcement therefore lives below the graph. But it must
neither fork "cloud vs self-hosted" nor bloat the base binary — so it is a
core-level *optional dependency*, reusing bones the core already has (storage
decorators, Integrant, schema `extend-builder`, the two-channel package
model). Cloud vs self-hosted is a **mode, not code**: one binary; "cloud" =
addon wired + restriction policy. A self-hoster who wants multi-user installs
the same addon — they are not "cloud".

**What holds the seam in place.** Most of the vars the addon reaches here
have no caller in this repo — they exist *for* the addon — so a rename or a
move can pass every graphden test and only break at pin-bump time, in a repo
this CI cannot see. `tools/open-core-seam.edn` lists every var the addon
reaches (generated from its own requires) and
`test/graphden/open_core_seam_test.clj` resolves each one, so the break
lands where the change is made. It proves the NAME resolves, not the
contract — an arity or semantic change still needs the addon's own suite.

The seams, in wiring order:

## Context

`graphden.tenancy.context` — the one tenancy namespace that stays in core,
because core code must be able to *ask* about tenancy without depending on the
policy. Holds the `*current-org*` thread-local (bound per-request by the
addon; unbound = platform/single-tenant), `platform-tier?` /
`platform-admin?` predicates, and the BYO execution-mode resolution
(`GRAPHDEN_BYO_EXECUTOR` / `:org.execution-mode`).

### Capability seams (fine-grained)

Beyond the yes/no predicates above, `context.clj` carries two
installable capability seams — policy lives in the addon, core keeps
only the hook; both are **default-deny** without the addon:

- **Platform axis** — `install-platform-cap-fn!` /
  `current-has-platform-cap? cap`: admits a *delegate* holding one
  platform right (`:view-all-stats`, `:manage-orgs`, …) without the
  whole `:platform-admin` umbrella. The addon's predicate returns
  true for the umbrella too, so an operator passes every gate.
- **Org axis** — `install-org-cap-fn!` / `current-has-org-cap? cap`:
  the same shape scoped to the current org (`:manage-users`,
  `:publish-packages`, …); the addon implements grants + role
  bundles + owner-implies-all.

Because both default to deny, a gate in core/packages MUST pair the
capability check with a `current-platform-tier?` short-circuit to
stay open on single-tenant / operator installs — the shape
`(or (current-platform-tier?) (current-has-org-cap? :publish-packages))`
guarding `publish-package-apply` (`registry/impls.clj`) and the
`:view-all-stats` gate in `app/execution/impls.clj` are the two
precedents.

## Auth seam

Auth is provider-driven, never hardcoded. Core defines
`graphden.auth.provider/AuthProvider` and a default single-token
implementation behind the `:auth/provider` Integrant key
(`system/init/exec.clj`); the graph reaches the *decision* through the
`:authenticate-request` seam in `web/ring-adapter`. Blank `AUTH_TOKEN` = no
provider = the instance runs open (self-hosted no-login mode); a set token or
the addon's session/user provider turns auth on, and then reads are gated too.
The addon overrides `:auth/provider` with token→`{:user :org}` resolution.

## Addon config manifest

`graphden.system.config/read-config` deep-merges Aero fragments listed in
`GRAPHDEN_ADDON_CONFIGS` (comma-separated classpath resources) over the core
config; a fragment's `:graphden/require` vector loads the addon's init-key
namespaces first. An addon fragment overrides indirection keys
(`:auth/provider`, `:app/storage`, …) and adds its own components. Unset env
= no-op = single-tenant.

## Storage & schema seams

- **Storage decorator slot** — `:app/storage` (`system/init/storage.clj`) is
  an identity pass-through **under** `VersionedStorage`, so the addon's
  `OrgScoped` decorator sits where `vs/unwrap` cannot bypass it:
  `Versioned(OrgScoped(Postgres))`.
- **Schema extensions** — `:db/schema {:extensions […]}` lets the addon add
  entities (`:org`, `:user`, `:grant`, `:role`, `:domain`, `:token`, …)
  without editing core schemas.
- **Datasource wrap** — the `:datasource-wrap` seam lets the addon wrap the
  `:db/postgres` pool to `set_config` the current org on every borrow (the
  RLS session variable).
- **The `org-id` column is core, not addon** (decision guard): it sits on the
  graph entities at the identity level (`NULL` ≡ public, not versioned) so
  core queries work in both modes. A tenant is a legitimate cross-cutting
  field, not graph semantics — the addon enables *enforcement*, not the
  column.

## Route-collection seam

`graphden.system.route-collection` — a JVM-wide **ordered collection of
fall-through routers** consulted by `branch_router/dispatch` *inside*
request-scope (so an addon control-plane panel runs with `*current-org*`
bound; a boot-frozen router could not be org-scoped). The addon installs its
router via `install-router!`; without the addon the collection is empty and
dispatch passes through byte-for-byte. The frontend half:
`api-routes-js/install-from-routers!` regenerates the `window.API` cache from
the union of the main router and any installed collections, so addon routes
reach editor JS without hardcoded paths. Note: the optional first-party
`registry` / `mcp` packages deliberately do NOT use this seam — they need
per-branch freshness and are served via `branch_router` handler slots instead.

## Effect gate

The runtime sandbox mechanism is entirely in core
(`executor/compile_runtime.clj`); the addon only sets policy values.

- Every security-sensitive base-fn calls `record-effect!`; with
  `*allowed-effects*` bound to a set, a category outside it throws
  `:execution/forbidden-effect`. `nil` = unrestricted = zero overhead.
- `cloud-forbidden-effects` = `#{:env :io :network :process :raw-sql :cross-org}`.
  `:raw-sql` exists because `:db` must stay allowed (tenants need org-scoped
  storage) while the raw HoneySQL escape hatches would ride on `:db` past
  org-scope and RLS — they record `:raw-sql` additionally.
  `:cross-org` marks the platform-only dispatch primitives that run a fn
  under ANOTHER org's `*current-org*` scope (the cloud domain-router's
  `:execute-in-org`) — safe on the operator plan, forbidden on every
  tenant plan.
- **Two layers** (a single blanket binding around the handler breaks the
  platform, whose own handler reads storage via `:raw-sql`-recording
  primitives):
  1. request level — the addon binds `cloud-request-allowed-effects`
     (safe set + `:raw-sql`) around tenant requests;
  2. the tenant's own graph — the exec-ctx carries
     `cloud-allowed-effects-for` (plan-resolved via the
     `cloud-allowed-effects-resolver` seam, falling back to the locked
     `default-cloud-allowed-effects` = `#{:db :state :time :random}`).
  The platform ctx stays unrestricted; the restriction is a property of
  *executing a user graph*, not a wrapper around the platform.
- Contract for new base-fns: any security-sensitive primitive MUST
  `record-effect!`, or it is a sandbox hole. A new sensitive category goes in
  both `types.core/known-effect-categories` and `cloud-forbidden-effects`.
- **Contract for the platform's own request paths** (`/partials/*`,
  `/api/*` in the `app` package): the handler's effect closure must stay
  inside `cloud-request-allowed-effects`, because on the cloud it runs
  under that set for every tenant session — a closure that reaches `:env`
  or `:network` answers 403 for every tenant, exactly as the branch popover
  (`GRAPHDEN_HUB_URL` via `:env`) and the feedback probe + intake did in
  production, 2026-08-28 → 09-02. Two sanctioned shapes:
  - a *deployment setting* the UI needs (hub URL, feedback URL / armed
    flag, the asset-override rescue hatch) is DECLARED under
    `:exec/deploy-config` in the system config and read through the
    `:deploy-config` base-fn — a boot-time snapshot in the platform process,
    `:effects #{}` ([CONFIGURATION § `:exec/deploy-config`](CONFIGURATION.md#execdeploy-config)).
    Declaring a key is the operator's statement that the value is public;
    a secret never goes there (vault / Clojure-side config), and nothing
    outside the declared map exists in the snapshot.
  - an *outbound notification* (the feedback ping) is the built-in
    alerter's job on its tick, outside any request
    ([MONITORING § 3b](MONITORING.md#3b-built-in-domain-alerter-opt-in--telegram-or-webhook)).
  `tenant_effect_budget_test` pins every `app` route's closure against the
  set, with an explicit ledger for the operator-only exceptions.

## Execute guard

`:execute-guard` on the ExecutionContext — an injectable
`(fn [ctx fn-id])` admission check `cr/execute` consults **once at
top-level** (the `*execute-authorized*` flag keeps it off the hot sub-fn
path), throwing `:authz/forbidden`. The addon installs per-namespace
`:execute` authorization here; core installs nothing.

## Packages channel

`:app/packages {:extra-package-names […]}` (`system/init/packages.clj`) —
the addon ships its control-plane UI as an ordinary fns-package
(`tenancy-admin`) loaded only when the addon is active, without rewriting the
core package list.

## What the addon provides

Everything above is a socket. The plugs — `OrgScopedStorage` (own+public
read / own write / org stamping / tenant-forbidden privileged entities),
Postgres RLS policies, grants + roles + per-namespace authz, plans/tiers +
quotas, users + sessions, the FaaS app-router (tenant apps = handler-fns, not
owned services; the OPERATOR org's own apps run in the platform ctx —
`:operator-org` — so the landing can, e.g., serve its own images through
`:read-resource-bytes`), domains + DNS verification, demo-org GC, operator
bootstrap —
live in the private `graphden-tenancy` repo (see its `docs/PLATFORM_NOTES.md`
for the design rationale) and reach production through the `graphden-cloud`
build.
