(ns graphden.types.list-projection-contract-test
  "Check the shipped projection closures without a whole-package sweep."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as loader]
    [graphden.packages.sync :as sync]
    [graphden.types.check :as check]
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
  (doseq [definition (:fn-defs loaded)
          :let [n (:name definition)]
          :when (and n (:parent definition)
                     (not (#{:_types-usages-parsed :_types-usages-validation} n))
                     (or (some #(str/starts-with? (name n) %) ["_graph-tree-" "_types-usage-" "_types-usages-"])
                         (#{:graph-tree :_types-usages-apply} n)))]
    (check/check-fn-def! definition))
  (doseq [[n expected-args] [[:graph-tree #{}]
                            [:_types-usages-apply #{:parsed}]]]
    (let [contract (registry/rich-type-of n)]
      (is (= expected-args (set (keys (:args contract)))) (str n))
      (is (= #{:db} (:effects contract)) (str n))))
  (doseq [n [:_types-usage-incoming? :_types-usage-target-root? :_types-usage-summary :_graph-tree-count-row]]
    (is (empty? (:effects (registry/rich-type-of n)))
        (str n " captures inert facts rather than rereading storage for every row"))))
