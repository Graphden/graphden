# Tenancy addon — session handoff

How to resume the tenancy / org-admin work in a fresh session.

## How to update the session and continue

1. **Pull the branch**

   ```bash
   git checkout feature/org-admin-ui   # forked from feature/auth-seam
   git log --oneline main..HEAD | head -40   # the 32 tenancy commits
   ```

2. **Prime a fresh Claude Code session** — paste this:

   > Read `docs/TENANCY_HANDOFF.md` and `docs/PLATFORM_PLAN.md` §3.0 + §8.
   > We're on `feature/org-admin-ui`. The tenancy authz core (§4) is DONE
   > and tested. Continue with the grants-admin panel per the handoff's
   > "Next task" — start with step 1 (route-collection seam).

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

## Next task: grants-admin panel (§6) — IN PROGRESS

**Architectural decision taken (and why):** the panel lives in CORE (`app`),
NOT a `tenancy-admin` addon package. Reason: the panel's route must sit in
the core `:all` list (`app/route-groups/fns.edn`), and `:all` can't reference
a conditionally-loaded addon route without breaking sync — and there's no
route-collection seam. So the panel is in `app/admin` and DEGRADES gracefully
(`:try`) when the addon's `:grant` entity is absent. The clean addon-package
home is a future refactor once a route-collection seam exists (tag-based
collection via `:fn-names-with-tag` is the likely path).

**Done:** `app/admin` module + `:list-grants` base-fn (`(sp/query-entities
(:storage ctx) :grant {})`, registered via the `impls` map, tested).

**Remaining steps (each its own commit):**

1. **Grants-admin partial** in `app/admin/fns.edn`. Patterns confirmed:
   - hiccup nodes: `{:parent :hiccup :args {:tag {:value "td"} :attrs {:value
     {:class "x"}} :children [ref-or-{:value "text"}]}}`. Inline anon
     `{:parent …}` is NOT allowed in `:children` — extract every cell/row to a
     named `_`-fn-def.
   - row cells read the mapped item: `{:parent :get :args {:coll {:as :item}
     :key {:value :subject} :default {:value ""}}}`.
   - rows: `{:parent :map :args {:func :_grant-row :coll :list-grants}}`;
     `tbody`'s `:children` takes the rows-list ref (hiccup flattens lists).
   - degradation: `{:parent :try :args {:body :_grants-table :on-throw
     {:parent :const :args {:value :_grants-disabled}}}}` — `:on-throw` is a
     `[:fn :any]` callable, so wrap the fallback hiccup in `:const`.
   - handler/route: `:render-hiccup` → `:html-ok-response` →
     `:get-auth-required {:path {:value "/partials/grants-admin"} :handler …}`.
     Mirror `app/secrets/fns.edn` lines ~528-650 + `:_partial-secrets-panel-handler`.
2. **Register the route** — add `:partial-grants-admin` to `:all` in
   `app/route-groups/fns.edn`.
3. **Sync-validate** — bootstrap (`bb test` smoke or an integration test that
   runs the full sync); the type-checker catches hiccup/`:get`/`:try` shape
   errors. Iterate until the sweep is clean.
4. **Editor-JS mount** — a sidebar section like `editor-secrets.js`: fetch
   `/partials/grants-admin`, swap into a host, gate visibility on the admin
   capability. `bb rebuild` + Playwright (cache-bust!).
5. **Test** — degraded path (no addon → "Tenancy not active") is unit-able;
   happy path needs the addon active (model on `grant_schema_integration_test`).

## Next task (old, superseded): grants-admin panel (§6 product layer)

Grants are ordinary `:grant` entities; **create/delete already work** via the
generic `POST /api/entities/grant` + `DELETE /api/entities/grant/:id`. What's
missing is LIST + a route + the UI. It has two architectural gaps and some
graph-composition work — do it in this order, each its own commit:

1. **Route-collection seam** (prerequisite). `:all` in
   `app/route-groups/fns.edn` is a fixed `:list`; an addon package can't add a
   route (redefining `:all` collides on the unique name). Add a seam so an
   addon's routes reach the router — mirror the `:db/schema {:extensions …}`
   pattern (`system/core.clj`) or the `:app/packages :extra-package-names`
   one: e.g. have the router builder concat an injectable extra-routes list.
   Test like `config_test`'s `app-packages-extra-seam`.

2. **`:list-grants` base-fn** (or reuse pg-query). Simplest: a one-line base-fn
   wrapping `(sp/query-entities storage :grant {})` so the panel doesn't have
   to build a HoneySQL map as a fn-def literal (the JSONB-keyword roundtrip on
   a literal `{:from :grant}` hsql is fragile — see the JSONB memories). Put it
   in the tenancy-admin package's impls.clj.

3. **tenancy-admin fns-package** — `resources/packages/tenancy-admin/`
   (`package.edn` deps `["core" "web" "storage" "app"]`, NOT in the core list).
   The grants-admin partial mirrors the secrets-panel
   (`app/secrets/fns.edn` ~lines 528-650): `:list-grants` → `:map` → hiccup
   table (subject | capability | namespace | delete `[data-act]` button) +
   a create form → `:render-hiccup` → `:html-ok-response` →
   `:get-auth-required` route, registered via the step-1 seam. Wire the package
   via the manifest: `:app/packages {:extra-package-names ["tenancy-admin"]}`
   (already documented in `resources/graphden/tenancy/addon.edn`).

4. **Editor-JS mount** — a small sidebar section like `editor-secrets.js`:
   fetch `/partials/grants-admin`, swap into a host, bind create/delete via
   event-delegation on `[data-act]`. Gate visibility on the admin capability.
   `bb rebuild` + Playwright-verify (cache-bust!).

5. **Test** — the happy path needs the addon active (full sync + `:grant`).
   Model on `grant_schema_integration_test.clj` (builds a storage with the
   `:grant` entity). Also test the degraded/empty path.

### Gotchas already discovered

- Don't put the panel in core packages "to avoid the seam" — it would couple
  core to `:grant` and violate the ADR. Build the route-collection seam (step
  1) and ship it as the tenancy-admin package.
- `bb test` / kaocha: `--focus` caps at ~3 before a spec error; batch.
- The capability/workspace headers (`X-Graphden-Capabilities` /
  `X-Graphden-Workspace`) are set by the request-scope; the editor reads them
  in `editor-branches.js` (`window.graphdenCanWrite/Execute/InWorkspace`).

## After the panel

Per `PLATFORM_PLAN.md` §8 step 6 + the remainder: subdomains/custom domains,
per-org LRU, org-scoped secrets, `:terminal`/`:list-append` (R2), and the
deploy/ops bits (non-superuser DB role, real token/grant sources, personal
`:ns` provisioning).
