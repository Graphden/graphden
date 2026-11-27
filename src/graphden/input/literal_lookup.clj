(ns graphden.input.literal-lookup
  "Prove one checked field read when its previously free map becomes a
   reference to an immutable literal in the same scope."
  (:require
    [graphden.packages.records.ids :as ids]
    [graphden.types.check.literals :as literals]
    [graphden.types.core :as types]))


(def ^:private lookup-id (ids/fn-id "core.collections" :get))
(def ^:private const-id (ids/fn-id "core.logic" :const))


(defn- value-use
  [graph [[root path] _ :as address] base-id]
  (let [binding (get-in graph [:scopes path :bindings address])
        use (conj path (:binding-id binding))
        target (get-in graph [:scopes use])
        expected {:from address :to [root use] :binding-id (:binding-id binding)
                  :target-fn-id (:fn-id target) :mode :value}]
    (when (and (= :ref (get-in binding [:expression :kind]))
               (= :value (get-in binding [:reference :mode]))
               (= (:fn-id target) (get-in binding [:expression :fn-id]))
               (= base-id (get-in target [:signature-source :base-fn-id]))
               (some #{expected} (:dependencies graph)))
      [[root use] target])))


(defn- operand-address
  [[scope node] field]
  (let [abi (:signature-source node)
        path (get-in abi [:argument-paths field])]
    (when (and scope (= [(ids/slot-id (:base-fn-id abi) field)] path))
      [scope path])))


(defn- literal-expression
  [graph [[_ path] _ :as address]]
  (let [expr (get-in graph [:scopes path :bindings address :expression])]
    (when (and (= :value (:kind expr)) (= #{:kind :value} (set (keys expr))))
      expr)))


(defn- constant-map
  [graph address]
  (let [constant (value-use graph address const-id)
        value (:value (literal-expression graph (operand-address constant :value)))]
    (when (map? value) value)))


(defn keyword-map-read-type
  "Ground the key variable of core get's map operand only for an exact
   keyword-keyed constant. The runtime validator still checks every key."
  [graph [[_ path :as scope] _ :as address] expected actual]
  (let [node (get-in graph [:scopes path])]
    (when (and (= lookup-id (get-in node [:signature-source :base-fn-id]))
               (= address (operand-address [scope node] :coll))
               (types/record-type? actual))
      (let [value (constant-map graph address)]
        (when (and (seq value) (every? keyword? (keys value)) (= actual (literals/classify-literal value))
                   (some (fn [arm]
                           (when (types/map-type? arm)
                             (let [[_ key-type val-type] arm
                                   solved (types/resolve (:substitution graph) key-type)]
                               (and (types/type-var? key-type) (or (= key-type solved) (= :keyword solved))
                                    (= :any val-type)))))
                         (types/union-members expected)))
          [:map :keyword :any])))))


(defn proves-read?
  [before after certificates {:keys [kind trigger address expected-type]}]
  (when (and (= [:value-type :field-read] [kind trigger])
             (contains? #{:null :bool :int :float :text :keyword :uuid} expected-type)
             (= [:deferred :runtime-type-obligation]
                ((juxt :status :reason) (get-in before [:proofs address])))
             (= :checked (get-in after [:proofs address :status]))
             (= (get-in before [:scopes (second (first address)) :bindings address :expression])
                (get-in after [:scopes (second (first address)) :bindings address :expression])))
    (let [lookup (value-use after address lookup-id)
          map-address (operand-address lookup :coll)
          key-address (operand-address lookup :key)
          certificate (some #(when (= map-address (:address %)) %) certificates)
          key-expr (literal-expression after key-address)
          m (constant-map after map-address)
          k (:value key-expr)]
      (and certificate key-expr (map? m) (contains? m k)
           (= (get-in certificate [:row :id]) (get-in after [:scopes (second (first map-address)) :bindings map-address :binding-id]))
           (= expected-type (get-in (second lookup) [:signature :return]) (literals/classify-literal (get m k)))))))
