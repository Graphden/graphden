(ns graphden.accounts.two-factor-test
  "Integration test for the full 2FA flow through the /auth/* router against a
   real Postgres: enroll → confirm → password login now demands a code →
   complete-2fa → session; wrong code fails; disable turns it back off. Tagged
   `:integration`."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.accounts.account-schema :as account-schema]
    [graphden.accounts.core :as core]
    [graphden.accounts.email :as email]
    [graphden.accounts.identity-schema :as identity-schema]
    [graphden.accounts.routes :as routes]
    [graphden.accounts.session-schema :as session-schema]
    [graphden.accounts.totp :as totp]
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


(defn- cookie-token
  [resp cookie-name]
  (some->> (get-in resp [:headers "Set-Cookie"])
           (re-find (re-pattern (str cookie-name "=([^;]+)")))
           second
           not-empty))


(defn- code-now
  [secret]
  (totp/code-at secret (quot (System/currentTimeMillis) 1000)))


(deftest ^:integration full-2fa-login-flow
  (let [router (routes/make-router {:storage (storage) :mailer (email/->CapturingMailer (atom []))
                                    :app-origin origin})
        session (cookie-token (router {:request-method :post :uri "/auth/signup"
                                       :body (json/generate-string {:email "2fa@example.com" :password "pw-2fa-123"})})
                              "gd_session")
        auth {"cookie" (str "gd_session=" session)}]
    (testing "enroll returns a secret + otpauth URI; account not yet enabled"
      (let [body (json/parse-string (:body (router {:request-method :post :uri "/auth/totp/enroll" :headers auth})) true)]
        (is (:ok body))
        (is (str/starts-with? (:otpauth-uri body) "otpauth://totp/Graphden:"))
        (is (false? (core/totp-enabled? (core/authenticate-token (storage) session))))
        (testing "a wrong code does not confirm"
          (is (= 400 (:status (router {:request-method :post :uri "/auth/totp/confirm"
                                       :headers auth :body (json/generate-string {:code "000000"})})))))
        (testing "the right code activates 2FA"
          (is (= 200 (:status (router {:request-method :post :uri "/auth/totp/confirm"
                                       :headers auth :body (json/generate-string {:code (code-now (:secret body))})}))))
          (is (true? (core/totp-enabled? (core/authenticate-token (storage) session)))))
        (testing "password login now demands a second factor (no session yet)"
          (let [resp (router {:request-method :post :uri "/auth/login"
                              :body (json/generate-string {:email "2fa@example.com" :password "pw-2fa-123"})})
                pending (cookie-token resp "gd_2fa")]
            (is (true? (:totp-required (json/parse-string (:body resp) true))))
            (is (nil? (cookie-token resp "gd_session")) "no full session before the code")
            (is (some? pending))
            (testing "a wrong code is rejected AND consumes the pending token (M2:
                      single-use — no brute-force against a held token)"
              (is (= 401 (:status (router {:request-method :post :uri "/auth/totp"
                                           :headers {"cookie" (str "gd_2fa=" pending)}
                                           :body (json/generate-string {:code "000000"})}))))
              (is (= 401 (:status (router {:request-method :post :uri "/auth/totp"
                                           :headers {"cookie" (str "gd_2fa=" pending)}
                                           :body (json/generate-string {:code (code-now (:secret body))})})))
                  "even the CORRECT code fails now — the wrong attempt burned the token"))
            (testing "a fresh login mints a new pending token; the correct code completes it"
              (let [resp2 (router {:request-method :post :uri "/auth/login"
                                   :body (json/generate-string {:email "2fa@example.com" :password "pw-2fa-123"})})
                    pending2 (cookie-token resp2 "gd_2fa")
                    ok (router {:request-method :post :uri "/auth/totp"
                                :headers {"cookie" (str "gd_2fa=" pending2)}
                                :body (json/generate-string {:code (code-now (:secret body))})})]
                (is (= 200 (:status ok)))
                (is (some? (core/authenticate-token (storage) (cookie-token ok "gd_session"))))))))
        (testing "disable with a valid code turns 2FA off; login is single-factor again"
          (is (= 200 (:status (router {:request-method :post :uri "/auth/totp/disable"
                                       :headers auth :body (json/generate-string {:code (code-now (:secret body))})}))))
          (is (false? (core/totp-enabled? (core/authenticate-token (storage) session))))
          (let [resp (router {:request-method :post :uri "/auth/login"
                              :body (json/generate-string {:email "2fa@example.com" :password "pw-2fa-123"})})]
            (is (some? (cookie-token resp "gd_session")) "single-factor session returns directly")))))))


(deftest ^:integration totp-reenroll-refused-when-already-enabled
  ;; M1 regression: re-enrolling must not silently strip an existing
  ;; second factor — the old body wrote :totp-enabled? false without
  ;; proof of the current device.
  (let [router (routes/make-router {:storage (storage) :mailer (email/->CapturingMailer (atom []))
                                    :app-origin origin})
        session (cookie-token (router {:request-method :post :uri "/auth/signup"
                                       :body (json/generate-string {:email "reenroll@example.com" :password "pw-reenroll-1"})})
                              "gd_session")
        auth {"cookie" (str "gd_session=" session)}
        body (json/parse-string (:body (router {:request-method :post :uri "/auth/totp/enroll" :headers auth})) true)]
    (router {:request-method :post :uri "/auth/totp/confirm"
             :headers auth :body (json/generate-string {:code (code-now (:secret body))})})
    (is (true? (core/totp-enabled? (core/authenticate-token (storage) session))))
    (testing "a second enroll is refused with 409 and 2FA stays enabled"
      (let [resp (router {:request-method :post :uri "/auth/totp/enroll" :headers auth})]
        (is (= 409 (:status resp)))
        (is (= "totp_already_enabled" (:error (json/parse-string (:body resp) true))))
        (is (true? (core/totp-enabled? (core/authenticate-token (storage) session)))
            "the existing second factor is intact — not silently disabled")))))
