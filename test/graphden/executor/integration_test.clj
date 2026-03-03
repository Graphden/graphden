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
    [graphden.storage.protocol.interface :as sp]))


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
                                               :args {:a :five-fn>   ; Execute five-fn, use result
                                                      :b :three-fn>}}]) ; Execute three-fn, use result

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
                                               :args {:a :value-fn>   ; Same fn-usage
                                                      :b :value-fn>}}]) ; Same fn-usage (deduplicated)

        (let [double-fn (first (sp/query-entities storage :fn {:name "double-fn"}))
              ctx (exec/create-context {:storage storage})
              result (exec/execute ctx (:id double-fn) nil)]
          ;; Result should be 10 + 10 = 20
          (is (= 20 result))
          ;; counted-const should be called only ONCE due to caching
          (is (= 1 @call-count)))
        (finally
          (sp/close storage))))))


;; === Depth Limit Integration Test ===

(deftest depth-limit-warning-integration-test
  (testing "warns when approaching max depth"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Initialize recursive-like function
        (registry/initialize-all! storage
                                  [{:identity {:args {:x :any}
                                               :return-type :any
                                               :impl (fn [{:keys [x]} _] @x)}}])

        ;; Create a chain of functions to reach depth warning threshold
        ;; With max-depth 10 and warning at 80%, warning triggers at depth 8
        (let [chain-defs (vec
                           (concat
                             [{:name :fn-0
                               :parent :identity
                               :args {:x 42}}]
                             (for [i (range 1 12)]
                               {:name (keyword (str "fn-" i))
                                :parent :identity
                                :args {:x (keyword (str "fn-" (dec i) ">"))}})))]
          (fn-composition/sync-fns-to-storage! storage chain-defs)

          (let [last-fn (first (sp/query-entities storage :fn {:name "fn-11"}))
                ctx (exec/create-context {:storage storage
                                          :max-depth 10})]
            ;; Should throw due to depth exceeded
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Maximum recursion depth exceeded"
                  (exec/execute ctx (:id last-fn) nil)))))
        (finally
          (sp/close storage))))))


;; === Timeout Integration Test ===
;; Note: Timeout is checked at the START of each function call, not during execution.
;; A single long-running base function will complete even if it exceeds timeout.
;; We test timeout with a chain of functions where the total execution time exceeds timeout.

(deftest timeout-integration-test
  (testing "throws when execution timeout exceeded across nested calls"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Initialize a slow function (100ms per call)
        (registry/initialize-all! storage
                                  [{:slow-fn {:args {:x :any}
                                              :return-type :any
                                              :impl (fn [{:keys [x]} _]
                                                      (Thread/sleep 30)
                                                      @x)}}])

        ;; Create a chain of 5 slow functions - 5 * 30ms = 150ms total
        ;; With 50ms timeout, this should exceed the limit
        (fn-composition/sync-fns-to-storage! storage
                                             [{:name :slow-0
                                               :parent :slow-fn
                                               :args {:x 42}}
                                              {:name :slow-1
                                               :parent :slow-fn
                                               :args {:x :slow-0>}}
                                              {:name :slow-2
                                               :parent :slow-fn
                                               :args {:x :slow-1>}}
                                              {:name :slow-3
                                               :parent :slow-fn
                                               :args {:x :slow-2>}}
                                              {:name :slow-4
                                               :parent :slow-fn
                                               :args {:x :slow-3>}}])

        (let [last-slow-fn (first (sp/query-entities storage :fn {:name "slow-4"}))
              ctx (exec/create-context {:storage storage
                                        :timeout-ms 50})] ; 50ms timeout, 5*30ms = 150ms chain
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Execution timeout exceeded"
                (exec/execute ctx (:id last-slow-fn) nil))))
        (finally
          (sp/close storage))))))


;; === Cache Eviction Integration Test ===

(deftest cache-eviction-integration-test
  (testing "evicts old cache entries when limit reached"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Initialize functions
        (registry/initialize-all! storage
                                  [{:const {:args {:x :any}
                                            :return-type :any
                                            :impl (fn [{:keys [x]} _] @x)}}])

        ;; Create many fn-usages to fill cache
        (let [fn-defs (vec
                        (for [i (range 10)]
                          {:name (keyword (str "val-" i))
                           :parent :const
                           :args {:x i}}))]
          (fn-composition/sync-fns-to-storage! storage fn-defs)

          ;; Execute with very small cache
          (let [ctx (exec/create-context {:storage storage
                                          :cache-max-size 5
                                          :cache-warning-threshold 3})]
            ;; Execute all functions
            (doseq [i (range 10)]
              (let [fn-entity (first (sp/query-entities storage :fn {:name (str "val-" i)}))]
                (exec/execute ctx (:id fn-entity) nil)))
            ;; Cache should not exceed max size
            (is (<= (count @(:result-cache ctx)) 5))))
        (finally
          (sp/close storage))))))


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
                                               :args {:a :three>
                                                      :b :four>}}
                                              {:name :outer-mult
                                               :parent :multiply
                                               :args {:a :two>
                                                      :b :inner-mult>}}])

        (let [outer-fn (first (sp/query-entities storage :fn {:name "outer-mult"}))
              ctx (exec/create-context {:storage storage})
              result (exec/execute ctx (:id outer-fn) nil)]
          (is (= 24 result)))
        (finally
          (sp/close storage))))))
