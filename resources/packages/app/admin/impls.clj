(ns graphden.packages.app.admin.impls
  "Impls for `app.admin` base-fns — the org-admin grants panel (§6) and org
   registration (§3.4). Thin storage shims over the tenancy addon's `:grant` /
   `:org` entities; they throw when the addon isn't active (no table), so
   callers guard with `:try`."
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.protocol.core :as sp]))


(defbase list-grants
  []
  (sp/query-entities (:storage ctx) :grant {}))


(defbase create-grant
  [subject capability namespace]
  (sp/create-entity (:storage ctx) :grant
                    {:subject subject :capability capability :namespace namespace}))


(defbase create-org
  [name]
  (sp/create-entity (:storage ctx) :org {:name name}))


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
  {:list-grants list-grants
   :create-grant create-grant
   :create-org create-org
   :set-org-handler set-org-handler})
