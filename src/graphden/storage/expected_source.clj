(ns graphden.storage.expected-source
  "Backend-canonical expected graph rows, calculated before a write. This
   capability never normalizes previously read source or certifies storage."
  (:require
    [graphden.storage.protocol.core :as sp]))


(defn canonical-graph
  "Canonicalize a complete expected five-kind graph using the writer backend.
   Return nil if any kind or the backend is unsupported. Callers retain their
   original write input and independently authorize every referenced source."
  [storage graph]
  (when (satisfies? sp/StorageExpectedSource storage)
    (reduce-kv
      (fn [result kind entity]
        (if-let [rows (sp/expected-stored-rows storage entity (mapv #(dissoc % :kind) (get graph kind)))]
          (assoc result kind rows)
          (reduced nil)))
      {}
      {:fns :fn :slots :slot :fn-slots :fn-slot :bindings :binding :list-items :binding-list-item})))
