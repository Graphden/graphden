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
    [graphden.storage.protocol.core :as sp])
  (:import
    (java.security
      MessageDigest
      SecureRandom)
    (java.util
      Base64
      Base64$Encoder)
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


(defn- sha256-hex
  [^String s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (MessageDigest/.digest digest (String/.getBytes s "UTF-8"))]
    (str/join (map #(format "%02x" (bit-and % 0xff)) bytes))))


(defn- random-bytes
  ^bytes [n]
  (let [b (byte-array n)]
    (SecureRandom/.nextBytes (SecureRandom.) b)
    b))


(defn- random-token
  "A high-entropy URL-safe token; only its SHA-256 hash is ever stored."
  []
  (Base64$Encoder/.encodeToString (Base64$Encoder/.withoutPadding (Base64/getUrlEncoder))
                                  (random-bytes 32)))


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
         token (random-token)]
     (sp/create-entity storage :session
                       {:token-hash (sha256-hex token)
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
                                           {:token-hash (sha256-hex token)}))]
      (when (and (contains? #{nil "api"} (:kind s)) (session-live? s))
        (when-let [acct (account-of storage (:account-id s))]
          (when (= "active" (:status acct))
            acct))))))


(defn revoke-token!
  "Delete the session behind `token` (server-side logout). No-op if unknown."
  [storage token]
  (when-let [s (and token
                    (first (sp/query-entities storage :session
                                              {:token-hash (sha256-hex token)})))]
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


(defn password-login!
  "Verify `email`+`password` and mint a session. Returns `{:account :account-id
   :token}` or nil (unknown email / bad password / suspended account)."
  [storage {:keys [email password]}]
  (let [email (normalize-email email)]
    (when-let [ident (and email (find-identity storage "password" email))]
      (when (verify-password password (:secret-data ident))
        (let [account-id (:account-id ident)]
          (when-let [acct (account-of storage account-id)]
            (when (= "active" (:status acct))
              {:account acct :account-id account-id
               :token (mint-session! storage account-id)})))))))


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


(defn verify-email!
  "Consume a verify token: mark every identity on the account carrying that
   email as verified, promote the email to `:account.primary-email` if free, and
   delete the token (single-use). Returns the account, or nil for an
   unknown / expired / non-verify token."
  [storage token]
  (when-not (str/blank? token)
    (when-let [s (first (sp/query-entities storage :session
                                           {:token-hash (sha256-hex token)}))]
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
