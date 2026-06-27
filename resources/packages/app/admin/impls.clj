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


(def impls
  {:list-grants list-grants
   :create-grant create-grant
   :create-org create-org})
