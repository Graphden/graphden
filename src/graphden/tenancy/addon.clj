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
   `:auth-provider` and binds `*current-org*` for the duration. Until a
   provider that resolves `:org` is wired (session/JWT), every request
   binds the public org — a safe no-op."
  (:require
    [graphden.auth.provider :as auth]
    [graphden.tenancy.auth :as tauth]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.storage :as ts]
    [integrant.core :as ig]))


(defmethod ig/init-key :org/scoped-storage [_ {:keys [base scoped-entities]}]
  (if scoped-entities
    (ts/org-scoped-storage base scoped-entities)
    (ts/org-scoped-storage base)))


(defmethod ig/init-key :auth/multi-tenant-provider [_ {:keys [tokens]}]
  ;; Overrides the core `:auth/provider` seam when a deployment wires it
  ;; (with its own `:tokens` map / secret) — see addon.edn's note. An empty
  ;; map authenticates nothing → every request public (safe).
  (tauth/token-map-provider (or tokens {})))


(defmethod ig/init-key :tenancy/request-scope [_ _]
  ;; (fn [ctx request thunk] …) — authenticate, then run the handler with
  ;; `*current-org*` bound. A request the provider can't authenticate (or a
  ;; provider that returns no `:org`) binds the public org.
  (fn [ctx request thunk]
    (let [principal (when-let [p (:auth-provider ctx)]
                      (auth/authenticate p request))]
      (tc/with-org (tc/org-from-principal principal)
                   (thunk)))))
