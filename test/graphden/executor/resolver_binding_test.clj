(ns ^:serial graphden.executor.resolver-binding-test
  "End-to-end test of the generic value-resolver binding
   (`{:resolver <fn> :value <stored>}` → `binding.resolver-fn-id`):
   the executor evaluates the resolver graph fn with the stored value
   as its single argument at arg-resolution time, and the result flows
   into the slot. `:override-kind :secret-path` is the legacy vault
   instance of the same mechanism (SECRETS.md § generalization)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.composition.core :as composition]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]))


(use-fixtures :once (setup/create-container-fixture))


(deftest resolver-binding-resolves-through-a-graph-fn
  (let [{:keys [ctx storage]} (setup/bootstrap-crud-graph-from-golden!)
        defs [{:name :rb-user :namespace "rbtest" :parent :str-upper
               ;; stored "abc" → resolver :str-upper → "ABC" flows into
               ;; the :string slot; the parent uppercases (idempotent
               ;; here) — result proves the resolver RAN.
               :args {:string {:resolver :str-upper :value "abc"}}}]]
    (composition/sync-fns-to-storage! storage defs)
    (cr/rebuild! ctx)
    (testing "the stored value is transformed by the resolver before use"
      (is (= "ABC"
             (exec/execute-with-named-args
               ctx (records/fn-id "rbtest" :rb-user) {}))))))
