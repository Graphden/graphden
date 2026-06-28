(ns graphden.tenancy.token-schema
  "Storage-backed auth tokens (PLATFORM_PLAN §3.4 — critical-path #1). Adds a
   `:token` entity via the `:db/schema` extension seam so onboarding a user is
   creating a row, NOT editing a config map + redeploying (which is what the
   `:auth/multi-tenant-provider` static-token map requires).

   - `token-hash` — SHA-256 hex of the bearer token. We store ONLY the hash;
                    the raw token is never persisted (a leaked DB can't be
                    replayed as bearers). UNIQUE → one principal per token.
   - `user`       — the principal's user id.
   - `org`        — the principal's org (→ `*current-org*` via the request
                    scope; this is what turns the addon into real tenancy).

   Platform-managed: `:token` is in `tenancy.storage/tenant-forbidden-entities`
   — tenants can neither read (enumerate other users' tokens) nor write (mint
   themselves a new org) the table. The `storage-token-provider` reads it in
   the platform context (auth runs before the request scope binds an org)."
  (:require
    [graphden.schema.protocol.protocol :as ds]))


(def ^:private token-entity-uuid
  #uuid "4f2a9c61-7d38-4e05-9b2c-1a6e8f0d35b7")


(def ^:private token-hash-field-uuid
  #uuid "9c14e7a0-3b62-4d18-8f5a-0e2d6c9b471f")


(def ^:private token-user-field-uuid
  #uuid "2e8d5b34-6a09-4c71-9d3f-7b1c4e0a86d2")


(def ^:private token-org-field-uuid
  #uuid "6b3f0c97-1e54-4a82-8c6d-9f2a7b504e13")


(defn extend-builder
  "Add the `:token` entity — `(token-hash, user, org)` with a UNIQUE hash."
  [builder]
  (-> builder
      (ds/add-entity :token token-entity-uuid
                     {:token-hash {:uuid token-hash-field-uuid :type :text}
                      :user {:uuid token-user-field-uuid :type :text}
                      :org {:uuid token-org-field-uuid :type :text}})
      (ds/add-constraint :token {:type :unique :fields [:token-hash]})))
