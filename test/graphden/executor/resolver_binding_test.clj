(ns graphden.executor.resolver-binding-test
  "End-to-end test of the generic value-resolver binding
   (`{:resolver <fn> :value <stored>}` → `binding.resolver-fn-id`):
   the executor evaluates the resolver graph fn with the stored value
   as its single argument at arg-resolution time, and the result flows
   into the slot. the retired `:override-kind :secret-path` was the legacy vault
   instance of the same mechanism (SECRETS.md § generalization)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]))


(use-fixtures :once (setup/create-container-fixture))


(deftest resolver-binding-resolves-through-a-graph-fn
  ;; ["core"] only — the test needs just :str-upper, and the full
  ;; [core web app] bundle makes the eager rebuild ~2600 fns (~3 min
  ;; measured) for this one assertion. Delta-invalidate after the sync
  ;; for the same reason.
  (let [{:keys [ctx storage]} (setup/bootstrap-crud-graph-from-golden!
                                "graphden.executor.resolver-binding-test"
                                ["core"])
        defs [{:name :rb-user :namespace "rbtest" :parent :str-upper
               ;; stored "abc" → resolver :str-upper → "ABC" flows into
               ;; the :string slot; the parent uppercases (idempotent
               ;; here) — result proves the resolver RAN.
               :args {:string {:resolver :str-upper :value "abc"}}}]]
    (setup/sync-and-invalidate! ctx storage defs)
    (testing "the stored value is transformed by the resolver before use"
      (is (= "ABC"
             (exec/execute-with-named-args
               ctx (records/fn-id "rbtest" :rb-user) {}))))))
