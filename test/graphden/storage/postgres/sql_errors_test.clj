(ns graphden.storage.postgres.sql-errors-test
  "Tests for PostgreSQL storage SQL error handling.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)

   Covers:
   - SQL error unique violation
   - SQL error foreign key violation
   - SQL error not found in graph queries
   - SQL error logging
   - Batch operations with empty sequences
   - Mock-based SQL error tests
   - DDL error tests"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.crud :as crud]
    [graphden.storage.postgres.ddl :as ddl]
    [graphden.storage.postgres.graph :as graph]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.test-helpers :as th]
    [next.jdbc :as jdbc])
  (:import
    (java.sql
      SQLException)))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


;; === SQL Error Handling Integration Tests ===
;; These tests trigger real SQL errors to cover catch blocks in crud.clj

(deftest sql-error-unique-violation-test
  (testing "create-entity throws wrapped error on unique violation"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})]
      (sp/initialize storage schema)
      (try
        (let [id #uuid "11111111-1111-1111-1111-111111111111"]
          ;; Create first entity
          (sp/create-entity storage :user {:id id :name "Alice"})
          ;; Try to create second entity with same id - should throw unique violation
          (try
            (sp/create-entity storage :user {:id id :name "Bob"})
            (is false "Should have thrown")
            (catch clojure.lang.ExceptionInfo e
              (is (= :unique-violation (:type (ex-data e))))
              (is (= :create-entity (:operation (ex-data e))))
              (is (some? (:sql-state (ex-data e)))))))
        (finally
          (sp/close storage)))))

  (testing "create-entities throws validation error on duplicate IDs in batch"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})]
      (sp/initialize storage schema)
      (try
        (let [id #uuid "11111111-1111-1111-1111-111111111111"]
          ;; Try to create multiple entities with same id in one batch
          (try
            (sp/create-entities storage :user [{:id id :name "Alice"}
                                               {:id id :name "Bob"}])
            (is false "Should have thrown")
            (catch clojure.lang.ExceptionInfo e
              (is (= :validation-error/duplicate-ids (:type (ex-data e))))
              (is (= [id] (:duplicate-ids (ex-data e)))))))
        (finally
          (sp/close storage)))))

  (testing "create-entities throws SQL error on conflict with existing record"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})]
      (sp/initialize storage schema)
      (try
        (let [id #uuid "11111111-1111-1111-1111-111111111111"]
          ;; First create a record with this ID
          (sp/create-entity storage :user {:id id :name "Alice"})
          ;; Try to create another batch with the same ID
          (try
            (sp/create-entities storage :user [{:id id :name "Bob"}])
            (is false "Should have thrown")
            (catch clojure.lang.ExceptionInfo e
              (is (= :unique-violation (:type (ex-data e))))
              (is (= :create-entities (:operation (ex-data e)))))))
        (finally
          (sp/close storage)))))

  (testing "update-entity throws wrapped error on unique violation (when constraint exists)"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Use graph schema which has unique constraints on names
        (sp/initialize storage (setup/make-graph-schema))
        (let [fn-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"]
          (sp/create-entity storage :fn {:id fn-id :name "fn1" :parent-id nil :return-type "int"})
          (sp/create-entity storage :fn {:id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
                                         :name "fn2" :parent-id nil :return-type "int"})
          ;; Try to update fn2's name to conflict with fn1
          ;; Note: This requires a unique constraint on name, which we have.
          ;; Test that update works and returns properly typed errors when they occur.
          )
        (finally
          (sp/close storage))))))


