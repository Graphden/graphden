(ns graphden.graph.core
  "Graph implementation - coordinates storage, schema, and cache"
  (:require
    [graphden.cache.interface :as cache]
    [graphden.graph.interface :as graph]
    [graphden.schema.interface :as schema]
    [graphden.storage.interface :as storage]
    [integrant.core :as ig]))


(defn- validate-node!
  "Validate node data, throw if invalid"
  [schema-provider node-data]
  (let [result (schema/validate* schema-provider :node node-data)]
    (when-not (:valid? result)
      (throw (ex-info "Invalid node data"
                      {:errors (:errors result)
                       :node-data node-data})))))


(defn- node-exists?
  "Check if node exists in storage"
  [storage node-name]
  (storage/exists?* storage :node node-name))


(defn- validate-parent-exists!
  "Validate that parent node exists"
  [storage parent-name node-name]
  (when (and parent-name (not (node-exists? storage parent-name)))
    (throw (ex-info "Parent node does not exist"
                    {:parent-name parent-name
                     :node-name node-name}))))


(defn- validate-arg-refs!
  "Validate that all arg values that are keywords reference existing nodes"
  [storage node-name args]
  (doseq [{:keys [arg-name arg-val]} args]
    (when (and (keyword? arg-val) (not (node-exists? storage arg-val)))
      (throw (ex-info "Arg references non-existent node"
                      {:node-name node-name
                       :arg-name arg-name
                       :arg-val arg-val})))))


(defn- validate-node-not-exists!
  "Validate that node does not already exist"
  [storage node-name]
  (when (node-exists? storage node-name)
    (throw (ex-info "Node already exists"
                    {:node-name node-name}))))


(defn- validate-no-children!
  "Validate that node has no children before deletion"
  [cache node-name]
  (let [children (cache/get-cached* cache (cache/children-key node-name))]
    (when (seq children)
      (throw (ex-info "Cannot delete node with children"
                      {:node-name node-name
                       :children children})))))


(defn- validate-no-arg-refs!
  "Validate that no other nodes reference this node as arg value"
  [cache node-name]
  (let [refs (cache/get-cached* cache (cache/arg-refs-key node-name))]
    (when (seq refs)
      (throw (ex-info "Cannot delete node that is referenced as arg"
                      {:node-name node-name
                       :arg-refs refs})))))


(defn- compute-root-ancestor
  "Compute root ancestor by walking up the tree"
  [storage node-name]
  (loop [current node-name]
    (when-let [node (storage/get-by-id* storage :node current)]
      (if-let [parent (:parent-name node)]
        (recur parent)
        current))))


(defn- compute-full-args
  "Compute full args by merging from ancestors"
  [storage node-name]
  (let [chain (loop [current node-name
                     acc []]
                (if-let [node (storage/get-by-id* storage :node current)]
                  (recur (:parent-name node) (conj acc node))
                  acc))]
    ;; Merge args from root to leaf (so child overrides parent)
    (reduce (fn [acc node]
              (reduce (fn [m arg]
                        (assoc m (:arg-name arg) arg))
                      acc
                      (:args node)))
            {}
            (reverse chain))))


(defrecord GraphImpl
  [storage schema-provider cache]

  graph/Graph

  (add-node
    [this {:keys [node-name parent-name args] :as node-data}]
    ;; Validations
    (validate-node! schema-provider node-data)
    (validate-node-not-exists! storage node-name)
    (validate-parent-exists! storage parent-name node-name)
    (validate-arg-refs! storage node-name args)

    ;; Store node
    (storage/put* storage :node node-name node-data)

    ;; Update cache
    (cache/on-node-added* cache node-data)

    this)


  (delete-node
    [this node-name]
    (when-not (node-exists? storage node-name)
      (throw (ex-info "Node does not exist" {:node-name node-name})))

    ;; Validations
    (validate-no-children! cache node-name)
    (validate-no-arg-refs! cache node-name)

    ;; Delete from storage
    (storage/delete* storage :node node-name)

    ;; Update cache
    (cache/on-node-deleted* cache node-name)

    this)


  (rename-node
    [this old-name new-name]
    (when-not (node-exists? storage old-name)
      (throw (ex-info "Node does not exist" {:node-name old-name})))
    (validate-node-not-exists! storage new-name)

    ;; Get old node
    (let [old-node (storage/get-by-id* storage :node old-name)
          new-node (assoc old-node :node-name new-name)]
      ;; Update storage
      (storage/delete* storage :node old-name)
      (storage/put* storage :node new-name new-node)

      ;; Update children's parent-name
      (doseq [child-name (cache/get-cached* cache (cache/children-key old-name))]
        (storage/update-entity* storage :node child-name
                                #(assoc % :parent-name new-name)))

      ;; Update nodes that reference this as arg value
      (doseq [[ref-node-name arg-name] (cache/get-cached* cache (cache/arg-refs-key old-name))]
        (storage/update-entity* storage :node ref-node-name
                                (fn [node]
                                  (update node :args
                                          (fn [args]
                                            (mapv (fn [arg]
                                                    (if (= (:arg-name arg) arg-name)
                                                      (assoc arg :arg-val new-name)
                                                      arg))
                                                  args))))))

      ;; Update cache
      (cache/on-node-renamed* cache old-name new-name))

    this)


  (get-node
    [_ node-name]
    (storage/get-by-id* storage :node node-name))


  (get-all-nodes
    [_]
    (storage/get-all* storage :node))


  (set-arg-value
    [this node-name arg-name value]
    (let [node (storage/get-by-id* storage :node node-name)]
      (when-not node
        (throw (ex-info "Node does not exist" {:node-name node-name})))
      (when-not (:parent-name node)
        (throw (ex-info "Cannot change arg in base node" {:node-name node-name})))

      ;; Validate arg-val if it's a reference
      (when (and (keyword? value) (not (node-exists? storage value)))
        (throw (ex-info "Arg references non-existent node"
                        {:node-name node-name
                         :arg-name arg-name
                         :arg-val value})))

      ;; Update storage
      (storage/update-entity* storage :node node-name
                              (fn [n]
                                (update n :args
                                        (fn [args]
                                          (mapv (fn [arg]
                                                  (if (= (:arg-name arg) arg-name)
                                                    (assoc arg :arg-val value)
                                                    arg))
                                                args)))))

      ;; Update cache
      (cache/on-arg-changed* cache node-name arg-name value))

    this)


  (get-root-ancestor
    [_ node-name]
    (cache/compute-if-absent* cache
                              (cache/root-ancestor-key node-name)
                              #(compute-root-ancestor storage node-name)))


  (get-full-args
    [_ node-name]
    (cache/compute-if-absent* cache
                              (cache/full-args-key node-name)
                              #(compute-full-args storage node-name)))


  (get-children
    [_ node-name]
    (or (cache/get-cached* cache (cache/children-key node-name))
        #{}))


  (get-arg-refs
    [_ node-name]
    (or (cache/get-cached* cache (cache/arg-refs-key node-name))
        #{})))


(defn create-graph
  "Create new Graph instance"
  [storage schema-provider cache]
  (->GraphImpl storage schema-provider cache))


;; Integrant integration
(defmethod ig/init-key ::graph
  [_ {:keys [storage schema-provider cache]}]
  (create-graph storage schema-provider cache))
