(ns graphden.input.references
  "Conservative retention for UUIDs stored inside offline JSONB paths."
  (:require
    [graphden.storage.protocol.core :as sp]))


(defn retained-ids
  [storage entity candidates]
  (let [paths (case entity
                :slot [:target-path :projection-path]
                :input-binding [:scope-path :projection-scope-path]
                nil)]
    (if (and paths (seq candidates))
      (let [ids-by-string (into {} (map (juxt str identity)) candidates)
            entities (sp/current-entities storage)]
        (into #{}
              (comp (mapcat (fn [row] (mapcat #(get row %) paths)))
                    (keep #(get ids-by-string (str %))))
              (mapcat #(when (contains? entities %)
                         (sp/query-entities storage % {}))
                      [:input-binding :input-binding-version])))
      #{})))
