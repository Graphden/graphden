(ns graphden.test-infra.execution-api
  "Read the actual app.execution graph in bootstrapped service/UI tests."
  (:require
    [graphden.executor.interface :as exec]
    [graphden.packages.records.ids :as ids]))


(defn get-execution
  [ctx execution-id]
  (exec/execute-with-named-args ctx (ids/fn-id "app.execution" :get-execution)
                                {:id execution-id}))
