(ns graphden.packages.app.editor-branches.impls
  "Storage boundary for the graph-change timeline partial."
  (:require
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.versioning.storage.history :as history]))


(defbase load-fn-change-events
  "One bounded page of change metadata for a fn and its owned graph rows."
  [fn-id limit offset]
  (cr/record-effect! :db)
  (when fn-id
    (history/change-groups (request/require-storage ctx) fn-id limit offset)))


(def impls
  {:load-fn-change-events load-fn-change-events})
