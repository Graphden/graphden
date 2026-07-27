(ns graphden.system.init.exec
  "Integrant init-keys for the executor context + the boot-time checks
   layered on top of it: vault client, auth provider, the
   `ExecutionContext`, the compiled registry, the per-branch router, the
   backend↔frontend URL drift check, the api-routes JS cache, and the
   demo-branches seeder.

   Split out of `graphden.system.core` (which now only loads this ns for
   its `defmethod` side effects). No behaviour change."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.auth.provider :as auth]
    [graphden.clients.vault :as vault]
    [graphden.crud.fn-execution.lookup :as fn-lookup]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.fleet.command :as fleet-command]
    [graphden.fleet.router :as fleet-router]
    [graphden.storage.postgres.notify :as pg-notify]
    [graphden.system.api-routes-js :as api-routes-js]
    [graphden.system.api-url-drift :as api-url-drift]
    [graphden.system.branch-router :as br]
    [graphden.system.demo-branches :as demo]
    [graphden.system.route-collection :as rc]
    [graphden.versioning.storage.core :as vs]
    [integrant.core :as ig]))


;; =============================================================================
;; Vault client (OpenBao / Vault KV v2)
;; =============================================================================
;;
;; Infrastructure-level secrets handle. The executor pulls this off
;; the context to auto-deref `:override-kind :secret-path` bindings
;; on `:secret-leaf`-parented fn-defs. Address + token live in
;; `system-*.edn` (and ultimately env), NEVER exposed to the user
;; fn-graph — that's the whole point of routing user secrets through
;; `:secret-leaf` instead of `:env`.
;;
;; Optional: when address is blank, the key returns nil and the
;; secret-leaf auto-deref raises a clear "vault not configured"
;; error on first use. Lets tests skip the openbao container.

