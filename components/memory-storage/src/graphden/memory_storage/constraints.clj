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
    [_this fn-id]
    ;; Optimized: build parent-map once and traverse in memory.
    ;; This avoids multiple state-atom derefs during traversal.
    (let [state @state-atom
          fns-data (crud/get-entity-data state :fn)
          ;; Build fn-id -> parent-fn-id map
          parent-map (->> fns-data
                          vals
                          (filter :parent-fn-id)
                          (map (juxt :id :parent-fn-id))
                          (into {}))]
      ;; Traverse parent chain in memory
      (loop [current-id (get parent-map fn-id)
             ancestor-ids #{}]
        (if (or (nil? current-id) (contains? ancestor-ids current-id))
          ancestor-ids
          (recur (get parent-map current-id)
                 (conj ancestor-ids current-id))))))


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
      ;; Use generic BFS traversal
      (sp/traverse-bfs
        owner-fn-id
        (fn [current-id]
          ;; Get fn references from arg-values (UUIDs that are fn refs)
          (->> (get arg-values-by-owner current-id [])
               (map :value)
               (filter uuid?)
               ;; Check if this UUID is actually a fn
               (filter #(contains? fns-data %))))
        {:context-id owner-fn-id}))))
