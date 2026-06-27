(ns graphden.tenancy.org-schema
  "Orgs registry (PLATFORM_PLAN §3.4 — FaaS ADR). Adds an `:org` entity via
   the `:db/schema` extension seam: the platform's record of each tenant org.

   - `name`          — the org slug; it IS the subdomain label AND the
                       `*current-org*` value (identity resolver, §3.2). UNIQUE.
   - `handler-fn-id` — the org's app-handler fn the platform's web-server
                       invokes for that subdomain / custom domain (the one
                       thing a tenant configures). Nullable until provisioned.

   Platform-managed: `:org` is in `tenancy.storage/tenant-forbidden-entities`,
   so tenants never read or write the registry directly — they reach their org
   only through the editor (token-authority) or their served app. App-routing
   reads `:org` in the public/platform context (before the request-scope binds
   an org), so the read guard doesn't block it."
  (:require
    [graphden.schema.protocol.protocol :as ds]))


(def ^:private org-entity-uuid
  #uuid "b3e1c8a4-2f57-4d96-8a1c-6e0b9d4f72a1")


(def ^:private org-name-field-uuid
  #uuid "1c9f4a72-8b35-4e60-9d2a-3f7c5e8a0b46")


(def ^:private org-handler-fn-id-field-uuid
  #uuid "7a4d2e90-6c18-4b53-9f8a-2d1e0c6b9354")


(defn extend-builder
  "Add the `:org` entity — `(name, handler-fn-id)` with a UNIQUE name."
  [builder]
  (-> builder
      (ds/add-entity :org org-entity-uuid
                     {:name {:uuid org-name-field-uuid :type :text}
                      :handler-fn-id {:uuid org-handler-fn-id-field-uuid
                                      :type :uuid
                                      :nullable? true}})
      (ds/add-constraint :org {:type :unique :fields [:name]})))
