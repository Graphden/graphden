(ns ^:ci/package-impl-consumer graphden.types.explorer-view-contract-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as loader]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.sync :as sync]
    [graphden.test-infra.type-contract :as type-contract]
    [graphden.types.check :as check]
    [graphden.types.check.narrowing :as narrowing]
    [graphden.types.core :as types]))


(deftest public-view-retains-its-owned-axis-contract
  (let [loaded (loader/load-packages ["core" "web" "app"])
        {sorted :fn-defs :keys [name->id]}
        (type-contract/prepared-closure loaded #(= :explorer-view (:name %)))]
    (is (every? (set (map :name sorted))
                [:view-members :_view-filter-record :_view-select-result])
        "The public view is checked with its actual composed member selector")
    (binding [types/*type-aliases-override* (atom {})]
      (sync/register-type-aliases! (:fn-defs loaded))
      (exec/with-isolated-rich-types
        (fn []
          (doseq [[n fd] (:base-fn-defs loaded)] (registry/record-rich-types! n fd))
          (binding [check/*ref-return-memo* (atom {})]
            (doseq [definition sorted]
              (try (check/check-fn-def! definition)
                   (catch clojure.lang.ExceptionInfo _))))
          (let [caller-narrowings (narrowing/build-caller-narrowings sorted)
                overrides (narrowing/build-ref-return-overrides sorted)]
            (binding [check/*ref-return-memo* (atom {})]
              (doseq [definition sorted]
                (narrowing/check-fn-def-with-narrowings! definition caller-narrowings overrides))))
          (let [rt (registry/rich-type-of :explorer-view)]
            (is (= #{:name :uses :uses-all :also :also-all :effects :kinds
                     :problems :namespaces :exclude :unused} (set (keys (:args rt)))))
            (is (= :fn-ref (get-in rt [:args :uses])))
            (is (= :fn-ref (get-in rt [:args :also])))
            (is (= #{:db} (:effects rt)))
            (is (= (types/resolve-alias :keyword-map) (:return rt)))))))
    (is (= (ids/fn-id "app.views" :explorer-view) (get name->id :explorer-view)))))
