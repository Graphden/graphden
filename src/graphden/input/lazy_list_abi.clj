(ns graphden.input.lazy-list-abi
  "Select native lazy element delivery for a checked generated list occurrence."
  (:require
    [graphden.input.list-builder :as builder]
    [graphden.packages.records.ids :as ids]))


(def ^:private cond-id (ids/fn-id "core.logic" :cond))
(def ^:private clauses-id (ids/slot-id cond-id :clauses))


(defn- clauses-consumer?
  [{:keys [base-fn-id argument-paths root-entry]}]
  (and (= cond-id base-fn-id (:fn-id root-entry))
       (= ["core.logic" :cond] ((juxt :namespace :name) root-entry))
       (= {:clauses [clauses-id]} argument-paths)
       (= {:clauses [:list :any]} (:args root-entry))
       (= :any (:return root-entry))
       (= #{} (:effects root-entry))
       (= #{:clauses} (:lazy-seq-args root-entry))))


(defn- results-covered?
  [expressions edge producer tail return-type]
  (let [operands (get-in producer [:generated-list :operands])
        literal-test (fn [name]
                       (let [path (get-in producer [:argument-paths name])]
                         (= {:op :literal :value true}
                            (select-keys (get expressions [(:scope edge) path]) [:op :value]))))]
    ;; The cond rule infers branch returns from its checked prefix. A caller
    ;; can extend an open tail with arbitrary results, unless a test in that
    ;; prefix has already guaranteed a match or the checked result is :any.
    (and (seq operands) (even? (count operands))
         (or (= {:op :literal :value []} (select-keys tail [:op :value]))
             (and (= :supplied (:op tail))
                  (or (= :any return-type) (some literal-test (take-nth 2 operands))))))))


(defn- generated-clauses?
  [producer edge tail]
  (let [{:keys [base-fn-id root-entry argument-paths generated-list]} producer
        {:keys [owner-id operands infer-return?]} generated-list
        origin (when (uuid? owner-id) (builder/origin owner-id clauses-id))
        args (assoc (zipmap operands (repeat :any)) :tail [:list :any])]
    (and origin (vector? operands) (false? infer-return?)
         (= operands (mapv #(keyword (str "item-" %)) (range (count operands))))
         (= (:builder-id origin) (:target-id edge) base-fn-id (:fn-id root-entry))
         (= (:binding-id origin) (:binding-id edge))
         (= args (:args root-entry)) (= [:list :any] (:return root-entry))
         (= argument-paths (into {} (map (fn [name] [name [(ids/slot-id base-fn-id name)]])) (keys args)))
         (= [:list :any] (:type tail)))))


(defn- checked-scope?
  [analysis scope native]
  (let [abi [:base-fn-id :root-entry :argument-paths :generated-list]]
    (= (select-keys native abi)
       (select-keys (get-in analysis [:scopes (second scope) :signature-source]) abi))))


(defn delayed-producer
  "Return the checked producer scope only if delayed elements are private
   to this cond. Its optional or literal tail still carries ordinary values."
  [analysis expressions scopes consumer reads]
  (let [native (get scopes consumer)
        address [consumer [clauses-id]]
        edge (get expressions address)
        child (:scope edge)
        producer (get scopes child)
        return-type (get-in analysis [:scopes (second consumer) :signature :return])
        tail (get expressions [child (get-in producer [:argument-paths :tail])])]
    (when (and (clauses-consumer? native) (checked-scope? analysis consumer native)
               (= :invoke (:op edge)) (= [:list :any] (:type edge))
               (not (contains? reads address)) (not (contains? reads [consumer []]))
               (fn? (:compile-delayed-list producer)) (checked-scope? analysis child producer)
               (generated-clauses? producer edge tail) (results-covered? expressions edge producer tail return-type))
      child)))