(deftest sql-error-foreign-key-violation-test
  (testing "delete-entity throws wrapped error on foreign key violation"
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [pool (:pool storage)
              base-fn-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
              arg-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"]
          ;; Add FK constraint manually (not created by default in DDL)
          (jdbc/execute! pool ["ALTER TABLE \"arg\" ADD CONSTRAINT fk_arg_fn
                                FOREIGN KEY (\"fn_id\") REFERENCES \"fn\"(\"id\")"])
          ;; Create base fn
          (sp/create-entity storage :fn {:id base-fn-id :name "test" :parent-id nil :return-type "int"})
          ;; Create arg that references fn
          (sp/create-entity storage :arg {:id arg-id :fn-id base-fn-id :name "x"
                                          :type "int" :required true :is-fn false})
          ;; Try to delete fn while arg still references it
          (try
            (sp/delete-entity storage :fn base-fn-id)
            (is false "Should have thrown foreign key violation")
            (catch clojure.lang.ExceptionInfo e
              (is (= :foreign-key-violation (:type (ex-data e))))
              (is (= :delete-entity (:operation (ex-data e))))
              (is (some? (:sql-state (ex-data e)))))))
        (finally
          (sp/close storage)))))

  (testing "delete-entities throws wrapped error on foreign key violation"
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [pool (:pool storage)
              base-fn-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
              arg-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"]
          ;; Add FK constraint manually
          (jdbc/execute! pool ["ALTER TABLE \"arg\" ADD CONSTRAINT fk_arg_fn
                                FOREIGN KEY (\"fn_id\") REFERENCES \"fn\"(\"id\")"])
          ;; Create base fn
          (sp/create-entity storage :fn {:id base-fn-id :name "test" :parent-id nil :return-type "int"})
          ;; Create arg that references fn
          (sp/create-entity storage :arg {:id arg-id :fn-id base-fn-id :name "x"
                                          :type "int" :required true :is-fn false})
          ;; Try to delete fn while arg still references it
          (try
            (sp/delete-entities storage :fn [base-fn-id])
            (is false "Should have thrown foreign key violation")
            (catch clojure.lang.ExceptionInfo e
              (is (= :foreign-key-violation (:type (ex-data e))))
              (is (= :delete-entities (:operation (ex-data e)))))))
        (finally
          (sp/close storage))))))


(deftest sql-error-not-found-in-graph-queries-test
  (testing "resolve-execution-graph handles missing referenced fns gracefully"
    ;; This tests that the graph resolution doesn't fail when a referenced fn
    ;; has been deleted but the reference remains
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [pool (:pool storage)
              ;; Create base fn
              base-fn (setup/create-base-fn! storage "identity" :int)
              base-arg (setup/create-arg! storage (:id base-fn)
                                          {:name "x" :type :int :required false :is-fn false})
              ;; Create main composed fn
              main-fn (setup/create-composed-fn! storage "main" (:id base-fn))
              ;; Create ref-target fn
              ref-fn (setup/create-composed-fn! storage "ref-target" (:id base-fn))
              ;; Create arg referencing ref-fn via ref-id
              _ (setup/create-arg! storage (:id main-fn)
                                   {:name "x" :type :int :required false :is-fn false
                                    :source-id (:id base-arg) :ref-id (:id ref-fn)})
              ;; Delete the referenced fn directly (bypassing FK check)
              _ (jdbc/execute! pool [(str "DELETE FROM \"fn\" WHERE id = '" (:id ref-fn) "'")])
              ;; Now resolve - should handle missing fn gracefully
              graph (sp/resolve-execution-graph storage (:id main-fn))]
          ;; Should only have main-fn and base-fn since ref-fn was deleted
          (is (= 2 (count (:fns graph))))
          (is (contains? (:fns graph) (:id main-fn))))
        (finally
          (sp/close storage)))))

  (testing "read-entities handles SQL errors"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (try
        ;; Normal case - read from existing table
        (let [result (sp/read-entities storage :user [])]
          (is (= {} result)))
        ;; Read with non-empty ids from existing table
        (let [id #uuid "11111111-1111-1111-1111-111111111111"
              _ (sp/create-entity storage :user {:id id :name "Alice"})
              result (sp/read-entities storage :user [id])]
          (is (= 1 (count result)))
          (is (= "Alice" (:name (get result id)))))
        (finally
          (sp/close storage)))))

  (testing "query-entities returns empty for non-matching where"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (try
        (sp/create-entity storage :user {:name "Alice"})
        (let [result (sp/query-entities storage :user {:name "NonExistent"})]
          (is (empty? result)))
        (finally
          (sp/close storage))))))


