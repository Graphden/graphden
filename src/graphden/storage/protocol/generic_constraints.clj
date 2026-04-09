(ns graphden.storage.protocol.generic-constraints
  "Generic constraint helpers using StorageCRUD.

   Provides a single ConstraintHelpers implementation that works with any
   storage backend through the StorageCRUD protocol. Backends no longer need
   to implement ConstraintHelpers themselves — they can use these generic
   functions for GraphConstraints validation.

   Usage in backend core.clj:
   ```clojure
   (require '[graphden.storage.protocol.generic-constraints :as gc])

   sp/GraphConstraints
   (validate-no-dependency-cycle! [this owner-fn-id ref-fn-id]
     (gc/validate-no-dependency-cycle! this owner-fn-id ref-fn-id))
   ```"
  (:require
    [graphden.storage.protocol.constraints :as constraints]
    [graphden.storage.protocol.core :as sp]))


(defrecord GenericConstraintHelpers
  [storage]

  sp/ConstraintHelpers

  (collect-dependency-chain
    [_this owner-fn-id]
    (constraints/collect-dependency-chain-impl
      (fn [current-id]
        ;; Get fn record to check parent-ids
        (let [fn-rec (sp/read-entity storage :fn current-id)
              ;; Get args for current fn
              args (sp/query-entities storage :arg {:fn-id current-id})
              ;; Collect fn references from args
              arg-refs (->> args
                            (mapcat (fn [arg]
                                      (cond-> []
                                        (:ref-id arg) (conj (:ref-id arg))
                                        (and (:value arg) (uuid? (:value arg))) (conj (:value arg)))))
                            (remove nil?)
                            set)
              ;; Add all parent-ids if present
              parent-ids (remove nil? (:parent-ids fn-rec))
              all-refs (into arg-refs parent-ids)]
          ;; Verify these are actual fn-ids
          (if (empty? all-refs)
            #{}
            (let [fn-results (sp/read-entities storage :fn (vec all-refs))]
              (set (keys fn-results))))))
      owner-fn-id)))


(defn validate-no-dependency-cycle!
  "Validates that referencing ref-fn does not create dependency cycle.
   Uses StorageCRUD to traverse dependency chain — works with any backend."
  [storage owner-fn-id ref-fn-id]
  (sp/validate-no-dependency-cycle-impl
    (->GenericConstraintHelpers storage) owner-fn-id ref-fn-id))
