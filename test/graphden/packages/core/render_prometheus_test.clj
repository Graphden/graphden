(ns graphden.packages.core.render-prometheus-test
  "Behavioural tests for the :render-prometheus GRAPH composition —
   the OpenMetrics exposition is a `:fix` worklist over the nested
   metrics map (core/system/fns.edn), so it must be driven through the
   EXECUTOR over a synced graph, not called as an impl. Cases carried
   over verbatim from the pre-decomposition impl unit test."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.test-infra.exec-harness :as harness]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*)) ["core"]))


(defn- render [m]
  (exec/execute-with-named-args
    harness/*context* (harness/fn-id "render-prometheus") {:m m}))


(deftest render-prometheus-formats-numeric-leaves
  (testing "numeric leaves flatten + prefix graphden_; nested maps join with _"
    (is (= "graphden_heap_mb 125\ngraphden_counters_registry_rebuild 59"
           (render (array-map "heap_mb" 125
                              "counters" (array-map "registry_rebuild" 59))))))
  (testing "non-numeric labels (strings) are dropped — samples are numeric"
    (is (= "graphden_threads 42"
           (render (array-map "threads" 42 "hostname" "abc")))))
  (testing "keys are sanitised to [a-z0-9_] and lower-cased"
    (is (= "graphden_os_load_avg 1.5"
           (render (array-map "OS load-avg" 1.5)))))
  (testing "keyword keys (JSONB-roundtripped counters) render by name"
    (is (= "graphden_registry_rebuild 3"
           (render {:registry_rebuild 3}))))
  (testing "empty / nil map → empty exposition"
    (is (= "" (render {})))
    (is (= "" (render nil)))))
