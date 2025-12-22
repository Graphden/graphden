(ns graphden.storage-protocol.interface-test
  "Tests for storage-protocol.
   Tests the helper functions directly.
   Contract tests for Storage/StorageIntrospection protocols
   will be added when implementations exist."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage-protocol.interface :as storage]))


;; === Type widening tests ===

(deftest type-widening-test
  (testing "type-widening map contains expected entries"
    (is (contains? storage/type-widening :int))
    (is (contains? storage/type-widening :bool))
    (is (contains? storage/type-widening :numeric))
    (is (contains? storage/type-widening :text))
    (is (contains? storage/type-widening :uuid))
    (is (contains? storage/type-widening :timestamptz)))

  (testing "int can widen to numeric, text, jsonb"
    (is (contains? (:int storage/type-widening) :numeric))
    (is (contains? (:int storage/type-widening) :text))
    (is (contains? (:int storage/type-widening) :jsonb)))

  (testing "text can only widen to jsonb"
    (is (= #{:jsonb} (:text storage/type-widening)))))


(deftest safe-type-change?-test
  (testing "same type is always safe"
    (is (storage/safe-type-change? :int :int))
    (is (storage/safe-type-change? :text :text))
    (is (storage/safe-type-change? :bool :bool))
    (is (storage/safe-type-change? :uuid :uuid))
    (is (storage/safe-type-change? :jsonb :jsonb))
    (is (storage/safe-type-change? :bytes :bytes)))

  (testing "widening is safe"
    (is (storage/safe-type-change? :int :numeric))
    (is (storage/safe-type-change? :int :text))
    (is (storage/safe-type-change? :int :jsonb))
    (is (storage/safe-type-change? :bool :text))
    (is (storage/safe-type-change? :bool :jsonb))
    (is (storage/safe-type-change? :numeric :text))
    (is (storage/safe-type-change? :numeric :jsonb))
    (is (storage/safe-type-change? :text :jsonb))
    (is (storage/safe-type-change? :uuid :text))
    (is (storage/safe-type-change? :timestamptz :text)))

  (testing "narrowing is not safe"
    (is (not (storage/safe-type-change? :text :int)))
    (is (not (storage/safe-type-change? :numeric :int)))
    (is (not (storage/safe-type-change? :jsonb :text)))
    (is (not (storage/safe-type-change? :text :bool)))
    (is (not (storage/safe-type-change? :text :uuid))))

  (testing "unrelated types are not safe"
    (is (not (storage/safe-type-change? :bool :int)))
    (is (not (storage/safe-type-change? :uuid :int)))
    (is (not (storage/safe-type-change? :timestamptz :int)))
    (is (not (storage/safe-type-change? :bytes :text)))))


;; === Protocol existence tests ===
;; These just verify the protocols are defined correctly

;; === Nullable change tests ===

(deftest safe-nullable-change?-test
  (testing "same value is safe"
    (is (storage/safe-nullable-change? true true))
    (is (storage/safe-nullable-change? false false)))

  (testing "false→true is safe (allowing more)"
    (is (storage/safe-nullable-change? false true)))

  (testing "true→false is unsafe (restricting)"
    (is (not (storage/safe-nullable-change? true false)))))


(deftest check-nullable-change!-test
  (testing "safe changes don't throw"
    (is (nil? (storage/check-nullable-change! :user :name true true)))
    (is (nil? (storage/check-nullable-change! :user :name false false)))
    (is (nil? (storage/check-nullable-change! :user :name false true))))

  (testing "unsafe change throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"nullable to non-nullable"
          (storage/check-nullable-change! :user :name true false))))

  (testing "exception contains correct data"
    (try
      (storage/check-nullable-change! :user :email true false)
      (catch clojure.lang.ExceptionInfo e
        (is (= :destructive-change (:type (ex-data e))))
        (is (= :user (:entity (ex-data e))))
        (is (= :email (:field (ex-data e))))
        (is (true? (:old-nullable? (ex-data e))))
        (is (false? (:new-nullable? (ex-data e))))))))


(deftest protocols-defined-test
  (testing "Storage protocol is defined"
    (is (some? storage/Storage))
    (is (contains? (:sigs storage/Storage) :initialize))
    (is (contains? (:sigs storage/Storage) :close)))

  (testing "StorageIntrospection protocol is defined"
    (is (some? storage/StorageIntrospection))
    (is (contains? (:sigs storage/StorageIntrospection) :current-entities))
    (is (contains? (:sigs storage/StorageIntrospection) :current-fields))
    (is (contains? (:sigs storage/StorageIntrospection) :current-enums))
    (is (contains? (:sigs storage/StorageIntrospection) :current-enum-values))
    (is (contains? (:sigs storage/StorageIntrospection) :schema-metadata))))
