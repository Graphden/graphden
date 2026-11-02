(ns graphden.packages.app.editor-branches.impls
  "Storage boundary for the graph-change timeline partial."
  (:require
    [graphden.crud.request :as request]
    [graphden.crud.type-check :as type-check]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.versioning.storage.history :as history]
    [graphden.versioning.storage.restore :as restore]))


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


(def ^:private restore-conflicts
  #{:history/branch-mismatch :history/too-large :history/incomplete-provenance
    :history/secret-type-conflict})


(defn- report-restore-conflict
  [f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (let [kind (:type (ex-data e))]
        (if (contains? restore-conflicts kind)
          {:status :conflict :reason kind}
          (throw e))))))


(defbase preview-fn-history-restore
  "Redacted restore plan for one readable function on its current branch."
  [fn-id branch-id revision]
  (cr/record-effect! :db)
  (when (and (uuid? fn-id) (uuid? branch-id) (pos-int? revision))
    (report-restore-conflict
      #(restore/preview (request/require-storage ctx) fn-id branch-id revision))))


(defbase commit-fn-history-restore
  "Apply an exact preview token in one guarded graph transaction."
  [fn-id branch-id revision expected-current]
  (cr/record-effect! :db)
  (when (and (uuid? fn-id) (uuid? branch-id) (pos-int? revision)
             (string? expected-current) (= 64 (count expected-current)))
    (report-restore-conflict
      #(restore/apply-restore! (request/require-storage ctx)
                               fn-id branch-id revision expected-current
                               (fn [storage owner-id]
                                 (let [result (type-check/type-check-fn-and-dependents!
                                                ctx storage owner-id
                                                {:reject-secret? true})]
                                   (when (:secret? result)
                                     (throw (ex-info "Restore violates secret type flow"
                                                     {:type :history/secret-type-conflict})))
                                   (some? (:diagnostic result))))))))


(def impls
  {:load-fn-change-events load-fn-change-events
   :load-fn-history-diff load-fn-history-diff
   :preview-fn-history-restore preview-fn-history-restore
   :commit-fn-history-restore commit-fn-history-restore})
