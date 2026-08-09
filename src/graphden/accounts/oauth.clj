(ns graphden.accounts.oauth
  "OAuth 2.0 Authorization-Code sign-in for GitHub and Google. Both share the
   generic code flow; the per-provider difference is only the endpoints and how
   the userinfo is normalized to `{:provider :subject :email :email-verified?
   :display-name}` — the shape `accounts.core/resolve-social-identity!` consumes.

   CSRF is handled at the route layer with a `state` value echoed through a
   short-lived cookie (no server secret, no entity). This namespace is pure OAuth
   plumbing: build the authorize URL, exchange the code, fetch + normalize the
   identity. Outbound calls use http-kit to fixed provider hosts."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [org.httpkit.client :as http])
  (:import
    (java.net
      URLEncoder)))


(def providers
  {"github" {:authorize-url "https://github.com/login/oauth/authorize"
             :token-url "https://github.com/login/oauth/access_token"
             :scope "read:user user:email"}
   "google" {:authorize-url "https://accounts.google.com/o/oauth2/v2/auth"
             :token-url "https://oauth2.googleapis.com/token"
             :scope "openid email profile"
             :authorize-extra {"access_type" "online" "prompt" "select_account"}}})


(defn provider?
  [k]
  (contains? providers k))


(defn- enc
  [s]
  (URLEncoder/encode (str s) "UTF-8"))


(defn- query-string
  [m]
  (str/join "&" (map (fn [[k v]] (str (enc k) "=" (enc v))) m)))


(defn authorize-url
  "The provider's authorize URL for `redirect-uri` + CSRF `state`."
  [provider-key {:keys [client-id]} redirect-uri state]
  (let [p (get providers provider-key)
        params (merge {"client_id" client-id
                       "redirect_uri" redirect-uri
                       "scope" (:scope p)
                       "state" state
                       "response_type" "code"}
                      (:authorize-extra p))]
    (str (:authorize-url p) "?" (query-string params))))


;; --- identity normalizers (pure — unit-tested directly) ---

(defn normalize-github
  "GitHub `/user` + `/user/emails` → the identity shape. Prefers the primary
   email, falling back to any verified one; `email-verified?` follows GitHub's
   own `verified` flag on the chosen address."
  [user emails]
  (when user
    (let [chosen (or (first (filter :primary emails))
                     (first (filter :verified emails)))]
      {:provider "github"
       :subject (str (:id user))
       :email (:email chosen)
       :email-verified? (boolean (:verified chosen))
       :display-name (or (not-empty (str (:name user))) (:login user))})))


(defn normalize-google
  "Google OIDC userinfo → the identity shape."
  [info]
  (when (:sub info)
    {:provider "google"
     :subject (:sub info)
     :email (:email info)
     :email-verified? (boolean (:email_verified info))
     :display-name (:name info)}))


;; --- HTTP plumbing ---

(defn- get-json
  [url headers]
  (let [resp @(http/get url {:headers headers :timeout 10000})]
    (when (and (:status resp) (< (:status resp) 300))
      (json/parse-string (:body resp) true))))


(defn- post-form-json
  [url form]
  (let [resp @(http/post url {:headers {"Accept" "application/json"
                                        "Content-Type" "application/x-www-form-urlencoded"}
                              :timeout 10000
                              :body (query-string form)})]
    (when (and (:status resp) (< (:status resp) 300))
      (json/parse-string (:body resp) true))))


(defn- github-identity
  [access-token]
  (let [auth {"Authorization" (str "Bearer " access-token)
              "User-Agent" "graphden"
              "Accept" "application/vnd.github+json"}]
    (normalize-github (get-json "https://api.github.com/user" auth)
                      (get-json "https://api.github.com/user/emails" auth))))


(defn- google-identity
  [access-token]
  (normalize-google (get-json "https://openidconnect.googleapis.com/v1/userinfo"
                              {"Authorization" (str "Bearer " access-token)})))


(defn exchange-code!
  "Exchange an authorization `code` for the provider's normalized identity map,
   or nil on failure. `config` = `{:client-id :client-secret}`."
  [provider-key {:keys [client-id client-secret]} code redirect-uri]
  (try
    (let [p (get providers provider-key)
          tokens (post-form-json (:token-url p)
                                 {"client_id" client-id "client_secret" client-secret
                                  "code" code "redirect_uri" redirect-uri
                                  "grant_type" "authorization_code"})]
      (when-let [access-token (:access_token tokens)]
        (case provider-key
          "github" (github-identity access-token)
          "google" (google-identity access-token)
          nil)))
    (catch Exception e
      (log/warn e "OAuth code exchange failed" {:provider provider-key})
      nil)))
