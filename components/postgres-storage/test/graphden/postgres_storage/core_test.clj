(ns graphden.postgres-storage.core-test
  "Unit tests for PostgreSQL storage core functions.
   Tests connection pool configuration validation."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.postgres-storage.core :as core]
    [graphden.storage-protocol.interface :as sp])
  (:import
    (java.sql
      SQLException)))


;; === create-pool validation tests ===
;; These tests verify parameter validation without actually creating pools.

(deftest create-pool-jdbc-url-validation-test
  (testing "rejects missing jdbc-url"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"jdbc-url is required"
          (core/create-pool {:username "user" :password "pass"}))))

  (testing "rejects nil jdbc-url"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"jdbc-url is required"
          (core/create-pool {:jdbc-url nil :username "user" :password "pass"}))))

  (testing "rejects non-string jdbc-url"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"jdbc-url must be a string"
          (core/create-pool {:jdbc-url 123 :username "user" :password "pass"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"jdbc-url must be a string"
          (core/create-pool {:jdbc-url :keyword :username "user" :password "pass"}))))

  (testing "rejects jdbc-url with wrong protocol"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"jdbc-url must start with 'jdbc:postgresql://'"
          (core/create-pool {:jdbc-url "jdbc:mysql://localhost/db"
                             :username "user"
                             :password "pass"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"jdbc-url must start with 'jdbc:postgresql://'"
          (core/create-pool {:jdbc-url "postgresql://localhost/db"
                             :username "user"
                             :password "pass"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"jdbc-url must start with 'jdbc:postgresql://'"
          (core/create-pool {:jdbc-url "http://localhost/db"
                             :username "user"
                             :password "pass"}))))

  (testing "jdbc-url validation error includes truncated url for long urls"
    (try
      (core/create-pool {:jdbc-url (str "jdbc:mysql://" (str/join (repeat 100 "x")))
                         :username "user"
                         :password "pass"})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :config-error/invalid-jdbc-url (:type data)))
          ;; URL should be truncated to 50 chars + "..."
          (is (= 53 (count (:jdbc-url data)))))))))


(deftest create-pool-credentials-validation-test
  (testing "rejects missing username"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"username is required"
          (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                             :password "pass"}))))

  (testing "rejects empty username"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"username is required"
          (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                             :username ""
                             :password "pass"}))))

  (testing "rejects whitespace-only username"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"username is required"
          (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                             :username "   "
                             :password "pass"}))))

  (testing "rejects missing password"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"password is required"
          (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                             :username "user"}))))

  (testing "rejects empty password"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"password is required"
          (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                             :username "user"
                             :password ""}))))

  (testing "rejects whitespace-only password"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"password is required"
          (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                             :username "user"
                             :password "   "})))))


(deftest create-pool-pool-size-validation-test
  (testing "rejects non-positive pool-size"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"pool-size must be a positive integer"
          (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                             :username "user"
                             :password "pass"
                             :pool-size 0})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"pool-size must be a positive integer"
          (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                             :username "user"
                             :password "pass"
                             :pool-size -5}))))

  (testing "rejects non-integer pool-size"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"pool-size must be a positive integer"
          (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                             :username "user"
                             :password "pass"
                             :pool-size 5.5}))))

  (testing "rejects pool-size exceeding maximum"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"pool-size exceeds maximum allowed value"
          (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                             :username "user"
                             :password "pass"
                             :pool-size 101}))))

  (testing "pool-size exceeds max error includes details"
    (try
      (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                         :username "user"
                         :password "pass"
                         :pool-size 200})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :config-error/invalid-pool-size (:type data)))
          (is (= 200 (:pool-size data)))
          (is (= 100 (:max-allowed data))))))))


(deftest create-pool-min-idle-validation-test
  (testing "rejects non-positive min-idle"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"min-idle must be a positive integer"
          (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                             :username "user"
                             :password "pass"
                             :min-idle 0})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"min-idle must be a positive integer"
          (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                             :username "user"
                             :password "pass"
                             :min-idle -1}))))

  (testing "rejects min-idle greater than pool-size"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"min-idle cannot exceed pool-size"
          (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                             :username "user"
                             :password "pass"
                             :pool-size 5
                             :min-idle 10}))))

  (testing "min-idle exceeds pool-size error includes details"
    (try
      (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                         :username "user"
                         :password "pass"
                         :pool-size 5
                         :min-idle 10})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :config-error/invalid-pool-config (:type data)))
          (is (= 10 (:min-idle data)))
          (is (= 5 (:pool-size data))))))))


(deftest create-pool-timeout-validation-test
  (testing "rejects non-positive connection-timeout"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"connection-timeout must be a positive integer"
          (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                             :username "user"
                             :password "pass"
                             :connection-timeout 0})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"connection-timeout must be a positive integer"
          (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                             :username "user"
                             :password "pass"
                             :connection-timeout -1000}))))

  (testing "rejects idle-timeout >= max-lifetime (when idle-timeout > 0)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"idle-timeout must be less than max-lifetime"
          (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                             :username "user"
                             :password "pass"
                             :idle-timeout 1800000
                             :max-lifetime 1800000})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"idle-timeout must be less than max-lifetime"
          (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                             :username "user"
                             :password "pass"
                             :idle-timeout 2000000
                             :max-lifetime 1800000}))))

  (testing "idle-timeout vs max-lifetime error includes details"
    (try
      (core/create-pool {:jdbc-url "jdbc:postgresql://localhost/db"
                         :username "user"
                         :password "pass"
                         :idle-timeout 2000000
                         :max-lifetime 1000000})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :config-error/invalid-pool-config (:type data)))
          (is (= 2000000 (:idle-timeout data)))
          (is (= 1000000 (:max-lifetime data))))))))


;; === with-query-timeout tests ===

(deftest with-query-timeout-validation-test
  (testing "rejects non-positive timeout"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Query timeout must be a positive integer"
          (core/with-query-timeout 0 #(identity :result))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Query timeout must be a positive integer"
          (core/with-query-timeout -1000 #(identity :result)))))

  (testing "rejects timeout below minimum (1000ms)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Query timeout must be at least 1000ms"
          (core/with-query-timeout 500 #(identity :result))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Query timeout must be at least 1000ms"
          (core/with-query-timeout 999 #(identity :result)))))

  (testing "accepts valid timeout at minimum"
    (is (= :result (core/with-query-timeout 1000 #(identity :result)))))

  (testing "accepts valid timeout above minimum"
    (is (= :result (core/with-query-timeout 60000 #(identity :result))))))


;; === PostgresStorage error classifier tests ===
;; Tests the StorageErrorClassifier protocol implementation on PostgresStorage

(defn- make-sql-exception
  "Creates a SQLException with the given SQL state code."
  [sql-state]
  (SQLException. "Test error" sql-state))


(deftest postgres-storage-error-classifier-test
  (let [storage (core/->PostgresStorage nil nil nil)]
    (testing "classify-error delegates to error classifier"
      (is (= :unique-violation (sp/classify-error storage (make-sql-exception "23505"))))
      (is (= :foreign-key-violation (sp/classify-error storage (make-sql-exception "23503"))))
      (is (= :unknown-sql-error (sp/classify-error storage (Exception. "generic")))))

    (testing "wrap-error delegates to error classifier"
      (let [ex (make-sql-exception "23505")
            wrapped (sp/wrap-error storage ex :create-entity {:entity-name :user})]
        (is (instance? clojure.lang.ExceptionInfo wrapped))
        (let [data (ex-data wrapped)]
          (is (= :unique-violation (:type data)))
          (is (= :create-entity (:operation data)))
          (is (= :user (:entity-name data))))))))
