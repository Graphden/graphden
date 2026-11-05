(ns graphden.types.history-presentation-contract-test
  "Check history and guarded-write response composition without a whole corpus sweep."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.parse :as records-parse]
    [graphden.packages.sync :as sync]
    [graphden.types.check :as check]
    [graphden.types.check.narrowing :as narrowing]
    [graphden.types.core :as types]))


(def ^:private loaded (loader/load-packages ["core" "web" "app"]))


(use-fixtures :once
  (fn [test-fn]
    (binding [types/*type-aliases-override* (atom {})]
      (sync/register-type-aliases! (:fn-defs loaded))
      (exec/with-isolated-rich-types
        (fn []
          (doseq [[fn-name fn-def] (:base-fn-defs loaded)]
            (registry/record-rich-types! fn-name fn-def))
          (test-fn))))))


(deftest history-and-owned-write-presentation-contracts
  (let [names #{:_history-group-row :_history-page-rows :_history-page-limit
                :_history-page-offset :_history-page-model :load-fn-change-events
                :_history-revision :_history-positive-revision? :_history-restore-args-valid?
                :_history-restore-error-kind :_history-restore-on-throw
                :_history-restore-preview-try :preview-fn-history-restore
                :_history-restore-token :_history-restore-token-valid?
                :_history-restore-commit-try :commit-fn-history-restore
                :_save-explorer-view-on-throw :save-explorer-view :apply-inheritance :parse-inheritance-command}
        selected (filterv #(or (names (:name %)) (str/starts-with? (name (:name %)) "_inheritance-wire-")) (:fn-defs loaded))
        extra-defs (into {} (map (fn [[n fd]] [n (assoc fd :name n)])) (:base-fn-defs loaded))
        {:keys [fn-defs name->id]} (records-parse/prepare-module
                                     selected (sync/compute-all-fn-name-ids loaded) extra-defs)
        sorted (mapv check/checker-view
                     (deps/topological-sort fn-defs (constantly false) name->id))]
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
  (doseq [[fn-name expected] [[:load-fn-change-events #{:fn-id :limit :offset}]
                              [:preview-fn-history-restore #{:fn-id :branch-id :revision}]
                              [:commit-fn-history-restore #{:fn-id :branch-id :revision :expected-current}]
                              [:save-explorer-view #{:command :request}]
                              [:apply-inheritance #{:command :request}]]]
    (is (= expected (set (keys (:args (registry/rich-type-of fn-name))))) (str fn-name))))
