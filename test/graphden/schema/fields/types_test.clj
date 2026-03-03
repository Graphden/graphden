(ns graphden.schema.fields.types-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.schema.fields.types :as ft]))


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
    (is (ft/valid-type? :custom-type "anything")))

  (testing "timestamptz validation"
    (is (ft/valid-type? :timestamptz (java.time.Instant/now)))
    (is (ft/valid-type? :timestamptz (java.time.LocalDateTime/now)))
    (is (ft/valid-type? :timestamptz (java.util.Date.)))
    (is (not (ft/valid-type? :timestamptz "2024-01-01")))
    (is (not (ft/valid-type? :timestamptz 1704067200))))

  (testing "bytes validation"
    (is (ft/valid-type? :bytes (byte-array [1 2 3])))
    (is (ft/valid-type? :bytes (byte-array 0)))
    (is (not (ft/valid-type? :bytes [1 2 3])))
    (is (not (ft/valid-type? :bytes "bytes"))))

  (testing "ref validation (same as uuid)"
    (is (ft/valid-type? :ref (random-uuid)))
    (is (not (ft/valid-type? :ref "not-a-uuid"))))

  (testing "fn validation (same as uuid)"
    (is (ft/valid-type? :fn (random-uuid)))
    (is (not (ft/valid-type? :fn "not-a-uuid"))))

  (testing "enum validation"
    (is (ft/valid-type? :enum :some-value))
    (is (ft/valid-type? :enum :another/namespaced))
    (is (not (ft/valid-type? :enum "string-value")))
    (is (not (ft/valid-type? :enum 42))))

  (testing "int boundary values"
    (is (ft/valid-type? :int Long/MAX_VALUE))
    (is (ft/valid-type? :int Long/MIN_VALUE))
    (is (ft/valid-type? :int 0))
    (is (ft/valid-type? :int -1)))

  (testing "nil handling for all types"
    (is (not (ft/valid-type? :uuid nil)))
    (is (not (ft/valid-type? :text nil)))
    (is (not (ft/valid-type? :int nil)))
    (is (not (ft/valid-type? :bool nil)))
    (is (ft/valid-type? :union nil))))


;; === Custom Type Registry Tests ===

(deftest register-custom-type!-test
  (testing "registers a custom type"
    (ft/register-custom-type! :email
                              {:validator #(and (string? %) (re-matches #".+@.+\..+" %))
                               :encoder identity
                               :decoder identity
                               :description "Email address"})
    (is (ft/custom-type? :email))
    (ft/unregister-custom-type! :email))

  (testing "throws on non-keyword type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Custom type key must be a keyword"
          (ft/register-custom-type! "not-keyword" {:validator identity}))))

  (testing "throws when overriding built-in type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Cannot override built-in type"
          (ft/register-custom-type! :text {:validator identity}))))

  (testing "uses default values when not provided"
    (ft/register-custom-type! :minimal {})
    (let [spec (ft/get-custom-type :minimal)]
      (is (some? (:validator spec)))
      (is (some? (:encoder spec)))
      (is (some? (:decoder spec)))
      (is (= "Custom type" (:description spec))))
    (ft/unregister-custom-type! :minimal))

  (testing "returns nil"
    (is (nil? (ft/register-custom-type! :temp {})))
    (ft/unregister-custom-type! :temp)))


(deftest unregister-custom-type!-test
  (testing "removes registered type"
    (ft/register-custom-type! :to-remove {})
    (is (ft/custom-type? :to-remove))
    (ft/unregister-custom-type! :to-remove)
    (is (not (ft/custom-type? :to-remove))))

  (testing "handles unregistering non-existent type gracefully"
    (is (nil? (ft/unregister-custom-type! :never-existed)))))


(deftest get-custom-type-test
  (testing "returns nil for non-existent type"
    (is (nil? (ft/get-custom-type :nonexistent))))

  (testing "returns spec for registered type"
    (ft/register-custom-type! :test-type {:description "Test"})
    (let [spec (ft/get-custom-type :test-type)]
      (is (map? spec))
      (is (= "Test" (:description spec))))
    (ft/unregister-custom-type! :test-type)))


