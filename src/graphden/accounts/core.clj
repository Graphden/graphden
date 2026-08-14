(ns graphden.accounts.core
  "Open-core identity operations over `:account` / `:identity` / `:session`.

   The whole point of the split is account-LINKING: one `:account` owns many
   `:identity` rows, so a person can sign in by password today and attach a
   Google/GitHub/Telegram identity tomorrow and reach the same account. Sessions
   are org-agnostic — the tenancy layer resolves the org from the account's
   membership; open core never touches orgs.

   Secrets: passwords are bcrypt-hashed (cost 12); session tokens are stored
   only as their SHA-256 hash — the raw token exists exactly once, at mint time,
   as the return value the caller hands to the client."
  (:require
    [clojure.string :as str]
    [graphden.accounts.crypto :as crypto]
    [graphden.accounts.totp :as totp]
    [graphden.storage.protocol.core :as sp])
  (:import
    (org.mindrot.jbcrypt
      BCrypt)))


(def ^:private ^:const bcrypt-cost 12)


(def ^:const default-session-ttl-ms
  "24h — the login-session lifetime. `mint-session!` with an explicit
   `:ttl-ms nil` means never (a long-lived API key)."
  (* 24 60 60 1000))


(defn- now
  ^long []
  (System/currentTimeMillis))


(defn hash-password
  "A bcrypt hash string (`$2a$<cost>$…`, random salt per call)."
  [password]
  (BCrypt/hashpw password (BCrypt/gensalt bcrypt-cost)))


(defn verify-password
  "True iff `password` matches the stored bcrypt hash. Never throws."
  [password stored]
  (boolean
    (when (and (seq password) (seq stored) (str/starts-with? stored "$2"))
      (try
        (BCrypt/checkpw password stored)
        (catch Exception _ false)))))


(defn normalize-email
  "Lower-cased, trimmed email, or nil when blank. The canonical form used as
   the `password` identity's `subject` and for email look-ups."
  [email]
  (some-> email str/trim str/lower-case not-empty))


;; ---------------------------------------------------------------------------
;; accounts

(defn create-account!
  [storage {:keys [display-name primary-email status]}]
  (sp/create-entity storage :account
                    {:display-name display-name
                     :primary-email primary-email
                     :status (or status "active")
                     :created-at (now)}))


(defn account-of
  "The `:account` row for a string account-id, or nil. `account-id` is stored
   downstream as `(str (:id account))`, so parse it back to the uuid id."
  [storage account-id]
  (when account-id
    (or (some->> (parse-uuid (str account-id)) (sp/read-entity storage :account))
        (first (sp/query-entities storage :account {:id account-id})))))


(defn accounts-of
  "Batch `account-of`: map of string account-id → `:account` row for every id
   that resolves (absent otherwise). One `read-entities` round trip — the
   admin-panel joins (org members, platform access) were calling `account-of`
   per subject, an N+1. A non-uuid id resolves to nothing: account ids are
   minted as `(str (:id account))`, and `account-of`'s text-query arm is
   rejected by the where-clause type validation anyway."
  [storage account-ids]
  (let [ids (into [] (comp (remove nil?) (map str) (distinct)) account-ids)
        uuid-of (into {} (keep (fn [s] (some->> (parse-uuid s) (vector s)))) ids)
        rows (when (seq uuid-of)
               (sp/read-entities storage :account (vec (vals uuid-of))))]
    (into {}
          (keep (fn [s]
                  (when-let [acct (some->> (get uuid-of s) (get rows))]
                    [s acct])))
          ids)))


(defn account-by-email
  "The single account whose VERIFIED `primary-email` is `email`, or nil."
  [storage email]
  (when-let [email (normalize-email email)]
    (first (sp/query-entities storage :account {:primary-email email}))))


;; ---------------------------------------------------------------------------
;; identities

(defn find-identity
  "The identity for `(provider, subject)` — unique — or nil."
  [storage provider subject]
  (first (sp/query-entities storage :identity {:provider provider :subject subject})))


(defn identities-for-account
  [storage account-id]
  (sp/query-entities storage :identity {:account-id account-id}))


(defn create-identity!
  [storage account-id {:keys [provider subject secret-data email email-verified?]}]
  (sp/create-entity storage :identity
                    {:account-id account-id
                     :provider provider
                     :subject subject
                     :secret-data secret-data
                     :email email
                     :email-verified? (boolean email-verified?)
                     :created-at (now)}))


