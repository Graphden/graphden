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


(def demo-ttl-ms
  "How long an anonymous landing-demo org + its token live before the demo-gc
   reaper purges the org (and the token goes inert). Default 1h; env-tunable via
   `GRAPHDEN_DEMO_TTL_MS`."
  (or (some-> (System/getenv "GRAPHDEN_DEMO_TTL_MS") parse-long) (* 60 60 1000)))


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


(defn make-per-key-limiter
  "Like `make-rate-limiter`, but the cap is supplied PER CALL — `(fn [key
   max-attempts] → bool)` — so different keys can carry different limits from one
   shared window state. Used by the per-ORG egress cap, where each org's limit
   comes from its plan tier (`plan/egress-limit-for`). Same in-memory fixed-
   window semantics + atomic test-and-record + idle-key sweep as
   `make-rate-limiter`."
  [window-ms]
  (let [state (atom {})
        last-prune (atom 0)]
    (fn [key max-attempts]
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
    ;; Session invalidation by the STABLE user-id (same retirement as
    ;; the delete cascade below — username matching drifts on rename).
    (let [tokens (sp/query-entities storage :token {:user-id (str user-id)})]
      (when (seq tokens)
        (sp/delete-entities storage :token (mapv :id tokens)))
      {:sessions-invalidated (count tokens)})))


(defn delete-user!
  "Operator op — delete the user with id `user-id` and CASCADE the rows
   that reference it by name: session `:token`s (`:user`) and `:grant`s
   (`:subject-id`), so no dangling auth / authz rows survive the account.

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
    (let [;; Cascade by the STABLE id only. The by-username union is
          ;; retired (audit-2 grant.subject completion): the addon boot
          ;; backfills legacy rows (`backfill-auth-subject-ids!`) and
          ;; every writer stamps the id, so a nil-subject-id row can no
          ;; longer exist past boot — and matching by mutable username
          ;; was the exact drift-bug class the id column was added for.
          uid-str (str user-id)
          token-ids (into #{} (map :id)
                          (sp/query-entities storage :token {:user-id uid-str}))
          grant-ids (into #{} (map :id)
                          (sp/query-entities storage :grant {:subject-id uid-str}))]
      (when (seq token-ids)
        (sp/delete-entities storage :token (vec token-ids)))
      (when (seq grant-ids)
        (sp/delete-entities storage :grant (vec grant-ids)))
      (sp/delete-entity storage :user user-id)
      {:tokens-deleted (count token-ids)
       :grants-deleted (count grant-ids)})))


(defn backfill-auth-subject-ids!
  "One-time, IDEMPOTENT migration for the P1 name→id authz change: for every
   `:token` row still missing its stable id, resolve the (immutable)
   username and stamp `:token.user-id`. Runs at addon startup so a LIVE
   DB carrying pre-P1 rows keeps authorizing after the deploy; a no-op
   on a fresh DB (every row is minted with its id). The GRANT arm is
   gone with the `grant.subject` column retirement — a nil-subject-id
   grant has no username source left; such rows are dead (match no
   subject) and `dev.integrity` reports them.

   Correctness note: filters nil-id rows in memory rather than querying
   `{:user-id nil}` — SQL `= NULL` matches nothing, so a where-clause filter
   would silently backfill zero rows."
  [storage]
  (let [user-id-of (memoize
                     (fn [username]
                       (some-> (first (sp/query-entities storage :user {:username username}))
                               :id str)))
        tokens (filter #(nil? (:user-id %)) (sp/query-entities storage :token {}))
        stamped (fn [entity id-key name-key rows]
                  (reduce (fn [n row]
                            (if-let [uid (user-id-of (name-key row))]
                              (do (sp/update-entity storage entity (:id row) {id-key uid})
                                  (inc n))
                              n))
                          0 rows))]
    {:tokens-backfilled (stamped :token :user-id :user tokens)}))


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
                                           ;; the STABLE identity (string form) —
                                           ;; authz keys on this, not the mutable
                                           ;; username
                                           :user-id (str (:id user))
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
    ;; `org` must NOT be the platform/public org: every tenancy gate treats
    ;; `= current-org public-org` as the trusted operator and turns itself off
    ;; (effect gate, RLS, per-namespace write guard), so a self-serve
    ;; `org="public"` account would be a full platform-admin — sandbox escape +
    ;; cross-tenant breach. Reject it up front.
    (when (and (not (str/blank? username)) (not (str/blank? password))
               (not (str/blank? org)) (not= org tc/public-org))
      (tc/with-org tc/public-org
                   (when (and (empty? (sp/query-entities storage :user {:username username}))
                              (empty? (sp/query-entities storage :org {:name org}))
                              ;; The `:org` table isn't the only trace of an org:
                              ;; an operator may have minted `:user`/`:token` rows
                              ;; for an org without a registry row. Signup must
                              ;; never JOIN such an org, so treat any existing
                              ;; user in `org` as "taken" too.
                              (empty? (sp/query-entities storage :user {:org org})))
                     ;; New org + its first user. The UNIQUE constraints on
                     ;; :org.name / :user.username are the real guard against a
                     ;; concurrent duplicate; the checks above are for the UX.
                     ;; `:plan "free"` = the REGISTERED tier (metered :network +
                     ;; own external DB); the locked `anonymous` default is only
                     ;; for landing demos, so a self-serve signup opts into
                     ;; `free` explicitly (tier-split). Paid upgrades go through
                     ;; the operator `set-org-plan` route.
                     (sp/create-entity storage :org {:name org :plan "free"})
                     (sp/create-entity storage :user
                                       {:username username
                                        :password-hash (hash-password password)
                                        :org org})
                     (login! ctx username password))))))


(defn demo-start!
  "Provision an EPHEMERAL ANONYMOUS demo org + a bearer token for a landing
   visitor (tier-split): a throwaway org on the locked `anonymous` plan (no
   network, small ceilings) with an `:expires-at` so the demo-gc reaper purges
   it after `demo-ttl-ms`. No user / password — the token authenticates as the
   org itself. Returns `{:token <raw> :org <name>}`; the raw token is shown once
   (only its SHA-256 is stored, same discipline as sessions / invites). Runs in
   the platform context. The org name is random, so concurrent calls can't
   collide; `:plan \"anonymous\"` keeps a demo locked EVEN without this fn (the
   fail-safe default), but we set it explicitly so the org reads honestly.

   NOT self-gated: this creates rows for an UNAUTHENTICATED caller, so the addon
   wires it behind an enable flag + a per-IP rate limiter (like `signup!`)."
  [ctx & _]
  (let [storage (:storage ctx)
        org (str "demo-" (subs (str (random-token)) 0 16))
        raw (random-token)
        expires (+ (System/currentTimeMillis) demo-ttl-ms)]
    (tc/with-org tc/public-org
                 (sp/create-entity storage :org
                                   {:name org :plan "anonymous" :expires-at expires})
                 (sp/create-entity storage :token
                                   {:token-hash (tauth/token-hash raw)
                                    :user org
                                    :user-id org
                                    :org org
                                    :expires-at expires})
                 {:token raw :org org})))


