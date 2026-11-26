(ns graphden.input.obligations
  "Separate supported deferred validation from incomplete analyzer coverage."
  (:require
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.types.core :as types]))


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


(defn- concrete-union-member?
  [member union]
  (and (types/union-type? union)
       (not (types/type-any? types/type-var? union))
       (some #{member} (types/union-members union))))


(defn- retain-obligation
  [by-id {:keys [id expected-type] :as incoming}]
  (if-let [{prior :expected-type} (get by-id id)]
    (cond
      (or (= expected-type prior) (= :any expected-type)
          (concrete-union-member? prior expected-type)) by-id
      (or (= :any prior) (concrete-union-member? expected-type prior)) (assoc by-id id incoming)
      ;; A single read can require multiple checks. Their intersection needs
      ;; an explicit representation before it can be passed to the compiler.
      :else (context/reject! :conflicting-runtime-obligations (select-keys incoming [:address :kind :trigger])))
    (assoc by-id id incoming)))


(defn separate
  "Checked means the supported analysis is complete. Deferred value validation
   remains an explicit obligation with a field-read or container boundary;
   callers must implement that boundary or refuse compilation/execution."
  [graph]
  (let [runtime? #(contains? runtime-codes (:code %))
        obligations (->> (:diagnostics graph) (filter runtime?) (map #(obligation graph %))
                         (reduce retain-obligation {}) vals
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
