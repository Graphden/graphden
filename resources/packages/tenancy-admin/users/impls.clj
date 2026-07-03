(ns graphden.packages.tenancy-admin.users.impls
  "Impls for the org-admin users panel base-fns (PLATFORM_PLAN §4.1), migrated
   out of app.admin via the route-collection seam (§6). `list-users` reads the
   addon's `:user` entity (redacting `:password-hash`); `invoke-create-user`
   calls the injectable `:user-ops` seam. Both throw / no-op without the addon,
   but this package only loads WITH it."
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.protocol.core :as sp]))


;; List users for the admin panel — strips `:password-hash` at the boundary so
;; the hashes never reach the wire / the UI (the only non-bare projection here;
;; it's a redaction, not composition).
(defbase list-users
  []
  (mapv #(dissoc % :password-hash) (sp/query-entities (:storage ctx) :user {})))


;; User model (§4.1): invoke the injectable `:user-ops` seam (the tenancy
;; addon's account ops). `create-user` is operator-only (the :user write-guard
;; denies tenants); no seam → nil.
(defbase invoke-create-user
  [username password org]
  (when-let [ops (:user-ops ctx)]
    ((:create-user ops) ctx username password org)))


;; Operator-only account admin (§4.1): reset another user's password
;; (invalidates their sessions) / delete a user (cascades tokens + grants).
;; Both go through the injectable seam; no seam → nil.
(defbase invoke-reset-password
  [user-id password]
  (when-let [ops (:user-ops ctx)]
    ((:reset-password ops) ctx user-id password)))


(defbase invoke-delete-user
  [user-id]
  (when-let [ops (:user-ops ctx)]
    ((:delete-user ops) ctx user-id)))


(def impls
  {:list-users list-users
   :invoke-create-user invoke-create-user
   :invoke-reset-password invoke-reset-password
   :invoke-delete-user invoke-delete-user})
