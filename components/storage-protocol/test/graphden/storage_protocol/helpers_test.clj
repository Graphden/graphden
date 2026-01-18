(ns graphden.storage-protocol.helpers-test
  "Tests for storage-protocol helper functions and utilities."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.storage-protocol.interface :as storage]))


;; === Mock ConstraintHelpers for testing shared implementations ===
;; (duplicated from constraints_test.clj - needed for dependency cycle tests)

(defrecord MockConstraintHelpers
  [fn-schema-map arg-schema-fn-schema-map parent-map arg-schema-ids-in-chain-map dependency-chain-map]

  storage/ConstraintHelpers

  (get-fn-schema-id-for-fn
    [_this fn-id]
    (get fn-schema-map fn-id))


  (get-fn-schema-id-for-arg-schema
    [_this arg-schema-id]
    (get arg-schema-fn-schema-map arg-schema-id))


  (get-parent-fn-id
    [_this fn-id]
    (get parent-map fn-id))


  (collect-parent-chain
    [this fn-id]
    (storage/collect-parent-chain-impl this fn-id))


  (collect-arg-schema-ids-in-chain
    [_this fn-id]
    (get arg-schema-ids-in-chain-map fn-id #{}))


  (collect-dependency-chain
    [_this fn-id]
    (get dependency-chain-map fn-id #{fn-id})))


;; === merge-arg-values-for-chain tests ===

(deftest merge-arg-values-for-chain-test
  (testing "returns nil for empty chain"
    (is (nil? (storage/merge-arg-values-for-chain [] []))))

  (testing "returns nil for nil chain via (seq nil)"
    (is (nil? (storage/merge-arg-values-for-chain [] nil))))

  (testing "child arg-value overrides parent"
    (let [parent-fn-id (random-uuid)
          child-fn-id (random-uuid)
          arg-schema-id (random-uuid)
          arg-values [{:id (random-uuid)
                       :owner-fn-id parent-fn-id
                       :arg-schema-id arg-schema-id
                       :value "parent-value"}
                      {:id (random-uuid)
                       :owner-fn-id child-fn-id
                       :arg-schema-id arg-schema-id
                       :value "child-value"}]
          ;; Chain: child -> parent (child first = lower position = wins)
          chain [child-fn-id parent-fn-id]
          result (storage/merge-arg-values-for-chain arg-values chain)]
      (is (= "child-value" (:value (get result arg-schema-id))))))

  (testing "uses Long/MAX_VALUE fallback for unknown owner"
    ;; This tests the edge case where an arg-value has an owner not in the chain
    (let [known-fn-id (random-uuid)
          unknown-fn-id (random-uuid)
          arg-schema-id (random-uuid)
          arg-values [{:id (random-uuid)
                       :owner-fn-id known-fn-id
                       :arg-schema-id arg-schema-id
                       :value "known"}
                      {:id (random-uuid)
                       :owner-fn-id unknown-fn-id
                       :arg-schema-id arg-schema-id
                       :value "unknown"}]
          chain [known-fn-id]
          result (storage/merge-arg-values-for-chain arg-values chain)]
      ;; Known owner should win (has lower position than MAX_VALUE)
      (is (= "known" (:value (get result arg-schema-id))))))

  (testing "handles multiple arg-schemas correctly"
    (let [fn-id (random-uuid)
          arg-schema-1 (random-uuid)
          arg-schema-2 (random-uuid)
          arg-values [{:owner-fn-id fn-id :arg-schema-id arg-schema-1 :value 1}
                      {:owner-fn-id fn-id :arg-schema-id arg-schema-2 :value 2}]
          chain [fn-id]
          result (storage/merge-arg-values-for-chain arg-values chain)]
      (is (= 1 (:value (get result arg-schema-1))))
      (is (= 2 (:value (get result arg-schema-2)))))))


;; === extract-uuid-refs-from-arg-values tests ===

(deftest extract-uuid-refs-from-arg-values-test
  (testing "extracts UUID values"
    (let [uuid1 (random-uuid)
          uuid2 (random-uuid)
          k1 (random-uuid)
          k2 (random-uuid)
          arg-values-map {k1 {:value uuid1}
                          k2 {:value uuid2}}
          result (storage/extract-uuid-refs-from-arg-values arg-values-map)]
      (is (= #{uuid1 uuid2} result))))

  (testing "parses UUID strings"
    (let [uuid1 (random-uuid)
          k1 (random-uuid)
          arg-values-map {k1 {:value (str uuid1)}}
          result (storage/extract-uuid-refs-from-arg-values arg-values-map)]
      (is (= #{uuid1} result))))

  (testing "ignores non-UUID values"
    (let [k1 (random-uuid)
          k2 (random-uuid)
          k3 (random-uuid)
          arg-values-map {k1 {:value "not-a-uuid"}
                          k2 {:value 123}
                          k3 {:value nil}}
          result (storage/extract-uuid-refs-from-arg-values arg-values-map)]
      (is (= #{} result))))

  (testing "handles empty map"
    (is (= #{} (storage/extract-uuid-refs-from-arg-values {}))))

  (testing "handles mixed values"
    (let [uuid1 (random-uuid)
          k1 (random-uuid)
          k2 (random-uuid)
          k3 (random-uuid)
          arg-values-map {k1 {:value uuid1}
                          k2 {:value "not-a-uuid"}
                          k3 {:value 42}}
          result (storage/extract-uuid-refs-from-arg-values arg-values-map)]
      (is (= #{uuid1} result)))))


;; === needs-special-encoding? tests ===

(deftest needs-special-encoding?-test
  (testing "returns true for JSONB type"
    (is (true? (storage/needs-special-encoding? :jsonb))))

  (testing "returns true for union type"
    (is (true? (storage/needs-special-encoding? :union))))

  (testing "returns true for enum type"
    (is (true? (storage/needs-special-encoding? :enum))))

  (testing "returns false for basic types"
    (is (false? (storage/needs-special-encoding? :text)))
    (is (false? (storage/needs-special-encoding? :int)))
    (is (false? (storage/needs-special-encoding? :bool)))
    (is (false? (storage/needs-special-encoding? :uuid)))
    (is (false? (storage/needs-special-encoding? :ref)))
    (is (false? (storage/needs-special-encoding? :numeric)))
    (is (false? (storage/needs-special-encoding? :timestamptz)))
    (is (false? (storage/needs-special-encoding? :bytes)))))


;; === default-query-timeout-ms tests ===

(deftest default-query-timeout-ms-test
  (testing "default timeout is 30 seconds"
    (is (= 30000 storage/default-query-timeout-ms)))

  (testing "timeout is a positive number"
    (is (pos? storage/default-query-timeout-ms))))


;; === storage-error-types tests ===

(deftest storage-error-types-test
  (testing "contains all expected error types"
    (is (contains? storage/storage-error-types :unique-violation))
    (is (contains? storage/storage-error-types :foreign-key-violation))
    (is (contains? storage/storage-error-types :not-null-violation))
    (is (contains? storage/storage-error-types :check-constraint-violation))
    (is (contains? storage/storage-error-types :table-not-found))
    (is (contains? storage/storage-error-types :connection-error))
    (is (contains? storage/storage-error-types :system-error/query-timeout))
    (is (contains? storage/storage-error-types :parse-error))
    (is (contains? storage/storage-error-types :unknown-sql-error)))

  (testing "is a set"
    (is (set? storage/storage-error-types))))


;; === StorageErrorClassifier protocol tests ===

(deftest storage-error-classifier-protocol-test
  (testing "StorageErrorClassifier protocol is defined"
    (is (some? storage/StorageErrorClassifier))
    (is (contains? (:sigs storage/StorageErrorClassifier) :classify-error))
    (is (contains? (:sigs storage/StorageErrorClassifier) :wrap-error))))


;; === Storage Implementation Helpers tests ===

(deftest create-rw-lock-test
  (testing "creates ReentrantReadWriteLock"
    (let [lock (storage/create-rw-lock)]
      (is (instance? java.util.concurrent.locks.ReentrantReadWriteLock lock)))))


(deftest standard-crud-validations!-test
  (testing "passes for valid data"
    (is (nil? (storage/standard-crud-validations! :user {:name "test"} nil)))
    (is (nil? (storage/standard-crud-validations!
                :user
                {:name "test"}
                {:name {:required true}}))))

  (testing "throws for non-map data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data must be a map"
          (storage/standard-crud-validations! :user "not a map" nil))))

  (testing "throws for missing required fields"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Required field.*missing"
          (storage/standard-crud-validations!
            :user
            {:other "field"}
            {:name {:required true}})))))


(deftest standard-batch-validations!-test
  (testing "passes for unique IDs"
    (let [id1 (random-uuid)
          id2 (random-uuid)]
      (is (nil? (storage/standard-batch-validations! :user [{:id id1} {:id id2}])))))

  (testing "throws for duplicate IDs"
    (let [dup-id (random-uuid)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Duplicate IDs"
            (storage/standard-batch-validations! :user [{:id dup-id} {:id dup-id}]))))))


;; === Error helpers tests ===

(deftest make-error-context-test
  (testing "creates error context with required fields"
    (let [ctx (storage/make-error-context :test-error :create "Test message" {:entity :user})]
      (is (= :test-error (:type ctx)))
      (is (= :create (:operation ctx)))
      (is (= "Test message" (:message ctx)))
      (is (= :user (:entity ctx)))))

  (testing "merges additional context"
    (let [ctx (storage/make-error-context :error-type :read "msg" {:id 123 :extra "data"})]
      (is (= :error-type (:type ctx)))
      (is (= :read (:operation ctx)))
      (is (= 123 (:id ctx)))
      (is (= "data" (:extra ctx))))))


(deftest make-storage-error-test
  (testing "creates storage error without cause"
    (let [err (storage/make-storage-error :test-error :create "Test message" {:entity :user})]
      (is (instance? clojure.lang.ExceptionInfo err))
      (is (= "Test message" (ex-message err)))
      (is (= :test-error (:type (ex-data err))))
      (is (= :create (:operation (ex-data err))))
      (is (= :user (:entity (ex-data err))))
      (is (nil? (ex-cause err)))))

  (testing "creates storage error with cause"
    (let [cause (ex-info "Original error" {:original true})
          err (storage/make-storage-error :wrapped-error :update "Wrapped" {:id 42} cause)]
      (is (instance? clojure.lang.ExceptionInfo err))
      (is (= "Wrapped" (ex-message err)))
      (is (= :wrapped-error (:type (ex-data err))))
      (is (= :update (:operation (ex-data err))))
      (is (= 42 (:id (ex-data err))))
      (is (= cause (ex-cause err)))
      (is (= "Original error" (ex-message (ex-cause err)))))))


(deftest validate-no-dependency-cycle-impl-test
  (testing "nil value-fn-id doesn't throw"
    (let [helpers (->MockConstraintHelpers {} {} {} {} {})]
      (is (nil? (storage/validate-no-dependency-cycle-impl helpers (random-uuid) nil)))))

  (testing "no cycle in dependencies doesn't throw"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          ;; fn-b depends on nothing special, fn-a not in its chain
          helpers (->MockConstraintHelpers {} {} {} {} {fn-b #{fn-b}})]
      (is (nil? (storage/validate-no-dependency-cycle-impl helpers fn-a fn-b)))))

  (testing "cycle in dependencies throws"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          ;; fn-b already depends on fn-a
          helpers (->MockConstraintHelpers {} {} {} {} {fn-b #{fn-a fn-b}})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Reference would create dependency cycle"
            (storage/validate-no-dependency-cycle-impl helpers fn-a fn-b)))))

  (testing "exception contains correct data"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          helpers (->MockConstraintHelpers {} {} {} {} {fn-b #{fn-a fn-b}})]
      (try
        (storage/validate-no-dependency-cycle-impl helpers fn-a fn-b)
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/dependency-cycle (:type (ex-data e))))
          (is (= fn-a (:owner-fn-id (ex-data e))))
          (is (= fn-b (:value-fn-id (ex-data e)))))))))


;; === ExecutionGraphResult validation tests ===

(deftest execution-graph-validation-test
  (let [fn-id (random-uuid)
        fn-schema-id (random-uuid)
        arg-schema-id (random-uuid)
        valid-fns {fn-id {:id fn-id :fn-schema-id fn-schema-id}}
        valid-fn-schemas {fn-schema-id {:id fn-schema-id :name "test-fn"}}
        valid-arg-schemas {arg-schema-id {:id arg-schema-id :fn-schema-id fn-schema-id}}]

    (testing "creates valid result with all required fields"
      (let [result (storage/->execution-graph
                     {:fns valid-fns
                      :fn-schemas valid-fn-schemas
                      :arg-schemas valid-arg-schemas
                      :resolved-args {}})]
        (is (storage/execution-graph? result))
        (is (= valid-fns (:fns result)))
        (is (= valid-fn-schemas (:fn-schemas result)))))

    (testing "throws when :fns is not a map"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :fns map"
            (storage/->execution-graph
              {:fns "not-a-map"
               :fn-schemas valid-fn-schemas
               :arg-schemas valid-arg-schemas
               :resolved-args {}}))))

    (testing "throws when :fns is empty"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":fns must contain at least"
            (storage/->execution-graph
              {:fns {}
               :fn-schemas valid-fn-schemas
               :arg-schemas valid-arg-schemas
               :resolved-args {}}))))

    (testing "throws when :fn-schemas is not a map"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :fn-schemas map"
            (storage/->execution-graph
              {:fns valid-fns
               :fn-schemas []
               :arg-schemas valid-arg-schemas
               :resolved-args {}}))))

    (testing "throws when :fn-schemas is empty"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":fn-schemas must contain at least"
            (storage/->execution-graph
              {:fns valid-fns
               :fn-schemas {}
               :arg-schemas valid-arg-schemas
               :resolved-args {}}))))

    (testing "throws when :arg-schemas is not a map"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :arg-schemas map"
            (storage/->execution-graph
              {:fns valid-fns
               :fn-schemas valid-fn-schemas
               :arg-schemas "invalid"
               :resolved-args {}}))))

    (testing "throws when :resolved-args is not a map"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :resolved-args map"
            (storage/->execution-graph
              {:fns valid-fns
               :fn-schemas valid-fn-schemas
               :arg-schemas valid-arg-schemas
               :resolved-args []}))))))


(deftest execution-graph?-test
  (testing "returns true for ExecutionGraphResult"
    (let [result (storage/->execution-graph
                   {:fns {(random-uuid) {:id (random-uuid)}}
                    :fn-schemas {(random-uuid) {:id (random-uuid)}}
                    :arg-schemas {}
                    :resolved-args {}})]
      (is (true? (storage/execution-graph? result)))))

  (testing "returns false for other types"
    (is (false? (storage/execution-graph? {})))
    (is (false? (storage/execution-graph? nil)))
    (is (false? (storage/execution-graph? "string")))))


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


;; === NULL Handling Contract Tests ===
;;
;; These tests document the expected NULL semantics for storage implementations.
;; All storage backends (postgres, datomic, memory) MUST follow SQL NULL semantics:
;;
;; 1. Unique constraints: NULL values are NOT considered equal for uniqueness
;;    - Multiple rows can have NULL in a unique-constrained column
;;    - Example: unique(:email) allows multiple rows with email=NULL
;;
;; 2. Equality comparisons: NULL = NULL returns UNKNOWN (not TRUE)
;;    - NULL != any_value returns UNKNOWN
;;    - WHERE field = NULL never matches (use WHERE field IS NULL)
;;
;; 3. Query behavior: Queries with where {:field nil} should match NULL values

(deftest null-handling-contract-uniqueness-test
  (testing "NULL semantics contract: multiple NULLs allowed in unique field"
    ;; This documents the contract that storage implementations must follow.
    ;; The actual enforcement happens at the storage level.
    ;; Memory storage: must explicitly skip NULL values in uniqueness checks
    ;; Postgres: native SQL NULL semantics
    ;; Datomic: doesn't store nil values, so uniqueness on absent field is automatic
    (let [field-specs {:email {:type :text :nullable? true}
                       :name {:type :text :nullable? false}}
          ;; Two records with nil email - should both be valid
          record1 {:name "Alice" :email nil}
          record2 {:name "Bob" :email nil}]
      ;; Both pass required field validation (email is nullable)
      (is (nil? (storage/validate-required-fields! :user field-specs record1)))
      (is (nil? (storage/validate-required-fields! :user field-specs record2)))))

  (testing "NULL semantics contract: NULL in required field rejected"
    (let [field-specs {:email {:type :text :nullable? false}}
          record {:email nil}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Required field 'email' is missing or nil"
            (storage/validate-required-fields! :user field-specs record)))))

  (testing "NULL semantics: missing field in nullable spec is valid"
    (let [field-specs {:email {:type :text :nullable? true}
                       :name {:type :text :nullable? false}}
          record {:name "Alice"}]  ; email not provided at all
      (is (nil? (storage/validate-required-fields! :user field-specs record))))))


(deftest null-handling-contract-nullable-changes-test
  (testing "nullable to non-nullable is destructive (may break existing data)"
    (is (not (storage/safe-nullable-change? true false))))

  (testing "non-nullable to nullable is safe"
    (is (true? (storage/safe-nullable-change? false true))))

  (testing "same nullable value is always safe"
    (is (true? (storage/safe-nullable-change? true true)))
    (is (true? (storage/safe-nullable-change? false false))))

  (testing "check-nullable-change! throws for unsafe changes"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"nullable to non-nullable"
          (storage/check-nullable-change! :user :email true false))))

  (testing "check-nullable-change! passes for safe changes"
    (is (nil? (storage/check-nullable-change! :user :email false true)))
    (is (nil? (storage/check-nullable-change! :user :email nil false)))))


(deftest null-handling-type-category-test
  (testing "canonical field types are defined for all backends"
    ;; Contract: all field types must have defined behavior for NULL handling
    (doseq [t storage/canonical-field-types]
      (is (contains? storage/type-category t)
          (str "Type " t " must have a category defined")))))


(deftest null-handling-in-batch-operations-test
  (testing "duplicate ID check ignores nil IDs"
    ;; Records without explicit :id get auto-generated UUIDs
    ;; So nil IDs should not trigger duplicate detection
    (let [data-seq [{:name "Alice"}  ; no :id
                    {:name "Bob"}]]  ; no :id
      (is (nil? (storage/validate-no-duplicate-ids! :user data-seq)))))

  (testing "nil IDs in explicit field are handled"
    ;; When :id is explicitly nil, it's treated as "auto-generate"
    (let [data-seq [{:id nil :name "Alice"}
                    {:id nil :name "Bob"}]]
      ;; Should not throw - nil is not counted as a duplicate
      (is (nil? (storage/validate-no-duplicate-ids! :user data-seq))))))


;; === GraphDataLoader protocol tests ===

(deftest graph-data-loader-protocol-test
  (testing "GraphDataLoader protocol is defined"
    (is (some? storage/GraphDataLoader))
    (is (contains? (:sigs storage/GraphDataLoader) :load-fn-record))
    (is (contains? (:sigs storage/GraphDataLoader) :load-fn-schema-record))
    (is (contains? (:sigs storage/GraphDataLoader) :load-arg-schemas-for-fn-schema))
    (is (contains? (:sigs storage/GraphDataLoader) :load-parent-chain))
    (is (contains? (:sigs storage/GraphDataLoader) :load-arg-values-for-fns))
    (is (contains? (:sigs storage/GraphDataLoader) :classify-uuid-refs))))


;; === ExecutionGraphReader protocol tests ===

(deftest execution-graph-reader-protocol-test
  (testing "ExecutionGraphReader protocol is defined"
    (is (some? storage/ExecutionGraphReader))
    (is (contains? (:sigs storage/ExecutionGraphReader) :graph-get-fn))
    (is (contains? (:sigs storage/ExecutionGraphReader) :graph-get-fn-schema))
    (is (contains? (:sigs storage/ExecutionGraphReader) :graph-get-arg-schemas))
    (is (contains? (:sigs storage/ExecutionGraphReader) :graph-get-resolved-args))
    (is (contains? (:sigs storage/ExecutionGraphReader) :graph-get-fn-result-value)))

  (testing "ExecutionGraphResult implements ExecutionGraphReader"
    (let [fn-id (random-uuid)
          fn-schema-id (random-uuid)
          arg-schema-id (random-uuid)
          frv-id (random-uuid)
          graph (storage/->execution-graph
                  {:fns {fn-id {:id fn-id :fn-schema-id fn-schema-id}}
                   :fn-schemas {fn-schema-id {:id fn-schema-id :name "test-fn"}}
                   :arg-schemas {arg-schema-id {:id arg-schema-id :fn-schema-id fn-schema-id}}
                   :resolved-args {fn-id {arg-schema-id {:value 42}}}
                   :fn-result-values {frv-id {:id frv-id :value "result"}}})]
      ;; Test protocol methods
      (is (= {:id fn-id :fn-schema-id fn-schema-id}
             (storage/graph-get-fn graph fn-id)))
      (is (= {:id fn-schema-id :name "test-fn"}
             (storage/graph-get-fn-schema graph fn-schema-id)))
      ;; graph-get-arg-schemas returns a map of {arg-schema-id -> arg-schema-record}
      (is (= {arg-schema-id {:id arg-schema-id :fn-schema-id fn-schema-id}}
             (storage/graph-get-arg-schemas graph fn-schema-id)))
      (is (= {arg-schema-id {:value 42}}
             (storage/graph-get-resolved-args graph fn-id)))
      (is (= {:id frv-id :value "result"}
             (storage/graph-get-fn-result-value graph frv-id)))))

  (testing "ExecutionGraphReader returns nil/empty for missing keys"
    (let [graph (storage/->execution-graph
                  {:fns {(random-uuid) {:id (random-uuid)}}
                   :fn-schemas {(random-uuid) {:id (random-uuid)}}
                   :arg-schemas {}
                   :resolved-args {}})]
      (is (nil? (storage/graph-get-fn graph (random-uuid))))
      (is (nil? (storage/graph-get-fn-schema graph (random-uuid))))
      ;; Returns empty map for missing fn-schema-id (no matching arg-schemas)
      (is (= {} (storage/graph-get-arg-schemas graph (random-uuid))))
      (is (nil? (storage/graph-get-resolved-args graph (random-uuid))))
      (is (nil? (storage/graph-get-fn-result-value graph (random-uuid)))))))


;; === Entity name validation tests ===

(deftest validate-entity-name-test
  (testing "valid entity names pass validation"
    (is (nil? (storage/validate-entity-name! :user "test")))
    (is (nil? (storage/validate-entity-name! :fn-schema "test")))
    (is (nil? (storage/validate-entity-name! :arg-value "test")))
    (is (nil? (storage/validate-entity-name! :my-entity-123 "test")))
    (is (nil? (storage/validate-entity-name! :a "test"))))

  (testing "rejects non-keyword entity names"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword"
          (storage/validate-entity-name! "user" "test")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword"
          (storage/validate-entity-name! nil "test")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must be a keyword"
          (storage/validate-entity-name! 123 "test"))))

  (testing "rejects entity names with invalid characters"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (storage/validate-entity-name! :User "test")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (storage/validate-entity-name! :user! "test")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (storage/validate-entity-name! :user.name "test")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid characters"
          (storage/validate-entity-name! :123user "test"))))

  (testing "rejects entity names exceeding max length"
    (let [long-name (keyword (str/join (repeat 65 "a")))]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds maximum length"
            (storage/validate-entity-name! long-name "test")))))

  (testing "error includes operation context"
    (try
      (storage/validate-entity-name! "bad" "create-entity")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-entity-name (:type (ex-data e))))
        (is (= "create-entity" (:operation (ex-data e))))))))


