(ns graphden.crud.branch-command-policy-graph-test
  "Graph branch input policy and the original public slot ownership."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.exec-harness :as harness]
    [graphden.versioning.storage.core :as vs]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*))))


(defn- call
  [name args]
  (exec/execute-by-name harness/*context* name args))


(defn- failure-kind
  [name args]
  (:type (ex-data (try (call name args) nil (catch clojure.lang.ExceptionInfo e e)))))


(deftest branch-normalization-is-graph-policy
  (doseq [value [nil "" "   " "open"]]
    (is (nil? (call "_branch-policy-normalized" {:write-policy value}))))
  (is (= "owner" (call "_branch-policy-normalized" {:write-policy " owner "})))
  (is (= "admins" (call "_branch-policy-normalized" {:write-policy "admins"})))
  (doseq [value [nil []]]
    (is (nil? (call "_branch-review-approvers-normalized" {:approver-ids value}))))
  (is (= ["1" "2"] (call "_branch-review-approvers-normalized" {:approver-ids [1 2]})))
  (doseq [value ["alice" {:user "alice"}]]
    (is (= :validation-error/approver-ids
           (failure-kind "_branch-review-approvers-normalized" {:approver-ids value}))))
  (doseq [value [0 2]]
    (is (= value (call "_branch-review-required-normalized" {:required-approvals value}))))
  (is (nil? (call "_branch-review-required-normalized" {:required-approvals nil})))
  (doseq [value [-1 "2" 1.5]]
    (is (= :validation-error/required-approvals
           (failure-kind "_branch-review-required-normalized" {:required-approvals value}))))
  (is (= {} (call "_branch-review-patch"
                  {:required-approvals :keep :allow-self-approval? :keep :approver-ids :keep})))
  (is (= {:required-approvals nil :allow-self-approval? false :approver-ids nil}
         (call "_branch-review-patch"
               {:required-approvals nil :allow-self-approval? false :approver-ids []}))))


(deftest branch-create-and-lookup-retain-trusted-owner-and-optional-false
  (let [storage harness/*storage*
        base-id (:id (first (sp/query-entities storage :branch {})))
        name (str "branch-command-" (random-uuid))
        options {:branch-id nil :base-branch-id base-id :forbid-invalid? false
                 :require-merge? false :write-policy " open "}]
    (is (= {:base-branch-id base-id :forbid-invalid? false :require-merge? false}
           (call "_branch-create-options" options)))
    (binding [tenancy/*current-principal* {:authenticated? true :user-id "trusted"}]
      (let [row (call "create-branch!" (assoc options :branch-name name))]
        (is (= "trusted" (:owner-id row)))
        (is (false? (:forbid-invalid? row)))
        (is (false? (:require-merge? row)))
        (is (= (:id row) (:id (call "resolve-branch-ref" {:ref name})))))
      (let [row (call "_create-branch-row"
                      {:branch-name (str name "-raw")
                       :options {:base-branch-id base-id :owner-id "forged"}})]
        (is (= "trusted" (:owner-id row)))))
    (let [uuid-name (str (random-uuid))
          row (vs/create-branch! storage uuid-name {:base-branch-id base-id})]
      (is (= (:id row) (:id (call "resolve-branch-ref" {:ref uuid-name})))))
    (doseq [value [nil "" "   " 42]]
      (is (nil? (call "resolve-branch-ref" {:ref value}))))))


(deftest public-branch-commands-forward-owned-inputs
  (let [base-id (:id (first (sp/query-entities harness/*storage* :branch {})))
        name (str "branch-owned-inputs-" (random-uuid))]
    (binding [tenancy/*current-principal* {:authenticated? true :user-id "trusted"}]
      (let [row (call "create-branch!" {:branch-name name :base-branch-id base-id})
            id (:id row)]
        (is (= base-id (:base-branch-id row)))
        (is (true? (call "set-branch-archived!" {:branch-id id :archived? true})))
        (is (some? (:archived-at (sp/read-entity harness/*storage* :branch id))))
        (is (false? (call "set-branch-archived!" {:branch-id id :archived? false})))
        (is (nil? (:archived-at (sp/read-entity harness/*storage* :branch id))))
        (is (= "proposed" (call "set-review-state!" {:branch-id id :proposed? true})))
        (is (nil? (call "set-review-state!" {:branch-id id :proposed? false})))
        (is (= {:required-approvals 2 :allow-self-approval? false :approver-ids ["alice"]}
               (call "set-branch-review-policy!" {:branch-id id :required-approvals 2
                                                  :allow-self-approval? false :approver-ids ["alice"]})))
        (is (= "  note  " (call "_branch-comment-text" {:body "  note  "})))
        (is (= {:source-branch-id id :body "  note  "}
               (call "_branch-comment-unanchored-row" {:source-branch-id id :body "  note  "})))
        (is (= :validation-error/comment-too-long
               (failure-kind "add-branch-comment!"
                             {:source-branch-id id :body (str/join (repeat 10001 "x"))
                              :entity-name nil :entity-id nil})))
        (let [comment-id (parse-uuid (call "add-branch-comment!"
                                           {:source-branch-id id :body "  note  "
                                            :entity-name nil :entity-id nil}))
              comment-row (sp/read-entity harness/*storage* :branch-comment comment-id)]
          (is (uuid? comment-id))
          (is (= "trusted" (:author-id comment-row)))
          (is (= "  note  " (:body comment-row))))))))


(deftest public-migrations-retain-owned-slot-identities
  (doseq [[root expected] [["resolve-branch-ref" #{"ref"}]
                           ["create-branch!" #{"branch-id" "branch-name" "base-branch-id" "forbid-invalid?" "write-policy" "require-merge?"}]
                           ["set-review-state!" #{"branch-id" "proposed?"}]
                           ["set-branch-archived!" #{"branch-id" "archived?"}]
                           ["load-fn-change-events" #{"fn-id" "limit" "offset"}]
                           ["preview-fn-history-restore" #{"fn-id" "branch-id" "revision"}]
                           ["commit-fn-history-restore" #{"fn-id" "branch-id" "revision" "expected-current"}]
                           ["save-explorer-view" #{"command" "request"}]
                           ["apply-inheritance" #{"command" "request"}]
                           ["parse-inheritance-command" #{"body"}]
                           ["slot-type-provenance" #{"parsed"}]
                           ["_set-branch-review-policy-row" #{"branch-id" "required-approvals" "allow-self-approval?" "approver-ids"}]
                           ["_add-branch-comment-row" #{"source-branch-id" "body" "entity-name" "entity-id"}]
                           ["error-boundary-wrap" #{"handler"}]]]
    (let [owned (sp/query-entities harness/*storage* :fn-slot {:fn-id (harness/fn-id root)})
          names (into #{} (map #(-> (sp/read-entity harness/*storage* :slot (:slot-id %)) :name)) owned)]
      (is (= expected names) root))))
