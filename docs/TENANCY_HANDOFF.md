# Tenancy addon — session handoff

How to resume the tenancy / org-admin work in a fresh session.

## How to update the session and continue

1. **Pull the branch**

   ```bash
   git checkout feature/tenancy-users
   git log --oneline main..HEAD | head -40   # the tenancy commits
   ```

2. **Prime a fresh Claude Code session** — paste this:

   > Read `docs/TENANCY_HANDOFF.md` and `docs/PLATFORM_PLAN.md` §3.0 + §8.
   > We're on `feature/tenancy-users`. The tenancy authz core (§4) AND the
   > grants/users admin panels (§6) are DONE and tested. Continue with the
   > remaining follow-ups (service sandbox, org-scoped fn-execution / branch).

3. **Run the tenancy tests to confirm green** (kaocha trips on >3 `--focus`,
   so batch them):

   ```bash
   clojure -M:dev:test -m kaocha.runner \
     --focus graphden.tenancy.authz-test \
     --focus graphden.tenancy.grant-test \
     --focus graphden.tenancy.grant-schema-test
   ```

4. **For UI changes**: `bb rebuild` (jar+docker, ~3-5 min), then verify with
   the Playwright MCP at `http://localhost:9002` — and **cache-bust the URL**
   (`?cb=N`) because `/assets/editor.css` is `immutable`-cached, so the first
   load after a rebuild serves the OLD sheet.

## What's DONE (feature/auth-seam, 31 commits + this branch)

The whole tenancy addon — `graphden.tenancy.*` + the seams in core. Inert
without the manifest (`GRAPHDEN_ADDON_CONFIGS=graphden/tenancy/addon.edn`).
Single-tenant is unchanged throughout.

| Area | Where |
|------|-------|
| Auth seam + org-resolving provider | `auth/provider.clj`, `tenancy/auth.clj` |
| Addon manifest (deep-merge + `:graphden/require`) | `system/config.clj` |
| Storage isolation: OrgScopedStorage + org-id col + RLS | `tenancy/storage.clj`, `tenancy/rls.clj`, `schema/graph/schema.clj` |
| request-scope seam (org bind + effect gate + grant 403 + headers) | `tenancy/addon.clj`, `system/branch_router.clj` |
| Grants: primitive + per-namespace write/execute + storage-backed + personal-ns + workspaces | `tenancy/grant.clj`, `tenancy/authz.clj`, `tenancy/grant_schema.clj` |
| Editor capability gating (per-action) + workspace highlight | `editor-branches.js`, `editor-sidebar.js`, `editor-styles.css` |
| fns-channel seam (`:app/packages :extra-package-names`) | `system/core.clj` |

Everything is unit-tested; isolation/RLS/per-namespace also have real-Postgres
integration tests; the editor gating was Playwright-verified 3×. Plan status
is tracked in `PLATFORM_PLAN.md` §8 (look for ✅).

## Admin-UI work — status (full suite GREEN)

Built on top of the tenancy core, all tested:

- **fns-channel seam** — addons append fns-packages via the manifest.
- **Grants-admin panel** (§6) — view + create + delete (below).
- **Subdomains** (§3.2) — `tenancy.subdomain`, identity resolver default.
- **Custom domains** (R10) — `tenancy.domain`, host resolver + DNS-TXT verify.
- **4 security fixes** (found auditing, each tested):
  1. subdomain is a GUARD not an authority — token is the org authority, a
     foreign subdomain → 403, never widens (was a cross-org read leak).
  2. tenant WRITE of `:service`/`:grant`/`:domain` denied — `:service` runs
     unsandboxed via the reconciler, so a tenant deploy escaped the effect gate.
  3. tenant READ of those denied — `:list-grants` leaked every org's grants.
  4. `:branch` added to the forbidden set — not org-scoped, picker leaked all
     orgs' branches; tenants confined to `main`.

### Substantial follow-ups (each a focused effort — design noted in PLATFORM_PLAN)

- **Service sandbox (§3.3)** — to let tenants deploy services safely: an
  `org-id` on `:service` + a `:service-scope` seam the reconciler applies
  (binds `*current-org*` + `*allowed-effects*` for tenant services) + the
  `:http-server` handler applying it per-request. Until then service deploy is
  platform-only (fix #2).
- **Org-scoped `:fn-execution`** — can't use the OrgScoped stamp (executions
  run in a background future where `*current-org*` is unbound; the own-guard
  would block the terminal UPDATE and drop the result). Needs request-time org
  capture propagated into the future. Low severity now (id-gated).
- **Org-scoped `:branch`** — needs a resolution-timing fix (branch resolves
  before the org binds).

## Grants + users admin panels (§6) — DONE (shipped as `tenancy-admin` package)

Shipped as a dedicated **`tenancy-admin` fns-package**
(`resources/packages/tenancy-admin/`, deps `["core" "web" "storage"
"app"]`, NOT in the core list). The route-collection seam this needed —
letting an addon contribute routes into the router without redefining
core's `:all` — was built, so the panels' routes (grants / users / auth /
provisioning / my-app) reach the router through it, and the tenancy routes
are carried into `window.API`. Panels are HTMX-native (no client-JS fetch
layer).

- `:list-grants` / `:create-grant` base-fns over the addon's `:grant`
  entity; the users panel over `:user`.
- `GET /partials/grants-admin` + `/partials/users` — hiccup tables,
  `:try`-degraded to a notice when the addon is absent.
- create via form-body POST; delete via the generic
  `DELETE /api/entities/{grant,user}/:id`.
- editor sidebar sections gated on authed + `graphdenTenancyActive()`.

Tested: base-fn unit tests, full bootstrap + type-check sweep, degraded-GET
integration, Playwright (gating + the wired handlers find every control).
**Remaining = the addon-active happy-path** (create → list → delete in a
live multi-tenant editor) — the user's verification, since it needs the
addon manifest + `:grant` + real tokens.

## Gotchas (when extending the panels)

- Panels live in the `tenancy-admin` package, NOT core — putting them in
  core would couple core to `:grant` / `:user` and violate the ADR. The
  route-collection seam is how addon routes reach the router; the panels'
  routes register through it.
- `bb test` / kaocha: `--focus` caps at ~3 before a spec error; batch.
- The capability/workspace headers (`X-Graphden-Capabilities` /
  `X-Graphden-Workspace`) are set by the request-scope; the editor reads
  them in `editor-branches.js` (`window.graphdenCanWrite/Execute/InWorkspace`).
- Hiccup composition: inline anon `{:parent …}` is NOT allowed in
  `:children` — extract every cell/row to a named `_`-fn-def. Degrade with
  `{:parent :try … :on-throw {:parent :const :args {:value …}}}` (`:on-throw`
  is a `[:fn :any]` callable, so the fallback hiccup must be `:const`-wrapped).
  Mirror `app/secrets/fns.edn`.

## After the panel

Per `PLATFORM_PLAN.md` §8 step 6 + the remainder: subdomains/custom domains,
per-org LRU, org-scoped secrets, `:terminal`/`:list-append` (R2), and the
deploy/ops bits (non-superuser DB role, real token/grant sources, personal
`:ns` provisioning).
