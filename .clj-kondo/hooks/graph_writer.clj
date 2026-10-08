(ns hooks.graph-writer
  (:require [clj-kondo.hooks-api :as api]))

(defn with-write
  [{:keys [node]}]
  (let [[_ args & body] (:children node)
        [storage entity] (:children args)]
    {:node (api/list-node
             (list (api/token-node 'graphden.storage.graph-writer/call-with-write)
                   storage entity
                   (api/list-node
                     (list* (api/token-node 'fn)
                            (api/vector-node [storage]) body))))}))
