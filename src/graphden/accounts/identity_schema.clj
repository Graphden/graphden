(ns graphden.accounts.identity-schema
  "The `:identity` entity — ONE way of signing in, bound to an `:account`.

   This is the table that makes account-LINKING possible: an account has many
   identities, so a person who signed up with a password can later attach a
   Google/GitHub/Telegram identity (or vice-versa) and reach the SAME account.

   - `account-id`     — owning `:account.id` (as a string, matching the
                        `(str (:id account))` convention downstream authz uses).
                        Indexed for the per-account listing + delete cascade.
   - `provider`       — \"password\" | \"google\" | \"github\" | \"telegram\".
   - `subject`        — the provider's stable user id: the lower-cased email for
                        \"password\", the OIDC `sub` for Google, the numeric user
                        id for GitHub/Telegram. UNIQUE per (provider, subject) —
                        one identity per external principal, so a second login
                        with the same external account resolves, never
                        duplicates.
   - `secret-data`    — bcrypt hash for the \"password\" provider; NULL for social
                        (the provider holds the secret). Never the raw password.
   - `email`          — the email THIS provider asserts (may differ per provider,
                        e.g. GitHub primary vs Google). Nullable (Telegram gives
                        none).
   - `email-verified?`— whether the provider vouched the email is confirmed.
                        Only a verified email may be promoted to
                        `:account.primary-email` or used to auto-link — enforced
                        in `accounts.core`.
   - `created-at`     — epoch millis.

   Platform-managed, non-versioned."
  (:require
    [graphden.schema.protocol.protocol :as ds]))


(def ^:private identity-entity-uuid
  #uuid "2cff2868-53a6-4d19-a640-a02fd7a90ad8")


(def ^:private identity-account-id-field-uuid
  #uuid "e3c1f322-d849-41ee-beea-659fb88d4b05")


(def ^:private identity-provider-field-uuid
  #uuid "bf6a0fa2-c7d2-4bca-a36f-9452b1a9663d")


(def ^:private identity-subject-field-uuid
  #uuid "7df6fcbe-2c5c-4c8c-b0f3-2036bcafd201")


(def ^:private identity-secret-data-field-uuid
  #uuid "0db0c42c-083d-47a1-ada8-967862a91f26")


(def ^:private identity-email-field-uuid
  #uuid "e2463f93-5dba-465c-961c-32b3061709db")


(def ^:private identity-email-verified-field-uuid
  #uuid "81f7da5f-046a-47ba-a9c4-425f407af6e5")


(def ^:private identity-created-at-field-uuid
  #uuid "6aa1f949-f0da-4bc8-a613-d064aba2ccae")


(defn extend-builder
  "Add the `:identity` entity with a UNIQUE (provider, subject)."
  [builder]
  (-> builder
      (ds/add-entity :identity identity-entity-uuid
                     {:account-id {:uuid identity-account-id-field-uuid
                                   :type :text
                                   :indexed? true}
                      :provider {:uuid identity-provider-field-uuid :type :text}
                      :subject {:uuid identity-subject-field-uuid :type :text}
                      :secret-data {:uuid identity-secret-data-field-uuid
                                    :type :text
                                    :nullable? true}
                      :email {:uuid identity-email-field-uuid
                              :type :text
                              :nullable? true}
                      :email-verified? {:uuid identity-email-verified-field-uuid
                                        :type :bool}
                      :created-at {:uuid identity-created-at-field-uuid :type :int}})
      (ds/add-constraint :identity {:type :unique :fields [:provider :subject]})))
