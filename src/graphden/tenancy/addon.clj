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
    [graphden.system.api-routes-js :as api-routes-js]
    [graphden.system.route-collection :as rc]
    [graphden.tenancy.app-router :as app-router]
    [graphden.tenancy.auth :as tauth]
    [graphden.tenancy.authz :as authz]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.demo-gc :as demo-gc]
    [graphden.tenancy.deploy :as deploy]
    [graphden.tenancy.domain :as domain]
    [graphden.tenancy.domain-schema :as domain-schema]
    [graphden.tenancy.grant :as grant]
    [graphden.tenancy.grant-schema :as grant-schema]
    [graphden.tenancy.org-schema :as org-schema]
    [graphden.tenancy.plan :as plan]
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
    ;; Install the read-path view-impl filter into the crud graph-read seam
    ;; (P0 stage 2b): /api/graph/entities now conceals a fn's internal
    ;; composition from a viewer who neither owns it (own-org) nor holds a
    ;; :view-impl grant. Closed over the same base storage + grant-store the
    ;; write guard uses. Whenever this tenancy storage is wired, the seam is on.
    (authz/install-view-impl-filter! grant-store base)
    ;; Install the per-org effect allow-list resolver (task #4): a tenant's
    ;; submitted graph runs under its plan's effects (free = locked, a paid
    ;; tier widens it). `base` reads the tenant-forbidden `:org` row
    ;; unrestricted, on the tenant's behalf.
    (plan/install! base)
    ;; Convey `*current-org*` into background (`:future`) threads alongside the
    ;; effect gate (task #6), so a tenant SERVICE's worker thread stays both
    ;; org-scoped AND effect-gated. Idempotent; `*allowed-effects*` is conveyed
    ;; by core already. Only registered when the addon is present — a single-
    ;; tenant deploy conveys nothing tenancy-specific.
    (cr/register-conveyed-var! #'tc/*current-org*)
    (ts/org-scoped-storage base (or scoped-entities ts/default-scoped-entities) authorize-write)))


(defmethod ig/halt-key! :org/scoped-storage [_ _]
  ;; Clear the process-global seams this component installed, so they're
  ;; lifecycle-bound: a stale filter/resolver (closed storage) can't survive
  ;; into a later system — the cross-test leak that would otherwise let one
  ;; namespace's addon boot break another's execute in the same JVM.
  (authz/uninstall-view-impl-filter!)
  (plan/uninstall!))


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
  ;; Verify the app's DB role is actually subject to those policies. A
  ;; superuser / BYPASSRLS role makes them a silent no-op (RLS is inert;
  ;; only OrgScopedStorage isolates). WARN by default so a trusted
  ;; single-tenant / dev install (superuser DB role) still boots;
  ;; GRAPHDEN_STRICT_RLS=true turns it into a hard boot failure for a
  ;; production multi-tenant deployment. See docs/DEPLOYMENT.md.
  (rls/verify-rls-enforcement! (:pool storage)
                               (= "true" (System/getenv "GRAPHDEN_STRICT_RLS")))
  :enabled)


