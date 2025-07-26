(ns arg.interface)

(defrecord Arg
  [arg-name parent-node-name arg-val])

(defn init-arg
  [{:keys [arg-name parent-node-name arg-val]}]
  (->Arg arg-name parent-node-name arg-val))

(defn init-arg-for-node-name
  [node-name arg]
  (-> arg
      (assoc :parent-node-name node-name)
      init-arg))