;; === Chain depth limits tests ===

(deftest chain-depth-limits-constants-test
  (testing "default-max-parent-chain-depth is defined"
    (is (pos-int? storage/default-max-parent-chain-depth))
    (is (= 100 storage/default-max-parent-chain-depth)))

  (testing "default-max-dependency-chain-depth is defined"
    (is (pos-int? storage/default-max-dependency-chain-depth))
    (is (= 1000 storage/default-max-dependency-chain-depth))))


(deftest collect-parent-chain-impl-depth-limit-test
  (testing "throws when parent chain exceeds depth limit"
    ;; Build a chain that exceeds the limit (100+)
    (let [ids (repeatedly 150 random-uuid)
          parent-map (zipmap (rest ids) (butlast ids))
          helpers (->MockConstraintHelpers {} {} parent-map {} {})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Parent chain exceeds maximum allowed depth"
            (storage/collect-parent-chain-impl helpers (last ids))))))

  (testing "exception contains correct data for depth exceeded"
    (let [ids (repeatedly 150 random-uuid)
          parent-map (zipmap (rest ids) (butlast ids))
          helpers (->MockConstraintHelpers {} {} parent-map {} {})]
      (try
        (storage/collect-parent-chain-impl helpers (last ids))
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/chain-too-deep (:type (ex-data e))))
          (is (= :parent (:chain-type (ex-data e))))
          (is (= storage/default-max-parent-chain-depth (:max-depth (ex-data e))))))))

  (testing "succeeds for chain at limit boundary"
    ;; Chain of exactly 100 should work (limit check is >100, not >=100)
    (let [ids (repeatedly 101 random-uuid)
          parent-map (zipmap (rest ids) (butlast ids))
          helpers (->MockConstraintHelpers {} {} parent-map {} {})]
      (is (set? (storage/collect-parent-chain-impl helpers (last ids)))))))


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