(defmethod ig/init-key :vault/client [_ {:keys [address token]}]
  (let [client (when (and (string? address) (not (str/blank? address)))
                 {:address address :token token})]
    (if client
      (log/info "Vault client configured for" address)
      (log/info "Vault client disabled (no :address) — secret-leaf auto-deref will throw on use"))
    ;; Also stash in the JVM-wide atom so consumers that don't get the
    ;; client through their request ctx (admin handlers running in a
    ;; per-branch ctx whose build doesn't carry :vault forward) can
    ;; still find it. See `graphden.clients.vault/active-client` for
    ;; the rationale.
    (reset! vault/active-client client)
    client))


(defmethod ig/halt-key! :vault/client [_ _]
  (reset! vault/active-client nil))


;; =============================================================================
;; Executor Context
;; =============================================================================

;; Authentication seam (PLATFORM_PLAN §3.0 / §4.1). Core wires the default
;; single-token provider; the tenancy addon overrides this key with a
;; session/JWT provider. Everything downstream (the `:authenticate-request`
;; base-fn → `:request-authenticated?` → auth middleware) is provider-
;; agnostic.
(defmethod ig/init-key :auth/provider [_ {:keys [token]}]
  (log/info "Wiring auth provider {:provider :single-token}")
  (auth/single-token-provider token))


(defn- parse-executor-orgs
  "`\"public,acme,beta\"` → `#{\"public\" \"acme\" \"beta\"}`; blank / nil → nil
   (compile the whole graph — the self-hosted default).

   The operator must list the PUBLIC org explicitly if the deployment has
   one: the platform packages live there once they are written through the
   tenancy decorator, and a pod without them compiles nothing. Rows with a
   NULL `:org-id` are un-owned and always in every shard, so a
   non-tenancy deployment that sets this by accident still works."
  [s]
  (when (string? s)
    (let [orgs (into #{} (comp (map str/trim) (remove str/blank?))
                     (str/split s #","))]
      (when (seq orgs) orgs))))


(defmethod ig/init-key :exec/context
  [_ {:keys [storage vault-client pg-storage base-fns auth-provider request-scope
             execute-guard app-router set-org-handler verify-domain user-ops
             executor-orgs byo-executor? executor-id]}]
  (log/info "Creating executor context...")
  ;; `assoc` (not the constructor's named opts) — the ExecutionContext
  ;; record stays narrow; vault rides on the extra-key surface
  ;; alongside `:compiled-templates`. Impls grab it via `(:vault ctx)`.
  ;;
  ;; `:notify-emitter` is built from the raw PG pool — short-lived
  ;; `SELECT pg_notify(...)` runs through the main pool just like
  ;; any other one-shot query. CRUD writers call
  ;; `((:notify-emitter ctx) event)` after a successful `:service`
  ;; row mutation. Falls back to a no-op when pg-storage is absent
  ;; (tests that don't wire PG).
  ;;
  ;; `:base-fns` comes via integrant ref from `:exec/base-fns` —
  ;; ctx-scoped (no global registry read). When the ref isn't wired
  ;; (older config files or tests that skip `:exec/base-fns`),
  ;; `create-context` falls back to the global registry snapshot.
  (let [emitter (if pg-storage
                  (pg-notify/make-emitter (:pool pg-storage))
                  pg-notify/noop-emitter)
        ;; Fleet forward-hop seam (T2.6): only wired when this pod has a fleet
        ;; identity (`GRAPHDEN_EXECUTOR_ID`). Forwards a misdirected request to
        ;; the executor holding the org's cell (per `:placement`) instead of
        ;; 421. All executors listen on the same port (`GRAPHDEN_PORT`).
        fleet-forward (when (seq executor-id)
                        (let [port (fleet-command/fleet-port)]
                          (fn [request org entry-fn-id]
                            (fleet-router/forward-or-nil storage executor-id port
                                                         org entry-fn-id request))))
        ;; Fleet control-plane command seam (docs/FLEET_RFC.md §6.3): the
        ;; internal cell load/evict endpoint a move (or an ops call) drives.
        ;; Wired on the same condition as the forward-hop (this pod is a fleet
        ;; member). Gated by the shared internal token, NOT the tenant auth-
        ;; provider — a cell command is cross-org platform authority.
        fleet-cmd (when (seq executor-id)
                    (fleet-command/make-command-handler (fleet-command/internal-token)))
        ctx-opts (cond-> {:storage storage}
                   (and base-fns (:base-fns base-fns))
                   (assoc :base-fns (:base-fns base-fns))
                   ;; The auth seam — read by `:authenticate-request` via
                   ;; `(:auth-provider ctx)`. Branch contexts inherit it
                   ;; (build-branch-ctx).
                   auth-provider (assoc :auth-provider auth-provider)
                   ;; Request-scope seam (§3.0 B4) — wrapped around each
                   ;; handler by the branch-router. Only the tenancy addon
                   ;; wires it; absent in core (single-tenant).
                   request-scope (assoc :request-scope request-scope)
                   ;; Per-namespace execute guard (§4.2). Addon-only.
                   execute-guard (assoc :execute-guard execute-guard)
                   ;; App-router seam (§3.4 FaaS) — serves a tenant's subdomain
                   ;; via that org's handler fn. Addon-only.
                   app-router (assoc :app-router app-router)
                   ;; Self-serve deploy seam (§3.4 4b) — addon-only.
                   set-org-handler (assoc :set-org-handler set-org-handler)
                   ;; Self-serve DNS-verify seam (§3.4 #2) — addon-only.
                   verify-domain (assoc :verify-domain verify-domain)
                   ;; User-model seam (§4.1) — create-user / login. Addon-only.
                   user-ops (assoc :user-ops user-ops)
                   ;; Executor shard — the orgs whose fns THIS pod compiles.
                   ;; Absent ⇒ the whole graph (self-hosted / single-tenant).
                   ;; A collection or predicate from an addon passes through;
                   ;; a comma-separated env string is parsed here.
                   executor-orgs
                   (assoc :executor-orgs (if (string? executor-orgs)
                                           (parse-executor-orgs executor-orgs)
                                           executor-orgs))
                   ;; Pod role — a BYO executor serves the `:byo` orgs in its
                   ;; shard; a hosted pod 421s them. From `GRAPHDEN_BYO_EXECUTOR`
                   ;; (parsed to bool) or an addon override.
                   byo-executor?
                   (assoc :byo-executor? (if (string? byo-executor?)
                                           (contains? #{"true" "1" "yes"} byo-executor?)
                                           (boolean byo-executor?)))
                   ;; Fleet forward-hop seam — only present when this pod has a
                   ;; fleet identity (see the let above).
                   fleet-forward (assoc :fleet-forward fleet-forward)
                   ;; Fleet control-plane command seam — same condition.
                   fleet-cmd (assoc :fleet-command fleet-cmd))]
    (cond-> (-> (exec/create-context ctx-opts)
                (assoc :notify-emitter emitter))
      vault-client (assoc :vault vault-client)
      ;; Privileged structural-read storage (§4 org-agnostic compile): the raw
      ;; PG beneath OrgScoped, re-wrapped for this branch. `rebuild!` reads the
      ;; fn-graph STRUCTURE through it so the compiled registry contains every
      ;; org's fns (isolation stays at runtime: org-scoped data reads via
      ;; `:storage` + the `resolve-fn` / execute-guard gates). `:pg-storage`
      ;; rides too so `build-branch-ctx` can build a per-branch compile storage.
      ;; In single-tenant this equals `:storage` (no OrgScoped) → a no-op.
      pg-storage (assoc :pg-storage pg-storage
                        :compile-storage (vs/->VersionedStorage
                                           pg-storage (vs/current-branch-id storage))))))


;; =============================================================================
;; Compiled Registry (compile-at-startup executor)
;; =============================================================================
;;
;; Walks every fn entity in storage and compiles each into a Clojure
;; closure of shape `(fn [all-fns free-args] result)`. Stored in the
;; context's `:compiled-registry` atom for the hot path (HTTP handlers).

(defmethod ig/init-key :exec/compiled-registry [_ {:keys [context]}]
  (log/info "Building compiled registry...")
  (let [registry (cr/rebuild! context)]
    (log/info "Compiled registry built:" (count registry) "fns")
    registry))


;; =============================================================================
;; Branch router — per-branch ExecutionContext + Ring dispatch
;; =============================================================================
;;
;; Each non-default branch needs its OWN compiled registry — the
;; executor closes over ctx at compile-time and a branch's bindings
;; can diverge from main. Lazy build on first request; cached
;; afterwards. Mutations call `invalidate-graph-cache!` on the per-
;; branch ctx via the standard executor invalidation path, so a
;; write on branch X clears X's registry without touching main's.
;;
;; The router lives in a process-wide atom
;; (`branch-router/active-router`) because the wrap base-fn impl
;; runs inside compiled closures that already closed over a fixed
;; ctx.

(defmethod ig/init-key :exec/branch-router [_ {:keys [context]}]
  (log/info "Initialising branch router...")
  (let [router (br/create-router context "_app-ring-response")]
    (br/set-active-router! router)
    router))


(defmethod ig/halt-key! :exec/branch-router [_ _router]
  (br/clear-active-router!))


;; =============================================================================
;; Backend↔frontend URL drift check
;; =============================================================================
;;
;; Runs once after `:_router` is compiled. Enumerates the live
;; router's `/api/*` paths, scans every editor JS file for `/api/*`
;; string-literals, and throws if any literal doesn't match a known
;; path or prefix. Catches "renamed a route fn-def, forgot to
;; update the JS" silently-broken-deploys at boot time.
;;
;; See `graphden.system.api-url-drift` for the algorithm and
;; `graphden.system.api-url-drift-test` for the per-helper unit
;; tests. The check is enabled by default; `skip?` (env-backed) is
;; a circuit breaker for test bootstraps that load a subset of
;; packages.

(defn env-truthy?
  "Parse a wire-friendly truthy flag. Accepts the EDN literal `true`,
   or any of `\"1\" \"true\" \"yes\" \"on\"` (case-insensitive) when
   the value came through an env var. Anything else (including the
   empty string from an unset env in `system-prod.edn`) is OFF.

   Used wherever an integrant arg can come from Aero `#env` (which
   collapses unset vars to `\"\"`, a truthy value in Clojure)."
  [raw]
  (cond
    (true? raw)                  true
    (or (false? raw) (nil? raw)) false
    (string? raw)                (contains? #{"1" "true" "yes" "on"}
                                            (str/lower-case raw))
    :else                        (boolean raw)))


(defmethod ig/init-key :exec/api-url-drift-check
  [_ {:keys [context skip?]}]
  ;; `skip?` reuses `env-truthy?` — unset env collapses to `""`,
  ;; which must NOT count as truthy. Empty string / false / nil →
  ;; run the check; "1" / "true" / "yes" / "on" → skip.
  (if (env-truthy? skip?)
    (do (log/info "API URL drift check skipped"
                  "— set GRAPHDEN_SKIP_URL_DRIFT_CHECK= to re-enable")
        :skipped)
    (do (log/info "Checking editor JS for /api/* URL drift...")
        (let [router (exec/execute-by-name context "_router" {})]
          (api-url-drift/check-router! router)
          (log/info "API URL drift check passed")
          :ok))))


;; =============================================================================
;; API routes JS cache
;; =============================================================================
;;
;; Builds the `window.API = {…}` JS module once at boot from the
;; live `:_router`. Stored in a process-global atom; read by the
;; `cached-api-routes-js` defbase (declared `:effects #{}` so the
;; bundle pipeline doesn't inherit handler effects through this
;; chain). The editor JS bundle's `:_editor-api-routes-script-tag`
;; renders the cached value into an inline `<script>` BEFORE the
;; main editor.js loads, exposing `window.API.<key>` to every
;; editor module.

(defmethod ig/init-key :exec/api-routes-js-cache
  [_ {:keys [context]}]
  (log/info "Building cached api-routes JS module...")
  (let [router (exec/execute-by-name context "_router" {})]
    (api-routes-js/install-from-router! router)
    (log/info "api-routes JS cache:" (count (api-routes-js/read-cache)) "bytes")
    :ok))


(defmethod ig/halt-key! :exec/api-routes-js-cache [_ _]
  (api-routes-js/clear-cache!))


;; =============================================================================
;; Optional-package route installers (route-collection seam)
;; =============================================================================
;;
;; First-party OPTIONAL packages (`mcp`, `registry`) contribute their routes
;; through the route collection (graphden.system.route-collection) instead of
;; being hard-listed in app's `:all` — so a deployment can drop the package
;; from `:package-names` and the app still boots. Each installer is TOLERANT:
;; if the package was omitted its router fn-def isn't synced to storage, so the
;; presence check misses and the installer no-ops. The init-keys are wired in
;; the base config (system-*.edn) with an ordering ref on `:exec/api-routes-js-
;; cache`, so the core-only `window.API` builds first and each installer then
;; rebuilds the union — mirrors how the tenancy addon installs `:tenancy`.

(defn- install-optional-router!
  "If `router-fn-name` is present (its package loaded), compile it into a
   fall-through router, install it under `key` in the route collection, and
   rebuild `window.API` from the core router ∪ the whole collection. Absent
   package → clean no-op. Returns `:installed` or nil."
  [context key router-fn-name]
  (when (fn-lookup/query-fn-by-name (:storage context) router-fn-name true)
    (log/info "Installing optional route-collection router:" key)
    (rc/install-router! key (exec/execute-by-name context router-fn-name {}))
    (api-routes-js/rebuild-window-api! (exec/execute-by-name context "_router" {}))
    :installed))


(defmethod ig/init-key :mcp/router-install [_ {:keys [context]}]
  (install-optional-router! context :mcp "mcp-router"))


(defmethod ig/halt-key! :mcp/router-install [_ _]
  (rc/remove-router! :mcp))


;; =============================================================================
;; Demo branches (dev only — no-op in prod when `:branches` is absent/empty)
;; =============================================================================
;;
;; Pre-bakes a couple of versioning-UI demo branches after package sync
;; finishes. Idempotent: existing branches with the same name are left
;; alone, so a JVM restart doesn't double-write. `bb deploy` wipes the
;; DB, sync re-runs, and the demo branches reappear.
;;
;; See `graphden.system.demo-branches` for the declaration shape.

(defmethod ig/init-key :exec/demo-branches [_ {:keys [context enabled? branches]}]
  (let [on? (env-truthy? enabled?)]
    (cond
      (not on?)
      (log/info "[demo-branches] disabled"
                "— set GRAPHDEN_DEMO_BRANCHES_ENABLED=1 to seed demo branches")

      (seq branches)
      (demo/seed! (:storage context) branches)

      :else
      (log/info "[demo-branches] enabled but :branches is empty — nothing to seed"))
    ;; Returning state keeps it visible in the system map for any
    ;; future REPL-driven re-seed.
    {:enabled? on?
     :branches (vec (or branches []))}))
