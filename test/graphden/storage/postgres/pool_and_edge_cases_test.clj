(ns graphden.storage.postgres.pool-and-edge-cases-test
  "Tests for PostgreSQL storage pool management, timeouts, and edge cases.

   Parallel-safe (previously `^:serial` for `with-redefs` over
   `metadata/read-metadata-rows` / `metadata/parse-metadata` — both
   process-global root rebinds):
   - the parse-extra edge cases call the PURE
     `metadata/parse-metadata-lenient` directly on fixture rows;
   - the metadata/DB-inconsistency test plants a real corrupted
     `_schema_metadata` row instead of stubbing `parse-metadata`;
   - the SQLException-rethrow tests bind the thread-local
     `metadata/*read-rows-override*` seam (house pattern —
     `advisory-lock/*impl-override*`)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.postgres.introspection :as introspection]
    [graphden.storage.postgres.metadata :as metadata]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.test-helpers :as th]
    [next.jdbc :as jdbc])
  (:import
    (com.zaxxer.hikari
      HikariDataSource)
    (java.sql
      SQLException)
    (javax.sql
      DataSource)))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


;; === with-query-timeout tests ===
(deftest close-pool-idempotency-test
  (testing "close-pool with nil pool returns true (no-op)"
    (is (true? (pg/close-pool nil))))

  (testing "close-pool is idempotent - can be called multiple times"
    (let [pool (pg/create-pool (merge (setup/get-container-config)
                                      {:pool-size 1 :min-idle 1}))]
      ;; First close - returns true on success
      (is (true? (pg/close-pool pool)))
      (is (true? (HikariDataSource/.isClosed pool)))
      ;; Second close - returns true (pool already closed, no-op)
      (is (true? (pg/close-pool pool)))
      (is (true? (HikariDataSource/.isClosed pool))))))


