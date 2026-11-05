(ns graphden.packages.app.editor-branches.impls
  "Storage boundary for the graph-change timeline partial."
  (:require
    [graphden.crud.request :as request]
    [graphden.crud.type-check :as type-check]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.versioning.storage.history :as history]
    [graphden.versioning.storage.restore :as restore]))


(defbase _load-fn-change-page
  "One bounded page of change metadata for a fn and its owned graph rows."
  [fn-id limit offset]
  (cr/record-effect! :db)
  (when fn-id
    (history/change-group-page (request/require-storage ctx) fn-id limit offset)))


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


(defbase _preview-fn-history-restore
  "Canonical scoped restore preview with snapshot, redaction and exact token."
  [fn-id branch-id revision]
  (cr/record-effect! :db)
  (restore/preview (request/require-storage ctx) fn-id branch-id revision))


(defbase _commit-fn-history-restore
  "Exact-token transaction and publication, retaining secret-flow validation."
  [fn-id branch-id revision expected-current]
  (cr/record-effect! :db)
  (restore/apply-restore! (request/require-storage ctx)
                          fn-id branch-id revision expected-current
                          (fn [storage owner-id]
                            (let [result (type-check/type-check-fn-and-dependents!
                                           ctx storage owner-id {:reject-secret? true})]
                              (when (:secret? result)
                                (throw (ex-info "Restore violates secret type flow"
                                                {:type :history/secret-type-conflict})))
                              (some? (:diagnostic result))))))


(def impls
  {:_load-fn-change-page _load-fn-change-page
   :load-fn-history-diff load-fn-history-diff
   :_preview-fn-history-restore _preview-fn-history-restore
   :_commit-fn-history-restore _commit-fn-history-restore})
