(ns graphden.storage.postgres.ddl-test
  "Unit tests for DDL helper functions.
   Tests pure functions that don't require database access.
   Database-dependent DDL operations are tested in interface_test.clj."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.postgres.ddl :as ddl]))


;; === build-column-spec tests ===

(deftest build-column-spec-basic-types-test
  (testing "text column without nullable"
    (is (= [:my_field :text [:not nil]]
           (ddl/build-column-spec :my-field {:type :text :nullable? false}))))

  (testing "text column with nullable"
    (is (= [:my_field :text]
           (ddl/build-column-spec :my-field {:type :text :nullable? true}))))

  (testing "int column"
    (is (= [:count :bigint [:not nil]]
           (ddl/build-column-spec :count {:type :int :nullable? false}))))

  (testing "bool column"
    (is (= [:is_active :boolean [:not nil]]
           (ddl/build-column-spec :is-active {:type :bool :nullable? false}))))

  (testing "uuid column"
    (is (= [:ref_id :uuid [:not nil]]
           (ddl/build-column-spec :ref-id {:type :uuid :nullable? false}))))

  (testing "numeric column"
    (is (= [:amount :numeric [:not nil]]
           (ddl/build-column-spec :amount {:type :numeric :nullable? false}))))

  (testing "timestamptz column"
    (is (= [:created_at :timestamptz [:not nil]]
           (ddl/build-column-spec :created-at {:type :timestamptz :nullable? false}))))

  (testing "jsonb column"
    (is (= [:metadata :jsonb [:not nil]]
           (ddl/build-column-spec :metadata {:type :jsonb :nullable? false}))))

  (testing "bytes column"
    (is (= [:data :bytea [:not nil]]
           (ddl/build-column-spec :data {:type :bytes :nullable? false})))))


(deftest build-column-spec-ref-type-test
  (testing "ref type maps to uuid (for foreign key storage)"
    (is (= [:user_id :uuid [:not nil]]
           (ddl/build-column-spec :user-id {:type :ref :nullable? false}))))

  (testing "nullable ref"
    (is (= [:parent_id :uuid]
           (ddl/build-column-spec :parent-id {:type :ref :nullable? true})))))


(deftest build-column-spec-enum-type-test
  (testing "enum type with enum-name produces quoted identifier"
    (let [result (ddl/build-column-spec :status {:type :enum
                                                 :enum-name :order-status
                                                 :nullable? false})]
      ;; Enum types get special handling with quoted identifier
      (is (= :status (first result)))
      (is (= :raw (first (second result))))
      (is (string? (second (second result))))
      (is (= [:not nil] (nth result 2)))))

  (testing "nullable enum"
    (let [result (ddl/build-column-spec :category {:type :enum
                                                   :enum-name :category-type
                                                   :nullable? true})]
      (is (= :category (first result)))
      ;; Should have only 2 elements (no NOT NULL constraint)
      (is (= 2 (count result))))))


(deftest build-column-spec-union-type-test
  (testing "union type maps to jsonb"
    (is (= [:value :jsonb [:not nil]]
           (ddl/build-column-spec :value {:type :union :nullable? false}))))

  (testing "nullable union"
    (is (= [:result :jsonb]
           (ddl/build-column-spec :result {:type :union :nullable? true})))))


(deftest build-column-spec-snake-case-conversion-test
  (testing "converts kebab-case field names to snake_case"
    (is (= [:my_long_field_name :text [:not nil]]
           (ddl/build-column-spec :my-long-field-name {:type :text :nullable? false}))))

  (testing "preserves already snake_case names"
    (is (= [:already_snake :text [:not nil]]
           (ddl/build-column-spec :already_snake {:type :text :nullable? false})))))
