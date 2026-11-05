(ns ^:ci/package-impl-consumer graphden.types.crud-apply-policy-contract-test
  "Check the shipped projection closures without a whole-package sweep."
  (:require
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


(def ^:private loaded (loader/load-packages ["core" "web" "app-base" "app" "registry"]))


(use-fixtures :once
  (fn [test-fn]
    (binding [types/*type-aliases-override* (atom {})]
      (sync/register-type-aliases! (:fn-defs loaded))
      (exec/with-isolated-rich-types
        (fn []
          (doseq [[fn-name fn-def] (:base-fn-defs loaded)]
            (registry/record-rich-types! fn-name fn-def))
          (test-fn))))))


(deftest projection-closures-retain-only-their-public-inputs
  (let [selected (filterv
                   (fn [{:keys [name parent]}]
                     (and parent (#{:_apply-error-code :_apply-write-rejection :_apply-rejection-kind :_apply-rejection-status
                                    :_apply-http-status :_apply-error-message :_apply-rejection-envelope :_apply-formatted-outcome :_apply-response
                                    :_apply-create-parsed :_apply-update-parsed
                                    :try-apply-create :try-apply-update
                                    :try-apply-seq-append :try-apply-seq-update :try-apply-seq-move} name)))
                   (:fn-defs loaded))
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
  (doseq [[n expected-args] [[:try-apply-create #{:entity-type :entity-data :type-str :form-data}]
                             [:try-apply-update #{:entity-type :entity-data :type-str :form-data :id-uuid}]
                             [:try-apply-seq-append #{:parsed :seq-binding}]
                             [:try-apply-seq-update #{:parsed :item}]
                             [:try-apply-seq-move #{:parsed :item}]]]
    (is (= expected-args (set (keys (:args (registry/rich-type-of n))))) (str n))))
