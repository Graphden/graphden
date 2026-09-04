(ns graphden.executor.compile.unread-bindings-test
  "`compile.renames/dead-env-bindings` through the runtime accessor: a
   binding a fn carries on a slot deep in its ref tree that nothing in
   the tree reads — the silent no-op class (the value is written, the
   run ignores it, nothing errors)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.test-infra.exec-harness :as harness]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*)) ["core"]))


(deftest dead-env-binding-detected-test
  (harness/sync!
    [;; The leaf binds its own :string — closer-fn-wins, so an OUTER
     ;; binding of :string can never reach it.
     {:name :ub-leaf :parent :str-upper :args {:string "leaf"}}
     {:name :ub-mid :parent :identity :args {:value :ub-leaf}}
     {:name :ub-dead :parent :ub-mid :args {:string "outer"}}
     ;; Same shape with the leaf's :string left FREE — the outer binding
     ;; is what feeds it.
     {:name :ub-leaf-free :parent :str-upper}
     {:name :ub-mid-free :parent :identity :args {:value :ub-leaf-free}}
     {:name :ub-live :parent :ub-mid-free :args {:string "outer"}}])

  (testing "the run confirms the semantics: the dead binding changes nothing, the live one feeds the leaf"
    (is (= "LEAF" (exec/execute-by-name harness/*context* "ub-dead" {})))
    (is (= "OUTER" (exec/execute-by-name harness/*context* "ub-live" {}))))

  (testing "the detector names exactly the binding nothing reads"
    (is (= [:string] (cr/unread-bindings harness/*context* (harness/fn-id "ub-dead"))))
    (is (= [] (cr/unread-bindings harness/*context* (harness/fn-id "ub-live")))))

  (testing "a fn without env-bindings answers [] without a walk"
    (is (= [] (cr/unread-bindings harness/*context* (harness/fn-id "ub-leaf"))))))
