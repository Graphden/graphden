(ns graphden.packages.app.editor-panels.impls
  "Impls for the sidebar panel partials. One thin boundary defbase —
   the type-diagnostics read joins the in-memory per-branch store
   (`graphden.types.diagnostics`) with fn names from storage; all
   rendering is graph fn-defs in `fns.edn`."
  (:require
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.diagnostics :as diag]
    [graphden.versioning.storage.core :as vs]))


(defbase type-diagnostics-list
  ;; Error-tolerance Phase 3 — the CURRENT branch's recorded type
  ;; diagnostics as flat display rows. The store is derived in-memory
  ;; state ({fn-id [diagnostic …]} per branch, re-recorded by every
  ;; post-write check), so the only storage round-trip is the batched
  ;; fn-name join. Branch comes from the request's versioned storage
  ;; (nil = default branch), same as the post-write recorder.
  []
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)
        errs (diag/branch-errors (vs/current-branch-id storage))
        fn-ids (vec (keys errs))
        fn-rows (when (seq fn-ids) (sp/read-entities storage :fn fn-ids))]
    (->> errs
         (mapcat (fn [[fn-id diags]]
                   (let [fn-name (or (:name (get fn-rows fn-id)) (str fn-id))]
                     (map (fn [d]
                            {:fn-id (str fn-id)
                             :fn-name fn-name
                             ;; `:arg-name` when the checker stamped one;
                             ;; a ref-mismatch diagnostic carries the
                             ;; bound fn under `:binding` instead.
                             :arg (or (some-> (:arg-name d) name)
                                      (when (keyword? (:binding d))
                                        (name (:binding d)))
                                      "")
                             :message (str (:message d))})
                          diags))))
         (sort-by :fn-name)
         vec)))


(def impls
  {:type-diagnostics-list type-diagnostics-list})
