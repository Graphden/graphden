(ns graphden.input.resolvers
  "Stored-value resolver arguments. Resolver evaluation is distinct from
   expected-type ref dispatch and receives no ambient caller arguments."
  (:require
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.surface :as surface]
    [graphden.types.check.literals :as literals]))


(defn mark-boundaries
  "A resolver uses the existing top-level single-argument invocation law.
   Its internal captures may read its own inputs, never the caller's scope."
  [graph]
  (assoc graph :lexical-boundaries
         (into #{} (comp (filter #(= :resolved-value (:mode %))) (map (comp second :to)))
               (:dependencies graph))))


(defn- arguments
  [field]
  (if (= :reference-inputs (:kind field))
    (mapcat arguments (:fields field))
    (if (:whole-bindable? field)
      [field]
      (mapcat arguments (:fields field)))))


(defn bind-argument
  "Consume zero or one effective parameter with the stored literal. Zero
   ignores the argument, preserving the existing variadic-ignore behavior.
   Derived argument facts have addresses but are not authored binding rows."
  [graph {:keys [from to]}]
  (let [fields (:fields (surface/input-at-scope graph (second to)))
        parameters (vec (mapcat arguments fields))]
    (when (> (count parameters) 1)
      (context/reject! :resolver-arity {:address from :parameter-count (count parameters)}))
    (if-let [{:keys [address]} (first parameters)]
      (let [expression (get-in graph [:scopes (second (first from)) :bindings from :expression])
            [scope path] address
            expected (:type (descriptor/node-at (get-in graph [:scopes (second scope) :configured-contract]) path))]
        (-> graph
            (assoc-in [:resolver-arguments address]
                      {:source-address from :value (:value expression)
                       :expected-type expected :actual-type (literals/classify-literal (:value expression))})
            (update :hidden-input-addresses conj address)))
      graph)))
