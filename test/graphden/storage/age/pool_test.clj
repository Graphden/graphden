(ns graphden.storage.age.pool-test
  "Tests for AGE connection pool management.

   Covers:
   - validate-pool-options! validation errors
   - create-pool with valid config
   - close-pool behavior"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.age.pool :as pool]
    [graphden.storage.age.test-setup :as setup])
  (:import
    (com.zaxxer.hikari
      HikariDataSource)))


(use-fixtures :once (setup/container-fixture))


;; =============================================================================
;; Validation Error Tests
;; =============================================================================

(deftest missing-jdbc-url-test
  (testing "Throws when jdbc-url is missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"jdbc-url is required"
          (pool/create-pool {:username "test"
                             :password "test"})))))


(deftest invalid-jdbc-url-type-test
  (testing "Throws when jdbc-url is not a string"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"jdbc-url must be a string"
          (pool/create-pool {:jdbc-url 123
                             :username "test"
                             :password "test"})))))


(deftest invalid-jdbc-url-prefix-test
  (testing "Throws when jdbc-url doesn't start with jdbc:postgresql://"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"jdbc:postgresql://"
          (pool/create-pool {:jdbc-url "jdbc:mysql://localhost:3306/db"
                             :username "test"
                             :password "test"})))))


(deftest missing-username-test
  (testing "Throws when username is missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"username is required"
          (pool/create-pool {:jdbc-url "jdbc:postgresql://localhost:5432/db"
                             :password "test"}))))

  (testing "Throws when username is empty"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"username is required"
          (pool/create-pool {:jdbc-url "jdbc:postgresql://localhost:5432/db"
                             :username "   "
                             :password "test"})))))


(deftest missing-password-test
  (testing "Throws when password is missing"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"password is required"
          (pool/create-pool {:jdbc-url "jdbc:postgresql://localhost:5432/db"
                             :username "test"}))))

  (testing "Throws when password is empty"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"password is required"
          (pool/create-pool {:jdbc-url "jdbc:postgresql://localhost:5432/db"
                             :username "test"
                             :password "   "})))))


(deftest invalid-pool-size-test
  (testing "Throws when pool-size is not positive"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"pool-size must be a positive integer"
          (pool/create-pool {:jdbc-url "jdbc:postgresql://localhost:5432/db"
                             :username "test"
                             :password "test"
                             :pool-size 0}))))

  (testing "Throws when pool-size exceeds 100"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"pool-size exceeds maximum"
          (pool/create-pool {:jdbc-url "jdbc:postgresql://localhost:5432/db"
                             :username "test"
                             :password "test"
                             :pool-size 101})))))


(deftest invalid-min-idle-test
  (testing "Throws when min-idle is not positive"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"min-idle must be a positive integer"
          (pool/create-pool {:jdbc-url "jdbc:postgresql://localhost:5432/db"
                             :username "test"
                             :password "test"
                             :min-idle 0}))))

  (testing "Throws when min-idle exceeds pool-size"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"min-idle cannot exceed pool-size"
          (pool/create-pool {:jdbc-url "jdbc:postgresql://localhost:5432/db"
                             :username "test"
                             :password "test"
                             :pool-size 5
                             :min-idle 10})))))


(deftest invalid-connection-timeout-test
  (testing "Throws when connection-timeout is not positive"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"connection-timeout must be a positive integer"
          (pool/create-pool {:jdbc-url "jdbc:postgresql://localhost:5432/db"
                             :username "test"
                             :password "test"
                             :connection-timeout 0})))))


(deftest invalid-idle-timeout-test
  (testing "Throws when idle-timeout >= max-lifetime"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"idle-timeout must be less than max-lifetime"
          (pool/create-pool {:jdbc-url "jdbc:postgresql://localhost:5432/db"
                             :username "test"
                             :password "test"
                             :idle-timeout 1800000
                             :max-lifetime 1800000})))))


;; =============================================================================
;; Pool Creation Tests
;; =============================================================================

(deftest create-pool-test
  (testing "create-pool creates a HikariDataSource"
    (let [config (setup/get-container-config setup/*container*)
          pool (pool/create-pool config)]
      (try
        (is (instance? HikariDataSource pool))
        (is (not (HikariDataSource/.isClosed pool)))
        (finally
          (pool/close-pool pool)))))

  (testing "create-pool with custom pool settings"
    (let [config (-> (setup/get-container-config setup/*container*)
                     (assoc :pool-size 3
                            :min-idle 1
                            :connection-timeout 5000))
          pool (pool/create-pool config)]
      (try
        (is (instance? HikariDataSource pool))
        (is (= 3 (HikariDataSource/.getMaximumPoolSize pool)))
        (is (= 1 (HikariDataSource/.getMinimumIdle pool)))
        (finally
          (pool/close-pool pool))))))


;; =============================================================================
;; Pool Close Tests
;; =============================================================================

(deftest close-pool-test
  (testing "close-pool returns true for open pool"
    (let [config (setup/get-container-config setup/*container*)
          pool (pool/create-pool config)]
      (is (true? (pool/close-pool pool)))
      (is (HikariDataSource/.isClosed pool))))

  (testing "close-pool returns true for already closed pool"
    (let [config (setup/get-container-config setup/*container*)
          pool (pool/create-pool config)]
      (pool/close-pool pool)
      (is (true? (pool/close-pool pool)))))

  (testing "close-pool returns true for nil pool"
    (is (true? (pool/close-pool nil)))))
