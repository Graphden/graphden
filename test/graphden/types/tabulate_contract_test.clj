(ns graphden.types.tabulate-contract-test
  (:require
    [clojure.edn :as edn]
    [clojure.java.io :as io]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.parse :as records-parse]
    [graphden.packages.sync :as sync]
    [graphden.types.check :as check]
    [graphden.types.core :as types]))


(def ^:private loaded (loader/load-packages ["core"]))


(use-fixtures :once
  (fn [t]
    (binding [types/*type-aliases-override* (atom {})]
      (sync/register-type-aliases! (:fn-defs loaded))
      (exec/with-isolated-rich-types
        (fn []
          (doseq [[fn-name fn-def] (:base-fn-defs loaded)]
            (registry/record-rich-types! fn-name fn-def))
          (let [defs (:fns (edn/read-string (slurp (io/resource "packages/app/reprs/fns.edn"))))
                names #{:_tabulate-cell-value :_tabulate-cell-scalar? :_tabulate-cell
                        :_tabulate-columns :_tabulate-record :_tabulate-row :_tabulate-rows :tabulate-records}
                selected (filterv #(contains? names (:name %)) defs)
                extra (into {} (map (fn [[n fd]] [n (assoc fd :name n)])) (:base-fn-defs loaded))
                {:keys [fn-defs name->id]} (records-parse/prepare-module
                                             selected (sync/compute-all-fn-name-ids loaded) extra)]
            (doseq [fd (deps/topological-sort fn-defs (constantly false) name->id)]
              (check/check-fn-def! (check/checker-view fd))))
          (t))))))


(deftest tabulation-has-exact-public-contract
  (let [rt (registry/rich-type-of :tabulate-records)]
    (is (= {:records [:list (types/resolve-alias :keyword-map)]
            :columns [:list :keyword]}
           (:args rt)))
    (is (= [:list [:list :text]] (:return rt)))
    (is (= #{} (:effects rt)))))


(deftest tabulation-retains-secret-record-content
  (registry/record-rich-types-raw!
    :secret-table-records {:args {} :return [:list [:map :keyword [:secret :text]]]})
  (check/check-fn-def! {:name :secret-table :parent :tabulate-records
                        :args {:records :secret-table-records}})
  (is (types/contains-secret? (:return (registry/rich-type-of :secret-table)))))
