(ns graphden.accounts.email-test
  "Pure unit tests (no network) for the mailer selection, the captured-message
   sink, and the verification email body."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.accounts.email :as email]))


(deftest make-mailer-picks-log-vs-resend
  (testing "blank / missing api-key → LogMailer (self-hosted without email)"
    (is (instance? graphden.accounts.email.LogMailer (email/make-mailer {})))
    (is (instance? graphden.accounts.email.LogMailer (email/make-mailer {:api-key ""}))))
  (testing "a real api-key → ResendMailer with the default sender when from blank"
    (let [m (email/make-mailer {:api-key "re_test_key"})]
      (is (instance? graphden.accounts.email.ResendMailer m))
      (is (= email/default-from (:from m))))
    (is (= "Me <me@x.com>" (:from (email/make-mailer {:api-key "re_x" :from "Me <me@x.com>"}))))))


(deftest capturing-mailer-collects-messages
  (let [sink (atom [])
        m (email/->CapturingMailer sink)]
    (is (:ok? (email/send-mail! m {:to "a@b.com" :subject "hi" :text "yo"})))
    (is (= [{:to "a@b.com" :subject "hi" :text "yo"}] @sink))))


(deftest verification-body-carries-the-link-and-token
  (let [{:keys [subject html text]} (email/verification-email-body "https://app.graphden.dev" "TOK-123")]
    (is (string? subject))
    (testing "both parts point at /auth/verify with the token"
      (is (str/includes? text "https://app.graphden.dev/auth/verify?token=TOK-123"))
      (is (str/includes? html "https://app.graphden.dev/auth/verify?token=TOK-123")))
    (testing "expiry is stated"
      (is (str/includes? text "24 hours")))))
