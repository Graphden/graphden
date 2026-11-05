(ns ^:integration ^:serial graphden.crud.branch-workflow-policy-graph-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.graph-harness :as gh]
    [graphden.versioning.merge.core :as policy]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (setup/create-container-fixture)
  (gh/graph-fixture (str (ns-name *ns*))))


(deftest approval-display-agrees-with-canonical-merge-verdicts
  (let [target (random-uuid)
        other-target (random-uuid)
        stamp "3|2026"
        rows [{:approver-id "alice" :content-stamp stamp :target-branch-id target}
              {:approver-id "bob" :content-stamp "old" :target-branch-id target}
              {:approver-id "dave" :content-stamp stamp :target-branch-id other-target}
              {:approver-id "erin" :content-stamp stamp :target-branch-id target}]
        report (fn [allow author self?]
                 (gh/exec-name :approvals-report
                               {:stamp stamp :target-branch-id target :approver-ids allow
                                :approvals rows :author-id author :allow-self? self?}))]
    (is (= {:approver-id "alice" :counted false :reason "author" :stale false}
           (first (report [] "alice" false))))
    (is (vector? (report [] "alice" false)))
    (is (= {:approver-id "alice" :counted true :reason nil :stale false}
           (first (report [] "alice" true))))
    (is (= [{:approver-id "alice" :counted true :reason nil :stale false}
            {:approver-id "bob" :counted false :reason "stale" :stale true}
            {:approver-id "dave" :counted false :reason "other-target" :stale true}
            {:approver-id "erin" :counted false :reason "not-an-approver" :stale true}]
           (vec (report ["alice" "bob" "dave"] nil false))))
    (doseq [[allow author self?] [[[] nil false] [[] "alice" false] [["erin"] nil true]]]
      (is (= (policy/count-valid-approvals* stamp target (set allow) rows author self?)
             (count (filter :counted (report allow author self?))))))))


(deftest branch-command-projections-preserve-patch-and-author
  (let [storage (:storage gh/*graph*)
        branch (vs/create-branch! storage (gh/uniq "policy"))
        id (:id branch)]
    (try
      (doseq [[input expected] [[" owner " "owner"] ["open" nil] [nil nil] ["  " nil]
                                ["\u2003owner\u2003" "owner"] ["\u2003" nil] ["admins" "admins"]]]
        (is (= expected (gh/exec-name :set-branch-policy! {:branch-id id :write-policy input})))
        (is (= expected (:write-policy (sp/read-entity storage :branch id)))))
      (doseq [invalid ["everyone" "OWNER"]]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"invalid write-policy"
              (gh/exec-name :set-branch-policy! {:branch-id id :write-policy invalid}))))
      (is (= "admins" (:write-policy (sp/read-entity storage :branch id))))
      (let [args {:branch-id id :required-approvals 2 :allow-self-approval? false :approver-ids ["alice"]}]
        (is (= {:required-approvals 2 :allow-self-approval? false :approver-ids ["alice"]}
               (gh/exec-name :set-branch-review-policy! args))))
      (is (= {:required-approvals 3 :allow-self-approval? false :approver-ids ["alice"]}
             (gh/exec-name :set-branch-review-policy!
                           {:branch-id id :required-approvals 3 :allow-self-approval? :keep :approver-ids :keep})))
      (binding [tenancy/*current-principal* {:authenticated? true :user-id "alice"}]
        (let [comment-id (gh/exec-name :add-branch-comment!
                                       {:source-branch-id id :body "Review" :entity-name nil :entity-id nil})
              comment-row (sp/read-entity storage :branch-comment (parse-uuid comment-id))]
          (is (= {:author-id "alice" :body "Review" :source-branch-id id}
                 (select-keys comment-row [:author-id :body :source-branch-id])))
          (sp/delete-entity storage :branch-comment (:id comment-row))))
      (finally (sp/delete-entity storage :branch id)))))
