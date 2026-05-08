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
        (let [fn-rec (sp/read-entity storage :fn current-id)
              bindings (sp/query-entities storage :binding {:fn-id current-id})
              binding-refs (into #{}
                                 (keep (fn [b]
                                         (or (:ref-fn-id b)
                                             (:type-override-fn-id b))))
                                 bindings)
              ;; Single batched query for ALL list-items belonging to
              ;; this fn's bindings (was N queries — one per binding).
              binding-ids (mapv :id bindings)
              item-refs (if (empty? binding-ids)
                          #{}
                          (into #{}
                                (keep :ref-fn-id)
                                (sp/query-entities storage :binding-list-item
                                                   {:binding-id binding-ids})))
              parent-ids (remove nil? (:parent-ids fn-rec))
              type-refs (keep #(get fn-rec %)
                              [:base-fn-id :element-fn-id :return-type-fn-id])
              all-refs (reduce into #{}
                               [binding-refs item-refs parent-ids type-refs])]
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
