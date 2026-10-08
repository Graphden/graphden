(ns graphden.crud.inheritance.check
  "Check a projected graph without publishing derived types or diagnostics."
  (:require [graphden.crud.type-check :as tc]
            [graphden.executor.registry.core :as registry]
            [graphden.types.check :as types-check]
            [graphden.types.diagnostics :as diag]))

(defn projected-diagnostics
  [storage fn-ids]
  (binding [registry/*rich-types-override* (registry/fork-rich-types-atom (registry/active-rich-types-atom))
            registry/*per-org-rich-override* (atom (registry/per-org-rich-snapshot-for-isolation))]
    (tc/with-org-alias-view*
      storage
      (fn []
        (into []
              (keep (fn [fn-id]
                      (try
                        (when-let [fn-def (tc/reconstruct-fn-def storage fn-id)]
                          (types-check/check-fn-def! fn-def))
                        nil
                        (catch clojure.lang.ExceptionInfo e
                          {:fn-id fn-id :reason (ex-message e) :diagnostic (diag/from-ex e)}))))
              fn-ids)))))
