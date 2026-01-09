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


;; === MemoryCache record ===

(defrecord MemoryCache
  [;; Atom containing cached graphs: {fn-id -> ExecutionGraphResult}
   graphs-atom
   ;; Atom containing dependencies:
   ;; {:fn-deps {dep-fn-id -> #{cache-id ...}}
   ;;  :fn-schema-deps {dep-fn-schema-id -> #{cache-id ...}}
   ;;  :arg-schema-deps {dep-arg-schema-id -> #{cache-id ...}}}
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
    (log/debug "Saving cache for fn-id" fn-id)
    (sp/with-write-lock rw-lock
                        (fn []
                          ;; First, remove old dependency entries if cache exists
                          (when (contains? @graphs-atom fn-id)
                            (swap! deps-atom
                                   (fn [deps]
                                     (-> deps
                                         (update :fn-deps
                                                 (fn [m]
                                                   (reduce-kv (fn [acc dep-id cache-ids]
                                                                (let [new-ids (disj cache-ids fn-id)]
                                                                  (if (empty? new-ids)
                                                                    (dissoc acc dep-id)
                                                                    (assoc acc dep-id new-ids))))
                                                              {}
                                                              m)))
                                         (update :fn-schema-deps
                                                 (fn [m]
                                                   (reduce-kv (fn [acc dep-id cache-ids]
                                                                (let [new-ids (disj cache-ids fn-id)]
                                                                  (if (empty? new-ids)
                                                                    (dissoc acc dep-id)
                                                                    (assoc acc dep-id new-ids))))
                                                              {}
                                                              m)))
                                         (update :arg-schema-deps
                                                 (fn [m]
                                                   (reduce-kv (fn [acc dep-id cache-ids]
                                                                (let [new-ids (disj cache-ids fn-id)]
                                                                  (if (empty? new-ids)
                                                                    (dissoc acc dep-id)
                                                                    (assoc acc dep-id new-ids))))
                                                              {}
                                                              m)))))))
                          ;; Store the graph
                          (let [exec-graph (if (sp/execution-graph? graph)
                                             graph
                                             (sp/->execution-graph graph))]
                            (swap! graphs-atom assoc fn-id exec-graph))
                          ;; Add new dependency entries
                          (swap! deps-atom
                                 (fn [deps]
                                   (-> deps
                                       (update :fn-deps
                                               (fn [m]
                                                 (reduce (fn [acc dep-fn-id]
                                                           (update acc dep-fn-id (fnil conj #{}) fn-id))
                                                         m
                                                         (keys (:fn-ids dependencies)))))
                                       (update :fn-schema-deps
                                               (fn [m]
                                                 (reduce (fn [acc dep-id]
                                                           (update acc dep-id (fnil conj #{}) fn-id))
                                                         m
                                                         (keys (:fn-schema-ids dependencies)))))
                                       (update :arg-schema-deps
                                               (fn [m]
                                                 (reduce (fn [acc dep-id]
                                                           (update acc dep-id (fnil conj #{}) fn-id))
                                                         m
                                                         (keys (:arg-schema-ids dependencies))))))))
                          nil)))


  (delete-cache!
    [_ fn-id]
    (log/debug "Deleting cache for fn-id" fn-id)
    (sp/with-write-lock rw-lock
                        (fn []
                          (let [existed? (contains? @graphs-atom fn-id)]
                            ;; Remove from graphs
                            (swap! graphs-atom dissoc fn-id)
                            ;; Remove dependency entries
                            (swap! deps-atom
                                   (fn [deps]
                                     (-> deps
                                         (update :fn-deps
                                                 (fn [m]
                                                   (reduce-kv (fn [acc dep-id cache-ids]
                                                                (let [new-ids (disj cache-ids fn-id)]
                                                                  (if (empty? new-ids)
                                                                    (dissoc acc dep-id)
                                                                    (assoc acc dep-id new-ids))))
                                                              {}
                                                              m)))
                                         (update :fn-schema-deps
                                                 (fn [m]
                                                   (reduce-kv (fn [acc dep-id cache-ids]
                                                                (let [new-ids (disj cache-ids fn-id)]
                                                                  (if (empty? new-ids)
                                                                    (dissoc acc dep-id)
                                                                    (assoc acc dep-id new-ids))))
                                                              {}
                                                              m)))
                                         (update :arg-schema-deps
                                                 (fn [m]
                                                   (reduce-kv (fn [acc dep-id cache-ids]
                                                                (let [new-ids (disj cache-ids fn-id)]
                                                                  (if (empty? new-ids)
                                                                    (dissoc acc dep-id)
                                                                    (assoc acc dep-id new-ids))))
                                                              {}
                                                              m))))))
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
                       #(get-in @deps-atom [:arg-schema-deps dep-arg-schema-id] #{}))))


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
           :arg-schema-deps {}})
    (ReentrantReadWriteLock.)))
