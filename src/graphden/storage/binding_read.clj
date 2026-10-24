(ns graphden.storage.binding-read
  "Bounded owner reads for exact binding slots. Versioned storage budgets the
   complete owner candidate set before filtering a mutable slot identity."
  (:require
    [graphden.storage.protocol.core :as sp]))


(def ^:private max-owner-candidates 256)


(defn owner-bindings
  [storage fn-id]
  (sp/query-bounded-entities storage :binding {:fn-id fn-id} max-owner-candidates))


(defn exact-slot
  [bindings slot-id]
  (filterv #(= slot-id (:slot-id %)) bindings))
