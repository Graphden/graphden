(ns graphden.storage-protocol.null-handling-test
  "Tests for NULL handling contract."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage-protocol.interface :as storage]))


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
