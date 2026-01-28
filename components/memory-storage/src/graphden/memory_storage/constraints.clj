(ns graphden.memory-storage.constraints
  "ConstraintHelpers implementation for memory storage.

   Provides constraint validation support through the ConstraintHelpers protocol
   implementation. Used by GraphConstraints to validate function relationships."
  (:require
    [graphden.memory-storage.crud :as crud]
    [graphden.storage-protocol.interface :as sp]))


(defn- build-arg-values-by-fn-index
  "Builds index: fn-id -> [arg-values...] by joining fn-arg -> arg-value.
   Returns map where each fn-id maps to vector of arg-value records."
  [fn-args-data arg-values-data]
  (reduce
    (fn [acc fn-arg]
      (let [fn-id (:fn-id fn-arg)
            arg-value-id (:arg-value-id fn-arg)
            arg-value (get arg-values-data arg-value-id)]
        (if arg-value
          (update acc fn-id (fnil conj []) arg-value)
          acc)))
    {}
    (vals fn-args-data)))


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
          ;; Build index: fn-id -> [arg-values...] via fn-arg join
          ;; This changes O(N*M) to O(N+M) where N=fns, M=arg-values
          fn-args-data (crud/get-entity-data state :fn-arg)
          arg-values-data (crud/get-entity-data state :arg-value)
          arg-values-by-fn (build-arg-values-by-fn-index fn-args-data arg-values-data)
          fns-data (crud/get-entity-data state :fn)]
      ;; Use generic BFS traversal
      (sp/traverse-bfs
        owner-fn-id
        (fn [current-id]
          ;; Get fn references from arg-values (UUIDs that are fn refs)
          (->> (get arg-values-by-fn current-id [])
               (map :value)
               (filter uuid?)
               ;; Check if this UUID is actually a fn
               (filter #(contains? fns-data %))))
        {:context-id owner-fn-id}))))
