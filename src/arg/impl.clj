(ns arg.impl
  (:require
   [arg.interface :as arg]))

(defrecord Arg
           [arg-name parent-node-name arg-val])

(defn init
  [{:keys [arg-name parent-node-name arg-val]}]
  (->Arg arg-name parent-node-name arg-val))

(defn init-for-node-name
  [node-name arg]
  (-> arg
      (assoc :parent-node-name node-name)
      init))

(defn set-val [this arg-val]
  (init (assoc this :arg-val
               arg-val)))

(extend Arg arg/Protocol
        {:set-val set-val})
