(ns graphden.tenancy.addon
  "Integrant wiring for the tenancy addon (PLATFORM_PLAN §3.0 B3).

   This is the addon's Clojure entry point — loaded ONLY when an addon
   config fragment names it in `:graphden/require` (see
   `resources/graphden/tenancy/addon.edn`). Core never requires this ns, so
   the whole `graphden.tenancy.*` module is inert in a single-tenant
   deployment.

   The fragment redirects `:db/versioned`'s base through
   `:org/scoped-storage`, so the storage stack becomes
   `Versioned(OrgScoped(app/storage(Postgres)))` — the decorator sits
   beneath versioning exactly where the §3.0 nuance-1 placement requires.

   It also wires `:tenancy/request-scope` onto `:exec/context`'s
   `:request-scope` seam (B4): the fn the branch-router's `dispatch` wraps
   each handler call with, which authenticates the request via the ctx's
   `:auth-provider`, binds `*current-org*`, AND — for a real tenant (org ≠
   public) — binds `*allowed-effects*` to `default-cloud-allowed-effects`,
   so a cloud tenant's graph can't read env / files / network / spawn
   processes (PLATFORM_PLAN §5 — the effect gate). The platform (public org
   / admin) runs unrestricted. `execute` only re-binds `*allowed-effects*`
   from the ctx (never set for branch ctxs), so this ambient binding flows
   straight through to `record-effect!`. When a `:grant-store` is wired, the
   wrap also enforces grants (§4.2): a tenant write / execute it isn't
   authorized for short-circuits to 403; reads stay open. Until a provider
   that resolves `:org` is wired, every request is public — a safe no-op."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.auth.provider :as auth]
    [graphden.crud.type-check :as typecheck]
    [graphden.executor.compile-runtime :as cr]
    [graphden.tenancy.app-router :as app-router]
    [graphden.tenancy.auth :as tauth]
    [graphden.tenancy.authz :as authz]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.deploy :as deploy]
    [graphden.tenancy.domain :as domain]
    [graphden.tenancy.domain-schema :as domain-schema]
    [graphden.tenancy.grant :as grant]
    [graphden.tenancy.grant-schema :as grant-schema]
    [graphden.tenancy.org-schema :as org-schema]
    [graphden.tenancy.rls :as rls]
    [graphden.tenancy.storage :as ts]
    [graphden.tenancy.subdomain :as subdomain]
    [graphden.tenancy.token-schema :as token-schema]
    [graphden.tenancy.user-schema :as user-schema]
    [graphden.tenancy.users :as users]
    [integrant.core :as ig]))


(defmethod ig/init-key :org/scoped-storage [_ {:keys [base scoped-entities grant-store]}]
  ;; `grant-store` (optional) turns on per-target-namespace write enforcement
  ;; (§4.2 refinement) — a denied :fn write throws :authz/forbidden, which
  ;; the request-scope maps to 403. Absent → org-scoping + RLS only.
  (let [authorize-write (when grant-store (authz/authorize-writer grant-store base))]
    (ts/org-scoped-storage base (or scoped-entities ts/default-scoped-entities) authorize-write)))


(defmethod ig/init-key :tenancy/datasource-wrap [_ _]
  ;; The fn `:db/postgres` applies to its pool so every connection carries
  ;; graphden.current_org — the RLS ops wiring (B5).
  rls/org-aware-datasource)


(defmethod ig/init-key :tenancy/rls-enabler [_ {:keys [storage]}]
  ;; Install the RLS policies at boot. Depends on `:db/postgres`, whose
  ;; init-key already created the tables (initialize-with-cleanup!), so the
  ;; ALTER TABLE / CREATE POLICY have something to attach to. Idempotent.
  (log/info "Installing RLS policies on org-scoped tables")
  (rls/enable-rls! (:pool storage))
  :enabled)


(defmethod ig/init-key :tenancy/grant-schema [_ _]
  ;; The `(builder → builder)` fn `:db/schema`'s :extensions seam applies to
  ;; add the `:grant` entity (§4.2 — storage-backed grants).
  grant-schema/extend-builder)


(defmethod ig/init-key :tenancy/org-schema [_ _]
  ;; Adds the `:org` entity (§3.4 — orgs registry for the FaaS app model).
  org-schema/extend-builder)


