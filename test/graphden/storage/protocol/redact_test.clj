(ns graphden.storage.protocol.redact-test
  "Tests for redaction and sensitive field handling."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.interface :as storage]))


;; === redact-sensitive tests ===

(deftest redact-sensitive-map-test
  (testing "redacts known sensitive keys"
    (is (= {:password "[REDACTED]"}
           (storage/redact-sensitive-map {:password "secret123"})))
    (is (= {:api-key "[REDACTED]"}
           (storage/redact-sensitive-map {:api-key "abc123"})))
    (is (= {:secret "[REDACTED]"}
           (storage/redact-sensitive-map {:secret "hidden"}))))

  (testing "preserves non-sensitive keys"
    (is (= {:username "john" :email "john@test.com"}
           (storage/redact-sensitive-map {:username "john" :email "john@test.com"}))))

  (testing "handles mixed keys"
    (is (= {:name "test" :password "[REDACTED]"}
           (storage/redact-sensitive-map {:name "test" :password "secret"}))))

  (testing "handles string keys for sensitive fields"
    (is (= {"password" "[REDACTED]"}
           (storage/redact-sensitive-map {"password" "secret123"})))
    (is (= {"api_key" "[REDACTED]"}
           (storage/redact-sensitive-map {"api_key" "abc123"})))))


(deftest redact-sensitive-deep-test
  (testing "redacts nested maps"
    (is (= {:config {:db {:password "[REDACTED]"}} :name "test"}
           (storage/redact-sensitive-deep
             {:config {:db {:password "secret"}} :name "test"}))))

  (testing "redacts in vectors"
    (is (= [{:password "[REDACTED]"} {:password "[REDACTED]"}]
           (storage/redact-sensitive-deep
             [{:password "p1"} {:password "p2"}]))))

  (testing "redacts in sets"
    (let [result (storage/redact-sensitive-deep
                   #{{:password "secret1"} {:password "secret2"}})]
      (is (set? result))
      (is (every? #(= "[REDACTED]" (:password %)) result))))

  (testing "redacts in sequences"
    (let [result (storage/redact-sensitive-deep
                   (list {:password "p1"} {:password "p2"}))]
      (is (seq? result))
      (is (every? #(= "[REDACTED]" (:password %)) result))))

  (testing "preserves non-sensitive data"
    (is (= {:user {:name "john"}}
           (storage/redact-sensitive-deep {:user {:name "john"}}))))

  (testing "handles nil and other types"
    (is (nil? (storage/redact-sensitive-deep nil)))
    (is (= "string" (storage/redact-sensitive-deep "string")))
    (is (= 42 (storage/redact-sensitive-deep 42)))))


;; === Sensitive Field Registry Tests ===

(deftest sensitive-field-registry-test
  (testing "default sensitive fields are detected"
    (is (storage/sensitive-field? :password))
    (is (storage/sensitive-field? :api-key))
    (is (storage/sensitive-field? :secret))
    (is (storage/sensitive-field? :auth-token)))

  (testing "non-sensitive fields are not detected"
    (is (not (storage/sensitive-field? :username)))
    (is (not (storage/sensitive-field? :email)))
    (is (not (storage/sensitive-field? :name)))))


(deftest register-sensitive-field-name!-test
  (testing "registers custom field name"
    (try
      (storage/register-sensitive-field-name! :employee-id)
      (is (storage/sensitive-field? :employee-id))
      (finally
        (storage/reset-sensitive-field-registry!))))

  (testing "throws on non-keyword"
    (is (thrown? clojure.lang.ExceptionInfo
          (storage/register-sensitive-field-name! "not-a-keyword")))))


(deftest register-sensitive-field-pattern!-test
  (testing "registers custom pattern"
    (try
      (storage/register-sensitive-field-pattern! #"(?i)hipaa")
      (is (storage/sensitive-field? :hipaa-data))
      (is (storage/sensitive-field? :patient-hipaa-record))
      (finally
        (storage/reset-sensitive-field-registry!))))

  (testing "throws on non-pattern"
    (is (thrown? clojure.lang.ExceptionInfo
          (storage/register-sensitive-field-pattern! "not-a-pattern")))))


(deftest register-sensitive-field-predicate!-test
  (testing "registers custom predicate"
    (try
      ;; Mark all fields in pii namespace as sensitive
      (storage/register-sensitive-field-predicate!
        (fn [k] (= "pii" (namespace k))))
      (is (storage/sensitive-field? :pii/social-security))
      (is (storage/sensitive-field? :pii/date-of-birth))
      (is (not (storage/sensitive-field? :user/email)))
      (finally
        (storage/reset-sensitive-field-registry!))))

  (testing "throws on non-function"
    (is (thrown? clojure.lang.ExceptionInfo
          (storage/register-sensitive-field-predicate! :not-a-function)))))


(deftest reset-sensitive-field-registry!-test
  (testing "resets to defaults"
    ;; Use a field name that doesn't match any default patterns
    (storage/register-sensitive-field-name! :custom-field-xyz)
    (is (storage/sensitive-field? :custom-field-xyz))
    (storage/reset-sensitive-field-registry!)
    (is (not (storage/sensitive-field? :custom-field-xyz)))
    ;; Defaults still work
    (is (storage/sensitive-field? :password))))
