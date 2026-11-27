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
      ;; The native jsonb validator accepts every map, including records.
      (and (= broad :jsonb) (or (types/map-type? narrow) (types/record-type? narrow)))
      (when-let [arms (seq (types/union-members narrow))]
        (every? #(check-implies? % broad) arms))
      (and (not (types/type-any? types/type-var? narrow))
           ;; A single closed arm suffices. Other union arms can have private
           ;; variables. Records allow arbitrary extra keys, so they imply
           ;; only a map that accepts every key and value.
           (some (fn [arm]
                   (and (not (types/type-any? types/type-var? arm))
                        (or (= narrow arm) (map-check-implies? narrow arm)
                            (and (types/record-type? narrow) (= [:map :any :any] arm))
                            (and (types/list-type? narrow) (types/list-type? arm) (= :any (types/list-elem arm))))))
                 (or (types/union-members broad) [broad])))))


(defn- keyword-map-check?
  "This dictionary check permits all values and requires keyword keys."
  [expected]
  (contains? #{[:map :keyword :any] [:union :null [:map :keyword :any]]} expected))


(defn- portable-record-field?
  [t]
  (cond
    (keyword? t) (contains? #{:any :jsonb :null :bool :text :keyword :int :numeric} t)
    (types/map-type? t) (and (portable-record-field? (types/map-key t)) (portable-record-field? (types/map-val t)))
    (types/list-type? t) (portable-record-field? (types/list-elem t))
    :else false))


(defn materialized-dictionary?
  [record dictionary]
  (and (types/record-type? record) (every? keyword? (keys record))
       (every? portable-record-field? (vals record)) (keyword-map-check? dictionary)))


(defn- retain-obligation
  [by-id {:keys [id expected-type] :as incoming}]
  (if-let [{prior :expected-type :as existing} (get by-id id)]
    (cond
      (some #(check-implies? % expected-type) (cons prior (:additional-types existing))) by-id
      (check-implies? expected-type prior)
      (assoc by-id id (cond-> incoming
                        (:additional-types existing) (assoc :additional-types (:additional-types existing))))
      ;; The JVM accepts sequential lists, while its jsonb predicate accepts
      ;; vectors. Check both on the original occurrence, without realizing it.
      (and (= :field-read (:trigger incoming))
           (= #{[:list :any] :jsonb} (set [expected-type prior])))
      (assoc by-id id (assoc incoming :expected-type [:list :any] :additional-types [:jsonb]))
      (and (= :container-materialization (:trigger incoming))
           (or (materialized-dictionary? expected-type prior)
               (materialized-dictionary? prior expected-type)))
      (let [record (if (types/record-type? prior) prior expected-type)
            dictionary (if (keyword-map-check? prior) prior expected-type)]
        (assoc by-id id (assoc incoming :expected-type record :additional-types [dictionary])))
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