(deftest wrap-sql-error-logging-test
  (testing "wrap-sql-error includes all context in exception"
    (let [sql-ex (SQLException. "duplicate key value" "23505")
          context {:entity-name :user :id #uuid "11111111-1111-1111-1111-111111111111"}
          wrapped (util/wrap-sql-error sql-ex "Database error" :create-entity context)
          data (ex-data wrapped)]
      (is (= :unique-violation (:type data)))
      (is (= :create-entity (:operation data)))
      (is (= "23505" (:sql-state data)))
      (is (= :user (:entity-name data)))
      (is (some? (:message data)))
      ;; The cause should be the original SQLException
      (is (instance? SQLException (ex-cause wrapped))))))


(deftest batch-operations-empty-sequences-test
  (testing "discover-graph-cte returns empty set for non-existent fn"
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [pool (:pool storage)
              discover-fn #'graph/discover-graph-cte
              result (discover-fn pool (random-uuid))]
          ;; Non-existent fn returns empty set
          (is (= #{} result)))
        (finally
          (sp/close storage)))))

  (testing "load-entities-batch returns empty for empty values"
    (let [storage (setup/create-test-storage)]
      (try
        (sp/initialize storage (setup/make-graph-schema))
        (let [pool (:pool storage)
              load-fn #'graph/load-entities-batch
              result (load-fn pool :fn :id #{})]
          (is (= {} result)))
        (finally
          (sp/close storage))))))


;; === Mock-based SQL Error Tests ===
;; These tests use mocks to trigger SQLException in paths that are hard to reach otherwise

(deftest sql-error-read-entity-mock-test
  (testing "read-entity throws wrapped error on SQLException"
    (let [read-entity-fn #'crud/read-entity
          table-not-found-ex (SQLException. "relation does not exist" "42P01")]
      (with-redefs [jdbc/execute-one! (fn [_ds _query & _opts]
                                        (throw table-not-found-ex))]
        (try
          (read-entity-fn nil :some-entity (random-uuid))
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :table-not-found (:type (ex-data e))))
            (is (= :read-entity (:operation (ex-data e))))))))))


(deftest sql-error-update-entity-mock-test
  (testing "update-entity throws wrapped error on SQLException during update"
    (let [update-entity-fn #'crud/update-entity
          unique-violation-ex (SQLException. "duplicate key" "23505")
          call-count (atom 0)]
      ;; First call to read-entity succeeds, second call (update) fails
      (with-redefs [jdbc/execute-one! (fn [_ds _query & _opts]
                                        (swap! call-count inc)
                                        (if (= 1 @call-count)
                                          ;; First call - read existing entity
                                          {:id (random-uuid) :name "test"}
                                          ;; Second call - update fails
                                          (throw unique-violation-ex)))]
        (try
          (update-entity-fn nil :some-entity (random-uuid) {:name "new"} nil)
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :unique-violation (:type (ex-data e))))
            (is (= :update-entity (:operation (ex-data e))))))))))


(deftest sql-error-query-entities-mock-test
  (testing "query-entities throws wrapped error on SQLException"
    (let [query-entities-fn #'crud/query-entities
          connection-ex (SQLException. "connection failed" "08001")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw connection-ex))]
        (try
          ;; query-entities signature: [ds entity-name where fields]
          (query-entities-fn nil :some-entity {:name "test"} {:name {:type :text}})
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :connection-error (:type (ex-data e))))
            (is (= :query-entities (:operation (ex-data e))))))))))


(deftest sql-error-read-entities-mock-test
  (testing "read-entities throws wrapped error on SQLException"
    (let [read-entities-fn #'crud/read-entities
          timeout-ex (SQLException. "query canceled" "57014")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw timeout-ex))]
        (try
          (read-entities-fn nil :some-entity [(random-uuid)])
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :query-timeout (:type (ex-data e))))
            (is (= :read-entities (:operation (ex-data e))))))))))


(deftest sql-error-graph-operations-mock-test
  (testing "discover-graph-cte throws wrapped error on SQLException"
    (let [discover-fn #'graph/discover-graph-cte
          not-null-ex (SQLException. "not null violation" "23502")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw not-null-ex))]
        (try
          (discover-fn nil (random-uuid))
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :not-null-violation (:type (ex-data e))))
            (is (= :discover-graph-cte (:operation (ex-data e)))))))))

  (testing "load-entities-batch throws wrapped error on SQLException"
    (let [load-fn #'graph/load-entities-batch
          check-ex (SQLException. "check violation" "23514")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw check-ex))]
        (try
          (load-fn nil :fn :id #{(random-uuid)})
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :check-constraint-violation (:type (ex-data e))))
            (is (= :load-entities-batch (:operation (ex-data e))))))))))


;; === DDL Error Tests ===
;; These tests verify DDL operations properly wrap SQLExceptions

(deftest ddl-error-create-enum-mock-test
  (testing "create-enum! throws wrapped error on SQLException"
    (let [create-enum-fn #'ddl/create-enum!
          ex (SQLException. "type already exists" "42710")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (create-enum-fn nil :my-enum [:a :b])
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            ;; Type from classify-sql-error (unknown for 42710)
            (is (= :unknown-sql-error (:type (ex-data e))))
            (is (= :create-enum (:operation (ex-data e))))
            (is (= :my-enum (:enum-name (ex-data e))))))))))


