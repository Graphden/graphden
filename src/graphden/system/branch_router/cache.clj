(ns graphden.system.branch-router.cache
  "Dropping entries from a BranchRouter's caches — the per-branch ctx map
   (`:handlers`), the `ref → id` map (`:ref-cache`) and the per-branch
   build holders (`:build-monitors`) — plus the pinned-branches seam that
   says which entries a heal / idle sweep / LRU cap must leave alone.

   Below both the router (`graphden.system.branch-router`) and the epoch
   heal (`graphden.system.branch-router.epoch`), which evict through
   `invalidate!` without depending on each other."
  (:require
    [clojure.tools.logging :as log]))


(defonce ^:private pinned-branches-fn
  ;; Seam: `(fn [] #{branch-id …})` — the branches whose cached ctx must
  ;; NOT be dropped by a heal, the idle sweep or the LRU cap. Registered by the
  ;; service reconciler (`init/services`): a running per-branch service
  ;; holds its ctx by reference, so dropping the router's entry left the
  ;; service on a registry nobody refreshes while every request built a
  ;; second, divergent ctx for the same branch. nil = no pins.
  (atom nil))


(defn set-pinned-branches-fn!
  "Install (or clear, with nil) the pinned-branches seam — see
   `pinned-branches-fn`."
  [f]
  (reset! pinned-branches-fn f))


(defn pinned-branches
  "The set of branch ids whose ctx a heal refreshes in place and the idle
   sweep + LRU cap leave alone; empty when no seam is registered or it
   throws."
  []
  (or (when-let [f @pinned-branches-fn]
        (try (set (f))
             (catch Exception e
               (log/warn e "pinned-branches seam failed; treating as none")
               nil)))
      #{}))


(defn- forget-ref-cache-for-branch!
  "Drop every `ref → id` entry that points at `branch-id`. Called from
   `invalidate!` so a delete-branch! followed by a re-create with the
   same name doesn't surface a stale id. Keys are `[scope ref]` /
   `[scope :id id]` (`branch-router/ref-cache-key`); matching is by value
   (branch-id) so it sweeps every org's entry for the branch."
  [router branch-id]
  (when-let [ref-cache (:ref-cache router)]
    (swap! ref-cache
           (fn [m]
             (reduce-kv (fn [acc k v]
                          (if (= v branch-id) acc (assoc acc k v)))
                        {}
                        m)))))


(defn invalidate!
  "Drop the cached entry for one branch + every ref → id mapping that
   points at it. Called after a write — the next request rebuilds.
   Mainly used after `delete-branch!` so the ctx doesn't outlive its
   branch row."
  [{:keys [handlers build-monitors] :as router} branch-id]
  ;; Bump the branch's build generation BEFORE dropping anything (L1): a
  ;; cold build for this branch may be mid-flight — holding the lock, its
  ;; result not yet installed. It captured the generation before its
  ;; multi-second compile and re-checks it at install (`install-built-
  ;; entry!`) via its captured holder reference, so the bump makes it
  ;; DISCARD a now-stale result instead of resurrecting a ctx for a
  ;; just-deleted branch. invalidate! deliberately does NOT take the
  ;; per-branch lock (held across the rebuild; a delete must not block on
  ;; it). Residual: the vanishingly-narrow window where invalidate!'s
  ;; holder read runs before the builder's `computeIfAbsent` creates the
  ;; holder — unreachable on the request path, since a build only starts
  ;; after `resolve-branch-id` saw the (not-yet-deleted) branch row.
  (when build-monitors
    (when-let [holder (java.util.concurrent.ConcurrentHashMap/.get build-monitors branch-id)]
      (java.util.concurrent.atomic.AtomicLong/.incrementAndGet ^java.util.concurrent.atomic.AtomicLong (:gen holder))))
  (swap! handlers dissoc branch-id)
  (forget-ref-cache-for-branch! router branch-id)
  (when build-monitors
    (java.util.concurrent.ConcurrentHashMap/.remove build-monitors branch-id)))


(defn invalidate-all!
  "Drop every cached per-branch entry + the entire ref-cache. Used by
   schema-migration paths that change the executor's shape under all
   branches."
  [{:keys [handlers ref-cache build-monitors]}]
  (reset! handlers {})
  (when ref-cache (reset! ref-cache {}))
  (when build-monitors
    (java.util.concurrent.ConcurrentHashMap/.clear build-monitors)))
