(ns graphden.tenancy.user-schema
  "User registry (PLATFORM_PLAN §4.1). Adds a `:user` entity via the
   `:db/schema` extension seam — the platform's record of each login identity,
   so auth is a real user model instead of a shared token.

   - `username`      — login id. UNIQUE.
   - `password-hash` — self-describing PBKDF2 string (`pbkdf2$iters$salt$hash`);
                       the raw password is never stored. See `tenancy.users`.
   - `org`           — the org the user belongs to (→ `*current-org*` via the
                       session token minted at login).

   Platform-managed: `:user` is in `tenancy.storage/tenant-forbidden-entities`
   — a tenant must not enumerate other users or mint accounts for other orgs.
   `login!` reads it in the platform context (login runs before any session
   exists); `create-user!` writes it as the operator (public org)."
  (:require
    [graphden.schema.protocol.protocol :as ds]))


(def ^:private user-entity-uuid
  #uuid "2c7f4b91-8e36-4a05-9d18-6b3e0f7a52c9")


(def ^:private user-username-field-uuid
  #uuid "9a1d6e34-5c82-4f70-8b29-3d7c0a4e61b8")


(def ^:private user-password-hash-field-uuid
  #uuid "4e8b2c57-1a93-4d60-9f8a-0c6b2e5d3719")


(def ^:private user-org-field-uuid
  #uuid "7f3a9d62-4b18-4e85-9c06-2a5e8b1d70f4")


(defn extend-builder
  "Add the `:user` entity — `(username, password-hash, org)` with UNIQUE name."
  [builder]
  (-> builder
      (ds/add-entity :user user-entity-uuid
                     {:username {:uuid user-username-field-uuid :type :text}
                      :password-hash {:uuid user-password-hash-field-uuid :type :text}
                      :org {:uuid user-org-field-uuid :type :text}})
      (ds/add-constraint :user {:type :unique :fields [:username]})))
