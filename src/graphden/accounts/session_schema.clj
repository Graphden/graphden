(ns graphden.accounts.session-schema
  "The `:session` entity — the open-core counterpart of the tenancy `:token`.

   A session authenticates a request to an `:account`. It is delivered two
   ways: as an HttpOnly cookie for the browser (required — an OAuth redirect
   can't carry a bearer) and as a bearer for API/CLI clients. Only the SHA-256
   hash of the token is stored, so a leaked DB can't be replayed.

   Deliberately org-AGNOSTIC: open core has no orgs. The session carries the
   `account-id` only; the tenancy layer resolves the org from the account's
   membership at request-scope time. This is the clean split that lets the
   tenancy `:token` be subsumed by `:session` in the Phase-4 cutover.

   - `token-hash` — SHA-256 hex of the session/bearer token. UNIQUE.
   - `account-id` — the authenticated `:account.id` (string form). Indexed for
                    the logout-all / delete-account cascade.
   - `expires-at` — epoch millis; NULL = never (a long-lived API key).
   - `kind`       — nil / \"api\" AUTHENTICATE; other kinds (reserved, e.g.
                    \"pending-2fa\") do not. Nullable.
   - `label`      — human label for a self-serve API key (\"laptop CLI\"); the
                    only thing a listing can show since the hash never leaves
                    the server. Nullable.
   - `scopes`     — space-separated scope names (\"write execute merge\") for a
                    `kind` \"api\" bearer. NULL = unscoped (a legacy or
                    browser session; the POLICY layer decides what that
                    means — accounts only stores and surfaces it). The open
                    core does not enforce scopes; the tenancy addon applies
                    them as a ceiling over the account's grants.
   - `created-at` — epoch millis.

   Platform-managed, non-versioned."
  (:require
    [graphden.schema.protocol.protocol :as ds]))


(def ^:private session-entity-uuid
  #uuid "0fd0aebf-7581-4798-bfd4-ef477af87ca9")


(def ^:private session-token-hash-field-uuid
  #uuid "49964a62-f40c-4b92-bcd2-5823104abe6e")


(def ^:private session-account-id-field-uuid
  #uuid "eba134e5-aa9a-44b8-a8ac-ede7075a7449")


(def ^:private session-expires-at-field-uuid
  #uuid "99bc388b-608b-4031-bb48-458d386ae738")


(def ^:private session-kind-field-uuid
  #uuid "c7c0d432-888c-4986-bbf6-7e1827219dc6")


(def ^:private session-label-field-uuid
  #uuid "3051143f-b997-4ad9-8a5a-818a8880deda")


(def ^:private session-scopes-field-uuid
  #uuid "cbdddd4a-1f58-48b5-a0a7-c1954122ec6d")


(def ^:private session-created-at-field-uuid
  #uuid "79ecedec-cdb7-4f2e-a5a4-8692f21becd5")


(defn extend-builder
  "Add the `:session` entity with a UNIQUE `token-hash`."
  [builder]
  (-> builder
      (ds/add-entity :session session-entity-uuid
                     {:token-hash {:uuid session-token-hash-field-uuid :type :text}
                      :account-id {:uuid session-account-id-field-uuid
                                   :type :text
                                   :indexed? true}
                      :expires-at {:uuid session-expires-at-field-uuid
                                   :type :int
                                   :nullable? true}
                      :kind {:uuid session-kind-field-uuid
                             :type :text
                             :nullable? true}
                      :label {:uuid session-label-field-uuid
                              :type :text
                              :nullable? true}
                      :scopes {:uuid session-scopes-field-uuid
                               :type :text
                               :nullable? true}
                      :created-at {:uuid session-created-at-field-uuid :type :int}})
      (ds/add-constraint :session {:type :unique :fields [:token-hash]})))
