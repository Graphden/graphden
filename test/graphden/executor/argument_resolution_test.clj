(ns graphden.executor.argument-resolution-test
  "Tests for argument resolution edge cases: lazy sequences, depth limits."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.argument-resolution :as arg-res]
    [graphden.storage.protocol.config :as config]
    [graphden.storage.protocol.interface :as sp]))


(deftest realize-lazy-seq-bounded-test
  (testing "realizes lazy sequence within limit"
    (let [result (#'arg-res/realize-lazy-seq-bounded (map inc [1 2 3]) 10)]
      (is (= [2 3 4] result))))

  (testing "throws when lazy sequence exceeds max-size"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Lazy sequence exceeds maximum allowed size"
          (#'arg-res/realize-lazy-seq-bounded (range 100) 10))))

  (testing "realizes exactly at max-size"
    (let [result (#'arg-res/realize-lazy-seq-bounded (range 5) 5)]
      (is (= [0 1 2 3 4] result))))

  (testing "handles empty sequence"
    (let [result (#'arg-res/realize-lazy-seq-bounded (map inc []) 10)]
      (is (= [] result)))))


(deftest realize-lazy-value-test
  (testing "passes through nil"
    (is (nil? (#'arg-res/realize-lazy-value nil))))

  (testing "passes through non-lazy values"
    (is (= 42 (#'arg-res/realize-lazy-value 42)))
    (is (= "hello" (#'arg-res/realize-lazy-value "hello")))
    (is (= [1 2 3] (#'arg-res/realize-lazy-value [1 2 3])))
    (is (= #{1 2} (#'arg-res/realize-lazy-value #{1 2}))))

  (testing "realizes lazy sequences"
    (let [result (#'arg-res/realize-lazy-value (map inc [1 2 3]))]
      (is (= [2 3 4] result))
      (is (vector? result))))

  (testing "realizes maps with lazy values recursively"
    (let [result (#'arg-res/realize-lazy-value {:a (map inc [1 2]) :b 42})]
      (is (= {:a [2 3] :b 42} result))))

  (testing "realizes other seqable types like range"
    (let [result (#'arg-res/realize-lazy-value (range 5))]
      (is (= [0 1 2 3 4] result))))

  (testing "throws on collection depth exceeding limit"
    (binding [config/*max-nested-collection-depth* 3]
      ;; Build nested map 4 levels deep
      (let [deep-map {:a {:b {:c {:d 1}}}}]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Collection nesting exceeds maximum allowed depth"
              (#'arg-res/realize-lazy-value deep-map))))))

  (testing "throws on lazy sequence exceeding size limit"
    (binding [config/*max-lazy-seq-size* 5]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Lazy sequence exceeds maximum allowed size"
            (#'arg-res/realize-lazy-value (map identity (range 100))))))))


;; === extract-arg-value tests ===

(deftest extract-arg-value-test
  (testing "extracts fn-id from :fn-ref union format"
    (let [fn-id (random-uuid)
          result (#'arg-res/extract-arg-value {:kind :fn-ref :fn-id fn-id})]
      (is (= fn-id result))))

  (testing "extracts value from :literal union format"
    (let [result (#'arg-res/extract-arg-value {:kind :literal :value 42})]
      (is (= 42 result))))

  (testing "extracts value from arg-value record format"
    (let [result (#'arg-res/extract-arg-value {:id (random-uuid) :value "test"})]
      (is (= "test" result))))

  (testing "returns direct value when already unwrapped"
    (is (= 42 (#'arg-res/extract-arg-value 42)))
    (is (= "hello" (#'arg-res/extract-arg-value "hello")))
    (is (= [1 2 3] (#'arg-res/extract-arg-value [1 2 3])))))


;; === try-parse-uuid tests ===

(deftest try-parse-uuid-test
  (testing "returns UUID for UUID input"
    (let [id (random-uuid)]
      (is (= id (#'arg-res/try-parse-uuid id)))))

  (testing "parses UUID string"
    (let [id (random-uuid)
          result (#'arg-res/try-parse-uuid (str id))]
      (is (= id result))))

  (testing "returns nil for non-UUID string"
    (is (nil? (#'arg-res/try-parse-uuid "not-a-uuid"))))

  (testing "returns nil for non-string/non-UUID"
    (is (nil? (#'arg-res/try-parse-uuid 42)))
    (is (nil? (#'arg-res/try-parse-uuid {:a 1})))))


;; === resolve-nested-fn-usages tests ===

(defn mock-storage
  "Creates a mock storage that returns specified entities."
  [entities]
  (reify
    sp/StorageCRUD
    (create-entity [_ _ _] nil)

    (read-entity [_ entity-type id] (get-in entities [entity-type id]))

    (update-entity [_ _ _ _] nil)

    (delete-entity [_ _ _] nil)

    (query-entities [_ _ _] nil)))


;; NOTE: resolve-nested-fn-usages and execute-external-fn-usage tests removed.
;; These functions were removed as part of simplifying the architecture.
;; Nested fn/fn-usage resolution is no longer supported.
;; Use explicit collection functions (pair, assoc-any, conj-any) instead.
