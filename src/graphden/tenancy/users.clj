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
      PBEKeySpec)))


(def ^:private ^:const pbkdf2-iterations 100000)
(def ^:private ^:const pbkdf2-key-bits 256)


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
  "A self-describing PBKDF2 hash string for `password` — `pbkdf2$iters$salt$hash`
   (salt + hash base64). Random salt per call."
  [password]
  (let [enc (Base64/getEncoder)
        salt (random-bytes 16)
        hash (pbkdf2 password salt pbkdf2-iterations)]
    (str "pbkdf2$" pbkdf2-iterations "$"
         (Base64$Encoder/.encodeToString enc salt) "$"
         (Base64$Encoder/.encodeToString enc hash))))


(defn verify-password
  "True iff `password` matches the stored `pbkdf2$…` string. Constant-time
   compare; recomputes with the stored salt + iteration count."
  [password stored]
  (boolean
    (when (and (seq password) (seq stored))
      (let [[algo iters salt-b64 hash-b64] (str/split stored #"\$")]
        (when (= algo "pbkdf2")
          (let [dec (Base64/getDecoder)
                salt (Base64$Decoder/.decode dec ^String salt-b64)
                expected (Base64$Decoder/.decode dec ^String hash-b64)
                actual (pbkdf2 password salt (parse-long iters))]
            (MessageDigest/isEqual expected actual)))))))


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
   (login precedes any session). Returns `{:token <raw> :user :org}` on success,
   nil on bad credentials (caller maps nil → 401)."
  [ctx username password]
  (let [storage (:storage ctx)]
    (tc/with-org tc/public-org
                 (let [user (first (sp/query-entities storage :user {:username username}))]
                   (when (and user (verify-password password (:password-hash user)))
                     (let [raw (random-token)
                           org (:org user)]
                       (sp/create-entity storage :token
                                         {:token-hash (tauth/token-hash raw)
                                          :user username
                                          :org org
                                          :expires-at (+ (System/currentTimeMillis) default-session-ttl-ms)})
                       {:token raw :user username :org org}))))))


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
