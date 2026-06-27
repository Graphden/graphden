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
   beneath versioning exactly where the §3.0 nuance-1 placement requires."
  (:require
    [graphden.tenancy.storage :as ts]
    [integrant.core :as ig]))


(defmethod ig/init-key :org/scoped-storage [_ {:keys [base scoped-entities]}]
  (if scoped-entities
    (ts/org-scoped-storage base scoped-entities)
    (ts/org-scoped-storage base)))
