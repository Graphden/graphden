(ns graphden.input.arguments
  "Invocation routing through a checked residual input surface. Values remain
   opaque at leaves; only declared record and reference-input groups are walked."
  (:require
    [graphden.input.ir :as ir]))


(defn route-values
  "Translate named residual inputs to the compiler's exact scoped addresses.
   This is invocation, not binding: no expression grammar or implicit deref.
   Unknown/occupied names fail before execution. Nil and false remain present."
  [surface supplied]
  (letfn [(route
            [node value path]
            (if (contains? node :fields)
              (let [fields (:fields node)
                    accepted (set (map :name fields))]
                (when-not (map? value)
                  (ir/reject! :record-input-required {:path path}))
                (when (some #(not (contains? accepted %)) (keys value))
                  (ir/reject! :unknown-input-field {:path path}))
                (into {} (mapcat (fn [field]
                                   (when (contains? value (:name field))
                                     (route field (get value (:name field))
                                            (conj path (:name field)))))) fields))
              {(:address node) value}))]
    (route (:root surface) supplied [])))
