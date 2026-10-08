(ns graphden.packages.app.branch-review-graph-test
  "Approval button state comes from the merge gate's actual stamp verdict."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [graphden.test-infra.exec-harness :as harness]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*)) ["core" "web" "app"]))


(defn- run-graph
  [name args]
  (exec/execute-with-named-args harness/*context* (harness/fn-id name) args))


(deftest stale-approval-remains-recorded-but-current-approval-can-be-renewed
  (binding [tc/*current-principal* {:authenticated? true :user-id "alice"}]
    (let [storage harness/*storage*
          target (vs/create-branch! storage (str "review-target-" (random-uuid))
                                    {:owner-id "alice" :write-policy "owner"})
          source (vs/create-branch! storage (str "review-source-" (random-uuid))
                                    {:base-branch-id (:id target) :owner-id "alice"})
          source-storage (vs/switch-branch storage (:id source))
          probe (sp/create-entity source-storage :fn
                                  {:name (str "review-probe-" (random-uuid))
                                   :parent-ids [(harness/fn-id "const")]})
          args {:source-branch-id (:id source)}
          policy {:branch-id (:id target) :required-approvals 1
                  :allow-self-approval? true :approver-ids ["alice"]}]
      (try
        (is (= "alice" (run-graph "current-user-id" {})))
        (run-graph "set-branch-review-policy!" policy)
        (is (false? (:mine-current (run-graph "proposal-approval-status" args))))
        (is (= "alice" (run-graph "approve-proposal!" args)))
        (is (= {:mine true :mine-current true :have 1 :satisfied true}
               (select-keys (run-graph "proposal-approval-status" args)
                            [:mine :mine-current :have :satisfied])))
        (sp/update-entity source-storage :fn (:id probe) {:description "edited after approval"})
        (let [status (run-graph "proposal-approval-status" args)]
          (is (= {:mine true :mine-current false :have 0 :satisfied false}
                 (select-keys status [:mine :mine-current :have :satisfied])))
          (is (true? (:stale (first (:approvers status))))))
        (is (= "alice" (run-graph "approve-proposal!" args)))
        (is (true? (:mine-current (run-graph "proposal-approval-status" args))))
        ;; An author's current approval can be withdrawn even when the
        ;; target excludes it from the required approval count.
        (run-graph "set-branch-review-policy!" (assoc policy :allow-self-approval? false))
        (let [status (run-graph "proposal-approval-status" args)]
          (is (= {:mine true :mine-current true :have 0 :satisfied false}
                 (select-keys status [:mine :mine-current :have :satisfied])))
          (is (= [{:approver-id "alice" :counted false :reason "author" :stale false}]
                 (filterv #(not (:stale %)) (:approvers status)))))
        (finally
          (vs/delete-branch! storage (:id source))
          (vs/delete-branch! storage (:id target)))))))
