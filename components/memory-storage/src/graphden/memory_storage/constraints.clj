(ns graphden.memory-storage.constraints
  "ConstraintHelpers implementation for memory storage.

   Provides constraint validation support through the ConstraintHelpers protocol
   implementation. Used by GraphConstraints to validate function relationships."
  (:require
    [graphden.memory-storage.crud :as crud]
    [graphden.storage-protocol.interface :as sp]))


(defrecord MemoryConstraintHelpers
  [state-atom]

  sp/ConstraintHelpers

  (get-fn-schema-id-for-fn
    [_this fn-id]
    (:fn-schema-id (crud/get-record @state-atom :fn fn-id)))


  (get-fn-schema-id-for-arg-schema
    [_this arg-schema-id]
    (:fn-schema-id (crud/get-record @state-atom :arg-schema arg-schema-id)))


  (get-parent-fn-id
    [_this fn-id]
    (:parent-fn-id (crud/get-record @state-atom :fn fn-id)))


  (collect-parent-chain
    [this fn-id]
    (sp/collect-parent-chain-impl this fn-id))


  (collect-arg-schema-ids-in-chain
    [this fn-id]
    (let [ancestor-ids (sp/collect-parent-chain this fn-id)]
      (->> (crud/get-entity-data @state-atom :arg-value)
           (vals)
           (filter #(contains? ancestor-ids (:owner-fn-id %)))
           (map :arg-schema-id)
           (set))))


  (collect-dependency-chain
    [_this owner-fn-id]
    (let [state @state-atom
          ;; Build index: owner-fn-id -> [arg-values...]
          ;; This changes O(N*M) to O(N+M) where N=fns, M=arg-values
          arg-values-by-owner (group-by :owner-fn-id (vals (crud/get-entity-data state :arg-value)))
          fns-data (crud/get-entity-data state :fn)]
      (loop [to-visit [owner-fn-id]
             visited #{owner-fn-id}  ; Mark as visited when added to queue
             iter-count 0]
        ;; Check iteration limit to prevent infinite loops
        (sp/check-graph-iteration-limit! iter-count owner-fn-id)
        (if (empty? to-visit)
          visited
          (let [current-id (first to-visit)
                rest-to-visit (rest to-visit)
                arg-values (get arg-values-by-owner current-id [])
                ;; Get fn references from arg-values (UUIDs that are fn refs)
                ref-fn-ids (->> arg-values
                                (map :value)
                                (filter uuid?)
                                ;; Check if this UUID is actually a fn
                                (filter #(contains? fns-data %))
                                ;; Filter out already visited
                                (remove visited))
                new-visited (into visited ref-fn-ids)]
            (recur (into (vec rest-to-visit) ref-fn-ids)
                   new-visited
                   (inc iter-count))))))))
