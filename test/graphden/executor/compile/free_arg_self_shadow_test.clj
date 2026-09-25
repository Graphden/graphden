(ns graphden.executor.compile.free-arg-self-shadow-test
  "Regression: a caller binding a nested free arg `:x` to a fn whose OWN
   free arg is also `:x` (`:_imp-owned-names` binding `:fn-defs
   :_imp-defs`). The bound fn's `:x` is the caller's — it must not read
   the binding being defined. Reading it made the binding's thunk force
   itself: an NPE out of the half-realized delay."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.test-infra.exec-harness :as harness]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*))))


(deftest nested-free-bound-to-same-named-free-test
  (harness/sync! [{:name :fss-b
                   :parent :rest
                   :args {:coll {:parent :coalesce
                                 :args {:value {:as :x} :default {:value []}}}}}
                  {:name :fss-a
                   :parent :count
                   :args {:coll {:parent :coalesce
                                 :args {:value {:as :x} :default {:value []}}}}}
                  {:name :fss-c
                   :parent :fss-a
                   :args {:x :fss-b}}])
  (testing "the bound fn's own :x comes from the caller, and its result feeds :x"
    (is (= 2 (exec/execute-by-name harness/*context* "fss-c" {:x [1 2 3]}))))
  (testing "a caller without :x reaches both coalesce defaults"
    (is (zero? (exec/execute-by-name harness/*context* "fss-c" {})))))
