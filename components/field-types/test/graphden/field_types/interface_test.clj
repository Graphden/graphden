(ns graphden.field-types.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.field-types.interface :as ft]))


(deftest types-test
  (testing "all expected types are defined"
    (is (= #{:uuid :text :int :bool :numeric :timestamptz :jsonb :bytes}
           ft/supported-types)))

  (testing "each type has metadata with description"
    (doseq [type-kw ft/supported-types]
      (is (contains? ft/types type-kw)
          (str "Type " type-kw " should be in types map"))
      (is (string? (get-in ft/types [type-kw :description]))
          (str "Type " type-kw " should have a description string")))))


(deftest type-mappings-test
  (testing "type-mappings contains all base types"
    (is (contains? ft/type-mappings :uuid))
    (is (contains? ft/type-mappings :text))
    (is (contains? ft/type-mappings :int))
    (is (contains? ft/type-mappings :bool))
    (is (contains? ft/type-mappings :numeric))
    (is (contains? ft/type-mappings :timestamptz))
    (is (contains? ft/type-mappings :jsonb))
    (is (contains? ft/type-mappings :bytes)))

  (testing "type-mappings contains special types"
    (is (contains? ft/type-mappings :ref))
    (is (contains? ft/type-mappings :enum))
    (is (contains? ft/type-mappings :union)))

  (testing "each type has all backend mappings"
    (doseq [t (keys ft/type-mappings)]
      (is (contains? (get ft/type-mappings t) :postgres) (str "Missing :postgres for " t))
      (is (contains? (get ft/type-mappings t) :datomic) (str "Missing :datomic for " t))
      (is (contains? (get ft/type-mappings t) :memory) (str "Missing :memory for " t))))

  (testing "postgres mappings are strings or :custom"
    (doseq [[t mapping] ft/type-mappings]
      (let [pg-type (:postgres mapping)]
        (is (or (string? pg-type) (= :custom pg-type))
            (str "Postgres mapping for " t " should be string or :custom")))))

  (testing "datomic mappings are keywords"
    (doseq [[t mapping] ft/type-mappings]
      (is (keyword? (:datomic mapping))
          (str "Datomic mapping for " t " should be keyword"))))

  (testing "can look up specific mappings"
    (is (= "UUID" (get-in ft/type-mappings [:uuid :postgres])))
    (is (= :db.type/long (get-in ft/type-mappings [:int :datomic])))
    (is (= "BIGINT" (get-in ft/type-mappings [:int :postgres])))
    (is (= :db.type/ref (get-in ft/type-mappings [:ref :datomic])))))


(deftest type-widening-test
  (testing "type-widening map contains expected entries"
    (is (contains? ft/type-widening :int))
    (is (contains? ft/type-widening :bool))
    (is (contains? ft/type-widening :numeric))
    (is (contains? ft/type-widening :text))
    (is (contains? ft/type-widening :uuid))
    (is (contains? ft/type-widening :timestamptz)))

  (testing "int can widen to numeric, text, jsonb"
    (is (contains? (:int ft/type-widening) :numeric))
    (is (contains? (:int ft/type-widening) :text))
    (is (contains? (:int ft/type-widening) :jsonb)))

  (testing "text can only widen to jsonb"
    (is (= #{:jsonb} (:text ft/type-widening)))))


(deftest types-equivalent?-test
  (testing "uuid and ref are equivalent"
    (is (ft/types-equivalent? :uuid :ref))
    (is (ft/types-equivalent? :ref :uuid)))

  (testing "jsonb and union are equivalent"
    (is (ft/types-equivalent? :jsonb :union))
    (is (ft/types-equivalent? :union :jsonb)))

  (testing "non-equivalent types return nil"
    (is (nil? (ft/types-equivalent? :text :int)))
    (is (nil? (ft/types-equivalent? :uuid :text)))))


(deftest valid-type?-test
  (testing "uuid validation"
    (is (ft/valid-type? :uuid (random-uuid)))
    (is (not (ft/valid-type? :uuid "not-a-uuid")))
    (is (not (ft/valid-type? :uuid 123))))

  (testing "text validation"
    (is (ft/valid-type? :text "hello"))
    (is (not (ft/valid-type? :text 123))))

  (testing "int validation"
    (is (ft/valid-type? :int 42))
    (is (not (ft/valid-type? :int 3.14)))
    (is (not (ft/valid-type? :int "42"))))

  (testing "bool validation"
    (is (ft/valid-type? :bool true))
    (is (ft/valid-type? :bool false))
    (is (not (ft/valid-type? :bool 1)))
    (is (not (ft/valid-type? :bool "true"))))

  (testing "numeric validation"
    (is (ft/valid-type? :numeric 42))
    (is (ft/valid-type? :numeric 3.14))
    (is (ft/valid-type? :numeric 42M))
    (is (not (ft/valid-type? :numeric "42"))))

  (testing "jsonb validation"
    (is (ft/valid-type? :jsonb {:a 1}))
    (is (ft/valid-type? :jsonb [1 2 3]))
    (is (not (ft/valid-type? :jsonb "string")))
    (is (not (ft/valid-type? :jsonb 42))))

  (testing "union accepts any value"
    (is (ft/valid-type? :union 42))
    (is (ft/valid-type? :union "string"))
    (is (ft/valid-type? :union nil)))

  (testing "unknown type returns true (forward compatibility)"
    (is (ft/valid-type? :unknown-future-type 42))
    (is (ft/valid-type? :custom-type "anything"))))
