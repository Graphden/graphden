(ns graphden.packages.tenancy-admin.auth.impls
  "Impls for the `tenancy-admin.auth` base-fns — the auth seams (login / signup / logout /
   logout-all). Each invokes the injectable `:user-ops` seam (the tenancy
   addon's account ops); core stays addon-agnostic, so without the addon they
   return nil (a single-tenant editor authenticates with a static bearer +
   GET /api/auth/check instead). The grants / users panels and the
   org / token / domain / my-app provisioning routes live in the
   `tenancy-admin` package (route-collection seam, PLATFORM_PLAN §6)."
  (:require
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]))


(defbase invoke-login
  [username password request]
  (when-let [ops (:user-ops ctx)]
    (cr/record-effect! :db)
    ;; `request` flows to the :login seam wrapper for per-IP rate-limiting
    ;; (brute-force defense); the core login! ignores it.
    ((:login ops) ctx username password request)))


;; Self-serve signup (§4.1): create a new org + user and auto-login. No addon →
;; nil. The seam refuses to join an existing org (new account → new org only).
(defbase invoke-signup
  [username password org request]
  (when-let [ops (:user-ops ctx)]
    (cr/record-effect! :db)
    ;; `request` flows to the :signup seam wrapper for per-IP rate-limiting;
    ;; the core signup! ignores it.
    ((:signup ops) ctx username password org request)))


;; Mint a single-use invite into the CALLER'S org (LAUNCH_PLAN stage 1.3).
;; No addon / unauthenticated / public-org caller / over-quota IP → nil.
(defbase invoke-invite-create
  [request]
  (when-let [ops (:user-ops ctx)]
    (when-let [create (:invite-create ops)]
      (cr/record-effect! :db)
      (create ctx request))))


;; Redeem an invite: create the user INSIDE the invite's org + auto-login.
;; nil on unknown/expired invite or taken username; {:rate-limited true} over quota.
(defbase invoke-invite-redeem
  [invite username password request]
  (when-let [ops (:user-ops ctx)]
    (when-let [redeem (:invite-redeem ops)]
      (cr/record-effect! :db)
      (redeem ctx invite username password request))))


;; Logout (§4.1): delete the caller's session token server-side (the seam reads
;; the bearer from `request`). No addon → nil. Returns true iff a row was
;; deleted; the editor clears its local token regardless.
(defbase invoke-logout
  [request]
  (when-let [ops (:user-ops ctx)]
    (cr/record-effect! :db)
    ((:logout ops) ctx request)))


;; Logout-all (§4.1): delete EVERY session token for the current user (the seam
;; reads *current-principal*). No addon → nil. The editor clears local too.
(defbase invoke-logout-all
  []
  (when-let [ops (:user-ops ctx)]
    (cr/record-effect! :db)
    ((:logout-all ops) ctx)))


(def impls
  {:invoke-login invoke-login
   :invoke-logout invoke-logout
   :invoke-logout-all invoke-logout-all
   :invoke-signup invoke-signup})