;; === Additional Helper Function Tests ===

(deftest canonical-type?-test
  (testing "returns true for canonical types"
    (is (true? (storage/canonical-type? :uuid)))
    (is (true? (storage/canonical-type? :text)))
    (is (true? (storage/canonical-type? :int)))
    (is (true? (storage/canonical-type? :bool)))
    (is (true? (storage/canonical-type? :numeric)))
    (is (true? (storage/canonical-type? :timestamptz)))
    (is (true? (storage/canonical-type? :bytes)))
    (is (true? (storage/canonical-type? :jsonb)))
    (is (true? (storage/canonical-type? :ref)))
    (is (true? (storage/canonical-type? :enum)))
    (is (true? (storage/canonical-type? :union))))

  (testing "returns false for non-canonical types"
    (is (false? (storage/canonical-type? :unknown)))
    (is (false? (storage/canonical-type? :string)))
    (is (false? (storage/canonical-type? :integer)))
    (is (false? (storage/canonical-type? nil)))))


(deftest reference-type?-test
  (testing "returns true for reference types"
    (is (true? (storage/reference-type? :ref))))

  (testing "returns false for non-reference types"
    (is (false? (storage/reference-type? :uuid)))
    (is (false? (storage/reference-type? :text)))
    (is (false? (storage/reference-type? :jsonb)))))


