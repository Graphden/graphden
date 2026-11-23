(ns graphden.input.field-default
  "Stored defaults for optional scalar inputs admitted by the pinned package reader."
  (:require
    [graphden.input.context :as context]
    [graphden.packages.records.ids :as ids]))


(def nullable-text [:union :null :text])


(def nullable-text-id (ids/fn-id "core.refinements" :nullable-text))


(defn supported?
  [required type value]
  (and (false? required) (= nullable-text type) (or (nil? value) (string? value))))


(defn read-envelope
  [field type envelope]
  (when-not (and (map? envelope) (= #{:value} (set (keys envelope)))
                 (= nullable-text-id (:type-fn-id field))
                 (supported? (:required field) type (:value envelope)))
    (context/reject! :invalid-field-default {:field-id (:id field)}))
  (:value envelope))


(defn apply-envelope
  "A path envelope belongs to the input type's own top-level membership.
   Overlay its exact child on this resolved occurrence only."
  [field node envelope own-input?]
  (if (and (map? envelope) (contains? envelope :target-path))
    (let [{:keys [target-path value]} envelope
          target (when (and own-input? (map? (:type node)) (vector? target-path) (= 1 (count target-path)))
                   (some #(when (= [(str (:id %))] target-path) %) (:fields node)))]
      (when-not (and (= #{:target-path :value} (set (keys envelope)))
                     target (= nullable-text-id (:type-id target))
                     (not (contains? target :default))
                     (supported? (:required target) (:type target) value))
        (context/reject! :invalid-field-default {:field-id (:id field)}))
      (update node :fields (fn [fields] (mapv #(if (= (:id target) (:id %)) (assoc % :default value) %) fields))))
    (assoc node :default (read-envelope field (:type node) envelope))))
