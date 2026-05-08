(ns graphden.storage.protocol.generic-graph
  "Generic ExecutionGraph resolution using StorageCRUD over the
   slot/fn-slot/binding model. Backends with optimised implementations
   (batched queries, CTEs, indexes) override this by implementing
   `ExecutionGraph` directly."
  (:require
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.graph :as graph]))


(defn- load-fn-record
  [storage fn-id]
  (sp/read-entity storage :fn fn-id))


(defn- load-fn-slots-for-fn
  [storage fn-id]
  (sp/query-entities storage :fn-slot {:fn-id fn-id}))


(defn- load-bindings-for-fn
  [storage fn-id]
  (sp/query-entities storage :binding {:fn-id fn-id}))


(defn- load-items-for-binding
  [storage binding-id]
  (sp/query-entities storage :binding-list-item {:binding-id binding-id}))


(defn- load-all-slots
  [storage]
  (sp/query-entities storage :slot {}))


(defn resolve-execution-graph
  "Resolve the complete graph reachable from `fn-id` via parent-ids,
   ref-fn-id (binding & list-item), and type-fn-id traversal."
  [storage fn-id]
  (when-not (sp/read-entity storage :fn fn-id)
    (throw (ex-info "Function not found"
                    {:type :not-found
                     :fn-id fn-id})))
  (graph/resolve-execution-graph-bfs
    {:load-fn-record         (partial load-fn-record storage)
     :load-fn-slots-for-fn   (partial load-fn-slots-for-fn storage)
     :load-bindings-for-fn   (partial load-bindings-for-fn storage)
     :load-items-for-binding (partial load-items-for-binding storage)
     :load-all-slots         #(load-all-slots storage)}
    fn-id))
