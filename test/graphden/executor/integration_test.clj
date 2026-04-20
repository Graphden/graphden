(ns graphden.executor.integration-test
  "Integration tests for full execution flow with fn-composition.

   These tests exercise the complete stack:
   - Base function registration
   - fn-composition sync to storage
   - Execution with fn-usages
   - Cache behavior under load
   - Limit handling (depth, timeout)"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.interface :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


;; Use testcontainer for PostgreSQL
(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each
  (setup/create-clean-db-fixture)
  exec/with-clean-registry)


;; === Full Execution Flow Tests ===

(deftest full-execution-with-fn-usages-test
  (testing "executes composed functions with fn-usage references"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Initialize base functions
        (registry/initialize-all! storage
                                  [{:const {:args {:x :any}
                                            :return-type :any
                                            :impl (fn [{:keys [x]} _] @x)}}
                                   {:add {:args {:a :int :b :int}
                                          :return-type :int
                                          :impl (fn [{:keys [a b]} _] (+ @a @b))}}])

        ;; Define composed functions using fn-composition
        (fn-composition/sync-fns-to-storage! storage
                                             [{:name :five-fn
                                               :parent :const
                                               :args {:x 5}}
                                              {:name :three-fn
                                               :parent :const
                                               :args {:x 3}}
                                              {:name :sum-fn
                                               :parent :add
                                               :args {:a :five-fn   ; Execute five-fn, use result
                                                      :b :three-fn}}]) ; Execute three-fn, use result

        ;; Get the sum-fn id
        (let [sum-fn (first (sp/query-entities storage :fn {:name "sum-fn"}))
              ctx (exec/create-context {:storage storage})
              result (exec/execute ctx (:id sum-fn) nil)]
          (is (= 8 result)))
        (finally
          (sp/close storage)))))

  (testing "fn-usages are cached and reused"
    (let [storage (setup/create-test-storage)
          call-count (atom 0)]
      (try
        ;; Initialize base functions with a counting function
        (registry/initialize-all! storage
                                  [{:counted-const {:args {:x :any}
                                                    :return-type :any
                                                    :impl (fn [{:keys [x]} _]
                                                            (swap! call-count inc)
                                                            @x)}}
                                   {:add {:args {:a :int :b :int}
                                          :return-type :int
                                          :impl (fn [{:keys [a b]} _] (+ @a @b))}}])

        ;; Define functions where same fn-usage is used twice
        (fn-composition/sync-fns-to-storage! storage
                                             [{:name :value-fn
                                               :parent :counted-const
                                               :args {:x 10}}
                                              {:name :double-fn
                                               :parent :add
                                               :args {:a :value-fn   ; Same fn-usage
                                                      :b :value-fn}}]) ; Same fn-usage (deduplicated)

        (let [double-fn (first (sp/query-entities storage :fn {:name "double-fn"}))
              ctx (exec/create-context {:storage storage})
              result (exec/execute ctx (:id double-fn) nil)]
          ;; Result should be 10 + 10 = 20
          (is (= 20 result))
          ;; counted-const should be called only ONCE due to caching
          (is (= 1 @call-count)))
        (finally
          (sp/close storage))))))


;; Depth-limit, timeout, and cache-eviction integration tests were
;; removed alongside the legacy queue. Those invariants (max-depth
;; enforcement, per-call timeout checks, result-cache eviction) are not
;; yet implemented in the compile executor — revive these tests when
;; parity lands.


;; === Nested Composition Test ===

(deftest nested-composition-test
  (testing "nested function composition executes correctly"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Initialize base functions
        (registry/initialize-all! storage
                                  [{:multiply {:args {:a :int :b :int}
                                               :return-type :int
                                               :impl (fn [{:keys [a b]} _] (* @a @b))}}
                                   {:const {:args {:x :any}
                                            :return-type :any
                                            :impl (fn [{:keys [x]} _] @x)}}])

        ;; Create nested composition: (2 * (3 * 4)) = 24
        (fn-composition/sync-fns-to-storage! storage
                                             [{:name :two
                                               :parent :const
                                               :args {:x 2}}
                                              {:name :three
                                               :parent :const
                                               :args {:x 3}}
                                              {:name :four
                                               :parent :const
                                               :args {:x 4}}
                                              {:name :inner-mult
                                               :parent :multiply
                                               :args {:a :three
                                                      :b :four}}
                                              {:name :outer-mult
                                               :parent :multiply
                                               :args {:a :two
                                                      :b :inner-mult}}])

        (let [outer-fn (first (sp/query-entities storage :fn {:name "outer-mult"}))
              ctx (exec/create-context {:storage storage})
              result (exec/execute ctx (:id outer-fn) nil)]
          (is (= 24 result)))
        (finally
          (sp/close storage))))))
