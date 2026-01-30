(ns graphden.storage-protocol.generic-graph
  "Generic ExecutionGraph resolution using StorageCRUD.

   Provides a default `resolve-execution-graph` implementation that works
   with any storage backend through the StorageCRUD protocol. Backends can
   keep optimized implementations as overrides.

   Usage:
   ```clojure
   (require '[graphden.storage-protocol.generic-graph :as gg])

   ;; Resolve graph using any StorageCRUD-compatible storage
   (gg/resolve-execution-graph storage fn-id)
   ```"
  (:require
    [graphden.storage-protocol.graph :as graph]
    [graphden.storage-protocol.interface :as sp]))


(defn- load-fn-record
  "Loads a single fn record by ID via StorageCRUD."
  [storage fn-id]
  (sp/read-entity storage :fn fn-id))


(defn- load-fn-schema-record
  "Loads a single fn-schema record by ID via StorageCRUD."
  [storage fn-schema-id]
  (sp/read-entity storage :fn-schema fn-schema-id))


(defn- load-arg-schemas-for-fn-schema
  "Loads all arg-schemas for a fn-schema via StorageCRUD.
   Returns {arg-schema-id -> arg-schema-record}."
  [storage fn-schema-id]
  (let [arg-schemas (sp/query-entities storage :arg-schema {:fn-schema-id fn-schema-id})]
    (into {} (map (juxt :id identity) arg-schemas))))


(defn- load-arg-values-for-fn
  "Loads all arg-values bound to a fn via fn-arg join.
   Returns sequence of arg-value records."
  [storage fn-id]
  (let [fn-args (sp/query-entities storage :fn-arg {:fn-id fn-id})
        arg-value-ids (keep :arg-value-id fn-args)]
    (if (empty? arg-value-ids)
      []
      (vals (sp/read-entities storage :arg-value (vec arg-value-ids))))))


(defn- classify-uuid-refs
  "Classifies UUIDs into fn-refs vs call-site-refs via StorageCRUD.
   Returns {:fn-refs #{fn-ids} :call-sites {cs-id -> cs-record}}.
   Call-site :fn-id values are also added to :fn-refs so the BFS
   traverses through call-sites to their target functions."
  [storage uuid-refs]
  (if (empty? uuid-refs)
    {:fn-refs #{} :call-sites {}}
    (let [refs-vec (vec uuid-refs)
          fn-results (sp/read-entities storage :fn refs-vec)
          fn-ref-ids (set (keys fn-results))
          remaining (remove fn-ref-ids refs-vec)
          call-site-results (if (empty? remaining)
                              {}
                              (sp/read-entities storage :call-site (vec remaining)))
          ;; Also visit the fn that each call-site points to
          call-site-fn-ids (into #{} (keep :fn-id) (vals call-site-results))]
      {:fn-refs (into fn-ref-ids call-site-fn-ids)
       :call-sites call-site-results})))


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
    (partial load-fn-schema-record storage)
    (partial load-arg-schemas-for-fn-schema storage)
    (partial load-arg-values-for-fn storage)
    (partial classify-uuid-refs storage)
    fn-id))
