(ns graphden.input.obligations
  "Separate supported deferred validation from incomplete analyzer coverage."
  (:require
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]))


(def ^:private runtime-codes #{:runtime-type-obligation :container-type-obligation})


(defn- obligation
  [graph {:keys [address expected-type]}]
  (let [[scope path] address
        node (descriptor/node-at (get-in graph [:scopes (second scope) :configured-contract]) path)
        container? (contains? node :fields)
        kind (if container? :container-type :value-type)
        trigger (if container? :container-materialization :field-read)]
    {:id [address kind trigger] :address address :scope scope :path path
     :kind kind :trigger trigger :expected-type (or expected-type (:type node))}))


(defn separate
  "Checked means the supported analysis is complete. Deferred value validation
   remains an explicit obligation with a field-read or container boundary;
   callers must implement that boundary or refuse compilation/execution."
  [graph]
  (let [runtime? #(contains? runtime-codes (:code %))
        obligations (->> (:diagnostics graph) (filter runtime?) (map #(obligation graph %))
                         (reduce #(assoc %1 (:id %2) %2) {}) vals
                         (sort-by (juxt (comp context/address-sort-key :address) :kind :trigger)) vec)
        diagnostics (filterv (complement runtime?) (:diagnostics graph))
        by-scope (group-by (comp second :scope) obligations)]
    (-> graph
        (assoc :diagnostics diagnostics :obligations obligations
               :status (if (empty? diagnostics) :checked :incomplete))
        (update :coverage assoc :runtime-validation-required (boolean (seq obligations)))
        (update :scopes
                #(into {} (map (fn [[path node]]
                                 (let [diagnostics (filterv (complement runtime?) (:diagnostics node))]
                                   [path (assoc node :obligations (vec (get by-scope path))
                                                :diagnostics diagnostics
                                                :status (if (empty? diagnostics) :checked :incomplete))]))) %)))))
