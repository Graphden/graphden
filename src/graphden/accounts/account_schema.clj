(ns graphden.accounts.account-schema
  "The `:account` entity — a PERSON, independent of how they authenticate.

   This is the open-core identity root: one account, many `:identity` rows
   (password, google, github, telegram — see `identity-schema`). The account's
   stable `:id` is the authz subject every downstream mechanism keys on (the
   tenancy layer's grants/roles join on it, exactly as they already join on the
   old `:token.user-id`).

   - `display-name`  — human label for the UI. Nullable: a social sign-up may
                       arrive without one until the person sets it.
   - `primary-email` — the account's canonical email, promoted from a VERIFIED
                       identity. UNIQUE-when-present (SQL UNIQUE treats NULLs as
                       distinct, so many not-yet-emailed accounts coexist); this
                       is the key operator-by-email and account-linking look up.
                       Un-verified emails never land here — that invariant is
                       enforced in `accounts.core`, not by this constraint.
   - `status`        — \"active\" | \"suspended\". A suspended account's sessions
                       stop authenticating (checked in the provider).
   - `totp-secret`   — base32 TOTP secret once 2FA is enrolled (nullable —
                       nil = never enrolled). Set at enrollment, cleared on
                       disable.
   - `totp-enabled?` — 2FA confirmed + active (nullable bool; nil/false = off).
                       Enrollment sets the secret but leaves this false until a
                       code confirms it.
   - `created-at`    — epoch millis.

   Platform-managed, non-versioned (like the tenancy identity entities): no
   version mirror, so this is the cheap schema path (schema fn + init-key +
   extensions vector + storage classification)."
  (:require
    [graphden.schema.protocol.protocol :as ds]))


(def ^:private account-entity-uuid
  #uuid "d756b704-e361-4a30-a9b6-336b80fc00f6")


(def ^:private account-display-name-field-uuid
  #uuid "2deaea9d-2784-4914-a4c6-61ab0c836a78")


(def ^:private account-primary-email-field-uuid
  #uuid "d0b2afc2-0b4c-4c99-930d-5da3724ba45f")


(def ^:private account-status-field-uuid
  #uuid "e8c31473-11d1-4371-abc8-05df3f6578a1")


(def ^:private account-created-at-field-uuid
  #uuid "619c1b95-f50d-4bb2-8c2c-d27e0865dda7")


(def ^:private account-totp-secret-field-uuid
  #uuid "627710cb-6a04-4e5c-8158-65d53b9cb6f8")


(def ^:private account-totp-enabled-field-uuid
  #uuid "4e9a1488-fcf2-4038-b76e-46bdf580136e")


(defn extend-builder
  "Add the `:account` entity with a UNIQUE-when-present `primary-email`."
  [builder]
  (-> builder
      (ds/add-entity :account account-entity-uuid
                     {:display-name {:uuid account-display-name-field-uuid
                                     :type :text
                                     :nullable? true}
                      :primary-email {:uuid account-primary-email-field-uuid
                                      :type :text
                                      :nullable? true
                                      :indexed? true}
                      :status {:uuid account-status-field-uuid :type :text}
                      :totp-secret {:uuid account-totp-secret-field-uuid
                                    :type :text
                                    :nullable? true}
                      :totp-enabled? {:uuid account-totp-enabled-field-uuid
                                      :type :bool
                                      :nullable? true}
                      :created-at {:uuid account-created-at-field-uuid :type :int}})
      (ds/add-constraint :account {:type :unique :fields [:primary-email]})))
