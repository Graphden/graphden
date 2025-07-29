(ns fast-refs.impl
  (:require
   [clojure.set :as clojure-set]
   [fast-refs.interface :as fast-refs]
   [util :refer [thrw]]))

(defrecord FastRefs
  [children-back-refs args-back-refs base-node-name full-args])

(defn init
  ([] (->FastRefs #{} {} nil []))
  ([{:keys [children-back-refs args-back-refs base-node-name full-args]}]
   (->FastRefs children-back-refs args-back-refs base-node-name full-args)))

(defn add-args-back-ref
  [this node-name arg-name]
  (init (update-in this
                   [:args-back-refs
                    node-name]
                   (fnil conj #{})
                   arg-name)))

(defn delete-arg-back-ref-node
  [this node-name arg-name]
  (let [updated (update-in this [:args-back-refs node-name] disj arg-name)]
    (init
     (if (empty? (get-in updated [:args-back-refs node-name]))
       (update updated :args-back-refs dissoc node-name)
       updated))))

(defn set-base-node-name
  [this base-node-name]
  (init (assoc this
               :base-node-name
               base-node-name)))

(defn set-full-args
  [this args parent-full-args]
  (init (assoc this
               :full-args
               (if (nil? parent-full-args)
                 (if-let [duplicates (->> args
                                          (map :arg-name)
                                          frequencies
                                          (filter (fn [[_ n]] (> n 1)))
                                          (map first)
                                          set
                                          not-empty)]
                   (thrw "Duplilcates in args when add node"
                         {:duplicates duplicates})
                   args)
                 (reduce (fn [parent-full-args [_ {:keys [arg-name]
                                                   :as arg}]]
                           (if (some #(= (:arg-name %) arg-name)
                                     parent-full-args)
                             (mapv #(if (= (:arg-name %)
                                           arg-name)
                                      (if (-> % :arg-val keyword?)
                                        (thrw "Arg already set in parents for node"
                                              {:arg-name arg-name})
                                        arg)
                                      %) parent-full-args)
                             (thrw "Unexisted arg in base for node"
                                   {:arg-name arg-name
                                    :arg arg
                                    :args args})))
                         parent-full-args
                         args)))))

(defn add-child-back-ref
  [this child-name]
  (init (update this
                :children-back-refs
                (fnil conj #{})
                child-name)))

(defn delete-child-back-ref
  [this child-name]
  (init (update this
                :children-back-refs
                disj
                child-name)))

(defn rename-arg-back-ref-node
  [this old-name new-name]
  (init (update this
                :args-back-refs
                clojure-set/rename-keys
                {old-name new-name})))

(extend FastRefs fast-refs/Protocol
        {:add-args-back-ref add-args-back-ref
         :delete-arg-back-ref-node delete-arg-back-ref-node
         :set-base-node-name set-base-node-name
         :set-full-args set-full-args
         :add-child-back-ref add-child-back-ref
         :delete-child-back-ref delete-child-back-ref
         :rename-arg-back-ref-node rename-arg-back-ref-node})
