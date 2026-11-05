(ns graphden.types.list-projection-contract-test
  "Check the shipped projection closures without a whole-package sweep."
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


(def ^:private loaded (loader/load-packages ["core" "web"]))


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
                     (and name parent
                          (not (#{:_types-usages-parsed :_types-usages-validation} name))
                          (or (some #(str/starts-with? (clojure.core/name name) %)
                                    ["_graph-tree-" "_types-usage-" "_types-usages-" "_cohort-" "_search-" "_namespace-"])
                              (#{:assoc-empty :graph-tree :_types-usages-apply :graph-search :graph-namespace-fns} name))))
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
  (doseq [[n expected-args] [[:graph-tree #{}]
                             [:_types-usages-apply #{:parsed}]
                             [:graph-search #{:q :descriptions?}]
                             [:graph-namespace-fns #{:namespace-id}]]]
    (let [contract (registry/rich-type-of n)]
      (is (= expected-args (set (keys (:args contract)))) (str n))
      (is (= #{:db} (:effects contract)) (str n))))
  (doseq [n [:_types-usage-incoming? :_types-usage-target-root? :_types-usage-summary :_graph-tree-count-row :_search-selection :_namespace-selection]]
    (is (empty? (:effects (registry/rich-type-of n)))
        (str n " captures inert facts rather than rereading storage for every row"))))
