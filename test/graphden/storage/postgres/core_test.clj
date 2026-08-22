(ns graphden.storage.postgres.core-test
  "Unit tests for PostgreSQL storage core functions.
   Tests connection pool configuration validation."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.postgres.core :as core]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp])
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

  (testing "jdbc-url validation error does not leak url (security)"
    (try
      (core/create-pool {:jdbc-url (str "jdbc:mysql://" (str/join (repeat 100 "x")))
                         :username "user"
                         :password "pass"})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :config-error/invalid-jdbc-url (:type data)))
          ;; URL should NOT be included in error data (may contain credentials)
          (is (nil? (:jdbc-url data)))
          ;; Instead, a hint about expected format should be provided
          (is (string? (:hint data))))))))


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
;; Note: with-query-timeout is defined in util.clj, not in core.clj
(defn- make-sql-exception
  "Creates a SQLException with the given SQL state code."
  [sql-state]
  (SQLException. "Test error" sql-state))


(deftest postgres-storage-error-classifier-test
  (let [storage (core/->PostgresStorage nil nil nil (atom {}))]
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


;; === Additional PostgreSQL error type tests ===

(deftest classify-sql-error-extended-test
  (testing "classifies serialization failure (40001)"
    (is (= :serialization-failure (util/classify-sql-error (make-sql-exception "40001")))))

  (testing "classifies deadlock detected (40P01)"
    (is (= :deadlock-detected (util/classify-sql-error (make-sql-exception "40P01")))))

  (testing "classifies read-only transaction (25006)"
    (is (= :read-only-transaction (util/classify-sql-error (make-sql-exception "25006")))))

  (testing "classifies not-null violation (23502)"
    (is (= :not-null-violation (util/classify-sql-error (make-sql-exception "23502")))))

  (testing "classifies check constraint violation (23514)"
    (is (= :check-constraint-violation (util/classify-sql-error (make-sql-exception "23514")))))

  (testing "classifies table not found (42P01)"
    (is (= :table-not-found (util/classify-sql-error (make-sql-exception "42P01")))))

  (testing "classifies query timeout (57014)"
    (is (= :query-timeout (util/classify-sql-error (make-sql-exception "57014")))))

  (testing "classifies connection errors by prefix (08xxx)"
    (is (= :connection-error (util/classify-sql-error (make-sql-exception "08000"))))
    (is (= :connection-error (util/classify-sql-error (make-sql-exception "08003"))))
    (is (= :connection-error (util/classify-sql-error (make-sql-exception "08006")))))

  (testing "returns unknown-sql-error for unrecognized codes"
    (is (= :unknown-sql-error (util/classify-sql-error (make-sql-exception "99999"))))
    ;; SQLException with nil SQL state - use helper to avoid type hint issues
    (let [^String nil-state nil]
      (is (= :unknown-sql-error (util/classify-sql-error (SQLException. "No SQL state" nil-state)))))))


(deftest error-predicate-functions-test
  (testing "serialization-failure?"
    (is (util/serialization-failure? (make-sql-exception "40001")))
    (is (not (util/serialization-failure? (make-sql-exception "40P01")))))

  (testing "deadlock-detected?"
    (is (util/deadlock-detected? (make-sql-exception "40P01")))
    (is (not (util/deadlock-detected? (make-sql-exception "40001")))))

  (testing "read-only-transaction?"
    (is (util/read-only-transaction? (make-sql-exception "25006")))
    (is (not (util/read-only-transaction? (make-sql-exception "23505")))))

  (testing "table-not-found?"
    (is (util/table-not-found? (make-sql-exception "42P01")))
    (is (not (util/table-not-found? (make-sql-exception "42P02")))))

  (testing "unique-violation?"
    (is (util/unique-violation? (make-sql-exception "23505")))
    (is (not (util/unique-violation? (make-sql-exception "23503")))))

  (testing "foreign-key-violation?"
    (is (util/foreign-key-violation? (make-sql-exception "23503")))
    (is (not (util/foreign-key-violation? (make-sql-exception "23505")))))

  (testing "not-null-violation?"
    (is (util/not-null-violation? (make-sql-exception "23502")))
    (is (not (util/not-null-violation? (make-sql-exception "23505")))))

  (testing "check-constraint-violation?"
    (is (util/check-constraint-violation? (make-sql-exception "23514")))
    (is (not (util/check-constraint-violation? (make-sql-exception "23505")))))

  (testing "connection-error?"
    (is (util/connection-error? (make-sql-exception "08000")))
    (is (not (util/connection-error? (make-sql-exception "23505")))))

  (testing "query-canceled?"
    (is (util/query-canceled? (make-sql-exception "57014")))
    (is (not (util/query-canceled? (make-sql-exception "23505"))))))


;; `with-query-timeout`'s validation table (positive, >= 1000 ms) belongs to
;; `protocol.config`, which defines it, and is pinned by `config-test`.
;; `postgres.util` only re-exports the var; `util-test` asserts THAT, once.
