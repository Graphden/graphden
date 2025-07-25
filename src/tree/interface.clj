(ns tree.interface
  (:require
   [clojure.set :as clojure-set]
   [node.interface :as node]))

(defrecord Tree [nodes])

(defprotocol TreeProtocol
  (add-node [this node])
  (add-nodes [this nodes])
  (delete-node [this node-name])
  (node-name->node [this node-name])
  (rename-args-back-ref-node [this args old-name new-name])
  (children->rename-parent-node [this children new-name])
  (rename-args-val [this node-names new-name])
  (disj-child-back-ref [this node])
  (disj-arg-back-ref [this args disj-name]))

(defn thrw [ex-str meta-data]
  (throw (ex-info ex-str
                  {:type ::exception
                   :meta meta-data})))

(defn nodes->refs [nodes]
  (->> nodes
       (map (fn [{:keys [node-name parent-name args]}]
              [node-name
               (into #{}
                     (conj (map :arg-val args)
                           parent-name))]))
       (reduce #(assoc %1 (first %2) (second %2)) {})))

(defn nodes->ref-layers
  [m]
  (loop [m   m
         acc []]
    (if (empty? m)
      acc
      (let [ks    (set (keys m))
            layer (into [] (comp (filter (fn [[_ v]]
                                           (empty? (clojure-set/intersection v ks))))
                                 (map first))
                        m)]
        (when (empty? layer)
          (throw (thrw "Cyclic dependency detected" {:graph m})))
        (recur (apply dissoc m layer)
               (conj acc layer))))))

(extend Tree TreeProtocol
        {:add-node
         (fn [this {[node-name parent-name args] :keys}]
           (if (node-name->node this node-name)
             (throw (Exception. (str "Node " node-name " already exists (on add)")))
             (if-let [{{:keys [base-node-name full-args]} :node-meta}
                      (or (nil? parent-name) (node-name->node this parent-name))]
               (->Tree (reduce (fn [nodes {:keys [arg-name val]}]
                                 (if (and (keyword? val) (node-name->node this val))
                                   (update nodes val node/add-args-back-ref node-name arg-name)
                                   (thrw "Unexisted val-node"
                                         {:val val
                                          :arg-name arg-name
                                          :node-name node-name})))
                               (-> this
                                   :nodes
                                   (update node-name node/set-base-node-name (or base-node-name parent-name))
                                   (update node-name node/set-full-args full-args)
                                   (update parent-name node/add-child-back-ref node-name))
                               args))
               (thrw "Unexisted parent"
                     {:parent-name parent-name
                      :node-name node-name}))))

         :add-nodes
         (fn [this nodes]
           (->> nodes
                nodes->refs
                nodes->ref-layers
                flatten
                (reduce (fn [tree
                             node-name]
                          (try (add-node tree (get nodes node-name))
                               (catch Exception e
                                 (thrw "Exception on add node" {:node-name node-name
                                                                :e e}))))
                        this)))

         :rename-args-back-ref-node
         (fn [this args old-name new-name]
           (reduce (fn [nodes {:keys [arg-name parent-node-name]}]
                     (if (node-name->node this parent-node-name)
                       (update nodes
                               parent-node-name
                               node/rename-arg-back-ref-node
                               old-name
                               new-name)
                       (thrw "Unexisted arg-parent-node"
                             {:arg-name arg-name
                              :old-name old-name})))
                   this
                   args))

         :children->rename-parent-node
         (fn [this children-names new-name]
           (reduce (fn [nodes child-name]
                     (when (node-name->node this child-name)
                       (update nodes
                               child-name
                               node/set-parent-node
                               new-name)))
                   this
                   children-names))

         :rename-args-val
         (fn [this node-names new-name]
           (reduce (fn [nodes [parent-node-name arg-names]]
                     (if (node-name->node this parent-node-name)
                       (thrw "Unexisted arg-backref-node"
                             {:parent-node-name parent-node-name})
                       (reduce #(update %1
                                        node/rename-arg-val
                                        %2
                                        new-name)
                               nodes
                               arg-names)))
                   this
                   node-names))

         :rename-node
         (fn [this old-name new-name]
           (if (node-name->node this new-name)
             (thrw "Node-name already exists"
                   {:node-name new-name})
             (if-let [{:keys [parent-name
                              args]
                       {:keys [children-back-refs
                               args-back-refs]} :node-meta}

                      (node-name->node this old-name)]
               (if (node-name->node this parent-name)
                 (-> this
                     :nodes
                     (update old-name node/rename-node new-name)
                     (clojure-set/rename-keys {old-name new-name})
                     (update parent-name node/rename-child-back-ref old-name new-name)
                     (rename-args-back-ref-node args old-name new-name)
                     (children->rename-parent-node children-back-refs new-name)
                     (rename-args-val args-back-refs new-name)
                     ->Tree)
                 (thrw "Unexisted parent"
                       {:parent-name parent-name
                        :node-name old-name}))
               (thrw "Unexisted node" {:node-name old-name}))))

         :node-name->node
         (fn [this node-name]
           (-> this
               :nodes
               (get node-name)))

         :disj-child-back-ref
         (fn [this {:keys [node-name parent-name]}]
           (if (node-name->node this parent-name)
             (->Tree (update-in this [parent-name :node-meta :children-back-refs] disj node-name))
             this))

         :disj-arg-back-ref
         (fn [this args disj-name]
           (reduce (fn [nodes {:keys [parent-node-name]}]
                     (if (node-name->node this parent-node-name)
                       (update-in nodes
                                  [parent-node-name
                                   :node-meta
                                   :args-back-refs]
                                  disj
                                  disj-name)
                       nodes))
                   this
                   args))

         :delete-node
         (fn [this node-name]
           (if-let [{:keys [args]
                     {:keys [children-back-refs
                             args-back-refs]} :node-meta
                     :as node}

                    (node-name->node this node-name)]
             (cond
               (not-empty children-back-refs)
               (thrw "Can't delete node, it has children"
                     {:node-name node-name
                      :children-back-refs children-back-refs})

               (not-empty args-back-refs)
               (thrw "Can't delete node, some nodes use it as arg"
                     {:node-name node-name
                      :args-back-refs args-back-refs})

               :else (-> this
                         :nodes
                         (dissoc node-name)
                         (disj-child-back-ref node)
                         (disj-arg-back-ref args node-name)
                         ->Tree))
             (thrw "Unexsted node"
                   {:node-name node-name})))})
