(ns graphden.test-infra.ui-operation-api
  "Real graph response adapters for authorized native UI operations."
  (:require
    [graphden.executor.interface :as exec]
    [graphden.packages.records.ids :as ids]))


(defn export-resolved
  [ctx input]
  (exec/execute-with-named-args ctx (ids/fn-id "app.ui-components" :_editor-configuration-plan)
                                {:input input}))


(defn export-current
  ([ctx] (export-current ctx {}))
  ([ctx request]
   (exec/execute-with-named-args ctx (ids/fn-id "app.ui-components" :_editor-root-plan)
                                 {:request request})))


(defn invoke-current
  [ctx request input]
  (exec/execute-with-named-args ctx (ids/fn-id "app.ui-components" :_editor-server-call)
                                {:request request :input input}))
