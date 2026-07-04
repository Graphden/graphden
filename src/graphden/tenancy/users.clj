(ns graphden.tenancy.users
  "User model (PLATFORM_PLAN §4.1) — login identities backing the auth seam.
   Replaces the shared token with real accounts: an operator creates users
   (`create-user!`), users log in (`login!`) and receive a SESSION TOKEN that
   is just a `:token` row (reusing §3.4 #1), so the existing
   `tenancy.auth/storage-token-provider` resolves it on every later request.

   Passwords are stored as bcrypt hashes (`$2a$<cost>$…`, random salt per call,
   work factor baked into the string) — never plaintext, salted + adaptive.
   Both ops are controlled seams, not tenant graph fns: `:user` is
   tenant-forbidden, and minting tokens / hashing passwords is privileged."
  (:require
    [clojure.string :as str]
    [graphden.auth.provider :as auth]
    [graphden.storage.postgres.util :as pgutil]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.auth :as tauth]
    [graphden.tenancy.context :as tc]
    [next.jdbc :as jdbc])
  (:import
    (java.security
      SecureRandom)
    (java.util
      Base64
      Base64$Encoder)
    (org.mindrot.jbcrypt
      BCrypt)))


(def ^:private ^:const bcrypt-cost 12)


(def ^:const default-session-ttl-ms
  "How long a login session token stays valid — 24h. (Configurable later;
   operator-minted API keys via `create-token` have no expiry.)"
  (* 24 60 60 1000))