(defmethod ig/init-key :tenancy/demo-gc [_ {:keys [storage period-ms]}]
  ;; Ephemeral-org reaper (task #7): periodically hard-purge orgs whose
  ;; `:expires-at` has passed. Depends on `:db/postgres` (same `:pool` the RLS
  ;; enabler uses for raw DDL); a NULL `:expires-at` is never selected, so real
  ;; tenants + the public org are untouched. Default period one hour, matching
  ;; `:exec/cleanup-scheduler`.
  (demo-gc/start-reaper! (:pool storage) (or period-ms (* 60 60 1000))))


(defmethod ig/halt-key! :tenancy/demo-gc [_ scheduler]
  (demo-gc/stop-reaper! scheduler))


(defmethod ig/init-key :tenancy/router-install [_ {:keys [context]}]
  ;; Route-collection seam (PLATFORM_PLAN §6): build the addon's
  ;; control-plane router from its `:tenancy-routes` (the org-admin panels,
  ;; loaded via `:app/packages {:extra-package-names ["tenancy-admin"]}`)
  ;; and install it into the JVM-wide route collection under `:tenancy`.
  ;; Depends on `:exec/context`, so it runs AFTER the compiled registry is
  ;; built and `tenancy-router` exists. `graphden.system.branch-router/dispatch`
  ;; consults every installed router INSIDE its request-scope, so the panels
  ;; run under `*current-org*` (org-scoped reads). Absent addon → no `:tenancy`
  ;; entry and the dispatch is a transparent pass-through.
  (log/info "Installing tenancy control-plane router...")
  (let [router (cr/execute-by-name context "tenancy-router" {})]
    (rc/install-router! :tenancy router)
    ;; Frontend half of the route-collection seam: contribute the addon's
    ;; `/api/*` routes to `window.API` so editor JS addresses them via
    ;; `window.API.<key>` (no hardcoded literals — the frontend auto-adapts to
    ;; the addon's routing graph). `rebuild-window-api!` regenerates from the
    ;; remembered first-party base routers (`:_router` + optional registry/mcp)
    ;; ∪ the WHOLE route-collection, so it composes with them. Runs AFTER
    ;; `:exec/api-routes-js-cache` (ig dependency in addon.edn) so the base
    ;; routers are already remembered.
    (api-routes-js/rebuild-window-api!)
    :installed))


(defmethod ig/halt-key! :tenancy/router-install [_ _]
  (rc/remove-router! :tenancy))


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


(defmethod ig/init-key :tenancy/user-ops
  [_ {:keys [signup-max-per-min signup-window-ms login-max-per-min login-window-ms]}]
  ;; User-model seam (§4.1) — `{:create-user … :login … :logout … :signup …}`.
  ;; Wired onto `:exec/context`'s `:user-ops`; the core `:invoke-*` base-fns call
  ;; into it. login! mints a session `:token` (TTL); logout!/logout-all! delete.
  ;; `:signup` and `:login` each wrap their op with a PER-IP fixed-window rate
  ;; limiter — signup blunts mass-account abuse, login blunts password
  ;; brute-force (bcrypt cost-12 slows but does not bound attempts).
  (let [signup-limiter (users/make-rate-limiter (or signup-max-per-min 20)
                                                (or signup-window-ms 60000))
        login-limiter  (users/make-rate-limiter (or login-max-per-min 10)
                                                (or login-window-ms 60000))]
    {:create-user users/create-user!
     :reset-password users/reset-password!
     :delete-user users/delete-user!
     :login (fn [ctx u p request]
              ;; Over-quota IP → nil, i.e. the SAME result as bad credentials
              ;; (→ 401), so the limiter's existence isn't revealed to an
              ;; attacker probing the boundary.
              (when (login-limiter (users/client-ip request))
                (users/login! ctx u p)))
     :logout users/logout!
     :logout-all users/logout-all!
     :signup (fn [ctx u p o request]
               ;; Over-quota IP → a {:rate-limited true} sentinel the handler
               ;; maps to 429 (distinct from a nil signup-failure → 401).
               (if (signup-limiter (users/client-ip request))
                 (users/signup! ctx u p o)
                 {:rate-limited true}))
     ;; Invites (LAUNCH_PLAN stage 1.3). Identity comes from the
     ;; AUTHENTICATED principal — org is never client input, so an invite
     ;; can't target a foreign org by construction. Platform/public
     ;; principals don't invite (operators use :create-user); the same
     ;; signup limiter throttles both minting and redeeming per IP.
     :invite-create (fn [ctx request]
                      (when-let [p tc/*current-principal*]
                        (when (and (:org p)
                                   (not= (:org p) tc/public-org)
                                   (signup-limiter (users/client-ip request)))
                          (users/create-invite! ctx (:user p) (:user-id p) (:org p)))))
     :invite-redeem (fn [ctx invite u pw request]
                      (if (signup-limiter (users/client-ip request))
                        (users/redeem-invite! ctx invite u pw)
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
            (do
              ;; One-time, idempotent migration for the P1 name→id authz change:
              ;; stamp `:token.user-id` / `:grant.subject-id` on any pre-P1 rows
              ;; so a LIVE DB keeps authorizing after this deploy. No-op on a
              ;; fresh DB. Runs here because this is the addon seam that has the
              ;; base storage in hand at startup.
              (users/backfill-auth-subject-ids! storage)
              (grant-schema/storage-grant-store storage))
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


(def ^:private rate-limited-response
  "429 for a tenant org over its request-rate window (LAUNCH_PLAN stage
   1.3). Plain text like its siblings; Retry-After is deliberately absent —
   the window is short and fixed, and advertising it precisely just tunes
   an abuser's clock."
  {:status 429
   :headers {"Content-Type" "text/plain"}
   :body "Rate limit exceeded — slow down."})


(def ^:private forbidden-response
  {:status 403
   :headers {"Content-Type" "application/json"}
   :body "{\"ok\":false,\"error\":\"forbidden\"}"})


(def ^:private misdirected-response
  "421 Misdirected Request — the semantically exact status: the request
   reached a server that cannot produce an authoritative response for
   this authority, and the client should retry on another connection.

   Emitted when this pod's `:executor-orgs` shard doesn't include the
   request's org (see `compile-runtime/org-in-shard?`). The pod compiled
   only its own shard, so it doesn't hold that org's fns; serving the
   request would 404 every fn rather than fail honestly.

   NOT a security response. A tenant that reaches the wrong pod is not
   doing anything wrong — the load balancer sent it there. Cross-org
   Host-spoofing is a different thing and still gets a 403, which is why
   that check runs first."
  {:status 421
   :headers {"Content-Type" "application/json"}
   :body "{\"ok\":false,\"error\":\"misdirected-request\"}"})


(def ^:private domain-error-status
  "The tenancy-admin seam impls throw these domain `:type`s on
   bad / duplicate / missing input. Map each to its HTTP status so the
   control-plane routes return a 4xx instead of a 500 — same shape as the
   `:authz/forbidden → 403` mapping below. Types NOT listed
   (`:org/scoped-storage`, `:app/*`) are internal and correctly stay 500."
  {:grant/invalid-capability 400
   :user/invalid 400
   :domain/unverified 400
   :user/not-found 404
   :user/exists 409
   ;; A concurrent duplicate on a UNIQUE column (`:org.name`,
   ;; `:domain.hostname`, `:token.token-hash`) — `create-org` / `create-domain`
   ;; / `create-token` write those directly, so the storage layer throws this.
   :constraint-violation/unique 409})


(defn- domain-error-response
  "4xx response carrying the machine-readable error `:type` (a keyword,
   so no JSON-escaping is needed) — mirrors `forbidden-response`."
  [err-type status]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (str "{\"ok\":false,\"error\":\"" (subs (str err-type) 1) "\"}")})


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
  (if-let [subj (and (not= org tc/public-org) grant-store
                     (grant/subject principal))]
    (vec (grant/workspace grant-store subj))
    []))


(defn- with-tenancy-headers
  [resp capabilities workspace]
  (cond-> resp
    (map? resp)
    (-> (assoc-in [:headers "X-Graphden-Capabilities"] (str/join "," capabilities))
        (assoc-in [:headers "X-Graphden-Workspace"] (str/join "," workspace)))))


(defmethod ig/init-key :tenancy/request-scope [_ {:keys [grant-store org-resolver base-domain host-resolver
                                                         org-rate-max-per-min org-rate-window-ms]}]
  ;; (fn [ctx request thunk] …) — authenticate, bind the org, restrict
  ;; effects, enforce grants, then run the handler. A request the provider
  ;; can't authenticate (or one with no `:org`) is public → unrestricted,
  ;; exactly as a single-tenant deployment behaves. Grant enforcement is
  ;; OPT-IN: only when a `:grant-store` is wired (else writes pass, subject
  ;; only to OrgScopedStorage + the effect gate). Every non-403 response
  ;; carries `X-Graphden-Capabilities` so the editor can gate affordances.
  ;; Per-ORG fixed-window limiter over ALL tenant API requests (auth
  ;; endpoints have their own per-IP limiters in :tenancy/user-ops). The
  ;; default is generous — the editor legitimately bursts (layout + types +
  ;; partials per interaction) — and it exists to blunt runaway loops and
  ;; scripted abuse, not to meter honest use. 0 disables. Keyed by org, not
  ;; IP: one org hammering from many IPs is throttled; many orgs behind one
  ;; NAT are not collectively punished. Platform/public is never limited.
  (let [org-rate-max (or org-rate-max-per-min 600)
        org-limiter (when (pos? org-rate-max)
                      (users/make-rate-limiter org-rate-max
                                               (or org-rate-window-ms 60000)))]
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
            ;; BYO refusal: a `:byo` org runs on the customer's own executor, so
            ;; a HOSTED pod must not serve it. Read `:org` HERE (public context,
            ;; before `with-org` binds the tenant org — `:org` is tenant-hidden
            ;; once scoped). A BYO executor pod (`:byo-executor?`) skips the
            ;; refusal for the orgs in its shard.
            byo-refused? (and (not (:byo-executor? ctx))
                              (tc/byo-org? (:storage ctx) org))
            ;; Per-request memo over the singleton grant-store, shared by every
            ;; `grants-for` consumer this request: the coarse gate, the header
            ;; builders (capabilities runs `can?` per action cap + workspace
            ;; once), and the storage write guard (fires per row on a batch
            ;; write). Without it each is a fresh `:grant` query. Bound onto
            ;; `*request-grant-store*` below so the init-time guard closure picks
            ;; it up. Fresh atom per request, read-only window (no grant mutation
            ;; between), so it can't serve stale grants.
            header-grant-store (when grant-store (grant/memoizing-grant-store grant-store))
            ;; Run the handler, attach the capability header, and map a
            ;; per-namespace `:authz/forbidden` thrown at the storage layer to
            ;; a clean 403 (the storage guard is where the target namespace is
            ;; known — see tenancy.authz).
            run (fn []
                  (try
                    (with-tenancy-headers
                      (thunk)
                      (request-capabilities header-grant-store principal org)
                      (request-workspace header-grant-store principal org))
                    (catch clojure.lang.ExceptionInfo e
                      (let [t (:type (ex-data e))]
                        (cond
                          (= :authz/forbidden t) forbidden-response
                          (domain-error-status t) (domain-error-response t (domain-error-status t))
                          :else (throw e))))))]
        ;; `*current-principal*` is read by the storage-layer per-namespace
        ;; guard; `*current-org*` scopes storage + RLS;
        ;; `*request-grant-store*` shares the per-request grant memo with the
        ;; storage guard (which fires per row on a batch write) + the coarse
        ;; gate below, collapsing all of them to ONE `:grant` query per subject.
        (binding [tc/*current-principal* principal
                  grant/*request-grant-store* header-grant-store]
          (tc/with-org org
                       (cond
                         ;; Cross-org (§3.2): an authenticated tenant whose org
                         ;; doesn't match the Host subdomain → wrong workspace →
                         ;; 403. Guards against Host-spoofing to reach another
                         ;; org; the subdomain can only deny, never widen.
                         cross-org?
                         forbidden-response
                         ;; Wrong pod: this executor's shard doesn't include the
                         ;; request's org, so its fns were never compiled here.
                         ;; Runs AFTER the cross-org check so a Host-spoofing
                         ;; attempt still gets 403 rather than a routing hint,
                         ;; and BEFORE the public-org short-circuit so a
                         ;; misconfigured shard (one that omits the public org,
                         ;; where the platform packages live) fails loudly at the
                         ;; first request instead of 404'ing every fn.
                         ;; Wrong executor → 421, for either reason: the org
                         ;; isn't in this pod's shard, OR it's a `:byo` org on a
                         ;; hosted pod (its graph lives here but running it is
                         ;; the customer's executor's job — `byo-refused?`).
                         (or (not (cr/org-in-shard? (:executor-orgs ctx) org))
                             byo-refused?)
                         misdirected-response
                         ;; Platform / admin — no restriction.
                         (= org tc/public-org)
                         (run)
                         ;; Tenant over its org-wide request-rate window → 429
                         ;; before any work runs. After the public short-circuit
                         ;; (platform is never limited), before the grant gate
                         ;; (a limited org shouldn't pay the grant query either).
                         (and org-limiter (not (org-limiter org)))
                         rate-limited-response
                         ;; Tenant who isn't a writer/executor at all for this request →
                         ;; 403 (the precise per-namespace check runs in `run` via the
                         ;; storage guard).
                         (and grant-store
                              (not (grant/request-permitted? header-grant-store principal request org)))
                         forbidden-response
                         ;; Tenant — gate the request's effects, run. The
                         ;; handler-level allow-list blocks the external-world
                         ;; effects (env / io / network / process) so a tenant
                         ;; can't drive them through a platform endpoint, but it
                         ;; ALLOWS `:raw-sql`: the trusted handler reads storage
                         ;; via `:pg-query` (a `:raw-sql`-recording base-fn) on
                         ;; the tenant's behalf — gating it here 403'd essentially
                         ;; every tenant request. The tenant's OWN submitted graph
                         ;; is gated more strictly (WITHOUT `:raw-sql`) at the
                         ;; execute boundary (`crud.fn-execution/apply-execute`
                         ;; sets `:allowed-effects` on the exec ctx).
                         ;;
                         ;; A READ request additionally runs under the org-filtered
                         ;; type-alias view (§4 Risk-2) so editor display paths
                         ;; (value-form / types) resolve a tenant's `Foo`, never
                         ;; another org's. Writes are NOT wrapped — registration
                         ;; (rebuild) must reach the org-agnostic global; their
                         ;; type-checks are filtered narrowly in crud.type-check.
                         :else
                         ;; Same tenant scope also turns on the error envelope:
                         ;; internal failures (no whitelisted :type) surface as
                         ;; "Internal error, ref: <uuid>" — full detail stays in
                         ;; the server log under the ref. The binding conveys
                         ;; into apply-execute's futures, so the async-persisted
                         ;; row the history panel reads is scrubbed too.
                         (binding [cr/*allowed-effects* cr/cloud-request-allowed-effects
                                   cr/*scrub-internal-errors?* true]
                           (if (= :read (grant/request->capability request))
                             (typecheck/with-org-alias-view* run)
                             (run))))))))))