(deftest complex-type?-test
  (testing "returns true for complex types"
    (is (true? (storage/complex-type? :jsonb)))
    (is (true? (storage/complex-type? :union))))

  (testing "returns false for non-complex types"
    (is (false? (storage/complex-type? :text)))
    (is (false? (storage/complex-type? :int)))
    (is (false? (storage/complex-type? :ref)))))


(deftest standard-query-validations!-test
  (testing "passes for valid query"
    (let [fields {:name {:type :text} :age {:type :int}}]
      (is (nil? (storage/standard-query-validations! :user fields {:name "Alice"})))))

  (testing "passes with nil where clause"
    (is (nil? (storage/standard-query-validations! :user nil nil))))

  (testing "throws for invalid where type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"where clause must be nil or a map"
          (storage/standard-query-validations! :user nil "invalid"))))

  (testing "throws for unknown field in where clause"
    (let [fields {:name {:type :text}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown field"
            (storage/standard-query-validations! :user fields {:unknown "value"})))))

  (testing "throws for type mismatch in where clause"
    (let [fields {:age {:type :int}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Type mismatch"
            (storage/standard-query-validations! :user fields {:age "not-an-int"}))))))


(deftest wrap-batch-error-test
  (testing "wraps ExceptionInfo with batch context"
    (let [original (ex-info "Original error" {:type :test-error})
          wrapped (storage/wrap-batch-error original 5 10)]
      (is (instance? clojure.lang.ExceptionInfo wrapped))
      (is (= "Original error" (ex-message wrapped)))
      (is (= :test-error (:type (ex-data wrapped))))
      (is (= 5 (:batch-index (ex-data wrapped))))
      (is (= 10 (:batch-size (ex-data wrapped))))
      (is (= original (ex-cause wrapped)))))

  (testing "wraps regular Exception with batch context"
    (let [original (Exception. "Regular exception")
          wrapped (storage/wrap-batch-error original 2 5)]
      (is (= "Regular exception" (ex-message wrapped)))
      (is (= :batch-error/partial-failure (:type (ex-data wrapped))))
      (is (= 2 (:batch-index (ex-data wrapped))))
      (is (= 5 (:batch-size (ex-data wrapped))))))

  (testing "includes failed-id when provided"
    (let [failed-id (random-uuid)
          original (ex-info "Error" {:type :test})
          wrapped (storage/wrap-batch-error original 0 3 failed-id)]
      (is (= failed-id (:failed-id (ex-data wrapped))))))

  (testing "omits failed-id when nil"
    (let [original (ex-info "Error" {:type :test})
          wrapped (storage/wrap-batch-error original 0 3 nil)]
      (is (not (contains? (ex-data wrapped) :failed-id))))))