(defn link-identity!
  "Attach a provider identity to an EXISTING account. Idempotent when the same
   identity is already on this account; throws `:accounts/identity-conflict`
   when it belongs to a DIFFERENT account (never silently steal it)."
  [storage account-id {:keys [provider subject] :as ident}]
  (let [existing (find-identity storage provider subject)]
    (cond
      (nil? existing) (create-identity! storage account-id ident)
      (= (:account-id existing) account-id) existing
      :else (throw (ex-info "identity already linked to another account"
                            {:type :accounts/identity-conflict
                             :provider provider})))))


(defn unlink-identity!
  "Remove every identity of `provider` from `account-id`. Returns the number
   removed. Callers MUST keep at least one identity on the account (don't lock
   the person out) — that guard lives at the API layer where the full identity
   set is known."
  [storage account-id provider]
  (let [ids (mapv :id (filter #(= provider (:provider %))
                              (identities-for-account storage account-id)))]
    (when (seq ids)
      (sp/delete-entities storage :identity ids))
    (count ids)))


(defn resolve-social-identity!
  "Resolve a social sign-in to an account, creating or auto-linking as needed.
   `info` = `{:provider :subject :email :email-verified? :display-name}`:

   1. an existing `(provider, subject)` identity → its account (returning user);
   2. else a VERIFIED email that an existing account owns as its primary-email →
      attach the new identity to THAT account (auto-link across providers);
   3. else a brand-new account (primary-email set only when the email is
      verified) plus the identity.

   Returns `{:account :account-id :created? :linked?}`. The caller mints the
   session — this is storage-only."
  [storage {:keys [provider subject email email-verified? display-name]}]
  (let [email (normalize-email email)]
    (if-let [ident (find-identity storage provider subject)]
      (let [account-id (:account-id ident)]
        {:account (account-of storage account-id) :account-id account-id
         :created? false :linked? false})
      (if-let [acct (and email email-verified? (account-by-email storage email))]
        (let [account-id (str (:id acct))]
          (create-identity! storage account-id
                            {:provider provider :subject subject
                             :email email :email-verified? true})
          {:account acct :account-id account-id :created? false :linked? true})
        (let [acct (create-account! storage {:display-name (or display-name email)
                                             :primary-email (when email-verified? email)
                                             :status "active"})
              account-id (str (:id acct))]
          (create-identity! storage account-id
                            {:provider provider :subject subject
                             :email email :email-verified? (boolean email-verified?)})
          {:account acct :account-id account-id :created? true :linked? false})))))


;; ---------------------------------------------------------------------------
;; sessions

(defn- session-live?
  [row]
  (let [exp (:expires-at row)]
    (or (nil? exp) (> exp (now)))))


(defn mint-session!
  "Create a `:session` for `account-id`, returning the RAW token (this is the
   only moment it exists in the clear — only its hash is stored). `opts`:
   `:ttl-ms` (default 24h; nil ⇒ never expires), `:kind`, `:label`."
  ([storage account-id] (mint-session! storage account-id nil))
  ([storage account-id opts]
   (let [ttl-ms (get opts :ttl-ms default-session-ttl-ms)
         token (crypto/random-token)]
     (sp/create-entity storage :session
                       {:token-hash (crypto/sha256-hex token)
                        :account-id account-id
                        :expires-at (when ttl-ms (+ (now) ttl-ms))
                        :kind (:kind opts)
                        :label (:label opts)
                        :created-at (now)})
     token)))


