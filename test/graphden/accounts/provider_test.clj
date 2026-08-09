(ns graphden.accounts.provider-test
  "Pure unit tests (no DB) for the accounts token-extraction + password
   helpers — bearer/cookie precedence and bcrypt round-trip."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.accounts.core :as accounts]
    [graphden.accounts.provider :as provider]))


(deftest normalize-email-is-lowercase-trimmed-or-nil
  (is (= "a@b.com" (accounts/normalize-email "  A@B.CoM ")))
  (is (nil? (accounts/normalize-email "   ")))
  (is (nil? (accounts/normalize-email nil))))


(deftest bcrypt-roundtrips-and-fails-closed
  (let [h (accounts/hash-password "correct horse")]
    (is (accounts/verify-password "correct horse" h))
    (is (not (accounts/verify-password "wrong" h)))
    (testing "a blank/garbage stored hash never verifies (never throws)"
      (is (not (accounts/verify-password "x" nil)))
      (is (not (accounts/verify-password "x" "")))
      (is (not (accounts/verify-password "x" "not-a-bcrypt-hash"))))))


(deftest request-token-prefers-bearer-then-cookie
  (testing "Authorization bearer wins"
    (is (= "BEARER-TOK"
           (provider/request-token {:headers {"authorization" "Bearer BEARER-TOK"
                                              "cookie" "gd_session=COOKIE-TOK"}}))))
  (testing "falls back to the gd_session cookie"
    (is (= "COOKIE-TOK"
           (provider/request-token {:headers {"cookie" "other=1; gd_session=COOKIE-TOK; x=y"}}))))
  (testing "no credentials → nil"
    (is (nil? (provider/request-token {:headers {}})))
    (is (nil? (provider/request-token {:headers {"cookie" "unrelated=1"}})))))
