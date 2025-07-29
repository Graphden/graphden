(ns tree.impl
  (:require
   [clojure.set :as clojure-set]
   [node.impl :as node-impl]
   [node.interface :as node]
   [tree.interface :as tree]
   [util :refer [thrw]]))

(defrecord Tree
  [nodes])

(defn nodes->refs
  [nodes]
  (->> nodes
       (map (fn [{:keys [node-name parent-name args]}]
              [node-name
               (if parent-name
                 (-> :arg-val
                     (map args)
                     (conj parent-name)
                     set)
                 #{})]))
       (into {})))

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
          (thrw "Cyclic dependency detected" {:graph m}))
        (recur (apply dissoc m layer)
               (conj acc layer))))))

(defn add-node
  [this {:keys [node-name parent-name args] :as node}]
  (if (tree/node-name->node this node-name)
    (thrw "Node already exists" {:node-name node-name})
    (if parent-name
      (if-let [{:keys [fast-refs]} (tree/node-name->node this parent-name)] ; TODO добавление base-node
        (let [{:keys [base-node-name full-args]} fast-refs]
          (->Tree (reduce (fn [nodes {:keys [arg-name arg-val]}]
                            (if (and (keyword? arg-val) (tree/node-name->node this arg-val))
                              (update nodes arg-val node/add-args-back-ref node-name arg-name)
                              (thrw "Unexisted val-node"
                                    {:arg-val arg-val
                                     :arg-name arg-name
                                     :node-name node-name})))
                          (-> this
                              :nodes
                              (assoc node-name (node-impl/init node))
                              (update node-name node/set-base-node-name (or base-node-name parent-name))
                              (update node-name node/set-full-args full-args)
                              (update parent-name node/add-child-back-ref node-name))
                          args)))
        (thrw "Unexisted parent"
              {:parent-name parent-name
               :node-name node-name}))
      (->Tree (-> this
                  :nodes
                  (assoc node-name (node-impl/init (assoc-in node [:fast-refs :full-args] args))))))))

(defn add-nodes
  [this nodes]
  (let [nodes-map (->> nodes
                       (map #(vector (:node-name %) %))
                       (into {}))]
    (->> nodes
         nodes->refs
         nodes->ref-layers
         flatten
         (reduce (fn [tree
                      node-name]
                   (try (add-node tree (get nodes-map node-name))
                        (catch Exception e
                          (thrw "Exception on add node" {:node-name node-name
                                                         :e e}))))
                 this))))

(defn rename-args-back-ref-node
  [this args old-name new-name]
  (reduce (fn [{:keys [nodes]} [_ {:keys [arg-name arg-val]}]]
            (if (tree/node-name->node this arg-val)
              (->Tree (update nodes
                              arg-val
                              node/rename-arg-back-ref-node
                              old-name
                              new-name))
              (thrw "Unexisted arg-val-node"
                    {:arg-name arg-name
                     :arg-val arg-val
                     :new-name new-name
                     :old-name old-name})))
          this
          args))

(defn children->rename-parent-node
  [this children-names new-name]
  (reduce (fn [{:keys [nodes]} child-name]
            (->Tree (if (tree/node-name->node this child-name)
                      (update nodes child-name node/set-parent-node new-name)
                      nodes)))
          this
          children-names))

(defn change-args-val
  [this node-names new-name]
  (reduce (fn [{:keys [nodes]} [parent-node-name arg-names]]
            (if (tree/node-name->node this parent-node-name)
              (->Tree (reduce #(update %1
                                       parent-node-name
                                       node/change-arg-val
                                       %2
                                       new-name)
                              nodes
                              arg-names))
              (thrw "Unexisted arg-backref-node"
                    {:parent-node-name parent-node-name})))
          this
          node-names))

(defn rename-node
  [this old-name new-name]
  (if (tree/node-name->node this new-name)
    (thrw "Node-name already exists"
          {:node-name new-name})
    (if-let [{:keys [parent-name
                     args]
              {:keys [children-back-refs
                      args-back-refs]} :fast-refs}

             (tree/node-name->node this old-name)]
      (if (tree/node-name->node this parent-name)
        (-> this
            (update-in [:nodes old-name] node/rename-node new-name)
            (update :nodes clojure-set/rename-keys {old-name new-name})
            (update-in [:nodes parent-name] node/rename-child-back-ref old-name new-name)
            :nodes
            ->Tree
            (rename-args-back-ref-node args old-name new-name)
            (tree/children->rename-parent-node children-back-refs new-name)
            (tree/change-args-val args-back-refs new-name))
        (thrw "Unexisted parent"
              {:parent-name parent-name
               :node-name old-name}))
      (thrw "Unexisted node" {:node-name old-name}))))

(defn node-name->node
  [this node-name]
  (-> this
      :nodes
      (get node-name)))

(defn disj-child-back-ref
  [this {:keys [node-name parent-name]}]
  (if (tree/node-name->node this parent-name)
    (->Tree (update-in this [:nodes parent-name] node/delete-child-back-ref node-name))
    this))

(defn disj-arg-back-ref
  [this args node-name]
  (reduce (fn [{:keys [nodes]} {:keys [parent-node-name arg-name]}]
            (->Tree (if (tree/node-name->node this parent-node-name)
                      (update nodes
                              parent-node-name
                              node/delete-arg-back-ref-node
                              node-name
                              arg-name)
                      nodes)))
          this
          args))

(defn delete-node
  [this node-name]
  (if-let [{:keys [args]
            {:keys [children-back-refs
                    args-back-refs]} :fast-refs
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
                ->Tree
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
         :change-args-val change-args-val
         :rename-node rename-node
         :node-name->node node-name->node
         :disj-child-back-ref disj-child-back-ref
         :disj-arg-back-ref disj-arg-back-ref
         :delete-node delete-node})
