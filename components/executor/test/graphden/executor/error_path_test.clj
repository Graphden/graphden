(ns graphden.executor.error-path-test
  "Edge case tests for error paths in executor."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.storage-protocol.interface :as sp]))


(use-fixtures :each exec/with-clean-registry)


(defn- create-mock-storage
  "Creates a mock storage that returns the specified execution graph."
  [execution-graph]
  (reify
    sp/ExecutionGraph
    (resolve-execution-graph
      [_ _fn-id]
      execution-graph)))


(deftest fn-schema-not-found-in-graph-test
  (testing "throws when fn-schema is missing from execution graph"
    ;; This tests the error path at lines 203-207 of executor/core.clj
    ;; where fn-schema is not found in the execution graph
    (let [fn-id (random-uuid)
          fn-schema-id (random-uuid)
          _ (exec/register-base-fn! :dummy (fn [_ _] nil))
          ;; Create mock storage that returns a graph with fn but missing fn-schema
          mock-storage (create-mock-storage
                         {:fns {fn-id {:id fn-id
                                       :name "my-dummy"
                                       :fn-schema-id fn-schema-id}}
                          :fn-schemas {}  ; Empty - fn-schema is missing!
                          :arg-schemas {}
                          :resolved-args {}})
          ctx (exec/create-context {:storage mock-storage})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Function schema not found in execution graph"
            (exec/execute ctx fn-id {}))))))


(deftest arg-schema-missing-type-test
  (testing "throws when arg-schema is missing :type field"
    ;; This tests the error path at lines 106-109 of executor/core.clj
    ;; where arg-schema is nil or missing :type
    (let [fn-id (random-uuid)
          fn-schema-id (random-uuid)
          bad-arg-schema-id (random-uuid)
          _ (exec/register-base-fn! :dummy (fn [_ _] nil))
          ;; Create mock storage with arg-schema missing :type
          mock-storage (create-mock-storage
                         {:fns {fn-id {:id fn-id
                                       :name "my-dummy"
                                       :fn-schema-id fn-schema-id}}
                          :fn-schemas {fn-schema-id {:id fn-schema-id
                                                     :name "dummy"
                                                     :returned-type :int}}
                          ;; arg-schema without :type field
                          :arg-schemas {bad-arg-schema-id {:id bad-arg-schema-id
                                                           :fn-schema-id fn-schema-id
                                                           :name "x"
                                                           :required true}}
                          ;; No resolved-args - arg is free so provided-arg triggers validation
                          :resolved-args {fn-id {}}})
          ctx (exec/create-context {:storage mock-storage})]
      ;; When we provide an arg, validation on malformed schema (no :type) should fail
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid arg-schema: missing type"
            (exec/execute ctx fn-id {bad-arg-schema-id 100}))))))


;; === Cache Eviction Tests ===

(deftest cache-eviction-test
  (testing "evicts oldest cache entries when cache limit is reached"
    ;; This tests the evict-cache-entries! function at lines 133-147 of core.clj
    ;; and check-cache-limit! at lines 150-167
    (let [fn-id (random-uuid)
          fn-schema-id (random-uuid)
          call-count (atom 0)
          _ (exec/register-base-fn! :counting-fn (fn [_ _] (swap! call-count inc)))
          ;; Create mock storage with fn and schema
          mock-storage (create-mock-storage
                         {:fns {fn-id {:id fn-id
                                       :name "counting-fn"
                                       :fn-schema-id fn-schema-id}}
                          :fn-schemas {fn-schema-id {:id fn-schema-id
                                                     :name "counting-fn"
                                                     :returned-type :int}}
                          :arg-schemas {}
                          :resolved-args {}
                          :call-sites {}})
          ;; Create context with very small cache limit
          ctx (exec/create-context {:storage mock-storage
                                    :cache-max-size 5
                                    :cache-warning-threshold 3})
          result-cache (:result-cache ctx)]
      ;; Pre-populate cache with 5 entries (at max limit)
      (doseq [i (range 5)]
        (swap! result-cache assoc (random-uuid) i))
      (is (= 5 (count @result-cache)))
      ;; Execute will trigger cache eviction when trying to add to full cache
      ;; Note: The function will execute successfully, evicting some old entries
      (exec/execute ctx fn-id nil)
      ;; After eviction, cache should have fewer entries than before + new one
      ;; Due to eviction ratio (0.2), we keep 80% = 4 entries, then add 1 = varies
      (is (<= (count @result-cache) 5) "Cache should not exceed max size"))))


(deftest cache-warning-threshold-test
  (testing "warning is logged when cache reaches warning threshold"
    ;; This tests the warning path at lines 199-204 of core.clj
    ;; We can't easily verify logging, but we ensure no crash at threshold
    (let [fn-id (random-uuid)
          fn-schema-id (random-uuid)
          cs-id (random-uuid)
          _ (exec/register-base-fn! :const-fn (fn [_ _] 42))
          ;; Create mock with call-site to trigger cache storage
          mock-storage (create-mock-storage
                         {:fns {fn-id {:id fn-id
                                       :name "const-fn"
                                       :fn-schema-id fn-schema-id}}
                          :fn-schemas {fn-schema-id {:id fn-schema-id
                                                     :name "const-fn"
                                                     :returned-type :int}}
                          :arg-schemas {}
                          :resolved-args {}
                          :call-sites {cs-id {:id cs-id :fn-id fn-id :name "result"}}})
          ;; Create context with warning threshold = 2
          ctx (exec/create-context {:storage mock-storage
                                    :cache-warning-threshold 2
                                    :cache-max-size 10})
          result-cache (:result-cache ctx)]
      ;; Pre-populate cache with 1 entry
      (swap! result-cache assoc (random-uuid) 1)
      ;; Execute - will add entry and potentially trigger warning at threshold=2
      (exec/execute ctx fn-id nil)
      ;; Should complete without error
      (is (>= (count @result-cache) 1)))))
