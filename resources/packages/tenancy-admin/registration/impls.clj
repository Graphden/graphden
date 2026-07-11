(ns graphden.packages.tenancy-admin.registration.impls
  "Impls for the operator-only provisioning base-fns (PLATFORM_PLAN §3.4),
   migrated out of app.admin via the route-collection seam (§6). Thin storage
   shims over the tenancy addon's `:org` / `:token` / `:domain` entities — all
   tenant-forbidden, so a tenant POST is denied by OrgScoped. They throw when
   the addon isn't active, but this package only loads WITH it."
  (:require
    [clojure.string :as str]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.protocol.core :as sp]))


(defn- token-sha256
  "SHA-256 hex of a bearer token — the key derivation for `:token.token-hash`.
   MUST match `graphden.tenancy.auth/token-hash` (the storage-token-provider
   hashes the same way at read time; the round-trip is tested)."
  [^String s]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (str/join (map #(format "%02x" (bit-and % 0xff))
                   (java.security.MessageDigest/.digest digest (String/.getBytes s "UTF-8"))))))


;; Mint a storage-backed auth token (§3.4 #1). Stores ONLY the hash — the raw
;; bearer is never persisted. Platform-only by entity guard (`:token` is
;; tenant-forbidden), so a tenant POST is denied by OrgScoped.
(defbase create-token
  [token user org]
  ;; Resolve the username to the user's STABLE id at the boundary so an
  ;; operator-minted API token carries `:user-id` — authz keys on it, not the
  ;; mutable username. nil id (no such user) → a token that authenticates but
  ;; holds no grants, same as before this field existed.
  (let [storage (:storage ctx)
        user-id (some-> (first (sp/query-entities storage :user {:username user})) :id str)]
    (sp/create-entity storage :token
                      {:token-hash (token-sha256 token) :user user :user-id user-id :org org})))


(defbase create-org
  [name]
  (sp/create-entity (:storage ctx) :org {:name name}))


;; Register a custom domain (§3.4 #2) — UNVERIFIED by default. The operator
;; flips `:verified?` (via the generic CRUD update) after confirming the
;; `graphden-verify=<org>` DNS-TXT record; only verified rows route. Platform-
;; only by entity guard (`:domain` is tenant-forbidden).
(defbase create-domain
  [hostname org]
  (sp/create-entity (:storage ctx) :domain
                    {:hostname hostname :org org :verified? false}))


;; Point an org at its app handler (§3.4 step 4) — the one thing a deployed
;; tenant app needs. A focused controlled mutation: find the org row by its
;; (unique) name, set `:handler-fn-id`. `handler-fn-id` may arrive as a string
;; (form body) or a UUID. Platform-only by entity guard (`:org` is
;; tenant-forbidden), so only the operator / a self-serve seam reaches it.
(defbase set-org-handler
  [name handler-fn-id]
  (when-let [row (first (sp/query-entities (:storage ctx) :org {:name name}))]
    (sp/update-entity (:storage ctx) :org (:id row)
                      {:handler-fn-id (cond-> handler-fn-id
                                        (string? handler-fn-id) parse-uuid)})))


(def impls
  {:create-token create-token
   :create-org create-org
   :create-domain create-domain
   :set-org-handler set-org-handler})