(deftest process-batch-with-index-test
  (testing "processes items and returns results"
    (let [items [{:id 1} {:id 2} {:id 3}]
          results (doall (storage/process-batch-with-index
                           items
                           :id
                           (fn [item _idx] (:id item))))]
      (is (= [1 2 3] results))))

  (testing "wraps exceptions with batch context"
    (let [items [{:id 1} {:id 2} {:id 3}]
          fail-on-2 (fn [item _idx]
                      (if (= 2 (:id item))
                        (throw (ex-info "Failed on 2" {:type :test-failure}))
                        (:id item)))]
      (try
        (doall (storage/process-batch-with-index items :id fail-on-2))
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= "Failed on 2" (ex-message e)))
          (is (= 1 (:batch-index (ex-data e))))
          (is (= 3 (:batch-size (ex-data e))))
          (is (= 2 (:failed-id (ex-data e))))))))

  (testing "handles regular exceptions"
    (let [items [{:id 1}]
          fail-fn (fn [_item _idx]
                    (throw (Exception. "Regular error")))]
      (try
        (doall (storage/process-batch-with-index items :id fail-fn))
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= "Regular error" (ex-message e)))
          (is (= :batch-error/partial-failure (:type (ex-data e))))))))

  (testing "works without get-id-fn"
    (let [items [{:x 1} {:x 2}]
          results (doall (storage/process-batch-with-index
                           items
                           nil
                           (fn [item _idx] (:x item))))]
      (is (= [1 2] results)))))


