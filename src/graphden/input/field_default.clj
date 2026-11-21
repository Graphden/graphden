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
