(ns graphden.datomic-storage.codec-test
  "Tests for datomic-storage.codec - value encoding/decoding."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.datomic-storage.codec :as codec]
    [graphden.storage-protocol.interface :as sp]))


;; === create-codec tests ===

(deftest create-codec-test
  (testing "creates a DatomicValueCodec instance"
    (let [c (codec/create-codec)]
      (is (some? c))
      (is (satisfies? sp/StorageValueCodec c)))))


;; === encode-value tests ===

(deftest encode-value-test
  (testing "encodes enum value to Datomic ident"
    (let [result (codec/encode-value :active {:type :enum :enum-name :status})]
      (is (= :status.value/active result))))

  (testing "passes through non-keyword enum value"
    (let [result (codec/encode-value "already-encoded" {:type :enum :enum-name :status})]
      (is (= "already-encoded" result))))

  (testing "encodes ref value to Datomic lookup ref"
    (let [uuid #uuid "550e8400-e29b-41d4-a716-446655440000"
          result (codec/encode-value uuid {:type :ref :ref-entity :user})]
      (is (vector? result))
      (is (= :user/id (first result)))
      (is (= uuid (second result)))))

  (testing "passes through non-uuid ref value"
    (let [result (codec/encode-value [:user/id #uuid "550e8400-e29b-41d4-a716-446655440000"]
                                     {:type :ref :ref-entity :user})]
      (is (vector? result))))

  (testing "encodes union value to EDN string"
    (let [result (codec/encode-value {:key "value"} {:type :union})]
      (is (string? result))
      (is (= "{:key \"value\"}" result))))

  (testing "passes through other types unchanged"
    (is (= "hello" (codec/encode-value "hello" {:type :text})))
    (is (= 42 (codec/encode-value 42 {:type :int})))
    (is (true? (codec/encode-value true {:type :bool}))))

  (testing "returns nil for nil value"
    (is (nil? (codec/encode-value nil {:type :text})))))


;; === decode-value tests ===

(deftest decode-value-test
  (testing "decodes union value from EDN string"
    (let [result (codec/decode-value "{:key \"value\"}" {:type :union})]
      (is (map? result))
      (is (= "value" (:key result)))))

  (testing "returns nil for non-string union value"
    (is (nil? (codec/decode-value nil {:type :union})))
    (is (nil? (codec/decode-value 123 {:type :union}))))

  (testing "passes through other types unchanged"
    (is (= :active (codec/decode-value :active {:type :enum})))
    (is (= "hello" (codec/decode-value "hello" {:type :text})))
    (is (= 42 (codec/decode-value 42 {:type :int})))))


;; === encode-row tests ===

(deftest encode-row-test
  (let [field-specs {:status {:type :enum :enum-name :status}
                     :name {:type :text}
                     :owner {:type :ref :ref-entity :user}}
        uuid #uuid "550e8400-e29b-41d4-a716-446655440000"]

    (testing "encodes all fields in row"
      (let [row {:status :active :name "Test" :owner uuid}
            result (codec/encode-row row field-specs)]
        (is (= :status.value/active (:status result)))
        (is (= "Test" (:name result)))
        (is (vector? (:owner result)))))

    (testing "handles fields without specs"
      (let [row {:status :active :extra "value"}
            result (codec/encode-row row field-specs)]
        (is (= :status.value/active (:status result)))
        (is (= "value" (:extra result)))))

    (testing "handles empty row"
      (is (= {} (codec/encode-row {} field-specs))))))


;; === decode-row tests ===

(deftest decode-row-test
  (let [field-specs {:data {:type :union}
                     :name {:type :text}}]

    (testing "decodes all fields in row"
      (let [row {:data "{:key 1}" :name "Test"}
            result (codec/decode-row row field-specs)]
        (is (= {:key 1} (:data result)))
        (is (= "Test" (:name result)))))

    (testing "handles fields without specs"
      (let [row {:data "{:a 1}" :extra 42}
            result (codec/decode-row row field-specs)]
        (is (= {:a 1} (:data result)))
        (is (= 42 (:extra result)))))

    (testing "handles empty row"
      (is (= {} (codec/decode-row {} field-specs))))))


;; === Protocol implementation tests ===

(deftest protocol-implementation-test
  (let [codec (codec/create-codec)]

    (testing "encode-value via protocol"
      (let [result (sp/encode-value codec :pending {:type :enum :enum-name :task-status})]
        (is (= :task-status.value/pending result))))

    (testing "decode-value via protocol"
      (let [result (sp/decode-value codec "[1 2 3]" {:type :union})]
        (is (= [1 2 3] result))))

    (testing "encode-row via protocol"
      (let [result (sp/encode-row codec {:x 1} {:x {:type :int}})]
        (is (= {:x 1} result))))

    (testing "decode-row via protocol"
      (let [result (sp/decode-row codec {:x 1} {:x {:type :int}})]
        (is (= {:x 1} result))))))