;; === validate-where-clause-fields! tests ===

(deftest validate-where-clause-fields!-test
  (testing "passes for known fields"
    (let [fields {:name {:type :text} :age {:type :int}}]
      (is (nil? (storage/validate-where-clause-fields! :user fields {:name "test"})))))

  (testing "passes for empty where clause"
    (is (nil? (storage/validate-where-clause-fields! :user {:name {:type :text}} {}))))

  (testing "throws for unknown field"
    (let [fields {:name {:type :text}}]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unknown field"
            (storage/validate-where-clause-fields! :user fields {:unknown "value"})))))

  (testing "exception contains correct data"
    (let [fields {:name {:type :text}}]
      (try
        (storage/validate-where-clause-fields! :user fields {:bad-field 123})
        (is false "Should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :validation-error/unknown-field (:type (ex-data e))))
          (is (= :user (:entity (ex-data e))))
          (is (= :bad-field (:field (ex-data e)))))))))


;; === kw->snake-case and snake->kw tests ===

(deftest kw->snake-case-test
  (testing "converts kebab-case to snake_case"
    (is (= "user_name" (storage/kw->snake-case :user-name)))
    (is (= "fn_schema_id" (storage/kw->snake-case :fn-schema-id))))

  (testing "handles simple keywords"
    (is (= "id" (storage/kw->snake-case :id)))
    (is (= "name" (storage/kw->snake-case :name)))))


