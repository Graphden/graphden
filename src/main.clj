(ns main
  (:require
   [clojure.set :as clojure-set]))

(defrecord Tree [nodes])

(defprotocol TreeProtocol
  (add-node [this node])
  (delete-node [this node-name])
  (node-name->node [this node-name]))

(defrecord Node [node-name parent-name args node-meta])

(defrecord NodeMeta [children-back-refs args-back-refs base-node-name full-args])

(defrecord Arg [arg-name parent-node-name val])

(defprotocol NodeProtocol
  (set-parent-node [this parent-name])
  (add-args-back-ref [this arg-node-name paren-arg-name])
  (set-base-node-name [this parent-node-name])
  (set-full-args [this parent-full-args])
  (add-child-back-ref [this child-name])
  (delete-child-back-ref [this child-name])
  (rename-child-back-ref [this child-name new-child-name])
  (rename-arg-back-ref-node [this old-name new-name])
  (change-arg-val [this arg-name arg-val])
  (rename-arg-val [this arg-name arg-val])
  (rename [this new-name]))

(defn init-arg [{:keys [arg-name parent-node-name arg-val]}]
  (->Arg arg-name parent-node-name arg-val))

(defn init-node-meta []
  (->NodeMeta #{} {} nil []))

(defn init-node
  ([node-name args] (init-node node-name nil args))
  ([node-name parent-name args]
   (->Node node-name
           parent-name
           (if parent-name
             (reduce #(assoc %1 (:arg-name %2) (init-arg %2)) {} args)
             (mapv init-arg args))
           (init-node-meta))))

(extend Node NodeProtocol
        {:add-args-back-ref
         (fn [this node-name arg-name]
           (update-in this
                      [:node-meta
                       :args-back-refs
                       node-name]
                      (fnil conj #{})
                      arg-name))

         :set-parent-node
         (fn [this parent-name]
           (assoc this :parent-name parent-name))

         :set-base-node-name
         (fn [{:keys [parent-name] :as this} base-node-name]
           (if parent-name
             (assoc-in this
                       [:node-meta
                        :base-node-name]
                       base-node-name)
             this))

         :set-full-args
         (fn [{:keys [node-name args parent-name] :as this} parent-full-args]
           (assoc-in this
                     [:node-meta
                      :full-args]
                     (if parent-name
                       (reduce (fn [args {:keys [arg-name]
                                          :as arg}]
                                 (if (filter #(= (:arg-name %) arg-name)
                                             args)
                                   (mapv #(if (= (:arg-name %)
                                                 arg-name)
                                            (if (-> % :arg-val keyword?)
                                              (throw (Exception. (str "Arg " arg-name
                                                                      " already set in parents for node " node-name)))
                                              arg)
                                            %) args)
                                   (throw (Exception. (str "Unexisted arg " arg-name
                                                           " in base for node " node-name)))))
                               parent-full-args
                               args)
                       (if-let [duplicates (->> args
                                                (map :arg-name)
                                                frequencies
                                                (filter (fn [[_ n]] (> n 1)))
                                                (map first)
                                                set
                                                not-empty)]
                         (throw (Exception. (str "Duplilcates in args: " duplicates
                                                 "when add node " node-name)))
                         args))))

         :add-child-back-ref
         (fn [this child-name]
           (update-in this
                      [:node-meta
                       :children-back-refs]
                      (fnil conj #{})
                      child-name))

         :delete-child-back-ref
         (fn [this child-name]
           (update-in this
                      [:node-meta
                       :children-back-refs]
                      disj
                      child-name))

         :rename-child-back-ref
         (fn [this child-name new-child-name]
           (-> this
               (delete-child-back-ref child-name)
               (add-child-back-ref new-child-name)))

         :rename-arg-back-ref-node
         (fn [this old-name new-name]
           (update-in this
                      [:node-meta
                       :args-back-refs]
                      clojure-set/rename-keys
                      {old-name new-name}))

         :change-arg-val
         (fn [this arg-name arg-val]
           (if (nil? (:parent-name this))
             (throw (Exception. (str "Can't change arg in base node "
                                     (:node-name this))))
             (assoc-in this
                       [:args
                        arg-name]
                       arg-val)))

         :rename-arg-val
         (fn [this arg-name arg-val]
           (if (and (keyword? arg-val)
                    (keyword? (-> this :args arg-name)))
             (change-arg-val this arg-name arg-val)
             (throw (Exception. (str "Can't rename not node arg " arg-name
                                     " in node " (:node-name this)
                                     ": old val - " (-> this :args arg-name)
                                     ", new val - " arg-val)))))

         :rename
         (fn [this new-name]
           (assoc this :node-name new-name))})

(extend Tree TreeProtocol
        {:add-node
         (fn [this {[node-name parent-name args] :keys}]
           (if (node-name->node this node-name)
             (throw (Exception. (str "Node " node-name " already exists (on add)")))
             (if-let [{{:keys [base-node-name full-args]} :node-meta}
                      (or (nil? parent-name) (node-name->node this parent-name))]
               (->Tree (reduce (fn [nodes {:keys [arg-name val]}]
                                 (if (and (keyword? val)(node-name->node this val))
                                   (update nodes val add-args-back-ref node-name arg-name)
                                   (throw (Exception. (str "Unexisted val-node " val
                                                           " for arg " arg-name
                                                           " for node " node-name
                                                           " when add node")))))
                               (-> this
                                   :nodes
                                   (update node-name set-base-node-name (or base-node-name parent-name))
                                   (update node-name set-full-args full-args)
                                   (update parent-name add-child-back-ref node-name))
                               args))
               (throw (Exception. (str "Unexisted parent " parent-name
                                       " for added node " node-name
                                       " when add node"))))))

         :rename-node
         (fn [this old-name new-name]
           (if (node-name->node this new-name)
             (throw (Exception. "Node with new name (" new-name ") already exists"))
             (if-let [{:keys [parent-name
                              args]
                       {:keys [children-back-refs
                               args-back-refs]} :node-meta}

                      (node-name->node this old-name)]
               (if (node-name->node this parent-name)
                 (let [nodes (-> this
                                 :nodes
                                 (update old-name rename new-name)
                                 (clojure-set/rename-keys {old-name new-name})
                                 (update parent-name rename-child-back-ref old-name new-name))
                       nodes (reduce (fn [nodes {:keys [arg-name parent-node-name]}]
                                       (if (node-name->node this parent-node-name)
                                         (update nodes
                                                 parent-node-name
                                                 rename-arg-back-ref-node
                                                 old-name
                                                 new-name)
                                         (throw (Exception. (str "Unexisted arg-parent-node " parent-node-name
                                                                 " for arg " arg-name
                                                                 " for node " old-name
                                                                 " when rename node")))))
                                     nodes
                                     args)
                       nodes (reduce (fn [nodes child-name]
                                       (when (node-name->node this child-name)
                                         (update nodes
                                                 child-name
                                                 set-parent-node
                                                 new-name)))
                                     nodes
                                     children-back-refs)
                       nodes (reduce (fn [nodes [parent-node-name arg-names]]
                                       (if (node-name->node this parent-node-name)
                                         (throw (Exception. (str "Unexisted arg-backref-node " parent-node-name
                                                                 "when rename node " old-name)))
                                         (reduce #(update %1
                                                          rename-arg-val
                                                          %2
                                                          new-name)
                                                 nodes
                                                 arg-names)))
                                     nodes
                                     args-back-refs)]
                   (->Tree nodes))
                 (throw (Exception. "Unexisted parent " parent-name
                                    " for added node " old-name
                                    " when rename node")))
               (throw (Exception. "Unexisted node " old-name " on rename")))))

         :node-name->node
         (fn [this node-name]
           (-> this
               :nodes
               (get node-name)))

         :delete-node
         (fn [this node-name]
           (if-let [{:keys [parent-name
                            args]
                     {:keys [children-back-refs
                             args-back-refs]} :node-meta}

                    (node-name->node this node-name)]
             (cond
               (not-empty children-back-refs)
               (throw (Exception. (str "Can't delete node " node-name
                                       ", it has children " children-back-refs)))

               (not-empty args-back-refs)
               (throw (Exception. (str "Can't delete node " node-name
                                       ", some nodes use it as arg: " args-back-refs)))

               :else (let [nodes (-> this
                                     :nodes
                                     (dissoc node-name))
                           nodes (if (node-name->node this parent-name)
                                   (update-in nodes [parent-name :node-meta :children-back-refs] disj node-name)
                                   nodes)
                           nodes (reduce (fn [nodes {:keys [parent-node-name]}]
                                           (if (node-name->node this parent-node-name)
                                             (update-in nodes
                                                        [parent-node-name
                                                         :node-meta
                                                         :args-back-refs]
                                                        disj
                                                        node-name)
                                             nodes))
                                         nodes
                                         args)]
                       (->Tree nodes)))
             (throw (Exception. "Unexsted node " node-name " on delete"))))})
