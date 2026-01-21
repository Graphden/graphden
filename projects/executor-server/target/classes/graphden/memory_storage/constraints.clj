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