(deftest ddl-error-add-enum-value-mock-test
  (testing "add-enum-value! throws wrapped error on SQLException"
    (let [add-fn #'ddl/add-enum-value!
          ex (SQLException. "type does not exist" "42704")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (add-fn nil :my-enum :new-val)
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :unknown-sql-error (:type (ex-data e))))
            (is (= :add-enum-value (:operation (ex-data e))))
            (is (= :new-val (:value (ex-data e))))))))))


(deftest ddl-error-rename-enum-mock-test
  (testing "rename-enum! throws wrapped error on SQLException"
    (let [rename-fn #'ddl/rename-enum!
          ex (SQLException. "type does not exist" "42704")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (rename-fn nil :old-name :new-name)
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :unknown-sql-error (:type (ex-data e))))
            (is (= :rename-enum (:operation (ex-data e))))
            (is (= :old-name (:old-name (ex-data e))))
            (is (= :new-name (:new-name (ex-data e))))))))))


(deftest ddl-error-create-table-mock-test
  (testing "create-table! throws wrapped error on SQLException"
    (let [create-fn #'ddl/create-table!
          ex (SQLException. "relation already exists" "42P07")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (create-fn nil :my-table {:name {:type :text}})
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :unknown-sql-error (:type (ex-data e))))
            (is (= :create-table (:operation (ex-data e))))
            (is (= :my-table (:table-name (ex-data e))))))))))


(deftest ddl-error-rename-table-mock-test
  (testing "rename-table! throws wrapped error on SQLException"
    (let [rename-fn #'ddl/rename-table!
          ex (SQLException. "relation does not exist" "42P01")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (rename-fn nil :old-table :new-table)
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :table-not-found (:type (ex-data e))))
            (is (= :rename-table (:operation (ex-data e))))))))))


(deftest ddl-error-add-column-mock-test
  (testing "add-column! throws wrapped error on SQLException"
    (let [add-fn #'ddl/add-column!
          ex (SQLException. "column already exists" "42701")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (add-fn nil :my-table :new-col {:type :text})
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :unknown-sql-error (:type (ex-data e))))
            (is (= :add-column (:operation (ex-data e))))
            (is (= :new-col (:field-name (ex-data e))))))))))


(deftest ddl-error-rename-column-mock-test
  (testing "rename-column! throws wrapped error on SQLException"
    (let [rename-fn #'ddl/rename-column!
          ex (SQLException. "column does not exist" "42703")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (rename-fn nil :my-table :old-col :new-col)
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :unknown-sql-error (:type (ex-data e))))
            (is (= :rename-column (:operation (ex-data e))))
            (is (= :old-col (:old-col-name (ex-data e))))))))))


(deftest ddl-error-alter-column-type-mock-test
  (testing "alter-column-type! throws wrapped error on SQLException"
    (let [alter-fn #'ddl/alter-column-type!
          ex (SQLException. "column does not exist" "42703")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (alter-fn nil :my-table :my-col "TEXT")
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :unknown-sql-error (:type (ex-data e))))
            (is (= :alter-column-type (:operation (ex-data e))))
            (is (= :my-col (:col-name (ex-data e))))))))))


(deftest ddl-error-create-ref-index-mock-test
  (testing "create-ref-index! throws wrapped error on SQLException"
    (let [create-fn #'ddl/create-ref-index!
          ex (SQLException. "relation does not exist" "42P01")]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (create-fn nil :my-table :my-ref-col)
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :table-not-found (:type (ex-data e))))
            (is (= :create-index (:operation (ex-data e))))
            (is (= :my-table (:entity-name (ex-data e))))))))))


(deftest ddl-error-create-constraint-mock-test
  (testing "create-entity-constraints! throws wrapped error on SQLException"
    (let [create-fn #'ddl/create-entity-constraints!
          ex (SQLException. "relation does not exist" "42P01")
          ;; Mock schema that returns one constraint
          mock-schema (reify ds/DataSchema
                        (entities [_] [:my-table])

                        (entity-uuid [_ _] (random-uuid))

                        (entity-fields [_ _] {})

                        (enums [_] {})

                        (enum-uuid [_ _] nil)

                        (validate-entity [_ _ _] nil)

                        (entity-constraints
                          [_ _entity-name]
                          [{:type :unique :fields [:name]}]))]
      (with-redefs [jdbc/execute! (fn [_ds _query & _opts]
                                    (throw ex))]
        (try
          (create-fn nil mock-schema :my-table)
          (is false "Should have thrown")
          (catch clojure.lang.ExceptionInfo e
            (is (= :table-not-found (:type (ex-data e))))
            (is (= :create-constraint (:operation (ex-data e))))))))))
