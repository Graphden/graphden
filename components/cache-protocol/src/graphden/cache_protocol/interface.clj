(ns graphden.cache-protocol.interface
  "Protocol for execution graph caching.

   CacheStorage provides O(1) access to precomputed execution graphs,
   eliminating the need for O(depth) recursive queries during execution.

   Implementations maintain cache consistency by:
   1. Storing denormalized graph data (fns, schemas, merged args)
   2. Tracking dependencies with ref-counts for proper invalidation
   3. Rebuilding affected caches when source entities change

   ## Value Codec

   This namespace also re-exports value encoding/decoding functions from
   `value-codec` for cache implementations. Use these instead of importing
   the internal module directly."
  (:require
    [clojure.tools.logging :as log]
    [graphden.cache-protocol.value-codec :as codec]))


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
      :call-sites {call-site-id -> call-site-record}
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
        :arg-schema-ids {arg-schema-id -> count}
        :call-site-ids {call-site-id -> count}}

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
    "Returns set of cache-ids that depend on dep-arg-schema-id.")

  (find-caches-by-call-site-dep
    [this dep-call-site-id]
    "Returns set of cache-ids that depend on dep-call-site-id."))


(defn cached-storage?
  "Returns true if storage implements CacheStorage protocol."
  [storage]
  (satisfies? CacheStorage storage))


;; === Cache loading timeout configuration ===

(def ^:dynamic *cache-load-timeout-ms*
  "Timeout for individual cache loading queries in milliseconds.
   Default is 30000 ms (30 seconds). Used by load-cache-data-parallel."
  30000)


(defn- deref-with-timeout
  "Derefs a future with timeout. Returns ::timeout if timed out.
   Logs a warning on timeout for visibility."
  [fut timeout-ms future-name]
  (let [result (deref fut timeout-ms ::timeout)]
    (when (= result ::timeout)
      (log/warn "Cache loading timed out for" future-name "after" timeout-ms "ms"))
    result))


(defn load-cache-data-parallel
  "Loads all cache data in parallel with timeout protection.
   Returns nil if cache doesn't exist or any query times out.

   Arguments:
   - cache-id: UUID of the cache (typically fn-id)
   - load-fns-fn: (fn [cache-id]) -> {fn-id -> fn-record}
   - load-fn-schemas-fn: (fn [cache-id]) -> {fn-schema-id -> schema-record}
   - load-arg-schemas-fn: (fn [cache-id]) -> {arg-schema-id -> arg-schema-record}
   - load-merged-args-fn: (fn [cache-id]) -> {fn-id -> {arg-schema-id -> value}}

   Starts all queries in parallel and waits for results with timeout.
   If any query times out, returns nil (cache miss)."
  [cache-id load-fns-fn load-fn-schemas-fn load-arg-schemas-fn load-merged-args-fn]
  (let [timeout-ms *cache-load-timeout-ms*
        fns-future (future (load-fns-fn cache-id))
        fn-schemas-future (future (load-fn-schemas-fn cache-id))
        arg-schemas-future (future (load-arg-schemas-fn cache-id))
        resolved-args-future (future (load-merged-args-fn cache-id))
        ;; Wait for fns first to check existence
        fns (deref-with-timeout fns-future timeout-ms "fns")]
    ;; If timeout or no fns found, cache doesn't exist
    (when (and (not= fns ::timeout) (seq fns))
      (let [fn-schemas (deref-with-timeout fn-schemas-future timeout-ms "fn-schemas")
            arg-schemas (deref-with-timeout arg-schemas-future timeout-ms "arg-schemas")
            resolved-args (deref-with-timeout resolved-args-future timeout-ms "resolved-args")]
        ;; If any other query timed out, treat as cache miss
        (when (and (not= fn-schemas ::timeout)
                   (not= arg-schemas ::timeout)
                   (not= resolved-args ::timeout))
          {:fns fns
           :fn-schemas fn-schemas
           :arg-schemas arg-schemas
           :resolved-args resolved-args})))))


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
  ;; call-site-ids is optional for backward compatibility, but if present must be a map
  (when (and (contains? dependencies :call-site-ids)
             (not (map? (:call-site-ids dependencies))))
    (throw (ex-info "Dependencies :call-site-ids must be a map" {:type :invalid-dependencies :key :call-site-ids :value (:call-site-ids dependencies)})))
  true)


(defn validate-uuid!
  "Validates that value is a UUID.
   Throws ex-info with :type :invalid-uuid on validation failure."
  [value param-name]
  (when-not (uuid? value)
    (throw (ex-info (str param-name " must be a UUID")
                    {:type :invalid-uuid :param param-name :value value})))
  true)


;; === Graph building helpers ===

(defn build-cached-graph
  "Builds an execution graph from cache data.
   Returns nil if fns is empty (cache miss).

   Arguments:
   - fns: map of {fn-id -> fn-record}
   - fn-schemas: map of {fn-schema-id -> schema-record}
   - arg-schemas: map of {arg-schema-id -> arg-schema-record}
   - resolved-args: map of {fn-id -> {arg-schema-id -> value}}
   - call-sites: (optional) map of {call-site-id -> call-site-record}

   This is a convenience wrapper around sp/->execution-graph for
   cache implementations."
  ([fns fn-schemas arg-schemas resolved-args]
   (build-cached-graph fns fn-schemas arg-schemas resolved-args {}))
  ([fns fn-schemas arg-schemas resolved-args call-sites]
   (when (seq fns)
     ;; Use require/resolve to avoid circular dependency with storage-protocol
     (let [->execution-graph (requiring-resolve 'graphden.storage-protocol.interface/->execution-graph)]
       (->execution-graph
         {:fns fns
          :fn-schemas fn-schemas
          :arg-schemas arg-schemas
          :resolved-args resolved-args
          :call-sites call-sites})))))


;; ============================================================================
;; VALUE CODEC RE-EXPORTS
;; ============================================================================
;;
;; Re-export value encoding/decoding functions from value-codec.
;; Use these instead of importing graphden.cache-protocol.value-codec directly.

(def parse-cached-value
  "Parses a cached value from the union format.
   See codec/parse-cached-value for details."
  codec/parse-cached-value)


(def format-cached-value
  "Formats a value for caching in the union format.
   See codec/format-cached-value for details."
  codec/format-cached-value)


(def fn-ref?
  "Returns true if the value is a function reference."
  codec/fn-ref?)


(def literal-value?
  "Returns true if the value is a wrapped literal value."
  codec/literal-value?)
