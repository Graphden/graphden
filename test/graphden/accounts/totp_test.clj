(ns graphden.accounts.totp-test
  "Unit tests for TOTP against the RFC 6238 SHA-1 reference vectors, plus base32
   round-trip, the skew window, and the otpauth URI."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.accounts.totp :as totp]))


;; RFC 6238 seed "12345678901234567890" → base32:
(def ^:private rfc-secret "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")


(deftest base32-roundtrips
  (is (= "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"
         (totp/base32-encode (.getBytes "12345678901234567890" "UTF-8"))))
  (is (= "12345678901234567890"
         (String. (totp/base32-decode rfc-secret) "UTF-8"))))


(deftest matches-rfc-6238-vectors
  (testing "the 6-digit SHA-1 reference codes"
    (is (= "287082" (totp/code-at rfc-secret 59)))
    (is (= "081804" (totp/code-at rfc-secret 1111111109)))
    (is (= "005924" (totp/code-at rfc-secret 1234567890)))))


(deftest valid?-accepts-current-and-skewed-rejects-wrong
  (let [t 1234567890]
    (is (totp/valid? rfc-secret "005924" t) "current step")
    (is (totp/valid? rfc-secret (totp/code-at rfc-secret (- t 30)) t) "previous step (skew -1)")
    (is (totp/valid? rfc-secret (totp/code-at rfc-secret (+ t 30)) t) "next step (skew +1)")
    (is (not (totp/valid? rfc-secret (totp/code-at rfc-secret (+ t 120)) t)) "outside the window")
    (is (not (totp/valid? rfc-secret "000000" t)))
    (is (not (totp/valid? rfc-secret "" t)))
    (is (not (totp/valid? rfc-secret nil t)))))


(deftest generate-secret-is-usable
  (let [s (totp/generate-secret)]
    (is (>= (count s) 32) "20 bytes → 32 base32 chars")
    (is (re-matches #"[A-Z2-7]+" s))
    (testing "a freshly generated secret verifies its own current code"
      (is (totp/valid? s (totp/code-at s 1700000000) 1700000000)))))


(deftest otpauth-uri-shape
  (let [uri (totp/otpauth-uri "Graphden" "a@b.com" rfc-secret)]
    (is (str/starts-with? uri "otpauth://totp/Graphden:a@b.com?"))
    (is (str/includes? uri (str "secret=" rfc-secret)))
    (is (str/includes? uri "issuer=Graphden"))
    (is (str/includes? uri "digits=6"))))
