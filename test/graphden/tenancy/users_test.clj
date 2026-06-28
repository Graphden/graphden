(ns graphden.tenancy.users-test
  "Pure password-hashing unit tests (no storage). The create-user!/login!
   storage round-trip lives in the addon-active integration suite
   (`graphden.integration.faas-app-test`)."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.tenancy.users :as users]))


(deftest password-hashing-roundtrip
  (testing "verify accepts the right password, rejects others"
    (let [h (users/hash-password "correct horse battery staple")]
      (is (true? (users/verify-password "correct horse battery staple" h)))
      (is (false? (users/verify-password "wrong" h)))
      (is (false? (users/verify-password "" h)))))
  (testing "a fresh salt per call — same password hashes to different strings"
    (is (not= (users/hash-password "pw") (users/hash-password "pw"))))
  (testing "the stored value is a bcrypt hash ($2…)"
    (is (str/starts-with? (users/hash-password "pw") "$2")))
  (testing "garbage / nil never throws — just false"
    (is (false? (users/verify-password "pw" "not-a-hash")))
    (is (false? (users/verify-password "pw" nil)))
    (is (false? (users/verify-password nil "pbkdf2$1$a$b")))))


(deftest rate-limiter-allows-then-blocks
  (let [limit (users/make-rate-limiter 3 60000)]
    (testing "allows up to max per window, then blocks the same key"
      (is (true? (limit "ip1")))
      (is (true? (limit "ip1")))
      (is (true? (limit "ip1")))
      (is (false? (limit "ip1")))
      (is (false? (limit "ip1"))))
    (testing "a different key has its own independent window"
      (is (true? (limit "ip2"))))))


(deftest rate-limiter-window-resets
  (let [limit (users/make-rate-limiter 1 50)]   ; 1 attempt per 50ms
    (is (true? (limit "k")))
    (is (false? (limit "k")))
    (Thread/sleep 70)
    (is (true? (limit "k")) "after the window elapses the key is allowed again")))


(deftest client-ip-extraction
  (testing "first X-Forwarded-For hop wins, else :remote-addr, else unknown"
    (is (= "1.2.3.4" (users/client-ip {:headers {"x-forwarded-for" "1.2.3.4, 5.6.7.8"}})))
    (is (= "9.9.9.9" (users/client-ip {:remote-addr "9.9.9.9"})))
    (is (= "unknown" (users/client-ip {})))))


(deftest legacy-pbkdf2-hashes-still-verify
  ;; Accounts created before the bcrypt switch must still log in — verify-password
  ;; dispatches `pbkdf2$…` to the legacy path. This is a real PBKDF2 hash of
  ;; "legacy-pw" (all-zero salt, so it's stable to hard-code).
  (let [legacy "pbkdf2$100000$AAAAAAAAAAAAAAAAAAAAAA==$IJ7VI9MfVAgFv8PBJsAVTM9TXi+MtwfQBeYRwcjryGI="]
    (is (true? (users/verify-password "legacy-pw" legacy)))
    (is (false? (users/verify-password "wrong" legacy)))))
