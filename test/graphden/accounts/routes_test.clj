(ns graphden.accounts.routes-test
  "Integration tests for the /auth/* Ring router against a real Postgres:
   password signup/login/logout with the gd_session cookie, the email-verify
   redirect, OAuth start (redirect + state cookie) and callback (state check +
   with-redefed exchange → session), and the Telegram widget callback. Tagged
   `:integration`."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.accounts.account-schema :as account-schema]
    [graphden.accounts.core :as core]
    [graphden.accounts.crypto :as crypto]
    [graphden.accounts.email :as email]
    [graphden.accounts.identity-schema :as identity-schema]
    [graphden.accounts.oauth :as oauth]
    [graphden.accounts.routes :as routes]
    [graphden.accounts.session-schema :as session-schema]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.shared-container :as sc]))


(def ^:dynamic *container* nil)
(def ^:private storage-atom (atom nil))
(def ^:private origin "https://app.graphden.dev")


(defn- accounts-schema
  []
  (-> (mds/create-builder)
      (account-schema/extend-builder)
      (identity-schema/extend-builder)
      (session-schema/extend-builder)
      (ds/build)))


(defn- with-schema
  [f]
  (let [s (pg/create-storage (sc/get-config))]
    (sp/initialize s (accounts-schema))
    (reset! storage-atom s)
    (f)))


(use-fixtures :once
  (sc/shared-container-fixture #'*container*)
  with-schema)


(defn- storage
  []
  @storage-atom)


(defn- set-cookie-token
  "Extract the gd_session value from a response's Set-Cookie, or nil."
  [resp]
  (some->> (get-in resp [:headers "Set-Cookie"])
           (re-find #"gd_session=([^;]+)")
           second
           not-empty))


(deftest ^:integration password-signup-login-logout-through-http
  (let [sink (atom [])
        router (routes/make-router {:storage (storage) :mailer (email/->CapturingMailer sink)
                                    :app-origin origin})]
    (testing "signup sets a working session cookie + fires a verification email"
      (let [resp (router {:request-method :post :uri "/auth/signup"
                          :body (json/generate-string {:email "rt-a@example.com" :password "pw-rt-a-123"})})
            token (set-cookie-token resp)]
        (is (= 200 (:status resp)))
        (is (str/includes? (get-in resp [:headers "Set-Cookie"]) "HttpOnly"))
        (is (str/includes? (get-in resp [:headers "Set-Cookie"]) "Secure"))
        (is (some? (core/authenticate-token (storage) token)) "cookie session authenticates")
        (is (= 1 (count @sink)) "a verification email was sent")))
    (testing "login returns a fresh session; wrong password 401s"
      (let [ok (router {:request-method :post :uri "/auth/login"
                        :body (json/generate-string {:email "rt-a@example.com" :password "pw-rt-a-123"})})]
        (is (= 200 (:status ok)))
        (is (some? (core/authenticate-token (storage) (set-cookie-token ok)))))
      (is (= 401 (:status (router {:request-method :post :uri "/auth/login"
                                   :body (json/generate-string {:email "rt-a@example.com" :password "nope"})})))))
    (testing "logout revokes the presented session and clears the cookie"
      (let [token (set-cookie-token (router {:request-method :post :uri "/auth/login"
                                             :body (json/generate-string {:email "rt-a@example.com" :password "pw-rt-a-123"})}))
            resp (router {:request-method :post :uri "/auth/logout"
                          :headers {"cookie" (str "gd_session=" token)}})]
        (is (= 200 (:status resp)))
        (is (str/includes? (get-in resp [:headers "Set-Cookie"]) "Max-Age=0"))
        (is (nil? (core/authenticate-token (storage) token)) "session revoked server-side")))
    (testing "logout-all kills every session of the account"
      (let [login #(set-cookie-token (router {:request-method :post :uri "/auth/login"
                                              :body (json/generate-string {:email "rt-a@example.com" :password "pw-rt-a-123"})}))
            t1 (login)
            t2 (login)]
        (is (= 200 (:status (router {:request-method :post :uri "/auth/logout-all"
                                     :headers {"cookie" (str "gd_session=" t1)}}))))
        (is (nil? (core/authenticate-token (storage) t1)))
        (is (nil? (core/authenticate-token (storage) t2)) "the OTHER session died too")
        (is (= 401 (:status (router {:request-method :post :uri "/auth/logout-all"})))
            "logout-all requires auth")))))


