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
   - `expires-at` — epoch millis after which the token is dead (the provider
                    rejects it). NULL = never expires — operator-minted API
                    keys (`create-token`) leave it unset; login sessions set a
                    TTL. Logout deletes the row outright (server-side).

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


(def ^:private token-expires-at-field-uuid
  #uuid "1d8e3f60-9a47-4b25-8c91-5f0a6d2b7e84")


(def ^:private token-user-id-field-uuid
  #uuid "3a1b7e92-4c05-4d68-9f2a-8b6c1e0d47a5")


(def ^:private token-kind-field-uuid
  #uuid "538b0323-1890-5e7c-988e-56eb9f29f558")


(defn extend-builder
  "Add the `:token` entity — `(token-hash, user, org, expires-at)` with a
   UNIQUE hash."
  [builder]
  (-> builder
      (ds/add-entity :token token-entity-uuid
                     {:token-hash {:uuid token-hash-field-uuid :type :text}
                      :user {:uuid token-user-field-uuid :type :text :indexed? true}
                      ;; The STABLE identity for the session. `:user` above is
                      ;; the human-facing username (kept for display); every
                      ;; authz/session linkage keys on this id so a future
                      ;; username edit / delete-recreate can't detach or carry
                      ;; over sessions. Nullable for the additive migration
                      ;; (backfilled from `:user`); indexed for the
                      ;; delete-user! cascade.
                      ;; :text (the id's string form), not :uuid — the id is
                      ;; written as `(str (:id user))` so the same column format
                      ;; carries both prod uuids and any test-supplied id.
                      :user-id {:uuid token-user-id-field-uuid
                                :type :text
                                :nullable? true
                                :indexed? true}
                      :org {:uuid token-org-field-uuid :type :text}
                      :expires-at {:uuid token-expires-at-field-uuid
                                   :type :int
                                   :nullable? true}
                      ;; nil = session / operator API key (the only kinds that
                      ;; AUTHENTICATE — auth.clj matches :kind nil); "invite" =
                      ;; single-use org invite (users/redeem-invite!). Nullable
                      ;; for the additive migration, same pattern as :user-id.
                      :kind {:uuid token-kind-field-uuid
                             :type :text
                             :nullable? true}})
      (ds/add-constraint :token {:type :unique :fields [:token-hash]})))
