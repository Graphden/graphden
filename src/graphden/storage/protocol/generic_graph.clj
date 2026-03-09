(ns graphden.storage.protocol.generic-graph
  "Generic ExecutionGraph resolution using StorageCRUD.

   Provides a default `resolve-execution-graph` implementation that works
   with any storage backend through the StorageCRUD protocol. Backends can
   keep optimized implementations as overrides.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)

   Usage:
   ```clojure
   (require '[graphden.storage.protocol.generic-graph :as gg])

   ;; Resolve graph using any StorageCRUD-compatible storage
   (gg/resolve-execution-graph storage fn-id)
   ```"
  (:require
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.graph :as graph]))


(defn- load-fn-record
  "Loads a single fn record by ID via StorageCRUD."
  [storage fn-id]
  (sp/read-entity storage :fn fn-id))


(defn- load-args-for-fn
  "Loads all args for a fn via StorageCRUD.
   Returns sequence of arg records."
  [storage fn-id]
  (sp/query-entities storage :arg {:fn-id fn-id}))


(defn resolve-execution-graph
  "Resolves the complete execution graph for a function using StorageCRUD.

   This is a generic implementation that works with any backend. It uses
   the shared BFS algorithm from graph.clj with CRUD-based loader functions.

   Backends with optimized implementations (batched queries, CTEs, indexes)
   can override this by implementing the ExecutionGraph protocol directly."
  [storage fn-id]
  (when-not (sp/read-entity storage :fn fn-id)
    (throw (ex-info "Function not found"
                    {:type :not-found
                     :fn-id fn-id})))
  (graph/resolve-execution-graph-bfs
    (partial load-fn-record storage)
    (partial load-args-for-fn storage)
    fn-id))
