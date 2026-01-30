(ns graphden.cache-memory.interface
  "In-memory implementation of CacheStorage protocol.

   This component stores execution graph caches in memory using atoms.
   Provides O(1) access to precomputed execution graphs without
   recursive traversal of data structures.

   Thread-safe using ReentrantReadWriteLock for concurrent access.

   Usage:
   (def cache (create-cache))
   (cache/save-cache! cache fn-id graph deps)
   (cache/get-cached-graph cache fn-id)"
  (:require
    [clojure.tools.logging :as log]
    [graphden.cache-protocol.interface :as cache]
    [graphden.storage-protocol.interface :as sp])
  (:import
    (java.util.concurrent.locks
      ReentrantReadWriteLock)))


;; === Dependency map helpers ===

(def ^:private dep-keys
  "Keys for all dependency types in the deps atom."
  [:fn-deps :fn-schema-deps :arg-schema-deps :call-site-deps])


(defn- remove-cache-from-dep-map
  "Removes a cache-id from a single dependency map.
   Returns updated map with empty entries removed."
  [dep-map cache-id]
  (persistent!
    (reduce-kv (fn [acc dep-id cache-ids]
                 (let [new-ids (disj cache-ids cache-id)]
                   (if (seq new-ids)
                     (assoc! acc dep-id new-ids)
                     acc)))
               (transient {})
               dep-map)))


(defn- remove-from-all-deps
  "Removes a cache-id from all dependency maps."
  [deps cache-id]
  (reduce #(update %1 %2 remove-cache-from-dep-map cache-id)
          deps
          dep-keys))


(defn- add-to-dep-map
  "Adds a cache-id to dependency entries for given dep-ids."
  [dep-map cache-id dep-ids]
  (reduce (fn [acc dep-id]
            (update acc dep-id (fnil conj #{}) cache-id))
          dep-map
          dep-ids))


(defn- add-to-all-deps
  "Adds a cache-id to all dependency maps based on dependencies structure."
  [deps cache-id dependencies]
  (-> deps
      (update :fn-deps add-to-dep-map cache-id (keys (:fn-ids dependencies)))
      (update :fn-schema-deps add-to-dep-map cache-id (keys (:fn-schema-ids dependencies)))
      (update :arg-schema-deps add-to-dep-map cache-id (keys (:arg-schema-ids dependencies)))
      (update :call-site-deps add-to-dep-map cache-id (keys (:call-site-ids dependencies)))))


;; === Pure state computation functions ===
;; These functions compute new state without side effects for testability.

(defn- compute-save-cache-state
  "Computes the new graphs and deps state after saving a cache.
   Pure function for testability.

   Arguments:
   - graphs: Current graphs map {fn-id -> ExecutionGraph}
   - deps: Current deps map {:fn-deps ... :fn-schema-deps ... :arg-schema-deps ...}
   - fn-id: The function ID to cache
   - exec-graph: The execution graph to store
   - dependencies: Dependency tracking info

   Returns {:graphs new-graphs :deps new-deps}"
  [graphs deps fn-id exec-graph dependencies]
  (let [cache-existed? (contains? graphs fn-id)
        new-deps (cond-> deps
                   cache-existed? (remove-from-all-deps fn-id)
                   true (add-to-all-deps fn-id dependencies))
        new-graphs (assoc graphs fn-id exec-graph)]
    {:graphs new-graphs
     :deps new-deps}))


(defn- compute-delete-cache-state
  "Computes the new graphs and deps state after deleting a cache.
   Pure function for testability.

   Returns {:graphs new-graphs :deps new-deps :existed? boolean}"
  [graphs deps fn-id]
  (let [existed? (contains? graphs fn-id)]
    {:graphs (dissoc graphs fn-id)
     :deps (remove-from-all-deps deps fn-id)
     :existed? existed?}))


;; === MemoryCache record ===

(defrecord MemoryCache
  [;; Atom containing cached graphs: {fn-id -> ExecutionGraphResult}
   graphs-atom
   ;; Atom containing dependencies:
   ;; {:fn-deps {dep-fn-id -> #{cache-id ...}}
   ;;  :fn-schema-deps {dep-fn-schema-id -> #{cache-id ...}}
   ;;  :arg-schema-deps {dep-arg-schema-id -> #{cache-id ...}}
   ;;  :call-site-deps {dep-call-site-id -> #{cache-id ...}}}
   deps-atom
   ;; ReentrantReadWriteLock for thread safety
   ^ReentrantReadWriteLock rw-lock]

  cache/CacheStorage

  (get-cached-graph
    [_ fn-id]
    (sp/with-read-lock rw-lock
                       #(get @graphs-atom fn-id)))


  (cache-exists?
    [_ fn-id]
    (sp/with-read-lock rw-lock
                       #(contains? @graphs-atom fn-id)))


  (save-cache!
    [_ fn-id graph dependencies]
    (cache/validate-save-cache-args! fn-id graph dependencies)
    (let [exec-graph (if (sp/execution-graph? graph)
                       graph
                       (sp/->execution-graph graph))]
      (sp/with-write-lock rw-lock
                          (fn []
                            (let [{:keys [graphs deps]}
                                  (compute-save-cache-state @graphs-atom @deps-atom
                                                            fn-id exec-graph dependencies)]
                              (reset! graphs-atom graphs)
                              (reset! deps-atom deps))
                            nil))))


  (delete-cache!
    [_ fn-id]
    (log/debug "Deleting cache for fn-id" fn-id)
    (sp/with-write-lock rw-lock
                        (fn []
                          (let [{:keys [graphs deps existed?]}
                                (compute-delete-cache-state @graphs-atom @deps-atom fn-id)]
                            (reset! graphs-atom graphs)
                            (reset! deps-atom deps)
                            existed?))))


  (find-caches-by-fn-dep
    [_ dep-fn-id]
    (sp/with-read-lock rw-lock
                       #(get-in @deps-atom [:fn-deps dep-fn-id] #{})))


  (find-caches-by-fn-schema-dep
    [_ dep-fn-schema-id]
    (sp/with-read-lock rw-lock
                       #(get-in @deps-atom [:fn-schema-deps dep-fn-schema-id] #{})))


  (find-caches-by-arg-schema-dep
    [_ dep-arg-schema-id]
    (sp/with-read-lock rw-lock
                       #(get-in @deps-atom [:arg-schema-deps dep-arg-schema-id] #{})))


  (find-caches-by-call-site-dep
    [_ dep-call-site-id]
    (sp/with-read-lock rw-lock
                       #(get-in @deps-atom [:call-site-deps dep-call-site-id] #{}))))


(defn create-cache
  "Creates an in-memory cache storage instance.

   No configuration required - just call without arguments.

   Example:
     (def cache (create-cache))
     (def cached-storage (cached/wrap-with-cache storage cache))"
  []
  (->MemoryCache
    (atom {})
    (atom {:fn-deps {}
           :fn-schema-deps {}
           :arg-schema-deps {}
           :call-site-deps {}})
    (ReentrantReadWriteLock.)))
