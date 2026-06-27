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
    [graphden.executor.compile-runtime :as cr]
    [graphden.tenancy.auth :as tauth]
    [graphden.tenancy.authz :as authz]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.domain :as domain]
    [graphden.tenancy.grant :as grant]
    [graphden.tenancy.grant-schema :as grant-schema]
    [graphden.tenancy.org-schema :as org-schema]
    [graphden.tenancy.rls :as rls]
    [graphden.tenancy.storage :as ts]
    [graphden.tenancy.subdomain :as subdomain]
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
                       :else
                       (binding [cr/*allowed-effects* cr/default-cloud-allowed-effects]
                         (run))))))))
