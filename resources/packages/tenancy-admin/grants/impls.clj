(ns graphden.packages.tenancy-admin.grants.impls
  "Impls for `tenancy-admin.grants` base-fns — the org-admin grants panel
   data source. A thin storage-protocol shim over the addon's `:grant`
   entity."
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.protocol.core :as sp]))


(defbase list-grants
  []
  (sp/query-entities (:storage ctx) :grant {}))
