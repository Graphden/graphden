(ns graphden.storage.protocol.credentials-test
  "Tests for credential validation helpers."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as storage]))


;; === validate-credentials! tests ===

(deftest validate-credentials!-test
  (testing "passes for valid credentials"
    (is (nil? (storage/validate-credentials! "username" "password"))))

  (testing "passes for nil username - only validates if string"
    (is (nil? (storage/validate-credentials! nil "password"))))

  (testing "passes for nil password - only validates if string"
    (is (nil? (storage/validate-credentials! "username" nil))))

  (testing "throws for too long username"
    (let [long-user (str/join (repeat 200 "x"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"exceeds maximum length"
            (storage/validate-credentials! long-user "password")))))

  (testing "throws for too long password"
    (let [long-pass (str/join (repeat 1100 "x"))]  ; max-password-length = 1024
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"exceeds maximum length"
            (storage/validate-credentials! "username" long-pass)))))

  (testing "throws for username with control chars"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"control characters"
          (storage/validate-credentials! "user\u0000name" "password"))))

  (testing "throws for password with control chars"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"control characters"
          (storage/validate-credentials! "username" "pass\u0007word")))))


;; === validate-jdbc-url! tests ===

(deftest validate-jdbc-url!-test
  (testing "passes for valid JDBC URL"
    (is (nil? (storage/validate-jdbc-url! "jdbc:postgresql://localhost:5432/db"))))

  (testing "passes for nil URL - only validates if string"
    (is (nil? (storage/validate-jdbc-url! nil))))

  (testing "passes for non-string - only validates if string"
    (is (nil? (storage/validate-jdbc-url! 12345))))

  (testing "throws for too long URL"
    (let [long-url (str "jdbc:postgresql://localhost:5432/" (str/join (repeat 5000 "x")))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"exceeds maximum length"
            (storage/validate-jdbc-url! long-url)))))

  (testing "throws for URL with control characters"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"control characters"
          (storage/validate-jdbc-url! "jdbc:postgresql://localhost\u0000:5432/db")))))


;; === validate-credential-length! tests ===

(deftest validate-credential-length!-test
  (testing "passes for normal length credentials"
    (is (nil? (storage/validate-credential-length! "testuser" "username" 256)))
    (is (nil? (storage/validate-credential-length! "testpass" "password" 256))))

  (testing "passes for nil value"
    (is (nil? (storage/validate-credential-length! nil "username" 256))))

  (testing "throws for too-long credentials"
    (let [long-value (str/join (repeat 300 "x"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"exceeds maximum length"
            (storage/validate-credential-length! long-value "username" 256))))))


;; === validate-no-control-chars! tests ===

(deftest validate-no-control-chars!-test
  (testing "passes for normal strings"
    (is (nil? (storage/validate-no-control-chars! "normal_value" "field")))
    (is (nil? (storage/validate-no-control-chars! "with spaces" "field"))))

  (testing "passes for nil value"
    (is (nil? (storage/validate-no-control-chars! nil "field"))))

  (testing "throws for null bytes"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"control characters"
          (storage/validate-no-control-chars! "has\u0000null" "field"))))

  (testing "throws for other control chars"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"control characters"
          (storage/validate-no-control-chars! "has\u0007bell" "field"))))

  (testing "throws for newline (log injection prevention)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"control characters"
          (storage/validate-no-control-chars! "has\nnewline" "field"))))

  (testing "throws for carriage return"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"control characters"
          (storage/validate-no-control-chars! "has\rreturn" "field"))))

  (testing "throws for tab"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"control characters"
          (storage/validate-no-control-chars! "has\ttab" "field")))))
