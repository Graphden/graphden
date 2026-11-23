(ns graphden.input.arguments
  "Invocation routing through a checked residual input surface. Values remain
   opaque at leaves; only declared record and reference-input groups are walked."
  (:require
    [graphden.input.ir :as ir]))


(defn route-values
  "Translate named residual inputs to the compiler's exact scoped addresses.
   This is invocation, not binding: no expression grammar or implicit deref.
   Unknown/occupied names fail before execution. Nil and false remain present.
   A callback adapter may route existing field cells without reading them."
  ([surface supplied]
   (route-values surface supplied (fn [record {:keys [name]}] (get record name))))
  ([surface supplied leaf-value]
   (letfn [(route
             [node value path]
             (let [fields (:fields node)
                   accepted (set (map :name fields))]
               (when-not (map? value)
                 (ir/reject! :record-input-required {:path path}))
               (when (some #(not (contains? accepted %)) (keys value))
                 (ir/reject! :unknown-input-field {:path path}))
               (into (if (= :input-field (:kind node)) {(:address node) {}} {})
                     (mapcat (fn [{:keys [name address] :as field}]
                               (when (contains? value name)
                                 (if (contains? field :fields)
                                   (route field (get value name) (conj path name))
                                   {address (leaf-value value field)}))))
                     fields)))]
     (route (:root surface) supplied []))))
