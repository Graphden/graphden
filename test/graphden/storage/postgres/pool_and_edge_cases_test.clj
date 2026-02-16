(ns graphden.storage.postgres.pool-and-edge-cases-test
  "Tests for PostgreSQL storage pool management, timeouts, and edge cases."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.malli.interface :as mds]
    [graphden.schema.protocol.interface :as ds]
    [graphden.storage.postgres.core :as core]
    [graphden.storage.postgres.interface :as pg]
    [graphden.storage.postgres.introspection :as introspection]
    [graphden.storage.postgres.metadata :as metadata]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.interface :as sp]
    [graphden.storage.protocol.test-helpers :as th]
    [next.jdbc :as jdbc])
  (:import
    (com.zaxxer.hikari
      HikariDataSource)
    (java.sql
      SQLException)))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


;; === with-query-timeout tests ===

(deftest with-query-timeout-test
  (testing "with-query-timeout function changes timeout value (in milliseconds)"
    (is (= 30000 sp/*query-timeout-ms*) "Default should be 30000 ms")
    (pg/with-query-timeout 60000
                           (fn []
                             (is (= 60000 sp/*query-timeout-ms*) "Should be 60000 inside function")))
    (is (= 30000 sp/*query-timeout-ms*) "Should restore to 30000 after function"))

  (testing "with-query-timeout returns body result"
    (is (= 42 (pg/with-query-timeout 10000 #(+ 40 2)))))

  (testing "nested with-query-timeout works correctly"
    (pg/with-query-timeout 100000
                           (fn []
                             (is (= 100000 sp/*query-timeout-ms*))
                             (pg/with-query-timeout 200000
                                                    (fn []
                                                      (is (= 200000 sp/*query-timeout-ms*))))
                             (is (= 100000 sp/*query-timeout-ms*))))))


;; === Pool tests ===

(deftest close-pool-idempotency-test
  (testing "close-pool with nil pool returns true (no-op)"
    (is (true? (core/close-pool nil))))

  (testing "close-pool is idempotent - can be called multiple times"
    (let [pool (core/create-pool (merge (setup/get-container-config)
                                        {:pool-size 1 :min-idle 1}))]
      ;; First close - returns true on success
      (is (true? (core/close-pool pool)))
      (is (true? (HikariDataSource/.isClosed pool)))
      ;; Second close - returns true (pool already closed, no-op)
      (is (true? (core/close-pool pool)))
      (is (true? (HikariDataSource/.isClosed pool))))))


(deftest create-pool-validation-test
  (let [valid-opts (setup/get-container-config)]

    (testing "pool-size must be positive integer"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"pool-size must be a positive integer"
            (core/create-pool (assoc valid-opts :pool-size 0))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"pool-size must be a positive integer"
            (core/create-pool (assoc valid-opts :pool-size -1))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"pool-size must be a positive integer"
            (core/create-pool (assoc valid-opts :pool-size "10")))))

    (testing "min-idle must be positive integer"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"min-idle must be a positive integer"
            (core/create-pool (assoc valid-opts :min-idle 0))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"min-idle must be a positive integer"
            (core/create-pool (assoc valid-opts :min-idle -1)))))

    (testing "min-idle cannot exceed pool-size"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"min-idle cannot exceed pool-size"
            (core/create-pool (assoc valid-opts :pool-size 5 :min-idle 10)))))

    (testing "connection-timeout must be positive integer"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"connection-timeout must be a positive integer"
            (core/create-pool (assoc valid-opts :connection-timeout 0))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"connection-timeout must be a positive integer"
            (core/create-pool (assoc valid-opts :connection-timeout -1000)))))

    (testing "pool-size cannot exceed 100"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"pool-size exceeds maximum allowed value of 100"
            (core/create-pool (assoc valid-opts :pool-size 101)))))

    (testing "idle-timeout must be less than max-lifetime"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"idle-timeout must be less than max-lifetime"
            (core/create-pool (assoc valid-opts
                                     :idle-timeout 600000
                                     :max-lifetime 500000))))
      ;; Equal values should also fail
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"idle-timeout must be less than max-lifetime"
            (core/create-pool (assoc valid-opts
                                     :idle-timeout 600000
                                     :max-lifetime 600000)))))

    (testing "idle-timeout = 0 is allowed (never retire idle connections)"
      ;; idle-timeout = 0 is a special case meaning "never retire"
      ;; This should not throw even though 0 < max-lifetime
      (let [pool (core/create-pool (assoc valid-opts
                                          :idle-timeout 0
                                          :max-lifetime 1800000))]
        (is (some? pool))
        (core/close-pool pool)))))


;; === Unknown PostgreSQL type coverage tests ===

(deftest unknown-pg-type-coverage-test
  (testing "unknown postgres type falls through to default in current-columns"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000009001"
          field-uuid #uuid "00000000-0000-0000-0000-000000009002"]
      (try
        ;; Initialize with a normal schema to create the table
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid entity-uuid
                                     :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Add a column with an unusual postgres type directly
        (let [{:keys [jdbc-url username password]} (setup/get-container-config)]
          (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                                 :user username
                                                 :password password})]
            ;; Add a 'point' type column
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN location point"])))
        ;; Call the private current-columns function directly
        (let [current-columns-fn #'introspection/current-columns
              pool (:pool storage)
              columns (current-columns-fn pool "test_entity")]
          ;; The :location column should have type :point (unknown type passes through)
          (is (= :point (:type (:location columns)))))
        (finally
          (sp/close storage)))))

  (testing "current-columns handles timestamp without time zone"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000009010"
          field-uuid #uuid "00000000-0000-0000-0000-000000009011"]
      (try
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid entity-uuid
                                     :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Add a timestamp without time zone column
        (let [{:keys [jdbc-url username password]} (setup/get-container-config)]
          (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                                 :user username
                                                 :password password})]
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN created_at timestamp without time zone"])))
        (let [current-columns-fn #'introspection/current-columns
              pool (:pool storage)
              columns (current-columns-fn pool "test_entity")]
          ;; timestamp without time zone maps to :timestamptz
          (is (= :timestamptz (:type (:created-at columns)))))
        (finally
          (sp/close storage)))))

  (testing "current-columns handles all postgres type mappings"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000009020"
          field-uuid #uuid "00000000-0000-0000-0000-000000009021"]
      (try
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid entity-uuid
                                     :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Add columns with various postgres types
        (let [{:keys [jdbc-url username password]} (setup/get-container-config)]
          (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                                 :user username
                                                 :password password})]
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN is_active boolean"])
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN amount numeric"])
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN data bytea"])
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN big_count bigint"])
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN updated_at timestamp with time zone"])))
        (let [current-columns-fn #'introspection/current-columns
              pool (:pool storage)
              columns (current-columns-fn pool "test_entity")]
          ;; boolean maps to :bool
          (is (= :bool (:type (:is-active columns))))
          ;; numeric stays as :numeric
          (is (= :numeric (:type (:amount columns))))
          ;; bytea maps to :bytes
          (is (= :bytes (:type (:data columns))))
          ;; bigint maps to :int
          (is (= :int (:type (:big-count columns))))
          ;; timestamp with time zone maps to :timestamptz
          (is (= :timestamptz (:type (:updated-at columns)))))
        (finally
          (sp/close storage))))))


;; === Edge case coverage tests ===

(deftest edge-case-coverage-test
  (testing "parse-extra handles non-string non-PGobject values"
    ;; This covers the :else branch in parse-extra (line 160)
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000008001"
          field-uuid #uuid "00000000-0000-0000-0000-000000008002"]
      (try
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid entity-uuid
                                     :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Mock read-metadata-rows to return a row with extra as a number (not string/PGobject)
        (let [fake-rows [{:uuid entity-uuid :kind "entity" :name "test-entity" :parent_uuid nil :extra nil}
                         {:uuid field-uuid :kind "field" :name "name" :parent_uuid entity-uuid
                          :extra 12345}]] ; number instead of string/PGobject
          (with-redefs [metadata/read-metadata-rows (constantly fake-rows)]
            ;; schema-metadata uses parse-metadata-lenient which calls parse-extra
            (let [metadata (sp/schema-metadata storage)]
              ;; Should not throw, just parse what it can
              (is (some? metadata)))))
        (finally
          (sp/close storage)))))

  (testing "parse-extra handles string 'null' value"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000008050"
          field-uuid #uuid "00000000-0000-0000-0000-000000008051"]
      (try
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid entity-uuid
                                     :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Mock read-metadata-rows to return "null" string
        (let [fake-rows [{:uuid entity-uuid :kind "entity" :name "test-entity" :parent_uuid nil :extra "null"}
                         {:uuid field-uuid :kind "field" :name "name" :parent_uuid entity-uuid
                          :extra "null"}]]
          (with-redefs [metadata/read-metadata-rows (constantly fake-rows)]
            (let [metadata (sp/schema-metadata storage)]
              (is (some? metadata)))))
        (finally
          (sp/close storage)))))

  (testing "parse-extra handles empty JSON object string"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000008060"
          field-uuid #uuid "00000000-0000-0000-0000-000000008061"]
      (try
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid entity-uuid
                                     :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Mock read-metadata-rows to return "{}" string
        (let [fake-rows [{:uuid entity-uuid :kind "entity" :name "test-entity" :parent_uuid nil :extra "{}"}
                         {:uuid field-uuid :kind "field" :name "name" :parent_uuid entity-uuid
                          :extra "{}"}]]
          (with-redefs [metadata/read-metadata-rows (constantly fake-rows)]
            (let [metadata (sp/schema-metadata storage)]
              (is (some? metadata)))))
        (finally
          (sp/close storage)))))

  (testing "parse-extra handles raw string input"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000008070"
          field-uuid #uuid "00000000-0000-0000-0000-000000008071"]
      (try
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid entity-uuid
                                     :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Mock read-metadata-rows to return a valid JSON string
        (let [fake-rows [{:uuid entity-uuid :kind "entity" :name "test-entity" :parent_uuid nil :extra nil}
                         {:uuid field-uuid :kind "field" :name "name" :parent_uuid entity-uuid
                          :extra "{\"type\": \"text\", \"nullable?\": \"false\"}"}]]
          (with-redefs [metadata/read-metadata-rows (constantly fake-rows)]
            (let [metadata (sp/schema-metadata storage)]
              (is (some? metadata))
              ;; Should have parsed the string values to keywords
              (is (= :text (:type (val (first (:fields metadata)))))))))
        (finally
          (sp/close storage)))))

  (testing "parse-extra handles empty string"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000008080"
          field-uuid #uuid "00000000-0000-0000-0000-000000008081"]
      (try
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid entity-uuid
                                     :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Mock read-metadata-rows to return empty string for extra
        (let [fake-rows [{:uuid entity-uuid :kind "entity" :name "test-entity" :parent_uuid nil :extra ""}
                         {:uuid field-uuid :kind "field" :name "name" :parent_uuid entity-uuid
                          :extra ""}]]
          (with-redefs [metadata/read-metadata-rows (constantly fake-rows)]
            (let [metadata (sp/schema-metadata storage)]
              ;; Should work, extra is just nil
              (is (some? metadata)))))
        (finally
          (sp/close storage))))))


;; === Uninitialized storage tests ===

(deftest uninitialized-storage-test
  (testing "current-fields returns nil on uninitialized storage"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Don't initialize - just try to read fields
        ;; This exercises the try/catch in current-fields
        (is (nil? (sp/current-fields storage :nonexistent)))
        (finally
          (sp/close storage)))))

  (testing "schema-metadata returns nil on uninitialized storage"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Don't initialize - just try to read metadata
        ;; This exercises the try/catch in schema-metadata
        (is (nil? (sp/schema-metadata storage)))
        (finally
          (sp/close storage))))))


;; === Metadata/DB inconsistency tests ===

(deftest metadata-db-inconsistency-test
  (testing "detects when metadata says field exists but DB column is missing"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000007001"
          field-uuid #uuid "00000000-0000-0000-0000-000000007002"
          schema1 (th/make-schema :entity-name :user
                                  :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})]
      (try
        ;; First initialize normally
        (sp/initialize storage schema1)
        ;; Mock parse-metadata to return metadata claiming a non-existent field
        (let [fake-metadata {:entities {entity-uuid :user}
                             :fields {field-uuid {:entity :user
                                                  :field :name
                                                  :type :text
                                                  :nullable? false}
                                      ;; This field doesn't exist in DB!
                                      #uuid "00000000-0000-0000-0000-000000007099"
                                      {:entity :user
                                       :field :ghost-field
                                       :type :text
                                       :nullable? false}}
                             :enums {}
                             :enum-values {}}
              schema2 (-> (mds/create-builder)
                          (ds/add-entity :user entity-uuid
                                         {:name {:uuid field-uuid :type :text}
                                          :ghost-field {:uuid #uuid "00000000-0000-0000-0000-000000007099"
                                                        :type :text}})
                          ds/build)]
          (with-redefs [metadata/parse-metadata (constantly fake-metadata)]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Metadata/DB inconsistency"
                  (sp/initialize storage schema2)))))
        (finally
          (sp/close storage))))))


;; === Table-not-found error handling tests ===

(deftest table-not-found-error-handling-test
  (testing "table-not-found? returns true for SQLState 42P01"
    (let [e (SQLException. "relation does not exist" "42P01")]
      (is (true? (#'util/table-not-found? e)))))

  (testing "table-not-found? returns false for other SQLState"
    (let [e (SQLException. "connection failed" "08001")]
      (is (false? (#'util/table-not-found? e)))))

  (testing "current-fields re-throws non-42P01 SQLException"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Mock read-metadata-rows to throw a non-42P01 SQLException
        (let [connection-error (SQLException. "connection failed" "08001")]
          (with-redefs [metadata/read-metadata-rows (fn [_] (throw connection-error))]
            (is (thrown? SQLException (sp/current-fields storage :any-entity)))))
        (finally
          (sp/close storage)))))

  (testing "schema-metadata re-throws non-42P01 SQLException"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Mock read-metadata-rows to throw a non-42P01 SQLException
        (let [connection-error (SQLException. "connection failed" "08001")]
          (with-redefs [metadata/read-metadata-rows (fn [_] (throw connection-error))]
            (is (thrown? SQLException (sp/schema-metadata storage)))))
        (finally
          (sp/close storage))))))


;; === Unknown kind strict mode test ===

(deftest unknown-kind-strict-mode-test
  (testing "unknown kind in strict mode falls through to default"
    ;; This covers the acc))) fallback (line 213) in strict parsing
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000008010"
          field-uuid #uuid "00000000-0000-0000-0000-000000008011"]
      (try
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid entity-uuid
                                     :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Insert unknown kind directly
        (let [{:keys [jdbc-url username password]} (setup/get-container-config)
              orphan-uuid (random-uuid)]
          (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                                 :user username
                                                 :password password})]
            (jdbc/execute! conn
                           ["INSERT INTO _schema_metadata (uuid, kind, name, parent_uuid) VALUES (?, ?, ?, ?)"
                            orphan-uuid "weird-kind" "mystery" nil])))
        ;; Second initialize uses strict parsing - should skip unknown kind
        (let [schema2 (th/make-schema :entity-name :test-entity
                                      :entity-uuid entity-uuid
                                      :fields {:name {:uuid field-uuid :type :text}
                                               :email {:uuid #uuid "00000000-0000-0000-0000-000000008012"
                                                       :type :text}})
              changes (sp/initialize storage schema2)]
          ;; Should succeed - unknown kind is just skipped in the reduce
          (is (= [{:entity :test-entity :field :email}] (:created (:fields changes)))))
        (finally
          (sp/close storage))))))
