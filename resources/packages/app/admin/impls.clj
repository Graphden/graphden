(ns graphden.packages.app.admin.impls
  "Impls for `app.admin` base-fns — the org-admin grants panel (§6).
   `:list-grants` / `:create-grant` are thin storage shims over the tenancy
   addon's `:grant` entity; they throw when the addon isn't active (no
   `:grant` table), so callers guard with `:try`."
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


(def impls
  {:list-grants list-grants
   :create-grant create-grant})
