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
        expected (or expected-type (:type node))
        container? (contains? node :fields)
        kind (if container? :container-type :value-type)
        trigger (if container? :container-materialization :field-read)]
    {:id [address kind trigger] :address address :scope scope :path path
     :kind kind :trigger trigger :expected-type (if (keyword? expected) (types/resolve-alias expected) expected)}))


(defn- map-check-implies?
  [narrow broad]
  (and (types/map-type? narrow) (types/map-type? broad)
       (= (types/map-key narrow) (types/map-key broad))
       (= :any (types/map-val broad))))


(defn- check-implies?
  [narrow broad]
  (or (= narrow broad) (= :any broad)
      (and (not (types/type-any? types/type-var? narrow))
           (not (types/type-any? types/type-var? broad))
           ;; Validate the same keys and every value in the stronger map.
           ;; Record checks do not constrain the keys of extra entries.
           (some #(or (= narrow %) (map-check-implies? narrow %))
                 (or (types/union-members broad) [broad])))))


(defn- retain-obligation
  [by-id {:keys [id expected-type] :as incoming}]
  (if-let [{prior :expected-type} (get by-id id)]
    (cond
      (check-implies? prior expected-type) by-id
      (check-implies? expected-type prior) (assoc by-id id incoming)
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
