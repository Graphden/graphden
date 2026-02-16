(ns graphden.storage.protocol.execution-graph-test
  "Tests for ExecutionGraphResult validation and reader."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.interface :as storage]))


;; === ExecutionGraphResult validation tests ===

(deftest execution-graph-validation-test
  (let [fn-id (random-uuid)
        fn-schema-id (random-uuid)
        arg-schema-id (random-uuid)
        valid-fns {fn-id {:id fn-id :fn-schema-id fn-schema-id}}
        valid-fn-schemas {fn-schema-id {:id fn-schema-id :name "test-fn"}}
        valid-arg-schemas {arg-schema-id {:id arg-schema-id :fn-schema-id fn-schema-id}}]

    (testing "creates valid result with all required fields"
      (let [result (storage/->execution-graph
                     {:fns valid-fns
                      :fn-schemas valid-fn-schemas
                      :arg-schemas valid-arg-schemas
                      :resolved-args {}})]
        (is (storage/execution-graph? result))
        (is (= valid-fns (:fns result)))
        (is (= valid-fn-schemas (:fn-schemas result)))))

    (testing "throws when :fns is not a map"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :fns map"
            (storage/->execution-graph
              {:fns "not-a-map"
               :fn-schemas valid-fn-schemas
               :arg-schemas valid-arg-schemas
               :resolved-args {}}))))

    (testing "throws when :fns is empty"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":fns must contain at least"
            (storage/->execution-graph
              {:fns {}
               :fn-schemas valid-fn-schemas
               :arg-schemas valid-arg-schemas
               :resolved-args {}}))))

    (testing "throws when :fn-schemas is not a map"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :fn-schemas map"
            (storage/->execution-graph
              {:fns valid-fns
               :fn-schemas []
               :arg-schemas valid-arg-schemas
               :resolved-args {}}))))

    (testing "throws when :fn-schemas is empty"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":fn-schemas must contain at least"
            (storage/->execution-graph
              {:fns valid-fns
               :fn-schemas {}
               :arg-schemas valid-arg-schemas
               :resolved-args {}}))))

    (testing "throws when :arg-schemas is not a map"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :arg-schemas map"
            (storage/->execution-graph
              {:fns valid-fns
               :fn-schemas valid-fn-schemas
               :arg-schemas "invalid"
               :resolved-args {}}))))

    (testing "throws when :resolved-args is not a map"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :resolved-args map"
            (storage/->execution-graph
              {:fns valid-fns
               :fn-schemas valid-fn-schemas
               :arg-schemas valid-arg-schemas
               :resolved-args []}))))))


(deftest execution-graph?-test
  (testing "returns true for ExecutionGraphResult"
    (let [result (storage/->execution-graph
                   {:fns {(random-uuid) {:id (random-uuid)}}
                    :fn-schemas {(random-uuid) {:id (random-uuid)}}
                    :arg-schemas {}
                    :resolved-args {}})]
      (is (true? (storage/execution-graph? result)))))

  (testing "returns false for other types"
    (is (false? (storage/execution-graph? {})))
    (is (false? (storage/execution-graph? nil)))
    (is (false? (storage/execution-graph? "string")))))


;; === ExecutionGraphReader protocol tests ===

(deftest execution-graph-reader-protocol-test
  (testing "ExecutionGraphReader protocol is defined"
    (is (some? storage/ExecutionGraphReader))
    (is (contains? (:sigs storage/ExecutionGraphReader) :graph-get-fn))
    (is (contains? (:sigs storage/ExecutionGraphReader) :graph-get-fn-schema))
    (is (contains? (:sigs storage/ExecutionGraphReader) :graph-get-arg-schemas))
    (is (contains? (:sigs storage/ExecutionGraphReader) :graph-get-resolved-args))
    (is (contains? (:sigs storage/ExecutionGraphReader) :graph-get-call-site)))

  (testing "ExecutionGraphResult implements ExecutionGraphReader"
    (let [fn-id (random-uuid)
          fn-schema-id (random-uuid)
          arg-schema-id (random-uuid)
          cs-id (random-uuid)
          graph (storage/->execution-graph
                  {:fns {fn-id {:id fn-id :fn-schema-id fn-schema-id}}
                   :fn-schemas {fn-schema-id {:id fn-schema-id :name "test-fn"}}
                   :arg-schemas {arg-schema-id {:id arg-schema-id :fn-schema-id fn-schema-id}}
                   :resolved-args {fn-id {arg-schema-id {:value 42}}}
                   :call-sites {cs-id {:id cs-id :value "result"}}})]
      ;; Test protocol methods
      (is (= {:id fn-id :fn-schema-id fn-schema-id}
             (storage/graph-get-fn graph fn-id)))
      (is (= {:id fn-schema-id :name "test-fn"}
             (storage/graph-get-fn-schema graph fn-schema-id)))
      ;; graph-get-arg-schemas returns a map of {arg-schema-id -> arg-schema-record}
      (is (= {arg-schema-id {:id arg-schema-id :fn-schema-id fn-schema-id}}
             (storage/graph-get-arg-schemas graph fn-schema-id)))
      (is (= {arg-schema-id {:value 42}}
             (storage/graph-get-resolved-args graph fn-id)))
      (is (= {:id cs-id :value "result"}
             (storage/graph-get-call-site graph cs-id)))))

  (testing "ExecutionGraphReader returns nil/empty for missing keys"
    (let [graph (storage/->execution-graph
                  {:fns {(random-uuid) {:id (random-uuid)}}
                   :fn-schemas {(random-uuid) {:id (random-uuid)}}
                   :arg-schemas {}
                   :resolved-args {}})]
      (is (nil? (storage/graph-get-fn graph (random-uuid))))
      (is (nil? (storage/graph-get-fn-schema graph (random-uuid))))
      ;; Returns empty map for missing fn-schema-id (no matching arg-schemas)
      (is (= {} (storage/graph-get-arg-schemas graph (random-uuid))))
      (is (nil? (storage/graph-get-resolved-args graph (random-uuid))))
      (is (nil? (storage/graph-get-call-site graph (random-uuid)))))))
