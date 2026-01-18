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
                          :resolved-args {fn-id
                                          {bad-arg-schema-id {:owner-fn-id fn-id
                                                              :arg-schema-id bad-arg-schema-id
                                                              :value 42}}}})
          ctx (exec/create-context {:storage mock-storage})]
      ;; When we try to provide an arg that will trigger validation on malformed schema
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid arg-schema: missing type"
            (exec/execute ctx fn-id {bad-arg-schema-id 100}))))))
