(ns graphden.tenancy.users
  "User model (PLATFORM_PLAN §4.1) — login identities backing the auth seam.
   Replaces the shared token with real accounts: an operator creates users
   (`create-user!`), users log in (`login!`) and receive a SESSION TOKEN that
   is just a `:token` row (reusing §3.4 #1), so the existing
   `tenancy.auth/storage-token-provider` resolves it on every later request.

   Passwords are stored as a self-describing PBKDF2 string
   (`pbkdf2$iters$salt$hash`, PBKDF2WithHmacSHA256) — never plaintext, salted +
   iterated. (bcrypt/argon2 is a hardening follow-up; PBKDF2 is built-in, no
   dep.) Both ops are controlled seams, not tenant graph fns: `:user` is
   tenant-forbidden, and minting tokens / hashing passwords is privileged."
  (:require
    [clojure.string :as str]
    [graphden.auth.provider :as auth]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.auth :as tauth]
    [graphden.tenancy.context :as tc])
  (:import
    (java.security
      MessageDigest
      SecureRandom)
    (java.util
      Base64
      Base64$Decoder
      Base64$Encoder)
    (javax.crypto
      SecretKey
      SecretKeyFactory)
    (javax.crypto.spec
      PBEKeySpec)
    (org.mindrot.jbcrypt
      BCrypt)))


(def ^:private ^:const pbkdf2-key-bits 256)   ; legacy verify only
(def ^:private ^:const bcrypt-cost 12)


(def ^:const default-session-ttl-ms
  "How long a login session token stays valid — 24h. (Configurable later;
   operator-minted API keys via `create-token` have no expiry.)"
  (* 24 60 60 1000))


(defn- random-bytes
  ^bytes [n]
  (let [b (byte-array n)]
    (SecureRandom/.nextBytes (SecureRandom.) b)
    b))


