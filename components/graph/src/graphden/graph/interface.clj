(ns graphden.graph.interface
  "Pure functional graph API.
   Graph is a plain map, all operations return new graph.")


;; === Graph structure ===
;;
;; {:nodes        {node-name -> node-data}
;;  :children     {parent-name -> #{child-names}}
;;  :root-ancestor {node-name -> root-name}
;;  :full-args    {node-name -> {arg-name -> arg-data}}
;;  :arg-refs     {target-name -> #{[source-name arg-name]}}}


(defn create-graph
  "Create empty graph"
  []
  {:nodes {}
   :children {}
   :root-ancestor {}
   :full-args {}
   :arg-refs {}})


;; === Queries (pure, no side effects) ===

(defn get-node
  "Get node by name, nil if not found"
  [graph node-name]
  (get-in graph [:nodes node-name]))


(defn get-all-nodes
  "Get all nodes as sequence"
  [graph]
  (vals (:nodes graph)))


(defn node-exists?
  "Check if node exists"
  [graph node-name]
  (contains? (:nodes graph) node-name))


(defn get-children
  "Get direct children of node"
  [graph node-name]
  (get-in graph [:children node-name] #{}))


(defn get-root-ancestor
  "Get root ancestor of node (nil for root nodes)"
  [graph node-name]
  (get-in graph [:root-ancestor node-name]))


(defn get-full-args
  "Get full args (merged from ancestors)"
  [graph node-name]
  (get-in graph [:full-args node-name] {}))


(defn get-arg-refs
  "Get nodes that reference this node as arg value"
  [graph node-name]
  (get-in graph [:arg-refs node-name] #{}))


;; === Mutations (return new graph) ===

(defn add-node
  "Add node to graph. Returns new graph.
   Throws if: node exists, parent doesn't exist, arg refs don't exist."
  [graph {:keys [node-name parent-name args] :as node-data}]
  ;; Validations
  (when (node-exists? graph node-name)
    (throw (ex-info "Node already exists" {:node-name node-name})))
  (when (and parent-name (not (node-exists? graph parent-name)))
    (throw (ex-info "Parent node does not exist"
                    {:node-name node-name :parent-name parent-name})))
  (doseq [{:keys [arg-name arg-val]} args]
    (when (and (keyword? arg-val) (not (node-exists? graph arg-val)))
      (throw (ex-info "Arg references non-existent node"
                      {:node-name node-name :arg-name arg-name :arg-val arg-val}))))

  ;; Compute derived data
  (let [parent-root (get-in graph [:root-ancestor parent-name])
        parent-full-args (get-in graph [:full-args parent-name] {})
        root-ancestor (or parent-root parent-name)
        full-args (reduce (fn [acc {:keys [arg-name] :as arg}]
                            (assoc acc arg-name arg))
                          parent-full-args
                          args)
        ;; Find which nodes this node references in args
        arg-ref-targets (keep (fn [{:keys [arg-name arg-val]}]
                                (when (keyword? arg-val)
                                  [arg-val node-name arg-name]))
                              args)]
    (cond-> graph
      ;; Add node
      true
      (assoc-in [:nodes node-name] node-data)

      ;; Set root ancestor (only for child nodes)
      parent-name
      (assoc-in [:root-ancestor node-name] root-ancestor)

      ;; Set full args
      true
      (assoc-in [:full-args node-name] full-args)

      ;; Add to parent's children
      parent-name
      (update-in [:children parent-name] (fnil conj #{}) node-name)

      ;; Track arg refs
      true
      (as-> g
        (reduce (fn [acc [target source arg-name]]
                  (update-in acc [:arg-refs target] (fnil conj #{}) [source arg-name]))
                g
                arg-ref-targets)))))


(defn delete-node
  "Delete node from graph. Returns new graph.
   Throws if: node doesn't exist, has children, is referenced as arg."
  [graph node-name]
  (when-not (node-exists? graph node-name)
    (throw (ex-info "Node does not exist" {:node-name node-name})))

  (let [children (get-children graph node-name)
        arg-refs (get-arg-refs graph node-name)]
    (when (seq children)
      (throw (ex-info "Cannot delete node with children"
                      {:node-name node-name :children children})))
    (when (seq arg-refs)
      (throw (ex-info "Cannot delete node that is referenced as arg"
                      {:node-name node-name :arg-refs arg-refs})))

    (let [node (get-node graph node-name)
          parent-name (:parent-name node)]
      (cond-> graph
        ;; Remove from parent's children
        parent-name
        (update-in [:children parent-name] disj node-name)

        ;; Remove node data
        true
        (update :nodes dissoc node-name)

        ;; Remove derived data
        true
        (update :root-ancestor dissoc node-name)

        true
        (update :full-args dissoc node-name)

        true
        (update :children dissoc node-name)

        true
        (update :arg-refs dissoc node-name)))))


(defn rename-node
  "Rename node. Returns new graph.
   Updates all references: children's parent-name, arg-refs."
  [graph old-name new-name]
  (when-not (node-exists? graph old-name)
    (throw (ex-info "Node does not exist" {:node-name old-name})))
  (when (node-exists? graph new-name)
    (throw (ex-info "Node already exists" {:node-name new-name})))

  (let [old-node (get-node graph old-name)
        new-node (assoc old-node :node-name new-name)
        children (get-children graph old-name)
        arg-refs (get-arg-refs graph old-name)]

    (-> graph
        ;; Update node
        (update :nodes dissoc old-name)
        (assoc-in [:nodes new-name] new-node)

        ;; Update children's parent-name
        (as-> g
          (reduce (fn [acc child-name]
                    (update-in acc [:nodes child-name] assoc :parent-name new-name))
                  g
                  children))

        ;; Update children set key
        (as-> g
          (if (seq children)
            (-> g
                (update :children dissoc old-name)
                (assoc-in [:children new-name] children))
            g))

        ;; Update parent's children set
        (as-> g
          (if-let [parent (:parent-name old-node)]
            (-> g
                (update-in [:children parent] disj old-name)
                (update-in [:children parent] conj new-name))
            g))

        ;; Update root-ancestor entries pointing to old-name
        (as-> g
          (reduce-kv (fn [acc k v]
                       (if (= v old-name)
                         (assoc-in acc [:root-ancestor k] new-name)
                         acc))
                     g
                     (:root-ancestor g)))

        ;; Rename key in root-ancestor
        (as-> g
          (if (contains? (:root-ancestor g) old-name)
            (let [v (get-in g [:root-ancestor old-name])]
              (-> g
                  (update :root-ancestor dissoc old-name)
                  (assoc-in [:root-ancestor new-name] v)))
            g))

        ;; Rename key in full-args
        (as-> g
          (if (contains? (:full-args g) old-name)
            (let [v (get-in g [:full-args old-name])]
              (-> g
                  (update :full-args dissoc old-name)
                  (assoc-in [:full-args new-name] v)))
            g))

        ;; Update arg-refs key
        (as-> g
          (if (seq arg-refs)
            (-> g
                (update :arg-refs dissoc old-name)
                (assoc-in [:arg-refs new-name] arg-refs))
            g))

        ;; Update nodes that reference this as arg value
        (as-> g
          (reduce (fn [acc [ref-node-name arg-name]]
                    (update-in acc [:nodes ref-node-name :args]
                               (fn [args]
                                 (mapv (fn [arg]
                                         (if (= (:arg-name arg) arg-name)
                                           (assoc arg :arg-val new-name)
                                           arg))
                                       args))))
                  g
                  arg-refs)))))


(defn set-arg-value
  "Set arg value in node. Returns new graph.
   Only allowed for child nodes (with parent)."
  [graph node-name arg-name value]
  (let [node (get-node graph node-name)]
    (when-not node
      (throw (ex-info "Node does not exist" {:node-name node-name})))
    (when-not (:parent-name node)
      (throw (ex-info "Cannot change arg in base node" {:node-name node-name})))
    (when (and (keyword? value) (not (node-exists? graph value)))
      (throw (ex-info "Arg references non-existent node"
                      {:node-name node-name :arg-name arg-name :arg-val value})))

    (-> graph
        ;; Update node args
        (update-in [:nodes node-name :args]
                   (fn [args]
                     (mapv (fn [arg]
                             (if (= (:arg-name arg) arg-name)
                               (assoc arg :arg-val value)
                               arg))
                           args)))

        ;; Update full-args cache
        (assoc-in [:full-args node-name arg-name :arg-val] value)

        ;; Update arg-refs if new value is a keyword
        (as-> g
          (if (keyword? value)
            (update-in g [:arg-refs value] (fnil conj #{}) [node-name arg-name])
            g)))))
