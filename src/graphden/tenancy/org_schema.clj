(ns graphden.tenancy.org-schema
  "Orgs registry (PLATFORM_PLAN §3.4 — FaaS ADR). Adds an `:org` entity via
   the `:db/schema` extension seam: the platform's record of each tenant org.

   - `name`          — the org slug; it IS the subdomain label AND the
                       `*current-org*` value (identity resolver, §3.2). UNIQUE.
   - `handler-fn-id` — the org's app-handler fn the platform's web-server
                       invokes for that subdomain / custom domain (the one
                       thing a tenant configures). Nullable until provisioned.
   - `execution-mode`— `\"hosted\"` (default; nil reads as hosted) or `\"byo\"`.
                       A `\"byo\"` org runs its graph on the customer's OWN
                       executor; the platform stores the graph but hosted pods
                       REFUSE to run it (a hosted pod that served it would leak
                       compute the tenant is supposed to bring). See
                       `tenancy.context/byo-org?` and the 421 refusals in
                       `app_router` / `addon`. Text, not an enum — `:org` is
                       platform-managed (tenant-forbidden), so provisioning
                       code sets it, and staying text avoids enum registration
                       through the addon schema seam.
   - `plan`          — the org's tier slug (task #4); resolves to the effect
                       allow-list + quota ceilings its graph runs under. nil =
                       the locked free tier. Text, same platform-managed reason.
   - `expires-at`    — ephemeral-org expiry (task #7). A demo / trial org sets
                       `now + TTL`; the `:tenancy/demo-gc` reaper hard-purges the
                       org + all its rows once it passes. nil = permanent (real
                       tenants + the public org are never reaped).

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


(def ^:private org-execution-mode-field-uuid
  #uuid "71d581e2-cd84-4676-a320-b052e3f25187")


(def ^:private org-plan-field-uuid
  #uuid "e4a7c012-9b3d-4f81-a6c5-2d8e0f1b73a9")


(def ^:private org-expires-at-field-uuid
  #uuid "8f2b6d10-4e73-4a91-b5c8-1f9a0d3e6c27")


(defn extend-builder
  "Add the `:org` entity — `(name, handler-fn-id, execution-mode)` with a
   UNIQUE name."
  [builder]
  (-> builder
      (ds/add-entity :org org-entity-uuid
                     {:name {:uuid org-name-field-uuid :type :text}
                      :handler-fn-id {:uuid org-handler-fn-id-field-uuid
                                      :type :uuid
                                      :nullable? true}
                      :execution-mode {:uuid org-execution-mode-field-uuid
                                       :type :text
                                       :nullable? true}
                      ;; The org's PLAN / tier (task #4) — resolves to the
                      ;; effect allow-list its submitted graph runs under (and
                      ;; the quota ceilings enforced elsewhere). nil reads as
                      ;; the locked free tier. Text, not an enum, for the same
                      ;; platform-managed reason as :execution-mode above.
                      :plan {:uuid org-plan-field-uuid
                             :type :text
                             :nullable? true}
                      ;; Ephemeral-org expiry (task #7). A demo / trial org sets
                      ;; this to `now + TTL`; the `:tenancy/demo-gc` reaper hard-
                      ;; purges the org and ALL its rows once it passes. nil (the
                      ;; default) = a permanent org, never reaped — so real
                      ;; tenants and the public org are untouched.
                      :expires-at {:uuid org-expires-at-field-uuid
                                   :type :timestamptz
                                   :nullable? true}})
      (ds/add-constraint :org {:type :unique :fields [:name]})))
