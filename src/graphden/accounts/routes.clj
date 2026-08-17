(ns graphden.accounts.routes
  "The `/auth/*` HTTP surface for the accounts module, installed as a plain Ring
   router through the route-collection seam (runs before the graph app-router,
   inside the request scope). Returns a response on a matched `/auth/...` path,
   or nil to fall through.

   Endpoints (pages + JSON; the full behavior table is docs/ACCOUNTS.md):
     GET  /login /account /reset   self-contained HTML pages
     GET  /auth/me                 current account (or {account:null}) — editor probe
     GET  /auth/tfa-state          {enabled} for the signed-in account
     POST /auth/signup             {email,password} → set gd_session, send verify
     POST /auth/login              {email,password} → set gd_session (or 2fa step)
     POST /auth/totp               second login step: gd_2fa cookie + code → session
     POST /auth/totp/enroll|confirm|disable   TOTP lifecycle for the account
     POST /auth/logout             revoke session + clear cookie
     POST /auth/logout-all         revoke every session of the account
     POST /auth/forgot             {email} → email a reset link (never enumerates)
     POST /auth/reset              {token,password} → set new pw, sign out everywhere
     POST /auth/resend-verification  re-send the verify mail (rate-limited)
     GET  /auth/verify?token=      consume email-verification link → redirect
     GET  /auth/identities         linked sign-in methods
     POST /auth/unlink             unlink a method (last one is refused)
     GET  /auth/:provider/start    (github|google) 302 to the provider + state cookie
     GET  /auth/:provider/callback (github|google) exchange → session → redirect
     GET  /auth/telegram/start     plant gd_link_intent nonce → {state} (link CSRF guard)
     GET  /auth/telegram/callback  verify widget HMAC → session; LINK only on nonce match

   Sessions are delivered as an HttpOnly `gd_session` cookie. There is no ring
   cookie middleware in the chain, so cookies are set as `Set-Cookie` headers
   directly (a string header key survives the encode wrap above the seam)."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.accounts.core :as core]
    [graphden.accounts.crypto :as crypto]
    [graphden.accounts.flows :as flows]
    [graphden.accounts.oauth :as oauth]
    [graphden.accounts.pages :as pages]
    [graphden.accounts.provider :as provider]
    [graphden.accounts.telegram :as telegram]
    [graphden.crud.request :as req]))


(defn- graph-page
  "Render a page through the injected graph `page-renderer` (the
   `app.auth-pages` fn-defs on the platform ctx), falling back to
   `fallback-thunk` (the built-in Clojure shell) when no renderer is
   wired or the graph cannot render — the login page must survive a
   graph outage."
  [page-renderer page-kw args fallback-thunk]
  (or (when page-renderer
        (try (page-renderer page-kw args)
             (catch Exception e
               (log/warn e "accounts: graph page render failed — serving the built-in shell"
                         {:page page-kw})
               nil)))
      (fallback-thunk)))


(def ^:private session-max-age-secs (* 24 60 60))
(def ^:private oauth-state-max-age-secs 600)
(def ^:private pending-2fa-max-age-secs 300)


;; The Telegram-link intent nonce. The widget's signed payload authenticates
;; its ORIGIN at Telegram but NOT the victim's intent, and there is no provider
;; redirect to bounce a `state` through (as OAuth has), so linking gets its own
;; per-request nonce: `/auth/telegram/start` plants this HttpOnly cookie and
;; hands the value back for the client to append as the callback's `state`;
;; the LINK arm fires only when the two match — the same intent proof `gd_oauth`
;; gives the OAuth pair.
(def ^:private link-intent-cookie "gd_link_intent")


(defn- https-origin?
  [origin]
  (str/starts-with? (str origin) "https"))


(defn- resolve-origin
  "The public origin for redirect URIs (OAuth): the configured value, else
   derived from the request Host (https assumed — prod is TLS-terminated).
   OAuth redirects are safe with a Host-derived origin because the provider
   allowlists redirect_uri; emailed LINKS are NOT — use `link-origin`."
  [configured request]
  (if (str/blank? (str configured))
    (str "https://" (get-in request [:headers "host"]))
    configured))