(defn authenticate-token
  "Resolve a raw session/bearer token to its ACTIVE account, or nil. Matches
   the session by hash, requires an authenticating `:kind` (nil/\"api\") that is
   still live, and an account whose status is \"active\". Fails closed."
  [storage token]
  (when-not (str/blank? token)
    (when-let [s (first (sp/query-entities storage :session
                                           {:token-hash (crypto/sha256-hex token)}))]
      (when (and (contains? #{nil "api"} (:kind s)) (session-live? s))
        (when-let [acct (account-of storage (:account-id s))]
          (when (= "active" (:status acct))
            acct))))))


(defn revoke-token!
  "Delete the session behind `token` (server-side logout). No-op if unknown."
  [storage token]
  (when-let [s (and token
                    (first (sp/query-entities storage :session
                                              {:token-hash (crypto/sha256-hex token)})))]
    (sp/delete-entities storage :session [(:id s)])))


(defn revoke-all-for-account!
  "Delete every session for `account-id` (logout everywhere; password reset)."
  [storage account-id]
  (let [ids (mapv :id (sp/query-entities storage :session {:account-id account-id}))]
    (when (seq ids)
      (sp/delete-entities storage :session ids))))


;; ---------------------------------------------------------------------------
;; password provider flows

(defn password-signup!
  "Create an account with a `password` identity and mint a session. Returns
   `{:account :account-id :token}`. The email is stored on the identity but NOT
   promoted to `:account.primary-email` until it is verified (Phase 1) — the
   login-email uniqueness rides on the identity's UNIQUE (provider, subject),
   not on primary-email. Throws `:accounts/email-taken` when that email is
   already a password identity or another account's verified primary email."
  [storage {:keys [email password display-name]}]
  (let [email (normalize-email email)]
    (when (str/blank? email) (throw (ex-info "email required" {:type :accounts/bad-email})))
    (when (or (find-identity storage "password" email)
              (account-by-email storage email))
      (throw (ex-info "email already registered" {:type :accounts/email-taken})))
    (let [acct (create-account! storage {:display-name (or display-name email)
                                         :primary-email nil
                                         :status "active"})
          account-id (str (:id acct))]
      (create-identity! storage account-id
                        {:provider "password" :subject email
                         :secret-data (hash-password password)
                         :email email :email-verified? false})
      {:account acct :account-id account-id :token (mint-session! storage account-id)})))


(defn totp-enabled?
  [account]
  (boolean (:totp-enabled? account)))


(defn password-login!
  "Verify `email`+`password`. Returns nil (unknown email / bad password /
   suspended). On success, when the account has 2FA enabled returns
   `{:account :account-id :totp-required? true}` (NO token — the caller runs the
   TOTP step); otherwise `{:account :account-id :token}` with a live session."
  [storage {:keys [email password]}]
  (let [email (normalize-email email)]
    (when-let [ident (and email (find-identity storage "password" email))]
      (when (verify-password password (:secret-data ident))
        (let [account-id (:account-id ident)]
          (when-let [acct (account-of storage account-id)]
            (when (= "active" (:status acct))
              (if (totp-enabled? acct)
                {:account acct :account-id account-id :totp-required? true}
                {:account acct :account-id account-id
                 :token (mint-session! storage account-id)}))))))))


;; ---------------------------------------------------------------------------
;; email verification
;;
;; A verification token reuses the `:session` shape (kind "verify") rather than
;; adding an entity (design principle #2): it is the same hashed, expiring,
;; account-scoped token, and kind "verify" is NOT in the authenticating set, so
;; it can never double as a login bearer — exactly how the tenancy `:token`
;; overloads kind "invite".

(def ^:const verification-ttl-ms
  "24h — how long an email-verification link is valid."
  (* 24 60 60 1000))


(defn mint-verification!
  "Create a verification token for `email` on `account-id`, returning the RAW
   token (embed it in the link). The email rides on the token's `:label`."
  [storage account-id email]
  (mint-session! storage account-id
                 {:kind "verify" :label (normalize-email email)
                  :ttl-ms verification-ttl-ms}))


(defn- promote-primary-email!
  "Set `:account.primary-email` to `email` when the account has none and no
   other account already claims it. A UNIQUE-constraint race is swallowed (the
   email stays verified on the identity; linking can merge accounts later)."
  [storage account-id email]
  (let [acct (account-of storage account-id)]
    (when (and acct (nil? (:primary-email acct)) (nil? (account-by-email storage email)))
      (try
        (sp/update-entity storage :account (:id acct) (assoc acct :primary-email email))
        (catch Exception _ nil)))))


(def ^:const reset-ttl-ms
  "1h — how long a password-reset link is valid."
  (* 60 60 1000))


(defn mint-password-reset!
  "Create a reset token for the account owning a `password` identity under
   `email`, returning `{:token :account-id}` — or nil when no such identity
   (callers MUST NOT reveal which; answer identically either way). The email
   rides on the token's `:label`. Reuses the `:session` shape (kind
   \"pw-reset\" — never authenticates)."
  [storage email]
  (let [email (normalize-email email)]
    (when-let [ident (and email (find-identity storage "password" email))]
      {:token (mint-session! storage (:account-id ident)
                             {:kind "pw-reset" :label email :ttl-ms reset-ttl-ms})
       :account-id (:account-id ident)})))


(defn reset-password!
  "Consume a reset token: set the new bcrypt hash on the account's `password`
   identity, revoke EVERY session of the account (the resetter proves email
   control; anyone else holding a session is signed out), and delete the token
   (single-use). Returns the account or nil (unknown/expired/wrong-kind token,
   or a password below 8 chars)."
  [storage token new-password]
  (when (and (not (str/blank? token)) (>= (count (str new-password)) 8))
    (when-let [s (first (sp/query-entities storage :session
                                           {:token-hash (crypto/sha256-hex token)}))]
      (when (and (= "pw-reset" (:kind s)) (session-live? s))
        (let [account-id (:account-id s)
              ident (find-identity storage "password" (:label s))]
          (when (and ident (= account-id (:account-id ident)))
            (sp/update-entity storage :identity (:id ident)
                              (assoc ident :secret-data (hash-password new-password)))
            (sp/delete-entities storage :session [(:id s)])
            (revoke-all-for-account! storage account-id)
            (account-of storage account-id)))))))


(defn verify-email!
  "Consume a verify token: mark every identity on the account carrying that
   email as verified, promote the email to `:account.primary-email` if free, and
   delete the token (single-use). Returns the account, or nil for an
   unknown / expired / non-verify token."
  [storage token]
  (when-not (str/blank? token)
    (when-let [s (first (sp/query-entities storage :session
                                           {:token-hash (crypto/sha256-hex token)}))]
      (when (and (= "verify" (:kind s)) (session-live? s))
        (let [account-id (:account-id s)
              email (:label s)]
          (doseq [ident (sp/query-entities storage :identity
                                           {:account-id account-id :email email})]
            (sp/update-entity storage :identity (:id ident)
                              (assoc ident :email-verified? true)))
          (promote-primary-email! storage account-id email)
          (sp/delete-entities storage :session [(:id s)])
          (account-of storage account-id))))))


;; ---------------------------------------------------------------------------
;; two-factor (TOTP)
;;
;; The account's `:totp-secret` is set at enrollment (enabled false) and only
;; flips `:totp-enabled?` true once a code confirms the authenticator is in
;; sync. The password-login 2FA step uses a short-lived "pending-2fa" session
;; (a `:session` kind that does not authenticate) so the password check and the
;; code check are two requests without ever exposing a full session in between.

(def ^:const pending-2fa-ttl-ms
  "5 min to enter the TOTP code after the password step."
  (* 5 60 1000))


(defn- now-secs
  ^long []
  (quot (now) 1000))


(defn begin-totp-enrollment!
  "Generate + store a fresh TOTP secret on `account-id` (enabled stays false
   until confirmed). Returns `{:secret :otpauth-uri}` to show as a QR."
  [storage account-id]
  (let [acct (account-of storage account-id)
        secret (totp/generate-secret)]
    (sp/update-entity storage :account (:id acct)
                      (assoc acct :totp-secret secret :totp-enabled? false))
    {:secret secret
     :otpauth-uri (totp/otpauth-uri "Graphden"
                                    (or (:primary-email acct) (str account-id))
                                    secret)}))


(defn confirm-totp!
  "Activate 2FA: verify `code` against the enrolled secret and, on success, set
   `:totp-enabled? true`. Returns true/false."
  [storage account-id code]
  (let [acct (account-of storage account-id)]
    (boolean
      (when (and (:totp-secret acct) (totp/valid? (:totp-secret acct) code (now-secs)))
        (sp/update-entity storage :account (:id acct) (assoc acct :totp-enabled? true))
        true))))


(defn verify-totp
  "True iff `code` is valid for the account's current secret."
  [storage account-id code]
  (let [acct (account-of storage account-id)]
    (boolean (and (totp-enabled? acct)
                  (totp/valid? (:totp-secret acct) code (now-secs))))))


(defn disable-totp!
  "Turn 2FA off — requires a valid current `code`. Clears the secret + flag.
   Returns true/false."
  [storage account-id code]
  (let [acct (account-of storage account-id)]
    (boolean
      (when (and (totp-enabled? acct) (totp/valid? (:totp-secret acct) code (now-secs)))
        (sp/update-entity storage :account (:id acct)
                          (assoc acct :totp-secret nil :totp-enabled? false))
        true))))


(defn mint-pending-2fa!
  "A short-lived non-authenticating token binding the passed-password step to
   the upcoming TOTP step."
  [storage account-id]
  (mint-session! storage account-id {:kind "pending-2fa" :ttl-ms pending-2fa-ttl-ms}))


(defn complete-2fa!
  "Given a pending-2fa token + a TOTP `code`, verify the code, consume the
   pending token, and mint a full session. Returns `{:account-id :token}` or
   nil (bad/expired pending token or wrong code)."
  [storage pending-token code]
  (when-not (str/blank? pending-token)
    (when-let [s (first (sp/query-entities storage :session
                                           {:token-hash (crypto/sha256-hex pending-token)}))]
      (when (and (= "pending-2fa" (:kind s)) (session-live? s)
                 (verify-totp storage (:account-id s) code))
        (sp/delete-entities storage :session [(:id s)])
        {:account-id (:account-id s)
         :token (mint-session! storage (:account-id s))}))))