(def default-invite-ttl-ms
  "Invites live 7 days — long enough to land in an inbox, short enough that a
   forgotten link doesn't stay a standing door into the org."
  (* 7 24 60 60 1000))


(defn create-invite!
  "Mint a SINGLE-USE invite into `org` (LAUNCH_PLAN stage 1.3 — before this,
   signup could only create a brand-new org, so a two-person team was
   impossible without the operator). Caller identity comes from the addon's
   op wrapper (the authenticated principal): any member may invite into their
   OWN org — the org is taken from the principal, never from client input, so
   an invite can't target a foreign org by construction. Returns
   `{:invite <raw> :org org}`; the raw token is shown once, only its SHA-256
   is stored (same discipline as sessions). Stored as a `:token` row with
   `:kind \"invite\"` — auth.clj refuses to authenticate it."
  [ctx inviter-username inviter-user-id org]
  (let [storage (:storage ctx)
        raw (random-token)]
    (tc/with-org tc/public-org
                 (sp/create-entity storage :token
                                   {:token-hash (tauth/token-hash raw)
                                    :user inviter-username
                                    :user-id inviter-user-id
                                    :org org
                                    :kind "invite"
                                    :expires-at (+ (System/currentTimeMillis)
                                                   default-invite-ttl-ms)}))
    {:invite raw :org org}))


(defn redeem-invite!
  "Redeem an invite: create `username` INSIDE the invite's org and auto-login.
   nil on any failure (unknown/expired invite, blank or taken username) so the
   endpoint maps nil → 401 exactly like login/signup. The invite row is
   deleted BEFORE the user row is created — single-use even when two redeems
   race (the loser's delete-entity finds the row gone and bails)."
  [ctx invite-token username password & _]
  (let [storage (:storage ctx)]
    (when (and (not (str/blank? invite-token))
               (not (str/blank? username))
               (not (str/blank? password)))
      (tc/with-org tc/public-org
                   (when-let [row (first (sp/query-entities
                                           storage :token
                                           {:token-hash (tauth/token-hash invite-token)
                                            :kind "invite"}))]
                     (when (and (tauth/token-live? row)
                                (empty? (sp/query-entities storage :user {:username username}))
                                (sp/delete-entity storage :token (:id row)))
                       (sp/create-entity storage :user
                                         {:username username
                                          :password-hash (hash-password password)
                                          :org (:org row)})
                       (login! ctx username password)))))))


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
