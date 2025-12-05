(ns graphden.cache-eager.core
  "Eager cache implementation - maintains derived data on every mutation.
   Similar to fast-refs approach: always up-to-date, O(1) reads."
  (:require
    [graphden.cache.interface :as cache]
    [integrant.core :as ig]))


(defrecord EagerCache
  [data-atom]

  cache/CacheStrategy

  (on-node-added
    [this {:keys [node-name parent-name args]}]
    (swap! data-atom
           (fn [data]
             (let [;; Get parent's cached data
                   parent-root (get-in data [:root-ancestor parent-name])
                   parent-full-args (get-in data [:full-args parent-name])
                   ;; Compute derived data for new node
                   root-ancestor (or parent-root parent-name)
                   ;; Merge parent's full-args with node's args
                   full-args (reduce (fn [acc arg]
                                       (assoc acc (:arg-name arg) arg))
                                     (or parent-full-args {})
                                     args)]
               (cond-> data
                 ;; Set root ancestor (if has parent)
                 parent-name
                 (assoc-in [:root-ancestor node-name] root-ancestor)

                 ;; Set full args
                 true
                 (assoc-in [:full-args node-name] full-args)

                 ;; Add to parent's children set
                 parent-name
                 (update-in [:children parent-name] (fnil conj #{}) node-name)

                 ;; Track arg references (which nodes use this node as arg value)
                 true
                 (as-> d
                   (reduce (fn [acc arg]
                             (let [arg-val (:arg-val arg)]
                               (if (keyword? arg-val)
                                 (update-in acc [:arg-refs arg-val]
                                            (fnil conj #{})
                                            [node-name (:arg-name arg)])
                                 acc)))
                           d
                           args))))))
    this)


  (on-node-deleted
    [this node-name]
    (swap! data-atom
           (fn [data]
             (let [;; Get node's data before deletion
                   children (get-in data [:children node-name])
                   arg-refs (get-in data [:arg-refs node-name])]
               ;; Only allow deletion if no children and no arg refs
               (when (or (seq children) (seq arg-refs))
                 (throw (ex-info "Cannot delete node with dependents"
                                 {:node-name node-name
                                  :children children
                                  :arg-refs arg-refs})))
               (-> data
                   (update :root-ancestor dissoc node-name)
                   (update :full-args dissoc node-name)
                   (update :children dissoc node-name)
                   (update :arg-refs dissoc node-name)))))
    this)


  (on-node-renamed
    [this old-name new-name]
    (swap! data-atom
           (fn [data]
             (-> data
                 ;; Update root-ancestor entries
                 (as-> d
                   (reduce-kv (fn [acc k v]
                                (if (= v old-name)
                                  (assoc-in acc [:root-ancestor k] new-name)
                                  acc))
                              d
                              (:root-ancestor d)))
                 ;; Rename key in root-ancestor
                 (as-> d
                   (if-let [v (get-in d [:root-ancestor old-name])]
                     (-> d
                         (update :root-ancestor dissoc old-name)
                         (assoc-in [:root-ancestor new-name] v))
                     d))
                 ;; Rename key in full-args
                 (as-> d
                   (if-let [v (get-in d [:full-args old-name])]
                     (-> d
                         (update :full-args dissoc old-name)
                         (assoc-in [:full-args new-name] v))
                     d))
                 ;; Rename in children refs
                 (as-> d
                   (if-let [v (get-in d [:children old-name])]
                     (-> d
                         (update :children dissoc old-name)
                         (assoc-in [:children new-name] v))
                     d))
                 ;; Update children sets that contain old-name
                 (as-> d
                   (reduce-kv (fn [acc k v]
                                (if (contains? v old-name)
                                  (update-in acc [:children k]
                                             #(-> % (disj old-name) (conj new-name)))
                                  acc))
                              d
                              (:children d)))
                 ;; Rename in arg-refs
                 (as-> d
                   (if-let [v (get-in d [:arg-refs old-name])]
                     (-> d
                         (update :arg-refs dissoc old-name)
                         (assoc-in [:arg-refs new-name] v))
                     d)))))
    this)


  (on-arg-changed
    [this node-name arg-name new-val]
    (swap! data-atom
           (fn [data]
             (-> data
                 ;; Update full-args
                 (update-in [:full-args node-name arg-name :arg-val]
                            (constantly new-val))
                 ;; Update arg-refs if new-val is a keyword (reference to another node)
                 (as-> d
                   (if (keyword? new-val)
                     (update-in d [:arg-refs new-val]
                                (fnil conj #{})
                                [node-name arg-name])
                     d)))))
    this)


  (on-parent-changed
    [this node-name new-parent-name]
    ;; Recompute root ancestor for this node and all descendants
    (swap! data-atom
           (fn [data]
             (let [new-root (or (get-in data [:root-ancestor new-parent-name])
                                new-parent-name)
                   ;; Recursively update descendants
                   update-descendants
                   (fn update-descendants
                     [d node root]
                     (let [children (get-in d [:children node])]
                       (reduce (fn [acc child]
                                 (-> acc
                                     (assoc-in [:root-ancestor child] root)
                                     (update-descendants child root)))
                               d
                               children)))]
               (-> data
                   (assoc-in [:root-ancestor node-name] new-root)
                   (update-descendants node-name new-root)))))
    this)


  (get-cached
    [_ cache-key]
    (get-in @data-atom cache-key))


  (compute-if-absent
    [_ cache-key _compute-fn]
    ;; Eager cache should always have the value
    ;; If not, something is wrong
    (or (get-in @data-atom cache-key)
        (throw (ex-info "Cache miss in eager cache - this should not happen"
                        {:cache-key cache-key})))))


(defn create-cache
  "Create new EagerCache instance"
  []
  (->EagerCache (atom {:root-ancestor {}
                       :full-args {}
                       :children {}
                       :arg-refs {}})))


;; Convenience functions for common queries
(defn get-root-ancestor
  "Get root ancestor for node"
  [cache node-name]
  (cache/get-cached cache (cache/root-ancestor-key node-name)))


(defn get-full-args
  "Get full args for node (merged from all ancestors)"
  [cache node-name]
  (cache/get-cached cache (cache/full-args-key node-name)))


(defn get-children
  "Get direct children of node"
  [cache node-name]
  (cache/get-cached cache (cache/children-key node-name)))


(defn get-arg-refs
  "Get nodes that reference this node as arg value"
  [cache node-name]
  (cache/get-cached cache (cache/arg-refs-key node-name)))


;; Integrant integration
(defmethod ig/init-key ::cache
  [_ _config]
  (create-cache))