(defn- dev-local-host?
  "A loopback / private-network / .local Host — safe to trust without a
   configured origin (developer machine). Anything else on the public
   internet is attacker-controllable via the Host header."
  [host]
  (let [h (-> (str host) (str/split #":") first str/lower-case)]
    (boolean
      (or (= h "localhost") (= h "::1")
          (str/starts-with? h "127.")
          (str/starts-with? h "0.0.0.0")
          (str/ends-with? h ".local")
          (re-matches #"10\..*|192\.168\..*|172\.(1[6-9]|2\d|3[01])\..*" h)))))


(defn- link-origin
  "Trusted origin for EMAILED links (password reset / email verification).
   The configured GRAPHDEN_APP_ORIGIN wins; when unset we derive from the
   request Host ONLY for a dev-local host — a public Host is
   attacker-controllable (a forged `Host:` header would email the victim a
   reset link pointing at the attacker → account takeover), so we return
   blank and the sender refuses. Distinct from `resolve-origin` (OAuth),
   whose Host fallback is safe behind provider redirect_uri allowlisting."
  [configured request]
  (cond
    (not (str/blank? (str configured))) configured
    (dev-local-host? (get-in request [:headers "host"]))
    (str "https://" (get-in request [:headers "host"]))
    :else ""))


(defn- cookie-str
  [name value {:keys [max-age secure? path same-site http-only]
               :or {path "/" same-site "Lax" http-only true}}]
  (str name "=" value
       "; Path=" path
       (when http-only "; HttpOnly")
       (when secure? "; Secure")
       "; SameSite=" same-site
       (when max-age (str "; Max-Age=" max-age))))


(defn- session-cookie
  [token origin]
  (cookie-str provider/session-cookie token
              {:max-age session-max-age-secs :secure? (https-origin? origin)}))


(defn- redirect
  ([location] (redirect location nil))
  ([location set-cookie]
   {:status 302
    :headers (cond-> {"Location" location}
               set-cookie (assoc "Set-Cookie" set-cookie))}))


(defn- json-resp
  ([status body] (json-resp status body nil))
  ([status body set-cookie]
   {:status status
    :headers (cond-> {"Content-Type" "application/json"}
               set-cookie (assoc "Set-Cookie" set-cookie))
    :body (json/generate-string body)}))


(defn- html-resp
  [html]
  {:status 200 :headers {"Content-Type" "text/html; charset=utf-8"} :body html})


;; --- handlers ---

(defn- current-account
  "The signed-in account for this request (bearer or gd_session), or nil."
  [storage request]
  (core/authenticate-token storage (provider/request-token request)))


(defn- finish-social
  "Common tail for an OAuth/Telegram callback that produced a normalized
   identity `info`.

   When the request already carries a session this is an identity-LINK — but
   linking is an authenticated, intent-bearing action, so we perform it ONLY
   when `link-ok?` proves per-request intent (OAuth: the `state`==`gd_oauth`
   check already ran in `handle-oauth-callback`; Telegram: `state`==the
   `gd_link_intent` cookie planted by `/auth/telegram/start`). Without that
   proof we refuse to link: a bare callback GET carrying the victim's
   SameSite=Lax `gd_session` must never silently attach an attacker's identity
   to the victim's account (CSRF account-takeover). The session's owner did not
   ask to swap identities either, so we leave them signed in as themselves and
   do nothing.

   With no session we log in / create and set a fresh session."
  [storage origin request info provider-key link-ok?]
  (if-let [acct (current-account storage request)]
    (if link-ok?
      (try
        (core/link-identity! storage (str (:id acct)) info)
        (redirect (str origin "/settings?linked=" provider-key))
        (catch clojure.lang.ExceptionInfo _
          (redirect (str origin "/settings?error=identity_conflict"))))
      (redirect (str origin "/settings?error=link_intent")))
    (let [{:keys [account-id]} (core/resolve-social-identity! storage info)]
      (redirect (str origin "/") (session-cookie (core/mint-session! storage account-id) origin)))))


(defn- handle-identities
  [storage request]
  (if-let [acct (current-account storage request)]
    (json-resp 200 {:ok true
                    :identities (mapv #(select-keys % [:provider :email :email-verified? :created-at])
                                      (core/identities-for-account storage (str (:id acct))))})
    (json-resp 401 {:ok false :error "unauthenticated"})))


(defn- handle-me
  [storage request]
  (if-let [acct (current-account storage request)]
    (json-resp 200 {:ok true
                    :account {:id (str (:id acct))
                              :email (:primary-email acct)
                              :display-name (:display-name acct)}})
    (json-resp 401 {:ok false :error "unauthenticated"})))


(defn- handle-tfa-state
  [storage request]
  (if-let [acct (current-account storage request)]
    (json-resp 200 {:ok true :enabled (core/totp-enabled? acct)})
    (json-resp 401 {:ok false :error "unauthenticated"})))


(defn- handle-unlink
  [storage request]
  (if-let [acct (current-account storage request)]
    (let [provider (:provider (req/read-json-body request))
          account-id (str (:id acct))
          idents (core/identities-for-account storage account-id)
          remaining (remove #(= provider (:provider %)) idents)]
      (if (empty? remaining)
        (json-resp 409 {:ok false :error "last_identity"})
        (do (core/unlink-identity! storage account-id provider)
            (json-resp 200 {:ok true}))))
    (json-resp 401 {:ok false :error "unauthenticated"})))


(defn- handle-signup
  [storage mailer email-renderer origin link-orig request]
  (let [{:keys [email password]} (req/read-json-body request)]
    (try
      (let [{:keys [account-id token]} (core/password-signup! storage {:email email :password password})]
        ;; Verify email uses the TRUSTED link origin (M5); the session
        ;; cookie's Secure flag still keys off the request origin.
        (flows/request-verification! storage mailer link-orig account-id email
                                     email-renderer)
        (json-resp 200 {:ok true :verification-sent true} (session-cookie token origin)))
      (catch clojure.lang.ExceptionInfo e
        (json-resp 409 {:ok false :error (name (or (:type (ex-data e)) :error))})))))


(defn- handle-resend-verification
  "Re-send the verification email for the signed-in account's still-unverified
   password identity. Idempotent-ish (mints a fresh token each call); answers
   200 whether or not there was anything to send (no state leak)."
  [storage mailer email-renderer link-orig request]
  (if-let [acct (current-account storage request)]
    (do
      (when-let [ident (->> (core/identities-for-account storage (str (:id acct)))
                            (filter #(and (= "password" (:provider %))
                                          (not (:email-verified? %))
                                          (:email %)))
                            first)]
        (flows/request-verification! storage mailer link-orig (str (:id acct)) (:email ident)
                                     email-renderer))
      (json-resp 200 {:ok true}))
    (json-resp 401 {:ok false :error "unauthenticated"})))


(defn- pending-2fa-cookie
  [token origin]
  (cookie-str "gd_2fa" token {:max-age pending-2fa-max-age-secs :secure? (https-origin? origin)}))


(defn- handle-login
  [storage origin request]
  (let [{:keys [email password]} (req/read-json-body request)
        result (core/password-login! storage {:email email :password password})]
    (cond
      (nil? result) (json-resp 401 {:ok false :error "invalid_credentials"})
      (:totp-required? result)
      (json-resp 200 {:ok true :totp-required true}
                 (pending-2fa-cookie (core/mint-pending-2fa! storage (:account-id result)) origin))
      :else (json-resp 200 {:ok true} (session-cookie (:token result) origin)))))


(defn- handle-totp
  "Second login step: a pending-2fa cookie + a TOTP code → a full session."
  [storage origin request]
  (let [{:keys [code]} (req/read-json-body request)
        pending (provider/cookie-value request "gd_2fa")]
    (if-let [{:keys [token]} (core/complete-2fa! storage pending code)]
      (json-resp 200 {:ok true} (session-cookie token origin))
      (json-resp 401 {:ok false :error "invalid_code"}))))


(defn- handle-totp-enroll
  [storage request]
  (if-let [acct (current-account storage request)]
    (try
      (json-resp 200 (assoc (core/begin-totp-enrollment! storage (str (:id acct))) :ok true))
      (catch clojure.lang.ExceptionInfo e
        (if (= :accounts/totp-already-enabled (:type (ex-data e)))
          (json-resp 409 {:ok false :error "totp_already_enabled"})
          (throw e))))
    (json-resp 401 {:ok false :error "unauthenticated"})))


(defn- handle-totp-confirm
  [storage request]
  (if-let [acct (current-account storage request)]
    (if (core/confirm-totp! storage (str (:id acct)) (:code (req/read-json-body request)))
      (json-resp 200 {:ok true})
      (json-resp 400 {:ok false :error "invalid_code"}))
    (json-resp 401 {:ok false :error "unauthenticated"})))


(defn- handle-totp-disable
  [storage request]
  (if-let [acct (current-account storage request)]
    (if (core/disable-totp! storage (str (:id acct)) (:code (req/read-json-body request)))
      (json-resp 200 {:ok true})
      (json-resp 400 {:ok false :error "invalid_code"}))
    (json-resp 401 {:ok false :error "unauthenticated"})))


(defn- handle-logout
  [storage origin request]
  (core/revoke-token! storage (provider/request-token request))
  (json-resp 200 {:ok true}
             (cookie-str provider/session-cookie "" {:max-age 0 :secure? (https-origin? origin)})))


(defn- handle-logout-all
  "Revoke EVERY session of the signed-in account (sign out everywhere)."
  [storage origin request]
  (if-let [acct (current-account storage request)]
    (do (core/revoke-all-for-account! storage (str (:id acct)))
        (json-resp 200 {:ok true}
                   (cookie-str provider/session-cookie "" {:max-age 0 :secure? (https-origin? origin)})))
    (json-resp 401 {:ok false :error "unauthenticated"})))


(def ^:private trusted-proxy-count
  "How many trusted reverse proxies sit in front of the app, from
   `GRAPHDEN_TRUSTED_PROXIES` (default 0).

   `X-Forwarded-For` is `client, proxyA, proxyB` where each proxy
   APPENDS the peer it saw, so the N rightmost hops are added by the N
   proxies we control and everything to their left is client-supplied.
   With N trusted proxies the real client is the entry N positions from
   the end. **N=0 means the app is directly reachable: the entire
   header is attacker-controlled** and must be ignored — otherwise a
   rotating `X-Forwarded-For:` sails past every per-IP limiter. Cloud
   (behind Caddy) sets `GRAPHDEN_TRUSTED_PROXIES=1`."
  (or (some-> (System/getenv "GRAPHDEN_TRUSTED_PROXIES") parse-long) 0))


(defn- client-ip
  "Best-effort client IP for rate-limiting. Trusts exactly
   `trusted-proxy-count` rightmost `X-Forwarded-For` hops; with 0
   trusted proxies the header is ignored entirely and the socket
   `remote-addr` is used, so a forged header can't defeat the limiter
   on a direct-reachable deploy."
  [request]
  (or (when (pos? trusted-proxy-count)
        (let [hops (some->> (get-in request [:headers "x-forwarded-for"])
                            (#(str/split % #","))
                            (mapv str/trim)
                            (filterv not-empty))]
          (when (>= (count hops) trusted-proxy-count)
            (nth hops (- (count hops) trusted-proxy-count)))))
      (:remote-addr request)
      "unknown"))


(defn- handle-forgot
  "POST /auth/forgot {email} — always 200 with the same body whether or not
   the email exists (no account enumeration); the reset link goes by email."
  [storage mailer email-renderer link-orig request]
  (let [{:keys [email]} (req/read-json-body request)]
    (flows/request-password-reset! storage mailer link-orig email email-renderer)
    (json-resp 200 {:ok true :reset-sent true})))


(defn- handle-reset
  "POST /auth/reset {token password} — consume the emailed token, set the new
   password, sign the account out everywhere."
  [storage request]
  (let [{:keys [token password]} (req/read-json-body request)]
    (if (core/reset-password! storage token password)
      (json-resp 200 {:ok true})
      (json-resp 400 {:ok false :error "invalid_or_expired"}))))


(defn- handle-verify
  [storage origin request]
  (let [token (get (req/parse-query-string (:query-string request)) "token")]
    (if (core/verify-email! storage token)
      (redirect (str origin "/?verified=1"))
      (redirect (str origin "/login?error=verify")))))


(defn- handle-oauth-start
  [oauth-providers origin provider-key]
  (if-let [cfg (get oauth-providers provider-key)]
    (let [state (crypto/random-token)
          redirect-uri (str origin "/auth/" provider-key "/callback")
          url (oauth/authorize-url provider-key cfg redirect-uri state)]
      (redirect url (cookie-str "gd_oauth" state
                                {:max-age oauth-state-max-age-secs :secure? (https-origin? origin)})))
    (redirect (str origin "/login?error=provider_disabled"))))


(defn- handle-oauth-callback
  [storage oauth-providers origin provider-key request]
  (let [q (req/parse-query-string (:query-string request))
        code (get q "code")
        state (get q "state")
        cookie-state (provider/cookie-value request "gd_oauth")
        cfg (get oauth-providers provider-key)]
    (cond
      (nil? cfg) (redirect (str origin "/login?error=provider_disabled"))
      (or (str/blank? code) (str/blank? state) (not= state cookie-state))
      (redirect (str origin "/login?error=oauth_state"))
      :else
      ;; `link-ok? true`: the URL `state`==`gd_oauth` check above IS the
      ;; per-request link intent for OAuth, so a signed-in callback may link.
      (if-let [info (oauth/exchange-code! provider-key cfg code (str origin "/auth/" provider-key "/callback"))]
        (finish-social storage origin request info provider-key true)
        (redirect (str origin "/login?error=oauth_failed"))))))


(defn- handle-telegram-start
  "Plant a short-lived `gd_link_intent` state nonce for a Telegram widget flow
   and hand it back so the client appends it to the callback URL as `state`.
   Auth-agnostic, like the OAuth `/start`: the LINK-vs-LOGIN decision stays at
   the callback — this only proves the callback was initiated from our origin
   in this browser, which is what stops the link-CSRF."
  [origin]
  (let [state (crypto/random-token)]
    (json-resp 200 {:ok true :state state}
               (cookie-str link-intent-cookie state
                           {:max-age oauth-state-max-age-secs :secure? (https-origin? origin)}))))


(defn- handle-telegram
  [storage telegram-cfg origin request]
  (let [q (req/parse-query-string (:query-string request))
        ;; `state` is OUR intent nonce, not part of Telegram's signed payload —
        ;; strip it before the HMAC check (verify-login signs every field but
        ;; `hash`, so leaving it in would break a legitimate signature).
        info (when telegram-cfg
               (telegram/verify-login (:bot-token telegram-cfg) (dissoc q "state")
                                      (quot (System/currentTimeMillis) 1000)))
        ;; LINK only on a matching intent nonce (see `link-intent-cookie`): the
        ;; widget's HMAC proves origin-at-Telegram, not that THIS signed-in user
        ;; asked to attach it. A bare callback carrying the victim's Lax
        ;; `gd_session` fails this check → `finish-social` refuses to link.
        url-state (get q "state")
        cookie-state (provider/cookie-value request link-intent-cookie)
        link-ok? (and (not (str/blank? url-state)) (= url-state cookie-state))]
    (if info
      (finish-social storage origin request info "telegram" link-ok?)
      (redirect (str origin "/login?error=telegram")))))


(defn make-router
  "Build the `/auth/*` Ring router. `opts`:
   `:storage` `:mailer` `:app-origin` (nil ⇒ derive from Host),
   `:oauth-providers` `{\"github\" {:client-id :client-secret} …}` (enabled only),
   `:telegram` `{:bot-token …}` or nil,
   `:page-renderer` (optional) `(fn [page-kw args] → html-or-nil)` — the graph
   render seam (`app.auth-pages`); nil / throw ⇒ built-in pages,
   `:email-renderer` (optional) `(fn [kind base-url token] → {:subject …}-or-nil)`
   — same seam for the transactional email bodies."
  [{:keys [storage mailer app-origin oauth-providers telegram
           page-renderer email-renderer]}]
  (let [provider-keys (set (keys oauth-providers))
        ;; The graph pages take providers as a {\"github\" true} map — a
        ;; JSONB-friendly membership map for the graph `:contains?`.
        provider-map (into {} (map (fn [p] [p true])) provider-keys)
        ;; Per-IP fixed-window limiters: login blunts password brute-force
        ;; (bcrypt slows but doesn't bound attempts), signup blunts
        ;; mass-account abuse, forgot blunts reset-mail spam. Over-quota →
        ;; the same shape as failure (401/generic 200) so the limiter's
        ;; existence isn't probeable.
        login-limit (crypto/fixed-window-limiter 10 60000)
        signup-limit (crypto/fixed-window-limiter 20 60000)
        forgot-limit (crypto/fixed-window-limiter 5 60000)]
    (fn [request]
      (let [uri (str (:uri request))
            method (:request-method request)]
        (when (or (str/starts-with? uri "/auth/") (= uri "/login") (= uri "/account") (= uri "/reset"))
          (let [origin (resolve-origin app-origin request)
                ;; Trusted origin for EMAILED links only — never a
                ;; poisonable public Host (M5). Blank ⇒ the sender
                ;; refuses rather than email an attacker's URL.
                link-orig (link-origin app-origin request)]
            (cond
              (and (= method :get) (= uri "/login"))
              (html-resp (graph-page page-renderer :login
                                     {:providers provider-map :telegram telegram}
                                     #(pages/login-page provider-keys telegram)))

              ;; The graph :account page (editor present) redirects into the
              ;; editor's Settings → Account card and takes no args; the
              ;; built-in fallback stays the full standalone page (headless).
              (and (= method :get) (= uri "/account"))
              (html-resp (graph-page page-renderer :account
                                     {}
                                     #(pages/account-page provider-keys)))

              (and (= method :get) (= uri "/reset"))
              (html-resp (graph-page page-renderer :reset {}
                                     #(pages/reset-page)))

              (and (= method :get) (= uri "/auth/me"))
              (handle-me storage request)

              ;; Enabled oauth providers, for clients that render link/sign-in
              ;; buttons OUTSIDE the served pages (the editor's Account card).
              ;; Public by design — /login exposes the same set in its HTML.
              (and (= method :get) (= uri "/auth/providers"))
              (json-resp 200 {:ok true :providers provider-map})

              (and (= method :get) (= uri "/auth/tfa-state"))
              (handle-tfa-state storage request)

              (and (= method :get) (= uri "/auth/verify"))
              (handle-verify storage origin request)

              (and (= method :post) (= uri "/auth/signup"))
              (if (signup-limit (client-ip request))
                (handle-signup storage mailer email-renderer origin link-orig request)
                (json-resp 429 {:ok false :error "rate_limited"}))

              (and (= method :post) (= uri "/auth/login"))
              (if (login-limit (client-ip request))
                (handle-login storage origin request)
                ;; same shape as bad credentials — not probeable
                (json-resp 401 {:ok false :error "invalid_credentials"}))

              (and (= method :post) (= uri "/auth/forgot"))
              (if (forgot-limit (client-ip request))
                (handle-forgot storage mailer email-renderer link-orig request)
                (json-resp 200 {:ok true :reset-sent true}))

              (and (= method :post) (= uri "/auth/reset"))
              (handle-reset storage request)

              (and (= method :post) (= uri "/auth/resend-verification"))
              (if (forgot-limit (client-ip request))
                (handle-resend-verification storage mailer email-renderer link-orig request)
                (json-resp 200 {:ok true}))

              (and (= method :post) (= uri "/auth/logout"))
              (handle-logout storage origin request)

              (and (= method :post) (= uri "/auth/logout-all"))
              (handle-logout-all storage origin request)

              (and (= method :get) (= uri "/auth/identities"))
              (handle-identities storage request)

              (and (= method :post) (= uri "/auth/unlink"))
              (handle-unlink storage request)

              (and (= method :post) (= uri "/auth/totp"))
              (handle-totp storage origin request)

              (and (= method :post) (= uri "/auth/totp/enroll"))
              (handle-totp-enroll storage request)

              (and (= method :post) (= uri "/auth/totp/confirm"))
              (handle-totp-confirm storage request)

              (and (= method :post) (= uri "/auth/totp/disable"))
              (handle-totp-disable storage request)

              (and (= method :get) (= uri "/auth/telegram/start"))
              (if telegram
                (handle-telegram-start origin)
                (json-resp 404 {:ok false :error "telegram_disabled"}))

              (and (= method :get) (= uri "/auth/telegram/callback"))
              (handle-telegram storage telegram origin request)

              :else
              (when-let [[_ pk action] (re-matches #"/auth/(github|google)/(start|callback)" uri)]
                (when (= method :get)
                  (case action
                    "start" (handle-oauth-start oauth-providers origin pk)
                    "callback" (handle-oauth-callback storage oauth-providers origin pk request)))))))))))
