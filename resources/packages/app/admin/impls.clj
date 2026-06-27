(ns graphden.packages.app.admin.impls
  "Impls for `app.admin` base-fns — the org-admin grants panel (§6).
   `:list-grants` is a thin storage shim over the tenancy addon's `:grant`
   entity; it throws when the addon isn't active (no `:grant` table), so the
   partial guards it with `:try`."
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.protocol.core :as sp]))


(defbase list-grants
  []
  (sp/query-entities (:storage ctx) :grant {}))


(def impls
  {:list-grants list-grants})
