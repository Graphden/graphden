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


(defbase load-fn-history-diff
  "A coherent, bounded comparison of two graph revisions for a scoped fn."
  [fn-id branch-id from-revision to-revision]
  (cr/record-effect! :db)
  (when (and (uuid? fn-id) (uuid? branch-id)
             (pos-int? from-revision) (pos-int? to-revision))
    (try
      (history/diff-at (request/require-storage ctx) fn-id branch-id
                       from-revision to-revision)
      (catch clojure.lang.ExceptionInfo e
        (if (= :history/too-large (:type (ex-data e)))
          {:error-code :history/too-large}
          (throw e))))))


(def impls
  {:load-fn-change-events load-fn-change-events
   :load-fn-history-diff load-fn-history-diff})
