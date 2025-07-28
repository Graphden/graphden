(ns node.impl
  (:require
   [arg.interface :as arg]
   [util :refer [thrw]]
   [node.interface :as node]
   [fast-refs.impl :as fast-refs-impl]
   [fast-refs.interface :as fast-refs]
   [arg.impl :as arg-impl]))

(defrecord Node
           [node-name parent-name args node-meta])

(defn init
  ([{:keys [node-name parent-name args node-meta]}] (->Node node-name parent-name args node-meta))
  ([node-name args] (init node-name nil args))
  ([node-name parent-name args]
   (let [init-arg #(if-let [parent-node-name (:parent-node-name %)]
                     (if (= parent-node-name node-name)
                       (arg-impl/init %)
                       (thrw "Incorrect arg parent-node-name" {:node-name node-name
                                                               :arg %
                                                               :parnet-node-name parent-node-name}))
                     (arg-impl/init-for-node-name node-name %))]
     (->Node node-name
             parent-name
             (if parent-name
               (reduce #(assoc %1 (:arg-name %2) (init-arg %2)) {} args)
               (mapv init-arg args))
             (fast-refs-impl/init)))))

(defn add-args-back-ref [this node-name arg-name]
  (init (update this
                :node-meta
                fast-refs/add-args-back-ref
                node-name
                arg-name)))

(defn set-parent-node [this parent-name]
  (init (assoc this :parent-name parent-name)))

(defn set-base-node-name [{:keys [parent-name] :as this} base-node-name]
  (if parent-name
    (init (update this
                  :node-meta
                  fast-refs/set-base-node-name
                  base-node-name))
    this))

(defn set-full-args [{:keys [args] :as this} parent-full-args]
  (init (update this
                :node-meta
                fast-refs/set-full-args
                args
                parent-full-args)))

(defn add-child-back-ref [this child-name]
  (init (update this
                :node-meta
                fast-refs/add-child-back-ref
                child-name)))

(defn delete-child-back-ref [this child-name]
  (init (update this
                :node-meta
                fast-refs/delete-child-back-ref
                child-name)))

(defn rename-child-back-ref [this child-name new-child-name]
  (-> this
      (delete-child-back-ref child-name)
      (add-child-back-ref new-child-name)
      init))

(defn rename-arg-back-ref-node [this old-name new-name]
  (init (update this
                :node-meta
                fast-refs/rename-arg-back-ref-node
                old-name
                new-name)))

(defn change-arg-val [this arg-name arg-val]
  (if (nil? (:parent-name this))
    (thrw "Can't change arg in base node"
          {:node-name (:node-name this)})
    (init (update-in this
                     [:args
                      arg-name]
                     arg/set-val
                     arg-val))))

(defn rename-node [this new-name]
  (init (assoc this :node-name new-name)))

(extend Node node/Protocol
        {:add-args-back-ref add-args-back-ref
         :set-parent-node set-parent-node
         :set-base-node-name set-base-node-name
         :set-full-args set-full-args
         :add-child-back-ref add-child-back-ref
         :delete-child-back-ref delete-child-back-ref
         :rename-child-back-ref rename-child-back-ref
         :rename-arg-back-ref-node rename-arg-back-ref-node
         :change-arg-val change-arg-val
         :rename-node rename-node})