(deftest custom-type?-test
  (testing "returns false for non-existent type"
    (is (not (ft/custom-type? :nonexistent))))

  (testing "returns true for registered type"
    (ft/register-custom-type! :check-type {})
    (is (ft/custom-type? :check-type))
    (ft/unregister-custom-type! :check-type)))


(deftest all-custom-types-test
  (testing "returns empty set when no custom types"
    (is (set? (ft/all-custom-types))))

  (testing "includes registered custom types"
    (ft/register-custom-type! :custom1 {})
    (ft/register-custom-type! :custom2 {})
    (let [types (ft/all-custom-types)]
      (is (contains? types :custom1))
      (is (contains? types :custom2)))
    (ft/unregister-custom-type! :custom1)
    (ft/unregister-custom-type! :custom2)))


(deftest all-supported-types-test
  (testing "includes built-in types"
    (let [all-types (ft/all-supported-types)]
      (is (contains? all-types :uuid))
      (is (contains? all-types :text))
      (is (contains? all-types :int))))

  (testing "includes custom types"
    (ft/register-custom-type! :custom-supported {})
    (is (contains? (ft/all-supported-types) :custom-supported))
    (ft/unregister-custom-type! :custom-supported)))


(deftest get-type-validator-test
  (testing "returns built-in validator"
    (let [validator (ft/get-type-validator :text)]
      (is (fn? validator))
      (is (validator "hello"))
      (is (not (validator 123)))))

  (testing "returns custom validator"
    (ft/register-custom-type! :positive-int
                              {:validator #(and (int? %) (pos? %))})
    (let [validator (ft/get-type-validator :positive-int)]
      (is (validator 5))
      (is (not (validator -1)))
      (is (not (validator 0))))
    (ft/unregister-custom-type! :positive-int))

  (testing "returns constantly true for unknown type"
    (let [validator (ft/get-type-validator :unknown)]
      (is (fn? validator))
      (is (validator 42))
      (is (validator nil)))))


(deftest get-type-encoder-test
  (testing "returns identity for built-in type"
    (let [encoder (ft/get-type-encoder :text)]
      (is (= "hello" (encoder "hello")))))

  (testing "returns custom encoder"
    (ft/register-custom-type! :upper-text
                              {:encoder str/upper-case})
    (let [encoder (ft/get-type-encoder :upper-text)]
      (is (= "HELLO" (encoder "hello"))))
    (ft/unregister-custom-type! :upper-text))

  (testing "returns identity for unknown type"
    (let [encoder (ft/get-type-encoder :unknown)]
      (is (= 42 (encoder 42))))))


(deftest get-type-decoder-test
  (testing "returns identity for built-in type"
    (let [decoder (ft/get-type-decoder :text)]
      (is (= "hello" (decoder "hello")))))

  (testing "returns custom decoder"
    (ft/register-custom-type! :lower-text
                              {:decoder str/lower-case})
    (let [decoder (ft/get-type-decoder :lower-text)]
      (is (= "hello" (decoder "HELLO"))))
    (ft/unregister-custom-type! :lower-text))

  (testing "returns identity for unknown type"
    (let [decoder (ft/get-type-decoder :unknown)]
      (is (= 42 (decoder 42))))))


(deftest get-backend-mapping-test
  (testing "returns mapping for built-in type"
    (is (= "UUID" (ft/get-backend-mapping :uuid :postgres)))
    (is (= :db.type/long (ft/get-backend-mapping :int :datomic)))
    (is (= :any (ft/get-backend-mapping :text :memory))))

  (testing "returns mapping for custom type"
    (ft/register-custom-type! :custom-mapped
                              {:backend-mappings {:postgres "VARCHAR(100)" :datomic :db.type/string :memory :any}})
    (is (= "VARCHAR(100)" (ft/get-backend-mapping :custom-mapped :postgres)))
    (is (= :db.type/string (ft/get-backend-mapping :custom-mapped :datomic)))
    (ft/unregister-custom-type! :custom-mapped))

  (testing "returns nil for unknown type"
    (is (nil? (ft/get-backend-mapping :unknown-type :postgres)))))
