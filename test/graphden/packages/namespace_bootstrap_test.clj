(ns ^:integration graphden.packages.namespace-bootstrap-test
  "A fresh production package sync must preserve namespaced composition through
   storage, the type sweep, execution, and a subsequent bundle import."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.packages.sync :as packages]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.types.diagnostics :as diagnostics]))


(use-fixtures :once (setup/create-container-fixture))
(use-fixtures :each exec/with-clean-registry exec/with-isolated-rich-types)


(deftest namespaced-twins-survive-fresh-bootstrap-and-incremental-import
  (let [storage (setup/create-test-storage)
        corpus {:packages [{:name "sample"}]
                :namespaces ["sample" "sample.a" "sample.b"]
                :base-fn-defs {:int-echo {:namespace "sample" :args {:value :int}
                                          :return-type :int :impl (fn [{:keys [value]} _] (force value))}
                               :text-echo {:namespace "sample" :args {:value :text}
                                           :return-type :text :impl (fn [{:keys [value]} _] (force value))}}
                :fn-defs [{:namespace "sample.a" :name :helper :parent :int-echo
                           :args {:value {:as :number}}}
                          {:namespace "sample.b" :name :helper :parent :text-echo
                           :args {:value {:as :text}}}
                          {:namespace "sample.a" :name :caller :parent :helper :args {:number 7}}
                          {:namespace "sample.b" :name :caller :parent :helper :args {:text "b"}}]}]
    (try
      (let [info (packages/register-base-fns-from-packages! storage corpus)]
        ;; No skipped sweep: this is the same two-stage path as production init.
        (packages/sync-fn-entities-from-packages! storage corpus info)
        (let [ctx (exec/create-context {:storage storage :base-fns (:base-fns info)})]
          (is (= 7 (exec/execute ctx (records/fn-id "sample.a" :caller) {})))
          (is (= "b" (exec/execute ctx (records/fn-id "sample.b" :caller) {})))
          (is (= :int (:return (registry/rich-type-of-id (records/fn-id "sample.a" :caller)))))
          (is (= :text (:return (registry/rich-type-of-id (records/fn-id "sample.b" :caller)))))
          (let [ids (packages/sync-bundle! storage [{:namespace "sample.a" :name :later
                                                     :parent :sample.b/helper :args {:text "imported"}}])]
            ;; Match the product import caller: sync returns changed identities;
            ;; its caller publishes them to the already-running context.
            (exec-ctx/invalidate-graph-cache! ctx ids)
            (recheck/record-imported-fn-types! ctx nil ids)
            (is (= "imported" (exec/execute ctx (records/fn-id "sample.a" :later) {}))))))
      (finally (sp/close storage)))))


(deftest same-name-type-failures-retain-both-diagnostics
  (let [storage (setup/create-test-storage)
        corpus {:packages [{:name "failures"}]
                :namespaces ["failures" "failures.a" "failures.b"]
                :base-fn-defs {:expect-int {:namespace "failures" :args {:value :int}
                                            :return-type :int :impl (fn [{:keys [value]} _] (force value))}}
                :fn-defs [{:namespace "failures.a" :name :bad :parent :expect-int
                           :args {:value "wrong-a"}}
                          {:namespace "failures.b" :name :bad :parent :expect-int
                           :args {:value "wrong-b"}}]}
        ids #{(records/fn-id "failures.a" :bad) (records/fn-id "failures.b" :bad)}]
    (try
      (binding [diagnostics/*diagnostics-override* (atom {})]
        (let [info (packages/register-base-fns-from-packages! storage corpus)
              error (try (packages/sync-fn-entities-from-packages! storage corpus info)
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))]
          (is (= :types/sweep-regression (:type error)))
          (is (= ids (set (keys (diagnostics/branch-errors nil)))))))
      (finally (sp/close storage)))))
