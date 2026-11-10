(ns ^:integration ^:serial graphden.editor.component-batch-integration-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.editor.components :as components]
    [graphden.editor.config-resolution :as resolution]
    [graphden.executor.browser-source :as source]
    [graphden.system.branch-router.epoch :as epoch]
    [graphden.tenancy.context :as tenancy]
    [graphden.test-infra.golden-app :as ga]))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


(def ^:private refresh-components
  ["inspector" "explorer" "canvas" "canvas-values" "canvas-inheritance"
   "canvas-metadata" "canvas-type-structure" "canvas-bindings"])


(deftest fresh-batch-plans-match-eight-independent-exports
  (let [ctx (:ctx ga/*bootstrap*)
        captures (atom 0)
        snapshots (atom 0)
        capture resolution/capture
        with-snapshot source/with-snapshot]
    (binding [tenancy/*seams-override* (atom {})
              tenancy/*current-principal* nil
              tenancy/*current-org* "public"]
      (epoch/seed-watermark! (:storage ctx))
      (with-redefs [resolution/capture (fn [& args] (swap! captures inc) (apply capture args))
                    source/with-snapshot (fn [& args] (swap! snapshots inc) (apply with-snapshot args))]
        (let [individual (into {} (map (fn [component]
                                         [component (components/export-resolved ctx {:component component})]))
                               refresh-components)]
          (is (every? :ok (vals individual)) "Every ordinary shipped component exports successfully")
          (is (= [8 8] [@captures @snapshots]))
          (reset! captures 0)
          (reset! snapshots 0)
          (let [batch (components/export-resolved-batch ctx {:components refresh-components})]
            (is (true? (:ok batch)))
            (is (= [1 1] [@captures @snapshots]))
            ;; Compare the complete wire-ready plans, not just their roots or
            ;; counts: no unrelated component body may enter another plan.
            (is (= individual (:results batch)))))))))
