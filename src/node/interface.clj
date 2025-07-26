(ns node.interface
  (:require
   [arg.interface :as arg]
   [clojure.set :as clojure-set]))

(defn thrw
  [ex-str meta-data]
  (throw (ex-info ex-str
                  {:type ::exception
                   :meta meta-data})))

(defrecord Node
  [node-name parent-name args node-meta])

(defrecord NodeMeta
  [children-back-refs args-back-refs base-node-name full-args])

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

  (rename-node [this new-name]))

(defn init-node-meta
  []
  (->NodeMeta #{} {} nil []))

(defn init-node
  ([node-name args] (init-node node-name nil args))
  ([node-name parent-name args]
   (let [init-arg #(if-let [parent-node-name (:parent-node-name %)]
                     (if (= parent-node-name node-name)
                       (arg/init-arg %)
                       (thrw "Incorrect arg parent-node-name" {:node-name node-name
                                                               :arg %
                                                               :parnet-node-name parent-node-name}))
                     (arg/init-arg-for-node-name node-name %))]
     (->Node node-name
             parent-name
             (if parent-name
               (reduce #(assoc %1 (:arg-name %2) (init-arg %2)) {} args)
               (mapv init-arg args))
             (init-node-meta)))))

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
                       (reduce (fn [parent-full-args [_ {:keys [arg-name]
                                                         :as arg}]]
                                 (if (empty? (filter #(= (:arg-name %) arg-name)
                                                     parent-full-args))
                                   (thrw "Unexisted arg in base for node"
                                         {:arg-name arg-name
                                          :arg arg
                                          :args args
                                          :node-name node-name})
                                   (mapv #(if (= (:arg-name %)
                                                 arg-name)
                                            (if (-> % :arg-val keyword?)
                                              (thrw "Arg already set in parents for node"
                                                    {:arg-name arg-name
                                                     :node-name node-name})
                                              arg)
                                            %) parent-full-args)))
                               parent-full-args
                               args)
                       (if-let [duplicates (->> args
                                                (map :arg-name)
                                                frequencies
                                                (filter (fn [[_ n]] (> n 1)))
                                                (map first)
                                                set
                                                not-empty)]
                         (thrw "Duplilcates in args when add node"
                               {:duplicates duplicates
                                :node-name node-name})
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
             (thrw "Can't change arg in base node"
                   {:node-name (:node-name this)})
             (assoc-in this
                       [:args
                        arg-name]
                       arg-val)))

         :rename-arg-val
         (fn [this arg-name arg-val]
           (let [old-val (-> this :args arg-name :arg-val)]
             (if (and (keyword? arg-val)
                      (keyword? old-val))
               (change-arg-val this arg-name arg-val)
               (thrw "Can't rename not node arg"
                     {:arg-name arg-name
                      :node-name (:node-name this)
                      :new-val arg-val
                      :old-val old-val}))))

         :rename-node
         (fn [this new-name]
           (assoc this :node-name new-name))})