(deftest ^:integration verify-link-through-http
  (let [sink (atom [])
        router (routes/make-router {:storage (storage) :mailer (email/->CapturingMailer sink)
                                    :app-origin origin})]
    (router {:request-method :post :uri "/auth/signup"
             :body (json/generate-string {:email "rt-verify@example.com" :password "pw-rt-v-123"})})
    (let [link-token (-> (re-find #"/auth/verify\?token=([^\s\"]+)" (:text (first @sink))) second)
          resp (router {:request-method :get :uri "/auth/verify" :query-string (str "token=" link-token)})]
      (is (= 302 (:status resp)))
      (is (= (str origin "/?verified=1") (get-in resp [:headers "Location"])))
      (is (= "rt-verify@example.com" (:primary-email (core/account-by-email (storage) "rt-verify@example.com")))))))


(deftest ^:integration oauth-start-and-callback
  (let [router (routes/make-router {:storage (storage) :mailer (email/->CapturingMailer (atom []))
                                    :app-origin origin
                                    :oauth-providers {"github" {:client-id "gh-cid" :client-secret "gh-sec"}}})]
    (testing "start 302s to the provider and plants a state cookie"
      (let [resp (router {:request-method :get :uri "/auth/github/start"})
            sc (get-in resp [:headers "Set-Cookie"])]
        (is (= 302 (:status resp)))
        (is (str/starts-with? (get-in resp [:headers "Location"]) "https://github.com/login/oauth/authorize?"))
        (is (str/includes? sc "gd_oauth="))))
    (testing "callback with a mismatched state is rejected"
      (let [resp (router {:request-method :get :uri "/auth/github/callback"
                          :query-string "code=abc&state=WRONG"
                          :headers {"cookie" "gd_oauth=RIGHT"}})]
        (is (= 302 (:status resp)))
        (is (str/includes? (get-in resp [:headers "Location"]) "error=oauth_state"))))
    (testing "callback with a good state exchanges → creates account → sets session"
      (with-redefs [oauth/exchange-code! (fn [_ _ _ _]
                                           {:provider "github" :subject "gh-777"
                                            :email "octo@example.com" :email-verified? true
                                            :display-name "Octo"})]
        (let [resp (router {:request-method :get :uri "/auth/github/callback"
                            :query-string "code=goodcode&state=S1"
                            :headers {"cookie" "gd_oauth=S1"}})
              token (set-cookie-token resp)]
          (is (= 302 (:status resp)))
          (is (= (str origin "/") (get-in resp [:headers "Location"])))
          (is (some? (core/authenticate-token (storage) token)))
          (let [ident (core/find-identity (storage) "github" "gh-777")]
            (is (= "octo@example.com" (:primary-email (core/account-of (storage) (:account-id ident))))
                "a verified GitHub email is promoted to primary-email")))))
    (testing "a disabled provider is rejected"
      (is (str/includes? (get-in (router {:request-method :get :uri "/auth/google/start"}) [:headers "Location"])
                         "error=provider_disabled")))))


(deftest ^:integration telegram-callback
  (let [bot "123:TG-BOTTOKEN"
        router (routes/make-router {:storage (storage) :mailer (email/->CapturingMailer (atom []))
                                    :app-origin origin :telegram {:bot-token bot}})
        auth-date (str (quot (System/currentTimeMillis) 1000))
        base {"id" "55" "first_name" "Tess" "username" "tess" "auth_date" auth-date}
        check (->> base (sort-by key) (map (fn [[k v]] (str k "=" v))) (str/join "\n"))
        h (crypto/hmac-sha256-hex (crypto/sha256-bytes bot) check)
        qs (str/join "&" (map (fn [[k v]] (str k "=" v)) (assoc base "hash" h)))]
    (testing "a valid widget payload logs in / creates the telegram account"
      (let [resp (router {:request-method :get :uri "/auth/telegram/callback" :query-string qs})
            token (set-cookie-token resp)]
        (is (= 302 (:status resp)))
        (is (some? (core/authenticate-token (storage) token)))
        (is (some? (core/find-identity (storage) "telegram" "55")))))
    (testing "a tampered payload is rejected"
      (let [bad (str/replace qs #"first_name=Tess" "first_name=Eve")
            resp (router {:request-method :get :uri "/auth/telegram/callback" :query-string bad})]
        (is (str/includes? (get-in resp [:headers "Location"]) "error=telegram"))))))


(deftest ^:integration linking-identities-through-http
  (let [router (routes/make-router {:storage (storage) :mailer (email/->CapturingMailer (atom []))
                                    :app-origin origin
                                    :oauth-providers {"github" {:client-id "c" :client-secret "s"}}})
        session (set-cookie-token (router {:request-method :post :uri "/auth/signup"
                                           :body (json/generate-string {:email "linkb@example.com" :password "pw-linkb-123"})}))]
    (testing "a signed-in user's OAuth callback LINKS the identity (no new account)"
      (with-redefs [oauth/exchange-code! (fn [_ _ _ _]
                                           {:provider "github" :subject "gh-link-1"
                                            :email "linkb@example.com" :email-verified? true :display-name "LB"})]
        (let [resp (router {:request-method :get :uri "/auth/github/callback"
                            :query-string "code=c&state=S"
                            :headers {"cookie" (str "gd_session=" session "; gd_oauth=S")}})]
          (is (str/includes? (get-in resp [:headers "Location"]) "/settings?linked=github"))
          (is (= (str (:id (core/authenticate-token (storage) session)))
                 (:account-id (core/find-identity (storage) "github" "gh-link-1")))
              "github attached to the signed-in account"))))
    (testing "GET /auth/identities lists the linked providers (no secrets)"
      (let [body (json/parse-string (:body (router {:request-method :get :uri "/auth/identities"
                                                    :headers {"cookie" (str "gd_session=" session)}}))
                                    true)]
        (is (= #{"password" "github"} (set (map :provider (:identities body)))))
        (is (not-any? :secret-data (:identities body)))))
    (testing "unlink github; unlinking the LAST identity is refused"
      (is (= 200 (:status (router {:request-method :post :uri "/auth/unlink"
                                   :headers {"cookie" (str "gd_session=" session)}
                                   :body (json/generate-string {:provider "github"})}))))
      (is (= 409 (:status (router {:request-method :post :uri "/auth/unlink"
                                   :headers {"cookie" (str "gd_session=" session)}
                                   :body (json/generate-string {:provider "password"})})))
          "can't remove the only remaining sign-in method"))
    (testing "linking an identity owned by ANOTHER account is a conflict"
      (let [mk (fn []
                 {:provider "github" :subject "gh-other" :email "othera@example.com"
                  :email-verified? true :display-name "A"})]
        (with-redefs [oauth/exchange-code! (fn [_ _ _ _] (mk))]
          (router {:request-method :get :uri "/auth/github/callback"
                   :query-string "code=c&state=S" :headers {"cookie" "gd_oauth=S"}}))
        (with-redefs [oauth/exchange-code! (fn [_ _ _ _] (mk))]
          (let [resp (router {:request-method :get :uri "/auth/github/callback"
                              :query-string "code=c&state=S"
                              :headers {"cookie" (str "gd_session=" session "; gd_oauth=S")}})]
            (is (str/includes? (get-in resp [:headers "Location"]) "error=identity_conflict"))))))
    (testing "the identities API requires auth"
      (is (= 401 (:status (router {:request-method :get :uri "/auth/identities"})))))))


(deftest ^:integration serves-login-and-account-pages
  (let [router (routes/make-router {:storage (storage) :mailer (email/->CapturingMailer (atom []))
                                    :app-origin origin
                                    :oauth-providers {"github" {:client-id "c" :client-secret "s"}}})]
    (testing "GET /login renders HTML with the password form + enabled social button"
      (let [resp (router {:request-method :get :uri "/login"})]
        (is (= 200 (:status resp)))
        (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/html"))
        (is (str/includes? (:body resp) "Sign in"))
        (is (str/includes? (:body resp) "/auth/github/start"))))
    (testing "GET /account renders HTML"
      (is (= 200 (:status (router {:request-method :get :uri "/account"})))))
    (testing "GET /auth/me needs auth and returns the account when signed in"
      (is (= 401 (:status (router {:request-method :get :uri "/auth/me"}))))
      (let [session (set-cookie-token (router {:request-method :post :uri "/auth/signup"
                                               :body (json/generate-string {:email "me@example.com" :password "pw-me-123"})}))
            body (json/parse-string (:body (router {:request-method :get :uri "/auth/me"
                                                    :headers {"cookie" (str "gd_session=" session)}}))
                                    true)]
        (is (:ok body))
        (is (some? (get-in body [:account :id])))
        (is (nil? (get-in body [:account :email])) "unverified email is not yet primary")))))


(deftest ^:integration non-auth-path-falls-through
  (let [router (routes/make-router {:storage (storage) :mailer (email/->CapturingMailer (atom []))
                                    :app-origin origin})]
    (is (nil? (router {:request-method :get :uri "/editor"}))
        "a non-/auth path returns nil so the seam falls through")))
