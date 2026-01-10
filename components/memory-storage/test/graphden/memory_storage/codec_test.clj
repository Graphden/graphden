(ns graphden.memory-storage.codec-test
  "Tests for memory-storage codec - passthrough implementation."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.memory-storage.codec :as codec]
    [graphden.storage-protocol.interface :as sp]))


(deftest create-codec-test
  (testing "creates a MemoryValueCodec instance"
    (let [c (codec/create-codec)]
      (is (instance? graphden.memory_storage.codec.MemoryValueCodec c)))))


(deftest encode-value-test
  (let [c (codec/create-codec)]
    (testing "returns value unchanged for all types"
      ;; nil
      (is (nil? (sp/encode-value c nil nil)))
      ;; string
      (is (= "hello" (sp/encode-value c "hello" {:type :text})))
      ;; number
      (is (= 42 (sp/encode-value c 42 {:type :int})))
      ;; boolean
      (is (true? (sp/encode-value c true {:type :bool})))
      ;; uuid
      (let [id (random-uuid)]
        (is (= id (sp/encode-value c id {:type :uuid}))))
      ;; map
      (is (= {:a 1} (sp/encode-value c {:a 1} {:type :jsonb})))
      ;; vector
      (is (= [1 2 3] (sp/encode-value c [1 2 3] {:type :jsonb})))
      ;; keyword
      (is (= :active (sp/encode-value c :active {:type :enum})))
      ;; bytes
      (let [b (byte-array [1 2 3])]
        (is (= b (sp/encode-value c b {:type :bytes}))))
      ;; instant
      (let [now (java.time.Instant/now)]
        (is (= now (sp/encode-value c now {:type :timestamptz})))))))


(deftest decode-value-test
  (let [c (codec/create-codec)]
    (testing "returns value unchanged for all types"
      ;; nil
      (is (nil? (sp/decode-value c nil nil)))
      ;; string
      (is (= "hello" (sp/decode-value c "hello" {:type :text})))
      ;; number
      (is (= 42 (sp/decode-value c 42 {:type :int})))
      ;; boolean
      (is (false? (sp/decode-value c false {:type :bool})))
      ;; uuid
      (let [id (random-uuid)]
        (is (= id (sp/decode-value c id {:type :uuid}))))
      ;; map
      (is (= {:nested {:a 1}} (sp/decode-value c {:nested {:a 1}} {:type :jsonb})))
      ;; vector
      (is (= [[1] [2]] (sp/decode-value c [[1] [2]] {:type :jsonb})))
      ;; keyword
      (is (= :pending (sp/decode-value c :pending {:type :enum})))
      ;; BigDecimal
      (is (= 3.14M (sp/decode-value c 3.14M {:type :numeric}))))))


(deftest encode-row-test
  (let [c (codec/create-codec)]
    (testing "returns row unchanged"
      (let [row {:id (random-uuid) :name "test" :count 5}
            fields {:id {:type :uuid}
                    :name {:type :text}
                    :count {:type :int}}]
        (is (= row (sp/encode-row c row fields)))))

    (testing "handles empty row"
      (is (= {} (sp/encode-row c {} {}))))

    (testing "handles nil row"
      (is (nil? (sp/encode-row c nil {}))))))


(deftest decode-row-test
  (let [c (codec/create-codec)]
    (testing "returns row unchanged"
      (let [row {:id (random-uuid)
                 :data {:key "value"}
                 :tags [:a :b :c]}
            fields {:id {:type :uuid}
                    :data {:type :jsonb}
                    :tags {:type :jsonb}}]
        (is (= row (sp/decode-row c row fields)))))

    (testing "handles empty row"
      (is (= {} (sp/decode-row c {} {}))))

    (testing "handles nil row"
      (is (nil? (sp/decode-row c nil {}))))))


(deftest protocol-satisfaction-test
  (testing "MemoryValueCodec satisfies StorageValueCodec protocol"
    (let [c (codec/create-codec)]
      (is (satisfies? sp/StorageValueCodec c)))))
