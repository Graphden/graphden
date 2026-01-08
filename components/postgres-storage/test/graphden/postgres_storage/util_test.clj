(ns graphden.postgres-storage.util-test
  "Tests for PostgreSQL storage utility functions."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.postgres-storage.util :as util]
    [graphden.storage-protocol.interface :as sp])
  (:import
    (java.sql
      SQLException)))


;; === Configuration Tests ===

(deftest get-query-timeout-seconds-test
  (testing "returns timeout from util/*query-timeout-ms* converted to seconds"
    ;; Default is 30000ms = 30 seconds
    (let [result (util/get-query-timeout-seconds)]
      (is (integer? result))
      (is (pos? result))
      (is (= 30 result))))

  (testing "respects custom timeout via binding"
    (binding [util/*query-timeout-ms* 60000]
      (is (= 60 (util/get-query-timeout-seconds))))))


;; === Helper to create SQLException with specific state ===

(defn- make-sql-exception
  "Creates a SQLException with the given SQL state code."
  [sql-state]
  (SQLException. "Test error" sql-state))


;; === Error Classification Tests ===

(deftest table-not-found?-test
  (testing "returns true for 42P01 (undefined_table)"
    (is (true? (util/table-not-found? (make-sql-exception "42P01")))))

  (testing "returns false for other states"
    (is (false? (util/table-not-found? (make-sql-exception "23505"))))
    (is (false? (util/table-not-found? (make-sql-exception "00000"))))))


(deftest unique-violation?-test
  (testing "returns true for 23505 (unique_violation)"
    (is (true? (util/unique-violation? (make-sql-exception "23505")))))

  (testing "returns false for other states"
    (is (false? (util/unique-violation? (make-sql-exception "23503"))))
    (is (false? (util/unique-violation? (make-sql-exception "42P01"))))))


(deftest foreign-key-violation?-test
  (testing "returns true for 23503 (foreign_key_violation)"
    (is (true? (util/foreign-key-violation? (make-sql-exception "23503")))))

  (testing "returns false for other states"
    (is (false? (util/foreign-key-violation? (make-sql-exception "23505"))))
    (is (false? (util/foreign-key-violation? (make-sql-exception "42P01"))))))


(deftest not-null-violation?-test
  (testing "returns true for 23502 (not_null_violation)"
    (is (true? (util/not-null-violation? (make-sql-exception "23502")))))

  (testing "returns false for other states"
    (is (false? (util/not-null-violation? (make-sql-exception "23505"))))
    (is (false? (util/not-null-violation? (make-sql-exception "42P01"))))))


(deftest check-constraint-violation?-test
  (testing "returns true for 23514 (check_violation)"
    (is (true? (util/check-constraint-violation? (make-sql-exception "23514")))))

  (testing "returns false for other states"
    (is (false? (util/check-constraint-violation? (make-sql-exception "23505"))))
    (is (false? (util/check-constraint-violation? (make-sql-exception "42P01"))))))


(deftest connection-error?-test
  (testing "returns true for connection failure class (08xxx)"
    (is (true? (util/connection-error? (make-sql-exception "08000"))))
    (is (true? (util/connection-error? (make-sql-exception "08001"))))
    (is (true? (util/connection-error? (make-sql-exception "08003"))))
    (is (true? (util/connection-error? (make-sql-exception "08006")))))

  (testing "returns false for other states"
    (is (false? (util/connection-error? (make-sql-exception "23505"))))
    (is (false? (util/connection-error? (make-sql-exception "42P01")))))

  (testing "returns false for null state"
    (is (false? (util/connection-error? (SQLException. "No state"))))))


(deftest query-canceled?-test
  (testing "returns true for 57014 (query_canceled)"
    (is (true? (util/query-canceled? (make-sql-exception "57014")))))

  (testing "returns false for other states"
    (is (false? (util/query-canceled? (make-sql-exception "23505"))))
    (is (false? (util/query-canceled? (make-sql-exception "42P01"))))))


(deftest classify-sql-error-test
  (testing "classifies unique violation"
    (is (= :unique-violation (util/classify-sql-error (make-sql-exception "23505")))))

  (testing "classifies foreign key violation"
    (is (= :foreign-key-violation (util/classify-sql-error (make-sql-exception "23503")))))

  (testing "classifies not null violation"
    (is (= :not-null-violation (util/classify-sql-error (make-sql-exception "23502")))))

  (testing "classifies check constraint violation"
    (is (= :check-constraint-violation (util/classify-sql-error (make-sql-exception "23514")))))

  (testing "classifies table not found"
    (is (= :table-not-found (util/classify-sql-error (make-sql-exception "42P01")))))

  (testing "classifies connection error"
    (is (= :connection-error (util/classify-sql-error (make-sql-exception "08001")))))

  (testing "classifies query timeout"
    (is (= :query-timeout (util/classify-sql-error (make-sql-exception "57014")))))

  (testing "returns unknown for unrecognized states"
    (is (= :unknown-sql-error (util/classify-sql-error (make-sql-exception "99999"))))
    (is (= :unknown-sql-error (util/classify-sql-error (make-sql-exception "00000"))))))


;; === Type Mapping Tests ===

(deftest kw->snake-case-test
  (testing "converts kebab-case to snake_case"
    (is (= "foo_bar" (util/kw->snake-case :foo-bar)))
    (is (= "my_field_name" (util/kw->snake-case :my-field-name))))

  (testing "leaves already snake_case unchanged"
    (is (= "foo_bar" (util/kw->snake-case :foo_bar))))

  (testing "handles single word"
    (is (= "foo" (util/kw->snake-case :foo)))))


(deftest ident->sql-test
  (testing "converts keyword to quoted SQL identifier"
    (is (= "\"foo_bar\"" (util/ident->sql :foo-bar)))
    (is (= "\"user\"" (util/ident->sql :user)))
    (is (= "\"order\"" (util/ident->sql :order)))))


(deftest field-type->pg-test
  (testing "maps basic types"
    (is (= "UUID" (util/field-type->pg {:type :uuid})))
    (is (= "TEXT" (util/field-type->pg {:type :text})))
    (is (= "BIGINT" (util/field-type->pg {:type :int})))
    (is (= "BOOLEAN" (util/field-type->pg {:type :bool})))
    (is (= "NUMERIC" (util/field-type->pg {:type :numeric})))
    (is (= "TIMESTAMPTZ" (util/field-type->pg {:type :timestamptz})))
    (is (= "JSONB" (util/field-type->pg {:type :jsonb})))
    (is (= "BYTEA" (util/field-type->pg {:type :bytes}))))

  (testing "maps ref type to UUID"
    (is (= "UUID" (util/field-type->pg {:type :ref}))))

  (testing "maps union type to JSONB"
    (is (= "JSONB" (util/field-type->pg {:type :union}))))

  (testing "maps enum type to quoted identifier"
    (is (= "\"my_status\"" (util/field-type->pg {:type :enum :enum-name :my-status}))))

  (testing "defaults unknown types to TEXT"
    (is (= "TEXT" (util/field-type->pg {:type :unknown-type})))))


(deftest check-snake-case-collisions!-test
  (testing "does not throw when no collisions"
    (is (nil? (util/check-snake-case-collisions! {} [:foo :bar :baz])))
    (is (nil? (util/check-snake-case-collisions! {} [:foo-bar :baz-qux]))))

  (testing "throws when collision detected"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Snake_case naming collision detected"
          (util/check-snake-case-collisions! {:context :test} [:foo-bar :foo_bar]))))

  (testing "includes collision details in exception"
    (try
      (util/check-snake-case-collisions! {:entity :user} [:my-field :my_field])
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= :user (:entity data)))
          (is (= 1 (count (:collisions data))))
          (is (= "my_field" (-> data :collisions first :snake-case)))
          (is (= #{:my-field :my_field} (set (-> data :collisions first :originals)))))))))


(deftest validate-sql-identifier!-test
  (testing "accepts valid identifiers"
    (is (nil? (util/validate-sql-identifier! "foo" {})))
    (is (nil? (util/validate-sql-identifier! "foo_bar" {})))
    (is (nil? (util/validate-sql-identifier! "my_table_123" {}))))

  (testing "accepts identifier at max length (63 chars)"
    (let [max-length-id (str/join (repeat 63 "a"))]
      (is (nil? (util/validate-sql-identifier! max-length-id {})))))

  (testing "rejects identifier exceeding max length"
    (let [too-long-id (str/join (repeat 64 "a"))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"SQL identifier too long"
            (util/validate-sql-identifier! too-long-id {}))))
    ;; Check exception data contains length info
    (try
      (util/validate-sql-identifier! (str/join (repeat 100 "a")) {:test true})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (= 100 (:length data)))
          (is (= 63 (:max-length data)))
          (is (= {:test true} (:context data)))))))

  (testing "rejects invalid identifiers"
    (is (thrown? clojure.lang.ExceptionInfo
          (util/validate-sql-identifier! "Foo" {})))      ; uppercase
    (is (thrown? clojure.lang.ExceptionInfo
          (util/validate-sql-identifier! "123foo" {})))   ; starts with number
    (is (thrown? clojure.lang.ExceptionInfo
          (util/validate-sql-identifier! "foo-bar" {})))  ; contains hyphen
    (is (thrown? clojure.lang.ExceptionInfo
          (util/validate-sql-identifier! "foo bar" {})))  ; contains space
    (is (thrown? clojure.lang.ExceptionInfo
          (util/validate-sql-identifier! "" {})))))       ; empty


(deftest enum-value->sql-test
  (testing "converts keyword to snake_case SQL value"
    (is (= "active" (util/enum-value->sql :active)))
    (is (= "in_progress" (util/enum-value->sql :in-progress))))

  (testing "rejects invalid enum values"
    (is (thrown? clojure.lang.ExceptionInfo
          (util/enum-value->sql :Invalid)))))     ; uppercase


(deftest sql->enum-value-test
  (testing "converts SQL value back to keyword"
    (is (= :active (util/sql->enum-value "active")))
    (is (= :in-progress (util/sql->enum-value "in_progress")))))


(deftest validate-pg-type!-test
  (testing "accepts valid PostgreSQL types"
    (is (nil? (util/validate-pg-type! "UUID" {})))
    (is (nil? (util/validate-pg-type! "TEXT" {})))
    (is (nil? (util/validate-pg-type! "BIGINT" {})))
    (is (nil? (util/validate-pg-type! "BOOLEAN" {})))
    (is (nil? (util/validate-pg-type! "NUMERIC" {})))
    (is (nil? (util/validate-pg-type! "TIMESTAMPTZ" {})))
    (is (nil? (util/validate-pg-type! "JSONB" {})))
    (is (nil? (util/validate-pg-type! "BYTEA" {}))))

  (testing "accepts quoted enum types"
    (is (nil? (util/validate-pg-type! "\"my_enum\"" {})))
    (is (nil? (util/validate-pg-type! "\"status_type\"" {}))))

  (testing "rejects invalid types"
    (is (thrown? clojure.lang.ExceptionInfo
          (util/validate-pg-type! "INVALID" {})))
    (is (thrown? clojure.lang.ExceptionInfo
          (util/validate-pg-type! "varchar(255)" {})))
    (is (thrown? clojure.lang.ExceptionInfo
          (util/validate-pg-type! "\"Invalid\"" {})))))   ; uppercase in quoted


;; === PostgresErrorClassifier Tests ===

(deftest postgres-error-classifier-test
  (let [classifier (util/create-error-classifier)]
    (testing "implements StorageErrorClassifier protocol"
      (is (satisfies? sp/StorageErrorClassifier classifier)))

    (testing "classify-error returns correct types for SQLException"
      (is (= :unique-violation (sp/classify-error classifier (make-sql-exception "23505"))))
      (is (= :foreign-key-violation (sp/classify-error classifier (make-sql-exception "23503"))))
      (is (= :not-null-violation (sp/classify-error classifier (make-sql-exception "23502"))))
      (is (= :table-not-found (sp/classify-error classifier (make-sql-exception "42P01"))))
      (is (= :query-timeout (sp/classify-error classifier (make-sql-exception "57014"))))
      (is (= :unknown-sql-error (sp/classify-error classifier (make-sql-exception "99999")))))

    (testing "classify-error returns :unknown-sql-error for non-SQLException"
      (is (= :unknown-sql-error (sp/classify-error classifier (ex-info "test" {}))))
      (is (= :unknown-sql-error (sp/classify-error classifier (Exception. "test")))))

    (testing "wrap-error returns ex-info with correct structure for SQLException"
      (let [ex (make-sql-exception "23505")
            wrapped (sp/wrap-error classifier ex :create-entity {:entity-name :user})]
        (is (instance? clojure.lang.ExceptionInfo wrapped))
        (let [data (ex-data wrapped)]
          (is (= :unique-violation (:type data)))
          (is (= :create-entity (:operation data)))
          (is (= :user (:entity-name data)))
          (is (= "23505" (:sql-state data))))))

    (testing "wrap-error handles non-SQLException"
      (let [ex (Exception. "generic error")
            wrapped (sp/wrap-error classifier ex :read-entity {:id "123"})]
        (is (instance? clojure.lang.ExceptionInfo wrapped))
        (let [data (ex-data wrapped)]
          (is (= :unknown-sql-error (:type data)))
          (is (= :read-entity (:operation data)))
          (is (= "123" (:id data))))))))


(deftest create-error-classifier-test
  (testing "creates PostgresErrorClassifier instance"
    (let [classifier (util/create-error-classifier)]
      (is (instance? graphden.postgres_storage.util.PostgresErrorClassifier classifier)))))
