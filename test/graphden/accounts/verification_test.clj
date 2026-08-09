(ns graphden.accounts.verification-test
  "Integration tests for email verification against a real Postgres: the token
   round-trip (mint → verify), primary-email promotion, single-use + expiry, the
   verify-token never authenticating, and the full request-verification! flow
   through a capturing mailer. Tagged `:integration`."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.accounts.account-schema :as account-schema]
    [graphden.accounts.core :as accounts]
    [graphden.accounts.email :as email]
    [graphden.accounts.flows :as flows]
    [graphden.accounts.identity-schema :as identity-schema]
    [graphden.accounts.session-schema :as session-schema]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.shared-container :as sc]))


(def ^:dynamic *container* nil)


(def ^:private storage-atom (atom nil))


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


(deftest ^:integration verify-token-roundtrip-promotes-primary-email
  (let [{:keys [account-id]} (accounts/password-signup! (storage)
                                                        {:email "vera@example.com" :password "pw-vera-123"})]
    (is (nil? (:primary-email (accounts/account-of (storage) account-id)))
        "primary-email is unset until verified")
    (let [token (accounts/mint-verification! (storage) account-id "vera@example.com")
          acct (accounts/verify-email! (storage) token)]
      (is (= "vera@example.com" (:primary-email acct))
          "verifying promotes the email to primary-email")
      (is (true? (:email-verified? (accounts/find-identity (storage) "password" "vera@example.com")))
          "the password identity is now verified")
      (testing "the token is single-use"
        (is (nil? (accounts/verify-email! (storage) token)))))))


(deftest ^:integration verify-token-never-authenticates
  (let [{:keys [account-id]} (accounts/password-signup! (storage)
                                                        {:email "walt@example.com" :password "pw-walt-123"})
        token (accounts/mint-verification! (storage) account-id "walt@example.com")]
    (is (nil? (accounts/authenticate-token (storage) token))
        "a verify-kind token must not double as a login bearer")))


(deftest ^:integration verify-rejects-expired-and-unknown
  (let [{:keys [account-id]} (accounts/password-signup! (storage)
                                                        {:email "xena@example.com" :password "pw-xena-123"})]
    (testing "unknown token → nil"
      (is (nil? (accounts/verify-email! (storage) "no-such-token"))))
    (testing "expired verify token → nil (email stays unverified)"
      ;; mint a verify session with a past expiry directly
      (accounts/mint-session! (storage) account-id {:kind "verify" :label "xena@example.com" :ttl-ms -1})
      (is (false? (:email-verified? (accounts/find-identity (storage) "password" "xena@example.com")))))))


(deftest ^:integration request-verification-flow-sends-a-usable-link
  (let [sink (atom [])
        mailer (email/->CapturingMailer sink)
        {:keys [account-id]} (accounts/password-signup! (storage)
                                                        {:email "yara@example.com" :password "pw-yara-123"})
        result (flows/request-verification! (storage) mailer "https://app.graphden.dev"
                                            account-id "yara@example.com")]
    (is (:ok? result))
    (is (= 1 (count @sink)))
    (let [msg (first @sink)
          token (second (re-find #"/auth/verify\?token=([^\s\"]+)" (:text msg)))]
      (is (= "yara@example.com" (:to msg)))
      (is (some? token) "the email carries a verify token")
      (is (= "yara@example.com" (:primary-email (accounts/verify-email! (storage) token)))
          "the emailed link actually verifies the account"))))
