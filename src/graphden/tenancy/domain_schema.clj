(ns graphden.tenancy.domain-schema
  "Custom-domain registry (PLATFORM_PLAN §3.4 #2 / R10). Adds a `:domain`
   entity via the `:db/schema` extension seam so `hostname → org` routing comes
   from storage (provisionable) instead of a config map (`static-host-resolver`).

   - `hostname`  — the full custom host (`app.acme.com`), lower-cased. UNIQUE.
   - `org`       — the org this host serves (→ the app-router runs that org's
                   handler for the host, exactly like a subdomain).
   - `verified?` — only VERIFIED rows resolve (DNS-TXT ownership proven). A row
                   starts unverified; the operator flips it after checking
                   `graphden-verify=<org>` (see `domain/verify-domain-ownership`).
                   An unverified host resolves to nil → falls through to the
                   subdomain / token, never hijacks routing.

   Platform-managed: `:domain` is in `tenancy.storage/tenant-forbidden-entities`
   — a tenant writing it could hijack another org's host; reading it could
   enumerate the custom-domain map. The resolver reads it in the platform
   context (app-routing runs before the request scope binds an org)."
  (:require
    [graphden.schema.protocol.protocol :as ds]))


(def ^:private domain-entity-uuid
  #uuid "8d5b2f17-4a93-4c60-9e28-1f7a3b6c094e")


(def ^:private domain-hostname-field-uuid
  #uuid "3a7e9c41-6b08-4d52-8f19-2c5d0e8b736a")


(def ^:private domain-org-field-uuid
  #uuid "5f1c8a36-9d24-4e70-8b53-6a2f4c907e18")


(def ^:private domain-verified-field-uuid
  #uuid "0e9b4d72-1c63-4a85-9f06-7d3a2e5c81b4")


(defn extend-builder
  "Add the `:domain` entity — `(hostname, org, verified?)` with a UNIQUE host."
  [builder]
  (-> builder
      (ds/add-entity :domain domain-entity-uuid
                     {:hostname {:uuid domain-hostname-field-uuid :type :text}
                      :org {:uuid domain-org-field-uuid :type :text}
                      :verified? {:uuid domain-verified-field-uuid :type :bool}})
      (ds/add-constraint :domain {:type :unique :fields [:hostname]})))
