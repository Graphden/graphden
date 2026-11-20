(ns graphden.input.source-rich-types
  "Exact storage evidence for closed legacy callable and collection shapes."
  (:require
    [graphden.input.descriptor :as descriptor]
    [graphden.input.format :as format]
    [graphden.input.migration :as migration]
    [graphden.packages.records.types :as records]
    [graphden.types.core :as types]))


(defn- supported-value-type?
  [type]
  (format/collection-type? #(or (types/primitive? %) (types/type-var? %)) type))


(defn callable-field?
  "The verified closure must contain the exact anonymous row for the field's
   stored FK. Use the authored type form because the parser hashes its literal
   shape. Nested callables and unresolved named types need additional evidence."
  [rows-by-id type-id type]
  (when (and (types/fn-type? type)
             (map? (types/fn-args type))
             (every? supported-value-type? (vals (types/fn-args type)))
             (supported-value-type? (types/fn-ret type)))
    (let [[row & more] (records/inline-fn-type-rows-from-form type)
          stored (get rows-by-id type-id)]
      (and (empty? more) (= type-id (:id row)) (seq stored)
           (every? #(migration/same-edn? row %) stored)))))


(defn list-return?
  "Recover a list return that the legacy parser stored as a sequence.
   Its frozen rich implementation entry must still match the authored type."
  [type-id type]
  (and (vector? type) (= :list (first type))
       (descriptor/stored-source-type? type type-id)))