(deftest snake->kw-test
  (testing "converts snake_case to kebab-case keyword"
    (is (= :user-name (storage/snake->kw "user_name")))
    (is (= :fn-schema-id (storage/snake->kw "fn_schema_id"))))

  (testing "handles simple strings"
    (is (= :id (storage/snake->kw "id")))
    (is (= :name (storage/snake->kw "name")))))


(deftest check-snake-case-collisions!-test
  (testing "passes for unique snake_case names"
    (is (nil? (storage/check-snake-case-collisions! {:context "test"} [:user :fn-schema :arg-value]))))

  (testing "throws for colliding names"
    ;; :user-name and :user_name would both become user_name
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"collision"
          (storage/check-snake-case-collisions! {:context "test"} [:user-name :user_name])))))


;; === traverse-bfs tests ===

(deftest traverse-bfs-test
  (testing "traverses graph and returns visited nodes"
    (let [graph {:a #{:b :c}
                 :b #{:d}
                 :c #{:d}
                 :d #{}}
          get-neighbors (fn [node] (get graph node #{}))]
      ;; traverse-bfs returns set of visited nodes
      (is (= #{:a :b :c :d} (storage/traverse-bfs :a get-neighbors)))))

  (testing "returns set with just start node if no neighbors"
    (is (= #{:x} (storage/traverse-bfs :x (constantly #{})))))

  (testing "handles cycles in graph"
    (let [graph {:a #{:b}
                 :b #{:c}
                 :c #{:a}}  ; cycle back to :a
          get-neighbors (fn [node] (get graph node #{}))]
      ;; Should visit each node exactly once despite cycle
      (is (= #{:a :b :c} (storage/traverse-bfs :a get-neighbors))))))


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


;; === Error Registry Extensibility Tests ===

(deftest error-registry-extensibility-test
  (testing "can register custom error types"
    (storage/register-error-type! :test-app/custom-error
                                  {:category :validation
                                   :retryable? true
                                   :severity :warning
                                   :description "Test custom error"})
    (is (contains? (storage/registered-error-types) :test-app/custom-error)))

  (testing "get-error-metadata returns registered metadata"
    (let [metadata (storage/get-error-metadata :test-app/custom-error)]
      (is (= :validation (:category metadata)))
      (is (true? (:retryable? metadata)))
      (is (= :warning (:severity metadata)))))

  (testing "error-retryable? returns correct value"
    (is (true? (storage/error-retryable? :test-app/custom-error)))
    (is (false? (storage/error-retryable? :nonexistent-error))))

  (testing "error-category returns correct value"
    (is (= :validation (storage/error-category :test-app/custom-error)))
    (is (= :unknown (storage/error-category :nonexistent-error))))

  (testing "pre-registered types exist"
    (is (contains? (storage/registered-error-types) :constraint-violation/unique))
    (is (contains? (storage/registered-error-types) :not-found))
    (is (contains? (storage/registered-error-types) :connection-error)))

  (testing "throws on invalid category"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid error category"
          (storage/register-error-type! :test-app/bad-category
                                        {:category :invalid-category}))))

  (testing "throws on non-keyword error-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be a keyword"
          (storage/register-error-type! "not-a-keyword"
                                        {:category :validation})))))


(deftest error-categories-test
  (testing "contains expected categories"
    (is (contains? storage/error-categories :constraint))
    (is (contains? storage/error-categories :validation))
    (is (contains? storage/error-categories :config))
    (is (contains? storage/error-categories :connection))
    (is (contains? storage/error-categories :execution))
    (is (contains? storage/error-categories :batch))
    (is (contains? storage/error-categories :unknown))))


(deftest error-severities-test
  (testing "contains expected severities"
    (is (contains? storage/error-severities :error))
    (is (contains? storage/error-severities :warning))
    (is (contains? storage/error-severities :info))))
