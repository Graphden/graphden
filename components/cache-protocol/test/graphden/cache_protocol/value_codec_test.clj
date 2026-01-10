(ns graphden.cache-protocol.value-codec-test
  "Tests for cache-protocol.value-codec - value encoding/decoding."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.cache-protocol.value-codec :as codec]))


;; === parse-cached-value tests ===

(deftest parse-cached-value-test
  (testing "returns nil for nil input"
    (is (nil? (codec/parse-cached-value nil))))

  (testing "extracts value from literal"
    (is (= 42 (codec/parse-cached-value {:kind :literal :value 42})))
    (is (= "hello" (codec/parse-cached-value {:kind :literal :value "hello"})))
    (is (= [1 2 3] (codec/parse-cached-value {:kind :literal :value [1 2 3]})))
    (is (= {:a 1} (codec/parse-cached-value {:kind :literal :value {:a 1}}))))

  (testing "handles nil literal value"
    (is (nil? (codec/parse-cached-value {:kind :literal :value nil}))))

  (testing "preserves fn-ref with keyword kind"
    (let [fn-id (random-uuid)
          result (codec/parse-cached-value {:kind "fn-ref" :fn-id fn-id})]
      (is (= :fn-ref (:kind result)))
      (is (= fn-id (:fn-id result)))))

  (testing "handles fn-ref with keyword kind"
    (let [fn-id (random-uuid)
          result (codec/parse-cached-value {:kind :fn-ref :fn-id fn-id})]
      (is (= :fn-ref (:kind result)))
      (is (= fn-id (:fn-id result)))))

  (testing "returns non-kinded maps as-is"
    (let [m {:foo "bar" :baz 123}]
      (is (= m (codec/parse-cached-value m)))))

  (testing "returns non-map values as-is"
    (is (= "string" (codec/parse-cached-value "string")))
    (is (= 123 (codec/parse-cached-value 123)))
    (is (= [1 2] (codec/parse-cached-value [1 2])))))


;; === format-cached-value tests ===

(deftest format-cached-value-test
  (testing "returns nil for nil input"
    (is (nil? (codec/format-cached-value nil))))

  (testing "wraps literals in union format"
    (is (= {:kind :literal :value 42}
           (codec/format-cached-value 42)))
    (is (= {:kind :literal :value "hello"}
           (codec/format-cached-value "hello")))
    (is (= {:kind :literal :value [1 2 3]}
           (codec/format-cached-value [1 2 3]))))

  (testing "wraps map literals (without :kind) in union format"
    (is (= {:kind :literal :value {:foo "bar"}}
           (codec/format-cached-value {:foo "bar"}))))

  (testing "preserves fn-ref as-is"
    (let [fn-id (random-uuid)
          fn-ref {:kind :fn-ref :fn-id fn-id}]
      (is (= fn-ref (codec/format-cached-value fn-ref)))))

  (testing "wraps literal maps that happen to have :kind but not :fn-ref"
    ;; A map with :kind but not :fn-ref should be wrapped
    (is (= {:kind :literal :value {:kind :other :data 1}}
           (codec/format-cached-value {:kind :other :data 1})))))


;; === fn-ref? tests ===

(deftest fn-ref?-test
  (testing "returns true for fn-ref"
    (is (true? (codec/fn-ref? {:kind :fn-ref :fn-id (random-uuid)}))))

  (testing "returns false for non-fn-ref"
    (is (false? (codec/fn-ref? {:kind :literal :value 42})))
    (is (false? (codec/fn-ref? {:foo "bar"})))
    (is (false? (codec/fn-ref? nil)))
    (is (false? (codec/fn-ref? "string")))
    (is (false? (codec/fn-ref? 123)))
    (is (false? (codec/fn-ref? [1 2])))))


;; === literal-value? tests ===

(deftest literal-value?-test
  (testing "returns true for literal"
    (is (true? (codec/literal-value? {:kind :literal :value 42})))
    (is (true? (codec/literal-value? {:kind :literal :value nil}))))

  (testing "returns false for non-literal"
    (is (false? (codec/literal-value? {:kind :fn-ref :fn-id (random-uuid)})))
    (is (false? (codec/literal-value? {:foo "bar"})))
    (is (false? (codec/literal-value? nil)))
    (is (false? (codec/literal-value? "string")))
    (is (false? (codec/literal-value? 123)))))


;; === Round-trip tests ===

(deftest round-trip-test
  (testing "format then parse returns original value"
    (doseq [value [42
                   "hello"
                   [1 2 3]
                   {:a 1 :b 2}
                   true
                   false
                   3.14]]
      (is (= value
             (codec/parse-cached-value
               (codec/format-cached-value value))))))

  (testing "fn-ref round-trip"
    (let [fn-id (random-uuid)
          fn-ref {:kind :fn-ref :fn-id fn-id}]
      (is (= fn-ref
             (codec/parse-cached-value
               (codec/format-cached-value fn-ref)))))))
