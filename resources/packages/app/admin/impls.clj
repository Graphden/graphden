(ns graphden.packages.app.admin.impls
  "Impls for `app.admin` base-fns — org / token / domain registration (§3.4),
   the self-serve my-app seams, plus the auth seams (login/signup/logout).
   Thin storage shims over the tenancy addon's `:org` / `:token` / `:domain`
   entities; they throw when the addon isn't active (no table), so callers
   guard with `:try`. The grants AND users panels moved to the `tenancy-admin`
   package (route-collection seam, PLATFORM_PLAN §6)."
  (:require
    [clojure.string :as str]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.protocol.core :as sp]))


(defn- token-sha256
  "SHA-256 hex of a bearer token — the key derivation for `:token.token-hash`.
   MUST match `graphden.tenancy.auth/token-hash` (the storage-token-provider
   hashes the same way at read time; the round-trip is tested). Core can't
   require the addon, so this standard algorithm is duplicated here."
  [^String s]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")]
    (str/join (map #(format "%02x" (bit-and % 0xff))
                   (java.security.MessageDigest/.digest digest (String/.getBytes s "UTF-8"))))))


;; Mint a storage-backed auth token (§3.4 #1). Stores ONLY the hash — the raw
;; bearer is never persisted. Platform-only by entity guard (`:token` is
;; tenant-forbidden), so a tenant POST is denied by OrgScoped.
(defbase create-token
  [token user org]
  (sp/create-entity (:storage ctx) :token
                    {:token-hash (token-sha256 token) :user user :org org}))


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


;; Self-serve deploy (§3.4 4b): invoke the injectable `:set-org-handler` seam
;; (the tenancy addon's controlled-privilege update). Core stays addon-
;; agnostic — no seam (single-tenant / no addon) → nil. The seam validates
;; ownership + does the `:org` update; it throws :authz/forbidden (→ 403 via
;; the request-scope) for a public/unauthorized caller.
(defbase invoke-set-org-handler
  [fn-id]
  (when-let [seam (:set-org-handler ctx)]
    (seam ctx (cond-> fn-id (string? fn-id) parse-uuid))))


;; Self-serve DNS-verify (§3.4 #2): invoke the injectable `:verify-domain` seam
;; (the tenancy addon's controlled-privilege verification). Core stays addon-
;; agnostic — no seam → nil. The seam validates the domain belongs to the
;; tenant's org, runs the privileged DNS-TXT lookup, flips `:verified?`; it
;; throws :authz/forbidden / :domain/unverified for a bad caller / failed proof.
(defbase invoke-verify-domain
  [hostname]
  (when-let [seam (:verify-domain ctx)]
    (seam ctx hostname)))


(defbase invoke-login
  [username password]
  (when-let [ops (:user-ops ctx)]
    ((:login ops) ctx username password)))


;; Self-serve signup (§4.1): create a new org + user and auto-login. No addon →
;; nil. The seam refuses to join an existing org (new account → new org only).
(defbase invoke-signup
  [username password org request]
  (when-let [ops (:user-ops ctx)]
    ;; `request` flows to the :signup seam wrapper for per-IP rate-limiting;
    ;; the core signup! ignores it.
    ((:signup ops) ctx username password org request)))


;; Logout (§4.1): delete the caller's session token server-side (the seam reads
;; the bearer from `request`). No addon → nil. Returns true iff a row was
;; deleted; the editor clears its local token regardless.
(defbase invoke-logout
  [request]
  (when-let [ops (:user-ops ctx)]
    ((:logout ops) ctx request)))


;; Logout-all (§4.1): delete EVERY session token for the current user (the seam
;; reads *current-principal*). No addon → nil. The editor clears local too.
(defbase invoke-logout-all
  []
  (when-let [ops (:user-ops ctx)]
    ((:logout-all ops) ctx)))


(def impls
  {:create-token create-token
   :create-org create-org
   :create-domain create-domain
   :set-org-handler set-org-handler
   :invoke-set-org-handler invoke-set-org-handler
   :invoke-verify-domain invoke-verify-domain
   :invoke-login invoke-login
   :invoke-logout invoke-logout
   :invoke-logout-all invoke-logout-all
   :invoke-signup invoke-signup})
