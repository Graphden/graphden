(ns graphden.storage.protocol.execution-graph-test
  "Tests for ExecutionGraphResult validation and reader.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.core :as storage]))


;; === ExecutionGraphResult validation tests ===

(deftest execution-graph-validation-test
  (let [fn-id (random-uuid)
        parent-id (random-uuid)
        arg-id (random-uuid)
        valid-fns {fn-id {:id fn-id :name "test-fn" :parent-id parent-id}
                   parent-id {:id parent-id :name "base-fn" :parent-id nil}}
        valid-args [{:id arg-id :fn-id fn-id :name "x" :type "int" :required true :is-fn false}]]

    (testing "creates valid result with all required fields"
      (let [result (storage/->execution-graph
                     {:fns valid-fns
                      :args valid-args})]
        (is (storage/execution-graph? result))
        (is (= valid-fns (:fns result)))
        (is (= valid-args (:args result)))))

    (testing "throws when :fns is not a map"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :fns map"
            (storage/->execution-graph
              {:fns "not-a-map"
               :args valid-args}))))

    (testing "throws when :fns is empty"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":fns must contain at least"
            (storage/->execution-graph
              {:fns {}
               :args valid-args}))))

    (testing "throws when :args is not a sequence"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :args sequence"
            (storage/->execution-graph
              {:fns valid-fns
               :args "invalid"}))))

    (testing "allows empty :args sequence"
      (let [result (storage/->execution-graph
                     {:fns valid-fns
                      :args []})]
        (is (storage/execution-graph? result))
        (is (= [] (:args result)))))))


(deftest execution-graph?-test
  (testing "returns true for ExecutionGraphResult"
    (let [result (storage/->execution-graph
                   {:fns {(random-uuid) {:id (random-uuid) :name "test"}}
                    :args []})]
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
    (is (contains? (:sigs storage/ExecutionGraphReader) :graph-get-args)))

  (testing "ExecutionGraphResult implements ExecutionGraphReader"
    (let [fn-id (random-uuid)
          parent-id (random-uuid)
          arg-1-id (random-uuid)
          arg-2-id (random-uuid)
          graph (storage/->execution-graph
                  {:fns {fn-id {:id fn-id :name "test-fn" :parent-id parent-id}
                         parent-id {:id parent-id :name "base-fn" :parent-id nil}}
                   :args [{:id arg-1-id :fn-id fn-id :name "a" :type "int" :value 1}
                          {:id arg-2-id :fn-id fn-id :name "b" :type "int" :value 2}]})]
      ;; Test graph-get-fn
      (is (= {:id fn-id :name "test-fn" :parent-id parent-id}
             (storage/graph-get-fn graph fn-id)))
      (is (= {:id parent-id :name "base-fn" :parent-id nil}
             (storage/graph-get-fn graph parent-id)))
      ;; Test graph-get-args - returns vector of args for fn-id
      (let [args (storage/graph-get-args graph fn-id)]
        (is (= 2 (count args)))
        (is (= #{"a" "b"} (set (map :name args)))))))

  (testing "ExecutionGraphReader returns nil/empty for missing keys"
    (let [graph (storage/->execution-graph
                  {:fns {(random-uuid) {:id (random-uuid) :name "test"}}
                   :args []})]
      (is (nil? (storage/graph-get-fn graph (random-uuid))))
      ;; Returns empty vector for fn with no args
      (is (= [] (storage/graph-get-args graph (random-uuid)))))))


;; === Accessor functions tests ===

(deftest graph-accessor-functions-test
  (let [fn-1-id (random-uuid)
        fn-2-id (random-uuid)
        arg-id (random-uuid)
        graph (storage/->execution-graph
                {:fns {fn-1-id {:id fn-1-id :name "fn-1"}
                       fn-2-id {:id fn-2-id :name "fn-2"}}
                 :args [{:id arg-id :fn-id fn-1-id :name "x" :type "int"}]})]

    (testing "get-graph-fns returns all fns"
      (let [fns (storage/get-graph-fns graph)]
        (is (= 2 (count fns)))
        (is (contains? fns fn-1-id))
        (is (contains? fns fn-2-id))))

    (testing "get-graph-args returns all args"
      (let [args (storage/get-graph-args graph)]
        (is (= 1 (count args)))
        (is (= arg-id (:id (first args))))))

    (testing "get-graph-args-for-fn returns args for specific fn"
      (let [fn-1-args (storage/get-graph-args-for-fn graph fn-1-id)
            fn-2-args (storage/get-graph-args-for-fn graph fn-2-id)]
        (is (= 1 (count fn-1-args)))
        (is (= "x" (:name (first fn-1-args))))
        (is (= [] fn-2-args))))))