(defmethod ig/init-key :tenancy/token-schema [_ _]
  ;; Adds the `:token` entity (§3.4 #1 — storage-backed auth tokens).
  token-schema/extend-builder)


(defmethod ig/init-key :tenancy/domain-schema [_ _]
  ;; Adds the `:domain` entity (§3.4 #2 — storage-backed custom domains).
  domain-schema/extend-builder)


(defmethod ig/init-key :tenancy/user-schema [_ _]
  ;; Adds the `:user` entity (§4.1 — login identities).
  user-schema/extend-builder)


(defmethod ig/init-key :tenancy/user-ops [_ {:keys [signup-max-per-min signup-window-ms]}]
  ;; User-model seam (§4.1) — `{:create-user … :login … :logout … :signup …}`.
  ;; Wired onto `:exec/context`'s `:user-ops`; the core `:invoke-*` base-fns call
  ;; into it. login! mints a session `:token` (TTL); logout!/logout-all! delete.
  ;; `:signup` wraps signup! with a PER-IP fixed-window rate limiter (default
  ;; 20/min) to blunt mass-signup abuse — an over-quota IP gets nil (→ 401).
  (let [signup-limiter (users/make-rate-limiter (or signup-max-per-min 20)
                                                (or signup-window-ms 60000))]
    {:create-user users/create-user!
     :login users/login!
     :logout users/logout!
     :logout-all users/logout-all!
     :signup (fn [ctx u p o request]
               ;; Over-quota IP → a {:rate-limited true} sentinel the handler
               ;; maps to 429 (distinct from a nil signup-failure → 401).
               (if (signup-limiter (users/client-ip request))
                 (users/signup! ctx u p o)
                 {:rate-limited true}))}))


(defmethod ig/init-key :tenancy/session-cleanup [_ {:keys [storage period-ms]}]
  ;; Periodically hard-delete expired session `:token` rows (§4.1) — the
  ;; provider already ignores them, this stops them accumulating. Mirrors
  ;; `:exec/cleanup-scheduler`. `:storage` = base (:db/postgres); platform
  ;; context. Default period 1h. Addon-only.
  (let [period (or period-ms (* 60 60 1000))
        ds (:pool storage)
        scheduler (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
    (log/info "Starting tenancy session-cleanup scheduler — period" period "ms")
    (java.util.concurrent.ScheduledExecutorService/.scheduleAtFixedRate
      scheduler
      ^Runnable (fn []
                  (try (users/cleanup-expired-tokens! ds)
                       (catch Exception e
                         (log/warn e "session-cleanup sweep failed"))))
      period period
      java.util.concurrent.TimeUnit/MILLISECONDS)
    scheduler))


(defmethod ig/halt-key! :tenancy/session-cleanup [_ ^java.util.concurrent.ScheduledExecutorService scheduler]
  (when scheduler
    (log/info "Stopping tenancy session-cleanup scheduler...")
    (java.util.concurrent.ExecutorService/.shutdown scheduler)
    (try (java.util.concurrent.ExecutorService/.awaitTermination
           scheduler 5 java.util.concurrent.TimeUnit/SECONDS)
         (catch InterruptedException _ nil))))


(defmethod ig/init-key :tenancy/storage-host-resolver [_ {:keys [storage]}]
  ;; A `HostResolver` over `:domain` rows (provisionable custom domains).
  ;; Wire into `:tenancy/app-router` / `:tenancy/request-scope` `:host-resolver`.
  ;; `:storage` = base (:db/postgres) — resolution runs in the platform context.
  (domain/storage-host-resolver storage))


(defmethod ig/init-key :tenancy/grant-store [_ {:keys [grants storage personal-ns-prefix]}]
  ;; Authorization primitive (§4.2). `:storage` → a store reading `:grant`
  ;; rows (persistent, manageable); else `:grants` → a static-map store.
  ;; `grant/can?` is identical either way. Empty → default-deny.
  ;; `:personal-ns-prefix` (e.g. "users") → every user implicitly owns
  ;; `<prefix>.<user>` (§4.4 personal namespaces).
  (cond-> (if storage
            (grant-schema/storage-grant-store storage)
            (grant/static-grant-store (or grants [])))
    personal-ns-prefix (grant/with-personal-namespaces personal-ns-prefix)))


(defmethod ig/init-key :tenancy/execute-guard [_ {:keys [grant-store]}]
  ;; Per-namespace execute gate (§4.2): `execute` consults this once per
  ;; top-level call. nil grant-store → no guard (org-coarse only).
  (when grant-store
    (authz/authorize-executor grant-store)))


(defmethod ig/init-key :auth/multi-tenant-provider [_ {:keys [tokens]}]
  ;; Overrides the core `:auth/provider` seam when a deployment wires it
  ;; (with its own `:tokens` map / secret) — see addon.edn's note. An empty
  ;; map authenticates nothing → every request public (safe).
  (tauth/token-map-provider (or tokens {})))


(defmethod ig/init-key :auth/storage-token-provider [_ {:keys [storage]}]
  ;; Storage-backed alternative to `:auth/multi-tenant-provider` (§3.4 #1):
  ;; resolves a bearer against `:token` rows, so onboarding is a row insert,
  ;; not a config edit + redeploy. `:storage` = base (:db/postgres) — auth
  ;; runs in the platform context, before the request scope binds an org.
  (tauth/storage-token-provider storage))


(defmethod ig/init-key :tenancy/app-router [_ {:keys [org-resolver base-domain host-resolver timeout-ms]}]
  ;; (§3.4 FaaS) The seam the branch-router's dispatch consults first: a
  ;; tenant-subdomain request is served by that org's handler fn, sandboxed +
  ;; time-bounded. Wired onto `:exec/context`'s `:app-router`.
  (app-router/make-app-router org-resolver base-domain host-resolver
                              (or timeout-ms app-router/default-app-timeout-ms)))


(defmethod ig/init-key :tenancy/set-org-handler [_ _]
  ;; (§3.4 4b) Self-serve deploy seam — `(fn [ctx fn-id] …)`. Wired onto
  ;; `:exec/context`'s `:set-org-handler`; the core `:invoke-set-org-handler`
  ;; base-fn calls it. Validates ownership + does the controlled `:org` update.
  deploy/set-org-handler!)


(defmethod ig/init-key :tenancy/verify-domain [_ _]
  ;; (§3.4 #2) Self-serve DNS-verify seam — `(fn [ctx hostname] …)`. Wired onto
  ;; `:exec/context`'s `:verify-domain`; the core `:invoke-verify-domain` base-fn
  ;; calls it. Validates org-ownership of the row, runs the privileged DNS-TXT
  ;; lookup, flips `:domain.verified?` under escalation.
  deploy/verify-domain!)


(defmethod ig/init-key :tenancy/org-resolver [_ {:keys [subdomains]}]
  ;; (§3.2) Resolve org from the Host subdomain. Default — IDENTITY: the
  ;; subdomain label IS the org-id (`acme.<base-domain>` → org `acme`), no
  ;; table needed. Pass `:subdomains {…}` only for vanity aliases where a
  ;; subdomain differs from its org-id. Wired into `:tenancy/request-scope`
  ;; with `:base-domain`.
  (if (seq subdomains)
    (subdomain/static-org-resolver subdomains)
    (subdomain/identity-org-resolver)))


(def ^:private forbidden-response
  {:status 403
   :headers {"Content-Type" "application/json"}
   :body "{\"ok\":false,\"error\":\"forbidden\"}"})


(defn- request-capabilities
  "The capabilities the editor uses to show/hide affordances for this
   request. Platform / admin (public org) and a deployment with no grant
   store → everything; a real tenant → the subset the user is granted on
   their org. Read is implicit (OrgScoped governs visibility) so only the
   action capabilities are surfaced."
  [grant-store principal org]
  (if (or (= org tc/public-org) (nil? grant-store))
    ["write" "execute"]
    (filterv #(grant/authorized? grant-store principal (keyword %) org)
             ["write" "execute"])))


(defn- request-workspace
  "The user's workspace (§4.4) for this request — the named namespaces their
   grants cover, for the editor to organise / highlight the namespace tree.
   Empty for platform/admin or no grant store (no workspace hint → editor
   shows everything)."
  [grant-store principal org]
  (if (or (= org tc/public-org) (nil? grant-store))
    []
    (vec (grant/workspace grant-store (:user principal)))))


(defn- with-tenancy-headers
  [resp capabilities workspace]
  (cond-> resp
    (map? resp)
    (-> (assoc-in [:headers "X-Graphden-Capabilities"] (str/join "," capabilities))
        (assoc-in [:headers "X-Graphden-Workspace"] (str/join "," workspace)))))


(defmethod ig/init-key :tenancy/request-scope [_ {:keys [grant-store org-resolver base-domain host-resolver]}]
  ;; (fn [ctx request thunk] …) — authenticate, bind the org, restrict
  ;; effects, enforce grants, then run the handler. A request the provider
  ;; can't authenticate (or one with no `:org`) is public → unrestricted,
  ;; exactly as a single-tenant deployment behaves. Grant enforcement is
  ;; OPT-IN: only when a `:grant-store` is wired (else writes pass, subject
  ;; only to OrgScopedStorage + the effect gate). Every non-403 response
  ;; carries `X-Graphden-Capabilities` so the editor can gate affordances.
  (fn [ctx request thunk]
    (let [principal (when-let [p (:auth-provider ctx)]
                      (auth/authenticate p request))
          ;; Org AUTHORITY is the authenticated principal (single-membership:
          ;; the token carries the user's org). The `Host` subdomain (§3.2) is
          ;; a routing GUARD, never a widener — it must NOT be able to set the
          ;; org context to one the principal doesn't belong to, or any caller
          ;; could read another org's data by spoofing the Host. So: the
          ;; subdomain only ever DENIES (a tenant on a foreign subdomain →
          ;; cross-org → 403); it never grants. Anonymous (no principal) → the
          ;; subdomain is ignored → public.
          org (tc/org-from-principal principal)
          ;; The org the Host points at: a `<org>.<base-domain>` subdomain
          ;; (§3.2) or a verified custom domain (R10). Same guard semantics
          ;; either way — it can only deny, never widen.
          host-org (or (subdomain/org-from-request org-resolver request base-domain)
                       (domain/org-from-request host-resolver request))
          cross-org? (and host-org
                          (not= org tc/public-org)
                          (not= host-org org))
          ;; Run the handler, attach the capability header, and map a
          ;; per-namespace `:authz/forbidden` thrown at the storage layer to
          ;; a clean 403 (the storage guard is where the target namespace is
          ;; known — see tenancy.authz).
          run (fn []
                (try
                  (with-tenancy-headers
                    (thunk)
                    (request-capabilities grant-store principal org)
                    (request-workspace grant-store principal org))
                  (catch clojure.lang.ExceptionInfo e
                    (if (= :authz/forbidden (:type (ex-data e)))
                      forbidden-response
                      (throw e)))))]
      ;; `*current-principal*` is read by the storage-layer per-namespace
      ;; guard; `*current-org*` scopes storage + RLS.
      (binding [tc/*current-principal* principal]
        (tc/with-org org
                     (cond
                       ;; Cross-org (§3.2): an authenticated tenant whose org
                       ;; doesn't match the Host subdomain → wrong workspace →
                       ;; 403. Guards against Host-spoofing to reach another
                       ;; org; the subdomain can only deny, never widen.
                       cross-org?
                       forbidden-response
                       ;; Platform / admin — no restriction.
                       (= org tc/public-org)
                       (run)
                       ;; Tenant who isn't a writer/executor at all for this request →
                       ;; 403 (the precise per-namespace check runs in `run` via the
                       ;; storage guard).
                       (and grant-store
                            (not (grant/request-permitted? grant-store principal request org)))
                       forbidden-response
                       ;; Tenant — gate effects (env / io / network / process), run.
                       ;; A READ request additionally runs under the org-filtered
                       ;; type-alias view (§4 Risk-2) so editor display paths
                       ;; (value-form / types) resolve a tenant's `Foo`, never
                       ;; another org's. Writes are NOT wrapped — registration
                       ;; (rebuild) must reach the org-agnostic global; their
                       ;; type-checks are filtered narrowly in crud.type-check.
                       :else
                       (binding [cr/*allowed-effects* cr/default-cloud-allowed-effects]
                         (if (= :read (grant/request->capability request))
                           (typecheck/with-org-alias-view* run)
                           (run)))))))))
