(ns graphden.packages.app.structured-input-api.impls
  (:require
    [graphden.crud.structured-input-http :as input-http]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.defbase :refer [defbase]]))


(defbase _structured-input-command
  [phase request]
  (runtime/record-effect! :db)
  (input-http/dispatch ctx phase request))


(defbase _structured-input-mcp-command
  [request command]
  (runtime/record-effect! :db)
  (input-http/mcp-dispatch ctx request command))


(def impls
  {:_structured-input-command {:impl _structured-input-command :taint-propagate? true}
   :_structured-input-mcp-command {:impl _structured-input-mcp-command :taint-propagate? true}})
