(ns graphden.cache-protocol.interface
  "Protocol for execution graph caching.

   CacheStorage provides O(1) access to precomputed execution graphs,
   eliminating the need for O(depth) recursive queries during execution.

   Implementations maintain cache consistency by:
   1. Storing denormalized graph data (fns, schemas, merged args)
   2. Tracking dependencies with ref-counts for proper invalidation
   3. Rebuilding affected caches when source entities change")


(defprotocol CacheStorage
  "Protocol for caching execution graphs.

   Implementations should store:
   - Denormalized copies of fns, fn-schemas, arg-schemas
   - Precomputed merged arguments for each fn in the graph
   - Dependency tracking with ref-counts for invalidation"

  (get-cached-graph
    [this fn-id]
    "Returns cached execution graph for fn-id, or nil if not cached.

     The returned graph has the same structure as resolve-execution-graph:
     {:fns {fn-id -> fn-record}
      :fn-schemas {schema-id -> schema-record}
      :arg-schemas {arg-schema-id -> arg-schema-record}
      :fn-result-values {frv-id -> frv-record}
      :merged-args {fn-id -> {arg-schema-id -> resolved-value}}}")

  (cache-exists?
    [this fn-id]
    "Returns true if cache exists for fn-id.")

  (save-cache!
    [this fn-id graph dependencies]
    "Saves execution graph to cache with dependency tracking.

     Parameters:
     - fn-id: The function this cache is for
     - graph: The execution graph (same structure as resolve-execution-graph)
     - dependencies: Map of dependency counts
       {:fn-ids {dep-fn-id -> count}
        :fn-schema-ids {schema-id -> count}
        :arg-schema-ids {arg-schema-id -> count}}

     The ref-counts in dependencies allow proper cache invalidation when
     a dependency is used multiple times in the graph.")

  (delete-cache!
    [this fn-id]
    "Deletes cache for fn-id. Returns true if cache existed.")

  (find-caches-by-fn-dep
    [this dep-fn-id]
    "Returns set of cache-ids (fn-ids) that depend on dep-fn-id.")

  (find-caches-by-fn-schema-dep
    [this dep-fn-schema-id]
    "Returns set of cache-ids that depend on dep-fn-schema-id.")

  (find-caches-by-arg-schema-dep
    [this dep-arg-schema-id]
    "Returns set of cache-ids that depend on dep-arg-schema-id."))


(defn cached-storage?
  "Returns true if storage implements CacheStorage protocol."
  [storage]
  (satisfies? CacheStorage storage))


;; === Validation utilities ===

(defn validate-graph!
  "Validates that graph has the expected structure.
   Throws ex-info with :type :invalid-graph on validation failure."
  [graph]
  (when-not (map? graph)
    (throw (ex-info "Graph must be a map" {:type :invalid-graph :value graph})))
  (when-not (map? (:fns graph))
    (throw (ex-info "Graph :fns must be a map" {:type :invalid-graph :key :fns :value (:fns graph)})))
  (when-not (map? (:fn-schemas graph))
    (throw (ex-info "Graph :fn-schemas must be a map" {:type :invalid-graph :key :fn-schemas :value (:fn-schemas graph)})))
  (when-not (map? (:arg-schemas graph))
    (throw (ex-info "Graph :arg-schemas must be a map" {:type :invalid-graph :key :arg-schemas :value (:arg-schemas graph)})))
  true)


(defn validate-dependencies!
  "Validates that dependencies map has the expected structure.
   Throws ex-info with :type :invalid-dependencies on validation failure."
  [dependencies]
  (when-not (map? dependencies)
    (throw (ex-info "Dependencies must be a map" {:type :invalid-dependencies :value dependencies})))
  (when-not (map? (:fn-ids dependencies))
    (throw (ex-info "Dependencies :fn-ids must be a map" {:type :invalid-dependencies :key :fn-ids :value (:fn-ids dependencies)})))
  (when-not (map? (:fn-schema-ids dependencies))
    (throw (ex-info "Dependencies :fn-schema-ids must be a map" {:type :invalid-dependencies :key :fn-schema-ids :value (:fn-schema-ids dependencies)})))
  (when-not (map? (:arg-schema-ids dependencies))
    (throw (ex-info "Dependencies :arg-schema-ids must be a map" {:type :invalid-dependencies :key :arg-schema-ids :value (:arg-schema-ids dependencies)})))
  true)


(defn validate-uuid!
  "Validates that value is a UUID.
   Throws ex-info with :type :invalid-uuid on validation failure."
  [value param-name]
  (when-not (uuid? value)
    (throw (ex-info (str param-name " must be a UUID")
                    {:type :invalid-uuid :param param-name :value value})))
  true)
