(ns graphden.cache.interface
  "Cache strategy protocol - abstraction over different caching approaches:
   - NoCache: always compute
   - TTL: cache with time-to-live
   - Trigger: invalidate on changes
   - Eager: update on every mutation (like fast-refs)")


(defprotocol CacheStrategy
  "Protocol for cache strategies that can optimize derived queries"

  ;; Event hooks - called when data changes
  (on-node-added
    [this node-data]
    "Called after a node is added. Returns updated cache.")

  (on-node-deleted
    [this node-name]
    "Called after a node is deleted. Returns updated cache.")

  (on-node-renamed
    [this old-name new-name]
    "Called after a node is renamed. Returns updated cache.")

  (on-arg-changed
    [this node-name arg-name new-val]
    "Called after an arg value changes. Returns updated cache.")

  (on-parent-changed
    [this node-name new-parent-name]
    "Called after node's parent changes. Returns updated cache.")

  ;; Cache access
  (get-cached
    [this cache-key]
    "Get cached value for key, returns nil if not cached")

  (compute-if-absent
    [this cache-key compute-fn]
    "Get cached value or compute using compute-fn if not present"))


;; Wrapper functions
(defn on-node-added*
  "Notify cache about node addition"
  [cache node-data]
  (on-node-added cache node-data))


(defn on-node-deleted*
  "Notify cache about node deletion"
  [cache node-name]
  (on-node-deleted cache node-name))


(defn on-node-renamed*
  "Notify cache about node rename"
  [cache old-name new-name]
  (on-node-renamed cache old-name new-name))


(defn on-arg-changed*
  "Notify cache about arg change"
  [cache node-name arg-name new-val]
  (on-arg-changed cache node-name arg-name new-val))


(defn on-parent-changed*
  "Notify cache about parent change"
  [cache node-name new-parent-name]
  (on-parent-changed cache node-name new-parent-name))


(defn get-cached*
  "Get cached value"
  [cache cache-key]
  (get-cached cache cache-key))


(defn compute-if-absent*
  "Get or compute value"
  [cache cache-key compute-fn]
  (compute-if-absent cache cache-key compute-fn))


;; Cache key constructors
(defn root-ancestor-key
  [node-name]
  [:root-ancestor node-name])


(defn full-args-key
  [node-name]
  [:full-args node-name])


(defn children-key
  [node-name]
  [:children node-name])


(defn arg-refs-key
  [node-name]
  [:arg-refs node-name])