(defn client-ip
  "Best-effort client IP for rate-limiting — the first `X-Forwarded-For` hop
   (trusts a front proxy to set it) or the socket `:remote-addr`."
  [request]
  (or (some-> (get-in request [:headers "x-forwarded-for"])
              (str/split #",") first str/trim not-empty)
      (:remote-addr request)
      "unknown"))


(defn make-rate-limiter
  "A per-key fixed-window limiter: allow at most `max-attempts` per `window-ms`.
   Returns `(fn [key] → bool)` that prunes the key's window, records an allowed
   attempt, and answers whether it was allowed. In-memory (per process); denied
   attempts don't grow the window (bounded by `max-attempts`), and fully-idle
   keys are swept at most once per window so the map stays bounded by the count
   of ACTIVE keys, not every IP ever seen."
  [max-attempts window-ms]
  (let [state (atom {})
        last-prune (atom 0)]
    (fn [key]
      (let [now (System/currentTimeMillis)
            cutoff (- now window-ms)]
        (when (> now (+ @last-prune window-ms))
          (reset! last-prune now)
          (swap! state (fn [m]
                         (persistent!
                           (reduce-kv (fn [acc k ts]
                                        (let [r (filterv #(> % cutoff) ts)]
                                          (if (seq r) (assoc! acc k r) acc)))
                                      (transient {}) m)))))
        ;; Atomic test-and-record: prune the key's window AND (if under cap)
        ;; append THIS attempt inside ONE `swap-vals!`, then decide by whether
        ;; the key's window grew vs its pruned prior. A separate read-then-swap
        ;; let two concurrent attempts both read the same sub-cap window, both
        ;; be allowed past the limit, and clobber each other's append.
        (let [prune (fn [ts] (filterv #(> % cutoff) ts))
              [old new] (swap-vals! state
                                    (fn [m]
                                      (let [recent (prune (get m key []))]
                                        (assoc m key (if (< (count recent) max-attempts)
                                                       (conj recent now)
                                                       recent)))))]
          (> (count (get new key)) (count (prune (get old key)))))))))


(defn- random-bytes
  ^bytes [n]
  (let [b (byte-array n)]
    (SecureRandom/.nextBytes (SecureRandom.) b)
    b))


(defn hash-password
  "A bcrypt hash string for `password` (`$2a$<cost>$…`, random salt per call).
   bcrypt is adaptive + salted; the cost (work factor) is baked into the hash."
  [password]
  (BCrypt/hashpw password (BCrypt/gensalt bcrypt-cost)))


(defn verify-password
  "True iff `password` matches the stored bcrypt hash (`$2…`). Never throws
   (a malformed/unknown hash → false)."
  [password stored]
  (boolean
    (when (and (seq password) (seq stored) (str/starts-with? stored "$2"))
      (try
        (BCrypt/checkpw password stored)
        (catch Exception _ false)))))


(defn- random-token
  "A high-entropy URL-safe session token (its SHA-256 hash is what's stored)."
  []
  (Base64$Encoder/.encodeToString (Base64$Encoder/.withoutPadding (Base64/getUrlEncoder))
                                  (random-bytes 32)))


(defn create-user!
  "Create a user (operator op — `:user` is tenant-forbidden, so a tenant caller
   is denied by the storage guard). Throws `:user/invalid` on a blank username
   OR blank password (a blank password bcrypt-hashes to a value `verify-password`
   can never match — an unusable account), `:user/exists` when taken. Returns
   the created `:user` row (no password)."
  [ctx username password org]
  (let [storage (:storage ctx)]
    (when (str/blank? username)
      (throw (ex-info "username required" {:type :user/invalid})))
    (when (str/blank? password)
      (throw (ex-info "password required" {:type :user/invalid})))
    (when (first (sp/query-entities storage :user {:username username}))
      (throw (ex-info "username already taken" {:type :user/exists :username username})))
    (dissoc (sp/create-entity storage :user
                              {:username username
                               :password-hash (hash-password password)
                               :org org})
            :password-hash)))


(defn reset-password!
  "Operator op — set a NEW password for the user with id `user-id` and
   invalidate every existing session `:token` for that user, so an
   old/leaked bearer can't be replayed after the reset.

   Runs in the CALLER's org (NOT forced to public-org): `:user` / `:token`
   are `tenant-forbidden-entities`, so the storage `guard-write!` /
   `tenant-hidden?` deny any caller whose org ≠ public-org — i.e. this is
   operator-only exactly like `create-user!`. Forcing public-org here
   would defeat that guard and let a tenant reset arbitrary accounts.

   Throws `:user/invalid` on a blank password, `:user/not-found` when no
   such user. Returns `{:sessions-invalidated n}`."
  [ctx user-id new-password]
  (when (str/blank? new-password)
    (throw (ex-info "password required" {:type :user/invalid})))
  (let [storage (:storage ctx)
        user (sp/read-entity storage :user user-id)]
    (when-not user
      (throw (ex-info "user not found" {:type :user/not-found :id user-id})))
    (sp/update-entity storage :user user-id
                      {:password-hash (hash-password new-password)})
    (let [tokens (sp/query-entities storage :token {:user (:username user)})]
      (doseq [row tokens]
        (sp/delete-entity storage :token (:id row)))
      {:sessions-invalidated (count tokens)})))


(defn delete-user!
  "Operator op — delete the user with id `user-id` and CASCADE the rows
   that reference it by name: session `:token`s (`:user`) and `:grant`s
   (`:subject`), so no dangling auth / authz rows survive the account.

   Runs in the CALLER's org (NOT forced to public-org) — `:user` /
   `:token` / `:grant` are `tenant-forbidden-entities`, so the storage
   `guard-write!` denies any caller whose org ≠ public-org. Operator-only
   exactly like `create-user!`; forcing public-org would let a tenant
   delete arbitrary accounts. Throws `:user/not-found`. Returns
   `{:tokens-deleted n :grants-deleted m}`."
  [ctx user-id]
  (let [storage (:storage ctx)
        user (sp/read-entity storage :user user-id)]
    (when-not user
      (throw (ex-info "user not found" {:type :user/not-found :id user-id})))
    (let [username (:username user)
          tokens (sp/query-entities storage :token {:user username})
          grants (sp/query-entities storage :grant {:subject username})]
      (doseq [row tokens]
        (sp/delete-entity storage :token (:id row)))
      (doseq [row grants]
        (sp/delete-entity storage :grant (:id row)))
      (sp/delete-entity storage :user user-id)
      {:tokens-deleted (count tokens)
       :grants-deleted (count grants)})))


(defn login!
  "Verify `username`/`password` and mint a SESSION TOKEN (a `:token` row, so the
   storage-token-provider resolves it later). Runs in the platform context
   (login precedes any session). Returns `{:token <raw> :user :org}` on success,
   nil on bad credentials (caller maps nil → 401).

   4-arg arity: the `:login` user-ops seam is invoked with the Ring `request`
   (its client IP feeds the addon's per-IP rate limiter); the core op ignores
   it — this arity is just the seam adapter."
  ([ctx username password]
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
  ([ctx username password _request] (login! ctx username password)))


(defn signup!
  "Self-serve registration (PLATFORM_PLAN §4.1): create a BRAND-NEW org and a
   user who owns it, then auto-login. The org must be FREE — signup can never
   join an existing org, so a new account can't reach another tenant's data.
   Runs in the platform context. Returns `{:token :user :org}` on success, nil
   on any failure (blank field, or username / org already taken) so the
   endpoint maps nil → an empty body exactly like login. Open signup; per-IP
   rate-limiting is applied by the `:user-ops` `:signup` wrapper (the addon),
   which may pass the request as a trailing arg that this core fn ignores."
  [ctx username password org & _]
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
  "Hard-delete every expired session `:token` in ONE SQL statement — O(1) for
   the reaper (the provider already ignores them via `token-live?`; this stops
   the rows piling up). NULL-expiry operator API keys are left alone. `ds` is
   the base datasource (`:db/postgres`'s `:pool`); `:token` carries no RLS
   policy, so the raw delete is unrestricted. Returns the count deleted."
  [ds]
  (let [t (pgutil/ident->sql :token)
        col (pgutil/ident->sql :expires-at)
        sql (str "DELETE FROM " t " WHERE " col " IS NOT NULL AND " col " < ?")]
    (or (:next.jdbc/update-count
          (jdbc/execute-one! ds [sql (System/currentTimeMillis)]))
        0)))
