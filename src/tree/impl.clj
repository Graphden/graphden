(ns tree.impl
  (:require
   [clojure.set :as clojure-set]
   [tree.interface :as tree]
   [node.interface :as node]
   [util :refer [thrw]]))

(defrecord Tree
           [nodes])

(defn nodes->refs
  [nodes]
  (->> nodes
       (map (fn [{:keys [node-name parent-name args]}]
              [node-name
               (-> :arg-val
                   (map args)
                   (conj parent-name)
                   set)]))
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

(defn add-node [this {:keys [node-name parent-name args]}]
  (if (tree/node-name->node this node-name)
    (thrw "Node already exists" {:node-name node-name})
    (if-let [{:keys [node-meta]} (when parent-name (tree/node-name->node this parent-name))]
      (let [{:keys [base-node-name full-args]} node-meta]
        (->Tree (reduce (fn [nodes {:keys [arg-name val]}]
                          (if (and (keyword? val) (tree/node-name->node this val))
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
                        args)))
      (thrw "Unexisted parent"
            {:parent-name parent-name
             :node-name node-name}))))

(defn add-nodes [this nodes]
  (->> nodes
       nodes->refs
       nodes->ref-layers
       flatten
       (reduce (fn [tree
                    node-name]
                 (try (tree/add-node tree (get nodes node-name))
                      (catch Exception e
                        (thrw "Exception on add node" {:node-name node-name
                                                       :e e}))))
               this)))

(defn rename-args-back-ref-node [this args old-name new-name]
  (reduce (fn [{:keys [nodes]} {:keys [arg-name parent-node-name]}]
            (if (tree/node-name->node this parent-node-name)
              (->Tree (update nodes
                              parent-node-name
                              node/rename-arg-back-ref-node
                              old-name
                              new-name))
              (thrw "Unexisted arg-parent-node"
                    {:arg-name arg-name
                     :old-name old-name})))
          this
          args))

(defn children->rename-parent-node [this children-names new-name]
  (reduce (fn [{:keys [nodes]} child-name]
            (->Tree (if (tree/node-name->node this child-name)
                      (update nodes child-name node/set-parent-node new-name)
                      nodes)))
          this
          children-names))

(defn rename-args-val [this node-names new-name]
  (reduce (fn [{:keys [nodes]} [parent-node-name arg-names]]
            (if (tree/node-name->node this parent-node-name)
              (->Tree (reduce #(update %1
                                       node/rename-arg-val
                                       %2
                                       new-name)
                              nodes
                              arg-names))
              (thrw "Unexisted arg-backref-node"
                    {:parent-node-name parent-node-name})))
          this
          node-names))

(defn rename-node [this old-name new-name]
  (if (tree/node-name->node this new-name)
    (thrw "Node-name already exists"
          {:node-name new-name})
    (if-let [{:keys [parent-name
                     args]
              {:keys [children-back-refs
                      args-back-refs]} :node-meta}

             (tree/node-name->node this old-name)]
      (if (tree/node-name->node this parent-name)
        (-> this
            (update-in [:nodes old-name] node/rename-node new-name)
            (update :nodes clojure-set/rename-keys {old-name new-name})
            (update-in [:nodes parent-name] node/rename-child-back-ref old-name new-name)
            ->Tree
            (tree/rename-args-back-ref-node args old-name new-name)
            (tree/children->rename-parent-node children-back-refs new-name)
            (tree/rename-args-val args-back-refs new-name))
        (thrw "Unexisted parent"
              {:parent-name parent-name
               :node-name old-name}))
      (thrw "Unexisted node" {:node-name old-name}))))

(defn node-name->node [this node-name]
  (-> this
      :nodes
      (get node-name)))

(defn disj-child-back-ref [this {:keys [node-name parent-name]}]
  (if (tree/node-name->node this parent-name)
    (->Tree (update-in this [parent-name :node-meta :children-back-refs] disj node-name))
    this))

(defn disj-arg-back-ref [this args disj-name]
  (reduce (fn [{:keys [nodes]} {:keys [parent-node-name]}]
            (->Tree (if (tree/node-name->node this parent-node-name)
                      (update-in nodes
                                 [parent-node-name
                                  :node-meta
                                  :args-back-refs]
                                 disj
                                 disj-name)
                      nodes)))
          this
          args))

(defn delete-node [this node-name]
  (if-let [{:keys [args]
            {:keys [children-back-refs
                    args-back-refs]} :node-meta
            :as node}

           (tree/node-name->node this node-name)]
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
                (tree/disj-child-back-ref node)
                (tree/disj-arg-back-ref args node-name)
                ->Tree))
    (thrw "Unexsted node"
          {:node-name node-name})))

(extend Tree tree/Protocol
        {:add-node add-node
         :add-nodes add-nodes
         :rename-args-back-ref-node rename-args-back-ref-node
         :children->rename-parent-node children->rename-parent-node
         :rename-args-val rename-args-val
         :rename-node rename-node
         :node-name->node node-name->node
         :disj-child-back-ref disj-child-back-ref
         :disj-arg-back-ref disj-arg-back-ref
         :delete-node delete-node})
