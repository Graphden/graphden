(ns graphden.types.explorer-view-contract-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is]]
    [graphden.executor.composition.deps :as deps]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as records-parse]
    [graphden.packages.sync :as sync]
    [graphden.types.check :as check]
    [graphden.types.core :as types]))


(deftest public-view-retains-its-owned-axis-contract
  (let [loaded (loader/load-packages ["core" "web" "app"])
        selected (filterv #(or (= :explorer-view (:name %))
                               (str/starts-with? (name (:name %)) "_explorer-"))
                          (:fn-defs loaded))
        extra (into {} (map (fn [[n fd]] [n (assoc fd :name n)])) (:base-fn-defs loaded))
        {:keys [fn-defs name->id]} (records-parse/prepare-module
                                     selected (sync/compute-all-fn-name-ids loaded) extra)]
    (binding [types/*type-aliases-override* (atom {})]
      (sync/register-type-aliases! (:fn-defs loaded))
      (exec/with-isolated-rich-types
        (fn []
          (doseq [[n fd] (:base-fn-defs loaded)] (registry/record-rich-types! n fd))
          (doseq [fd (deps/topological-sort fn-defs (constantly false) name->id)]
            (check/check-fn-def! (check/checker-view fd)))
          (let [rt (registry/rich-type-of :explorer-view)]
            (is (= #{:name :uses :uses-all :also :also-all :effects :kinds
                     :problems :namespaces :exclude :unused} (set (keys (:args rt)))))
            (is (= :fn-ref (get-in rt [:args :uses])))
            (is (= :fn-ref (get-in rt [:args :also])))
            (is (= #{:db} (:effects rt)))
            (is (= (types/resolve-alias :keyword-map) (:return rt)))))))
    (is (= (ids/fn-id "app.views" :explorer-view) (get name->id :explorer-view)))))
