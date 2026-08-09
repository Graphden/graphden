(ns graphden.accounts.routes
  "The `/auth/*` HTTP surface for the accounts module, installed as a plain Ring
   router through the route-collection seam (runs before the graph app-router,
   inside the request scope). Returns a response on a matched `/auth/...` path,
   or nil to fall through.

   Endpoints:
     POST /auth/signup             {email,password} → set gd_session, send verify
     POST /auth/login              {email,password} → set gd_session
     POST /auth/logout             revoke session + clear cookie
     GET  /auth/verify?token=      consume email-verification link → redirect
     GET  /auth/:provider/start    (github|google) 302 to the provider + state cookie
     GET  /auth/:provider/callback (github|google) exchange → session → redirect
     GET  /auth/telegram/callback  verify widget HMAC → session → redirect

   Sessions are delivered as an HttpOnly `gd_session` cookie. There is no ring
   cookie middleware in the chain, so cookies are set as `Set-Cookie` headers
   directly (a string header key survives the encode wrap above the seam)."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [graphden.accounts.core :as core]
    [graphden.accounts.crypto :as crypto]
    [graphden.accounts.flows :as flows]
    [graphden.accounts.oauth :as oauth]
    [graphden.accounts.provider :as provider]
    [graphden.accounts.telegram :as telegram]
    [graphden.crud.request :as req]))


(def ^:private session-max-age-secs (* 24 60 60))
(def ^:private oauth-state-max-age-secs 600)


(defn- https-origin?
  [origin]
  (str/starts-with? (str origin) "https"))


(defn- resolve-origin
  "The public origin for redirect URIs + links: the configured value, else
   derived from the request Host (https assumed — prod is TLS-terminated)."
  [configured request]
  (if (str/blank? (str configured))
    (str "https://" (get-in request [:headers "host"]))
    configured))


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


;; --- handlers ---

(defn- current-account
  "The signed-in account for this request (bearer or gd_session), or nil."
  [storage request]
  (core/authenticate-token storage (provider/request-token request)))


(defn- finish-social
  "Common tail for an OAuth/Telegram callback that produced a normalized
   identity `info`: if the request already carries a session, LINK the identity
   to that account (or report a conflict) and stay signed in; otherwise log in /
   create and set a fresh session."
  [storage origin request info provider-key]
  (if-let [acct (current-account storage request)]
    (try
      (core/link-identity! storage (str (:id acct)) info)
      (redirect (str origin "/settings?linked=" provider-key))
      (catch clojure.lang.ExceptionInfo _
        (redirect (str origin "/settings?error=identity_conflict"))))
    (let [{:keys [account-id]} (core/resolve-social-identity! storage info)]
      (redirect (str origin "/") (session-cookie (core/mint-session! storage account-id) origin)))))


(defn- handle-identities
  [storage request]
  (if-let [acct (current-account storage request)]
    (json-resp 200 {:ok true
                    :identities (mapv #(select-keys % [:provider :email :email-verified? :created-at])
                                      (core/identities-for-account storage (str (:id acct))))})
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
  [storage mailer origin request]
  (let [{:keys [email password]} (req/read-json-body request)]
    (try
      (let [{:keys [account-id token]} (core/password-signup! storage {:email email :password password})]
        (flows/request-verification! storage mailer origin account-id email)
        (json-resp 200 {:ok true :verification-sent true} (session-cookie token origin)))
      (catch clojure.lang.ExceptionInfo e
        (json-resp 409 {:ok false :error (name (or (:type (ex-data e)) :error))})))))


(defn- handle-login
  [storage origin request]
  (let [{:keys [email password]} (req/read-json-body request)]
    (if-let [{:keys [token]} (core/password-login! storage {:email email :password password})]
      (json-resp 200 {:ok true} (session-cookie token origin))
      (json-resp 401 {:ok false :error "invalid_credentials"}))))


(defn- handle-logout
  [storage origin request]
  (core/revoke-token! storage (provider/request-token request))
  (json-resp 200 {:ok true}
             (cookie-str provider/session-cookie "" {:max-age 0 :secure? (https-origin? origin)})))


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
      (if-let [info (oauth/exchange-code! provider-key cfg code (str origin "/auth/" provider-key "/callback"))]
        (finish-social storage origin request info provider-key)
        (redirect (str origin "/login?error=oauth_failed"))))))


(defn- handle-telegram
  [storage telegram-cfg origin request]
  (let [q (req/parse-query-string (:query-string request))
        info (when telegram-cfg
               (telegram/verify-login (:bot-token telegram-cfg) q
                                      (quot (System/currentTimeMillis) 1000)))]
    (if info
      (finish-social storage origin request info "telegram")
      (redirect (str origin "/login?error=telegram")))))


(defn make-router
  "Build the `/auth/*` Ring router. `opts`:
   `:storage` `:mailer` `:app-origin` (nil ⇒ derive from Host),
   `:oauth-providers` `{\"github\" {:client-id :client-secret} …}` (enabled only),
   `:telegram` `{:bot-token …}` or nil."
  [{:keys [storage mailer app-origin oauth-providers telegram]}]
  (fn [request]
    (let [uri (str (:uri request))
          method (:request-method request)]
      (when (str/starts-with? uri "/auth/")
        (let [origin (resolve-origin app-origin request)]
          (cond
            (and (= method :get) (= uri "/auth/verify"))
            (handle-verify storage origin request)

            (and (= method :post) (= uri "/auth/signup"))
            (handle-signup storage mailer origin request)

            (and (= method :post) (= uri "/auth/login"))
            (handle-login storage origin request)

            (and (= method :post) (= uri "/auth/logout"))
            (handle-logout storage origin request)

            (and (= method :get) (= uri "/auth/identities"))
            (handle-identities storage request)

            (and (= method :post) (= uri "/auth/unlink"))
            (handle-unlink storage request)

            (and (= method :get) (= uri "/auth/telegram/callback"))
            (handle-telegram storage telegram origin request)

            :else
            (when-let [[_ pk action] (re-matches #"/auth/(github|google)/(start|callback)" uri)]
              (when (= method :get)
                (case action
                  "start" (handle-oauth-start oauth-providers origin pk)
                  "callback" (handle-oauth-callback storage oauth-providers origin pk request))))))))))
