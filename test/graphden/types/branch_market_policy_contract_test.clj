(ns ^:ci/package-impl-consumer graphden.types.branch-market-policy-contract-test
  "Check the shipped projection closures without a whole-package sweep."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as loader]
    [graphden.packages.sync :as sync]
    [graphden.test-infra.type-contract :as type-contract]
    [graphden.types.check :as check]
    [graphden.types.check.narrowing :as narrowing]
    [graphden.types.core :as types]))


(def ^:private loaded (loader/load-packages ["core" "web" "app-base" "app" "registry"]))


(use-fixtures :once
  (fn [test-fn]
    (binding [types/*type-aliases-override* (atom {})]
      (sync/register-type-aliases! (:fn-defs loaded))
      ;; Do not inherit unswept signatures from other loaded test namespaces.
      (binding [registry/*rich-types-override* (atom {:by-id {} :by-name {}})
                registry/*per-org-rich-override* (atom {})]
        (doseq [[fn-name fn-def] (:base-fn-defs loaded)]
          (registry/record-rich-types! fn-name fn-def))
        (test-fn)))))


(deftest projection-closures-retain-only-their-public-inputs
  (let [{sorted :fn-defs}
        (type-contract/prepared-closure
          loaded
          (fn [{:keys [name parent]}]
            (and name parent
                 (or (some #(str/starts-with? (clojure.core/name name) %)
                           ["_approval-display-" "_approval-reason-" "_approval-approver"
                            "_moderation-" "_marketplace-" "_branch-policy-"])
                     (#{:_branch-comment-created-id :semver-rank :moderation-queue
                        :approvals-report :moderate-package-version! :set-branch-policy!
                        :set-branch-review-policy! :add-branch-comment!} name)))))]
    (is (every? (set (map :name sorted))
                [:parse-json-body :_request-body :_add-branch-comment-row
                 :_set-branch-review-policy-row])
        "Actual cross-module and private dependencies are checked with their consumers")
    ;; Match sync's two passes: seed inferred signatures, then enforce every
    ;; contract with caller refinements and referenced literal returns.
    (binding [check/*ref-return-memo* (atom {})]
      (doseq [definition sorted]
        (try (check/check-fn-def! definition)
             (catch clojure.lang.ExceptionInfo _))))
    (let [caller-narrowings (narrowing/build-caller-narrowings sorted)
          overrides (narrowing/build-ref-return-overrides sorted)]
      (binding [check/*ref-return-memo* (atom {})]
        (doseq [definition sorted]
          (narrowing/check-fn-def-with-narrowings! definition caller-narrowings overrides)))))
  (doseq [[n expected-args] [[:moderate-package-version! #{:pkg-name :pkg-version :decision :note}]
                             [:semver-rank #{:version}] [:moderation-queue #{}]
                             [:approvals-report #{:stamp :target-branch-id :approver-ids :approvals :author-id :allow-self?}]
                             [:set-branch-policy! #{:branch-id :write-policy}]
                             [:set-branch-review-policy! #{:branch-id :required-approvals :allow-self-approval? :approver-ids}]
                             [:add-branch-comment! #{:source-branch-id :body :entity-name :entity-id}]]]
    (is (= expected-args (set (keys (:args (registry/rich-type-of n))))) (str n))))
