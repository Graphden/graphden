(ns graphden.types.view-member-policy-contract-test
  "Check typed canonical view facts and pure graph axis composition."
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


(deftest view-axis-policy-contracts
  (let [selected (filterv #(or (= :view-members (:name %))
                               (str/starts-with? (name (:name %)) "_view-filter-")
                               (str/starts-with? (name (:name %)) "_view-select-"))
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
  (is (= #{:name :views :uses :effects :kinds :problems :namespaces :exclude :unused}
         (set (keys (:args (registry/rich-type-of :view-members)))))))
