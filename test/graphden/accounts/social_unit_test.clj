(ns graphden.accounts.social-unit-test
  "Pure unit tests (no network) for OAuth authorize-URL + userinfo normalizers
   and the Telegram login-widget HMAC verification."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.accounts.oauth :as oauth]
    [graphden.accounts.telegram :as telegram]))


(deftest authorize-url-has-the-required-params
  (let [url (oauth/authorize-url "google" {:client-id "cid.apps"} "https://app.graphden.dev/auth/google/callback" "STATE1")]
    (is (str/starts-with? url "https://accounts.google.com/o/oauth2/v2/auth?"))
    (is (str/includes? url "client_id=cid.apps"))
    (is (str/includes? url "response_type=code"))
    (is (str/includes? url "state=STATE1"))
    (is (str/includes? url "scope=openid+email+profile"))
    (is (str/includes? url "redirect_uri=https%3A%2F%2Fapp.graphden.dev%2Fauth%2Fgoogle%2Fcallback")
        "redirect_uri is URL-encoded")))


(deftest github-normalizer-picks-primary-verified-email
  (let [id (oauth/normalize-github
             {:id 777 :login "octocat" :name "The Octocat"}
             [{:email "alt@x.com" :primary false :verified true}
              {:email "octo@x.com" :primary true :verified true}])]
    (is (= {:provider "github" :subject "777" :email "octo@x.com"
            :email-verified? true :display-name "The Octocat"}
           id)))
  (testing "no name → falls back to login; unverified primary → email-verified? false"
    (is (= "octocat" (:display-name (oauth/normalize-github {:id 1 :login "octocat"} []))))
    (is (false? (:email-verified? (oauth/normalize-github {:id 1 :login "o"}
                                                          [{:email "e@x.com" :primary true :verified false}]))))))


(deftest google-normalizer-maps-oidc-userinfo
  (is (= {:provider "google" :subject "sub-9" :email "g@x.com"
          :email-verified? true :display-name "Gina"}
         (oauth/normalize-google {:sub "sub-9" :email "g@x.com" :email_verified true :name "Gina"})))
  (testing "no sub → nil (not a usable identity)"
    (is (nil? (oauth/normalize-google {:email "g@x.com"})))))


(deftest telegram-verify-accepts-valid-rejects-tampered-and-stale
  (let [bot "123456:TESTTOKEN"
        data {"id" "42" "first_name" "Ann" "username" "ann" "auth_date" "1000"
              "hash" "c4fed3031ad012db62983471d9144b8d6abc17af784752beb63475c8ebdcc7c8"}]
    (testing "a genuine payload within the freshness window verifies"
      (is (= {:provider "telegram" :subject "42" :email nil
              :email-verified? false :display-name "ann"}
             (telegram/verify-login bot data 1500))))
    (testing "a tampered field breaks the hash"
      (is (nil? (telegram/verify-login bot (assoc data "id" "99") 1500))))
    (testing "the wrong bot token breaks the hash"
      (is (nil? (telegram/verify-login "999:OTHER" data 1500))))
    (testing "a stale auth_date is rejected"
      (is (nil? (telegram/verify-login bot data (+ 1000 telegram/max-auth-age-secs 1)))))))