(defn- pbkdf2
  ^bytes [^String password ^bytes salt iters]
  (let [spec (PBEKeySpec. (String/.toCharArray password) salt (int iters) pbkdf2-key-bits)
        skf (SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256")]
    (SecretKey/.getEncoded (SecretKeyFactory/.generateSecret skf spec))))


(defn hash-password
  "A bcrypt hash string for `password` (`$2a$<cost>$…`, random salt per call).
   bcrypt is adaptive + salted; the cost (work factor) is baked into the hash."
  [password]
  (BCrypt/hashpw password (BCrypt/gensalt bcrypt-cost)))


(defn- pbkdf2-verify
  "Legacy PBKDF2 verify (`pbkdf2$iters$salt$hash`) — kept so any account created
   before the bcrypt switch still logs in. Constant-time compare; recomputes
   with the stored salt + iteration count."
  [password stored]
  (let [[algo iters salt-b64 hash-b64] (str/split stored #"\$")]
    (when (= algo "pbkdf2")
      (let [dec (Base64/getDecoder)
            salt (Base64$Decoder/.decode dec ^String salt-b64)
            expected (Base64$Decoder/.decode dec ^String hash-b64)
            actual (pbkdf2 password salt (parse-long iters))]
        (MessageDigest/isEqual expected actual)))))


(defn verify-password
  "True iff `password` matches the stored hash. Dispatches on format: bcrypt
   (`$2…`) for current accounts, legacy PBKDF2 (`pbkdf2$…`) for any created
   before the switch. Never throws (a malformed/unknown hash → false)."
  [password stored]
  (boolean
    (when (and (seq password) (seq stored))
      (try
        (cond
          (str/starts-with? stored "$2")      (BCrypt/checkpw password stored)
          (str/starts-with? stored "pbkdf2$") (pbkdf2-verify password stored)
          :else false)
        (catch Exception _ false)))))


(defn- legacy-hash?
  "True for a pre-bcrypt PBKDF2 hash — it gets transparently re-hashed to
   bcrypt on the next successful login (`login!`)."
  [stored]
  (boolean (and stored (str/starts-with? stored "pbkdf2$"))))


(defn- random-token
  "A high-entropy URL-safe session token (its SHA-256 hash is what's stored)."
  []
  (Base64$Encoder/.encodeToString (Base64$Encoder/.withoutPadding (Base64/getUrlEncoder))
                                  (random-bytes 32)))


(defn create-user!
  "Create a user (operator op — `:user` is tenant-forbidden, so a tenant caller
   is denied by the storage guard). Throws `:user/invalid` on a blank username,
   `:user/exists` when taken. Returns the created `:user` row (no password)."
  [ctx username password org]
  (let [storage (:storage ctx)]
    (when (str/blank? username)
      (throw (ex-info "username required" {:type :user/invalid})))
    (when (first (sp/query-entities storage :user {:username username}))
      (throw (ex-info "username already taken" {:type :user/exists :username username})))
    (dissoc (sp/create-entity storage :user
                              {:username username
                               :password-hash (hash-password password)
                               :org org})
            :password-hash)))


(defn login!
  "Verify `username`/`password` and mint a SESSION TOKEN (a `:token` row, so the
   storage-token-provider resolves it later). Runs in the platform context
   (login precedes any session). A legacy PBKDF2 hash is transparently
   re-hashed to bcrypt here — the only place we hold the plaintext. Returns
   `{:token <raw> :user :org}` on success, nil on bad credentials (caller maps
   nil → 401)."
  [ctx username password]
  (let [storage (:storage ctx)]
    (tc/with-org tc/public-org
                 (let [user (first (sp/query-entities storage :user {:username username}))]
                   (when (and user (verify-password password (:password-hash user)))
                     ;; Upgrade-on-login: a verified legacy PBKDF2 hash is
                     ;; re-stored as bcrypt, so old accounts migrate as they're
                     ;; used (no mass rehash, no plaintext kept).
                     (when (legacy-hash? (:password-hash user))
                       (sp/update-entity storage :user (:id user)
                                         {:password-hash (hash-password password)}))
                     (let [raw (random-token)
                           org (:org user)]
                       (sp/create-entity storage :token
                                         {:token-hash (tauth/token-hash raw)
                                          :user username
                                          :org org
                                          :expires-at (+ (System/currentTimeMillis) default-session-ttl-ms)})
                       {:token raw :user username :org org}))))))


(defn signup!
  "Self-serve registration (PLATFORM_PLAN §4.1): create a BRAND-NEW org and a
   user who owns it, then auto-login. The org must be FREE — signup can never
   join an existing org, so a new account can't reach another tenant's data.
   Runs in the platform context. Returns `{:token :user :org}` on success, nil
   on any failure (blank field, or username / org already taken) so the
   endpoint maps nil → an empty body exactly like login. (Open signup; rate-
   limiting / invite-gating is a follow-up.)"
  [ctx username password org]
  (let [storage (:storage ctx)]
    (when (and (not (str/blank? username)) (not (str/blank? password)) (not (str/blank? org)))
      (tc/with-org tc/public-org
                   (when (and (empty? (sp/query-entities storage :user {:username username}))
                              (empty? (sp/query-entities storage :org {:name org})))
                     ;; New org + its first user. The UNIQUE constraints on
                     ;; :org.name / :user.username are the real guard against a
                     ;; concurrent duplicate; the checks above are for the UX.
                     (sp/create-entity storage :org {:name org})
                     (sp/create-entity storage :user
                                       {:username username
                                        :password-hash (hash-password password)
                                        :org org})
                     (login! ctx username password))))))


(defn logout!
  "Invalidate the current session — delete the `:token` row for `request`'s
   bearer, so a leaked/observed token can't be replayed after sign-out
   (clearing the client's localStorage alone wouldn't). Idempotent: a missing /
   unknown bearer is a no-op. A caller can only delete the token it presents (a
   secret it already holds), so this needs no further authz. Returns true iff a
   row was deleted."
  [ctx request]
  (let [storage (:storage ctx)
        token (auth/extract-bearer request)]
    (boolean
      (when-not (str/blank? token)
        (tc/with-org tc/public-org
                     (when-let [row (first (sp/query-entities storage :token
                                                              {:token-hash (tauth/token-hash token)}))]
                       (sp/delete-entity storage :token (:id row))
                       true))))))


(defn logout-all!
  "Sign out everywhere — delete EVERY session `:token` for the current
   authenticated user (e.g. after a password change or a lost device). The user
   is read from `*current-principal*`, so a caller can only sweep its OWN
   sessions. Returns the count deleted; 0 when unauthenticated."
  [ctx]
  (let [storage (:storage ctx)
        user (:user tc/*current-principal*)]
    (if (str/blank? user)
      0
      (tc/with-org tc/public-org
                   (let [rows (sp/query-entities storage :token {:user user})]
                     (doseq [row rows]
                       (sp/delete-entity storage :token (:id row)))
                     (count rows))))))


(defn cleanup-expired-tokens!
  "Hard-delete every `:token` whose expiry is in the past — the provider already
   ignores them (`token-live?`), this stops the rows accumulating. Runs in the
   platform context. NULL-expiry tokens (operator API keys) are never touched.
   Returns the number deleted. (Equality-only `query-entities` can't express
   `expires-at < now`, so this reads + filters in-process; a SQL
   `DELETE … WHERE expires_at < ?` is a scale follow-up.)"
  [storage]
  (tc/with-org tc/public-org
               (let [now (System/currentTimeMillis)
                     expired (filter (fn [r] (when-let [e (:expires-at r)] (< e now)))
                                     (sp/query-entities storage :token {}))]
                 (doseq [row expired]
                   (sp/delete-entity storage :token (:id row)))
                 (count expired))))