(deftest create-pool-validation-test
  (let [valid-opts (setup/get-container-config)]

    (testing "jdbc-url is required"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"jdbc-url is required"
            (pg/create-pool (dissoc valid-opts :jdbc-url)))))

    (testing "jdbc-url must be a string"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"jdbc-url must be a string"
            (pg/create-pool (assoc valid-opts :jdbc-url 12345)))))

    (testing "jdbc-url must start with jdbc:postgresql://"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"jdbc-url must start with 'jdbc:postgresql://'"
            (pg/create-pool (assoc valid-opts :jdbc-url "jdbc:mysql://localhost/db")))))

    (testing "username is required and non-empty"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"username is required and cannot be empty"
            (pg/create-pool (dissoc valid-opts :username))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"username is required and cannot be empty"
            (pg/create-pool (assoc valid-opts :username ""))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"username is required and cannot be empty"
            (pg/create-pool (assoc valid-opts :username "   ")))))

    (testing "password is required and non-empty"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"password is required and cannot be empty"
            (pg/create-pool (dissoc valid-opts :password))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"password is required and cannot be empty"
            (pg/create-pool (assoc valid-opts :password ""))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"password is required and cannot be empty"
            (pg/create-pool (assoc valid-opts :password "   ")))))

    (testing "pool-size must be positive integer"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"pool-size must be a positive integer"
            (pg/create-pool (assoc valid-opts :pool-size 0))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"pool-size must be a positive integer"
            (pg/create-pool (assoc valid-opts :pool-size -1))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"pool-size must be a positive integer"
            (pg/create-pool (assoc valid-opts :pool-size "10")))))

    (testing "min-idle must be positive integer"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"min-idle must be a positive integer"
            (pg/create-pool (assoc valid-opts :min-idle 0))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"min-idle must be a positive integer"
            (pg/create-pool (assoc valid-opts :min-idle -1)))))

    (testing "min-idle cannot exceed pool-size"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"min-idle cannot exceed pool-size"
            (pg/create-pool (assoc valid-opts :pool-size 5 :min-idle 10)))))

    (testing "connection-timeout must be positive integer"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"connection-timeout must be a positive integer"
            (pg/create-pool (assoc valid-opts :connection-timeout 0))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"connection-timeout must be a positive integer"
            (pg/create-pool (assoc valid-opts :connection-timeout -1000)))))

    (testing "pool-size cannot exceed 100"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"pool-size exceeds maximum allowed value of 100"
            (pg/create-pool (assoc valid-opts :pool-size 101)))))

    (testing "idle-timeout must be less than max-lifetime"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"idle-timeout must be less than max-lifetime"
            (pg/create-pool (assoc valid-opts
                                   :idle-timeout 600000
                                   :max-lifetime 500000))))
      ;; Equal values should also fail
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"idle-timeout must be less than max-lifetime"
            (pg/create-pool (assoc valid-opts
                                   :idle-timeout 600000
                                   :max-lifetime 600000)))))

    (testing "idle-timeout = 0 is allowed (never retire idle connections)"
      ;; idle-timeout = 0 is a special case meaning "never retire"
      ;; This should not throw even though 0 < max-lifetime
      (let [pool (pg/create-pool (assoc valid-opts
                                        :idle-timeout 0
                                        :max-lifetime 1800000))]
        (is (some? pool))
        (pg/close-pool pool)))))


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
  ;; `parse-metadata-lenient` (and the `parse-extra` it wraps) is PURE —
  ;; feed it fixture rows directly instead of stubbing
  ;; `read-metadata-rows` under a live storage. Same rows the old
  ;; stubbed `sp/schema-metadata` round-trip fed it.
  (let [entity-uuid #uuid "00000000-0000-0000-0000-000000008001"
        field-uuid #uuid "00000000-0000-0000-0000-000000008002"
        rows (fn [entity-extra field-extra]
               [{:uuid entity-uuid :kind "entity" :name "test-entity"
                 :parent_uuid nil :extra entity-extra}
                {:uuid field-uuid :kind "field" :name "name"
                 :parent_uuid entity-uuid :extra field-extra}])
        plain-field-entry {:entity :test-entity :field :name}]
    (testing "parse-extra handles non-string non-PGobject values"
      ;; Number instead of string/PGobject — covers the :else branch in
      ;; parse-extra.
      (let [md (metadata/parse-metadata-lenient (rows nil 12345))]
        (is (= plain-field-entry (get-in md [:fields field-uuid]))
            "non-map extra is dropped; the field entry survives without a type")))

    (testing "parse-extra handles string 'null' value"
      (let [md (metadata/parse-metadata-lenient (rows "null" "null"))]
        (is (= plain-field-entry (get-in md [:fields field-uuid])))))

    (testing "parse-extra handles empty JSON object string"
      (let [md (metadata/parse-metadata-lenient (rows "{}" "{}"))]
        (is (= plain-field-entry (get-in md [:fields field-uuid])))))

    (testing "parse-extra handles raw string input"
      (let [md (metadata/parse-metadata-lenient
                 (rows nil "{\"type\": \"text\", \"nullable?\": \"false\"}"))]
        ;; String values are parsed back to keywords.
        (is (= :text (get-in md [:fields field-uuid :type])))))

    (testing "parse-extra handles empty string"
      (let [md (metadata/parse-metadata-lenient (rows "" ""))]
        (is (= plain-field-entry (get-in md [:fields field-uuid])))))))


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
          ghost-uuid #uuid "00000000-0000-0000-0000-000000007099"
          schema1 (th/make-schema :entity-name :user
                                  :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})]
      (try
        ;; First initialize normally
        (sp/initialize storage schema1)
        ;; Plant REAL corrupted state instead of stubbing `parse-metadata`:
        ;; a `_schema_metadata` row claiming a `ghost-field` that has no
        ;; matching DB column. The strict re-init parse reads it off the
        ;; actual table. Extra mirrors what `save-metadata-in-tx!` writes
        ;; (boolean nullable?, string type).
        (let [{:keys [jdbc-url username password]} (setup/get-container-config)]
          (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                                 :user username
                                                 :password password})]
            (jdbc/execute! conn
                           ["INSERT INTO _schema_metadata (uuid, kind, name, parent_uuid, extra) VALUES (?, ?, ?, ?, ?::jsonb)"
                            ghost-uuid "field" "ghost-field" entity-uuid
                            "{\"type\": \"text\", \"nullable?\": false}"])))
        ;; Re-initialize with a schema that declares the ghost field under
        ;; the same uuid — the field verifier finds it in old metadata but
        ;; no `ghost_field` column in the table.
        (let [schema2 (-> (mds/create-builder)
                          (ds/add-entity :user entity-uuid
                                         {:name {:uuid field-uuid :type :text}
                                          :ghost-field {:uuid ghost-uuid
                                                        :type :text}})
                          ds/build)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Metadata/DB inconsistency"
                (sp/initialize storage schema2))))
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
        ;; Thread-local seam (not `with-redefs`): make the metadata read
        ;; throw a non-42P01 SQLException.
        (let [connection-error (SQLException. "connection failed" "08001")]
          (binding [metadata/*read-rows-override* (fn [_] (throw connection-error))]
            (is (thrown? SQLException (sp/current-fields storage :any-entity)))))
        (finally
          (sp/close storage)))))

  (testing "schema-metadata re-throws non-42P01 SQLException"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Thread-local seam (not `with-redefs`): make the metadata read
        ;; throw a non-42P01 SQLException.
        (let [connection-error (SQLException. "connection failed" "08001")]
          (binding [metadata/*read-rows-override* (fn [_] (throw connection-error))]
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


;; `with-query-timeout`'s binding semantics — including nesting and
;; restoration — are pinned by `protocol.config-test`, which owns the fn.


(deftest close-pool-through-a-datasource-wrap-test
  ;; The tenancy addon's `:datasource-wrap` seam hands `:db/postgres` a
  ;; `reify DataSource` around the Hikari pool, and THAT is what halt passes
  ;; to `close-pool`. The cast to HikariDataSource threw out of the shutdown
  ;; hook in production (2026-09-03) and the pool was never closed. The
  ;; contract is the JDBC `Wrapper` protocol, so a wrap that delegates it
  ;; closes through; one that does not is left open (and warned about),
  ;; never cast.
  (let [delegating (fn [^DataSource ds]
                     (reify DataSource
                       (getConnection [_] (DataSource/.getConnection ds))

                       (getConnection [_ u p] (DataSource/.getConnection ds u p))

                       (getLoginTimeout [_] (DataSource/.getLoginTimeout ds))

                       (setLoginTimeout [_ n] (DataSource/.setLoginTimeout ds n))

                       (getLogWriter [_] (DataSource/.getLogWriter ds))

                       (setLogWriter [_ w] (DataSource/.setLogWriter ds w))

                       (getParentLogger [_] (DataSource/.getParentLogger ds))

                       (unwrap [_ iface] (DataSource/.unwrap ds iface))

                       (isWrapperFor [_ iface] (DataSource/.isWrapperFor ds iface))))
        opaque (fn [^DataSource ds]
                 (reify DataSource
                   (getConnection [_] (DataSource/.getConnection ds))

                   (getConnection [_ u p] (DataSource/.getConnection ds u p))

                   (getLoginTimeout [_] 0)

                   (setLoginTimeout [_ _] nil)

                   (getLogWriter [_] nil)

                   (setLogWriter [_ _] nil)

                   (getParentLogger [_] nil)

                   (unwrap [_ _] (throw (SQLException. "not a wrapper")))

                   (isWrapperFor [_ _] false)))]
    (testing "a wrap that delegates the JDBC Wrapper protocol closes the pool behind it"
      (let [pool (pg/create-pool (merge (setup/get-container-config) {:pool-size 1 :min-idle 1}))]
        (is (true? (pg/close-pool (delegating pool))))
        (is (true? (HikariDataSource/.isClosed pool)))
        (is (true? (pg/close-pool (delegating pool))) "still idempotent through the wrap")))
    (testing "a wrap that does not unwrap is left alone — no cast, no throw"
      (let [pool (pg/create-pool (merge (setup/get-container-config) {:pool-size 1 :min-idle 1}))]
        (try
          (is (true? (pg/close-pool (opaque pool))))
          (is (false? (HikariDataSource/.isClosed pool)) "the real pool was not reached, and is still open")
          (finally (pg/close-pool pool)))))))
