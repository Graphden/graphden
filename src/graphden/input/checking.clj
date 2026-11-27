(ns graphden.input.checking
  "Adapters to the existing checker, including one solution across use scopes."
  (:require
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.nullable-operands :as nullable-operands]
    [graphden.input.variables :as variables]
    [graphden.packages.records.ids :as ids]
    [graphden.types.core :as types]
    [graphden.types.core.execution :as execution]))


(defn check-entry
  [check-expression substitution {:keys [address expression expected-type reference guarded-read-type]}]
  (cond
    (= :unresolved (:mode reference))
    {:status :deferred :reason (:reason reference) :substitution substitution}
    (and (= :graph-callable (:mode reference)) (not (:capture-resolved? reference)))
    {:status :deferred :reason :capture-analysis-required :substitution substitution}
    (nil? check-expression)
    {:status :deferred :reason :type-checker-required :substitution substitution}
    (and (#{:text :uuid :int} guarded-read-type) (= expected-type guarded-read-type) (= :value (:mode reference))
         (= #{:null guarded-read-type} (set (types/union-members (:actual-type reference)))))
    (let [proof (check-expression {:address address :expected expected-type :actual guarded-read-type
                                   :expression expression :substitution substitution})]
      (assoc proof :status :deferred :reason :runtime-type-obligation))
    :else
    (check-expression {:address address :expected expected-type
                       :actual (or (:callable-type reference) (:actual-type reference))
                       :expression (case (:mode reference)
                                     :identity {:kind :value :value (:fn-id expression)}
                                     :resolved-value {:kind :ref :fn-id (:resolver-fn-id expression)}
                                     expression)
                       :substitution substitution})))


(defn check-entries
  [entries check-expression substitution]
  (reduce (fn [{:keys [substitution] :as result} {:keys [address] :as entry}]
            (let [proof (check-entry check-expression substitution entry)]
              (when-not (contains? #{:checked :deferred} (:status proof))
                (context/reject! :invalid-checker-result {:address address}))
              (-> result
                  (assoc :substitution (:substitution proof))
                  (assoc-in [:proofs address] proof))))
          {:substitution substitution :proofs {}} entries))


(defn merge-solutions
  "Join independently derived facts through the existing value/effect solvers."
  [left right]
  (let [joined (reduce-kv (fn [solution variable rich]
                            (let [unified (types/unify variable rich solution)]
                              (if (types/fail? unified)
                                (context/reject! :incompatible-input-substitutions {})
                                unified)))
                          left (execution/value-substitutions right))]
    (or (execution/merge-constraints joined right)
        (context/reject! :incompatible-execution-substitutions {}))))


(defn- proof-diagnostics
  [proofs]
  (into [] (keep (fn [[address proof]]
                   (when (= :deferred (:status proof))
                     {:code (:reason proof) :address address}))) proofs))


(defn- retained-diagnostic?
  [old-proofs diagnostic]
  (and (not (contains? #{:scoped-analysis-required :reference-availability-required} (:code diagnostic)))
       (not= (:code diagnostic) (:reason (get old-proofs (:address diagnostic))))))


(defn- resolve-entry
  [resolve-type entry]
  (-> entry
      (update :expected-type resolve-type)
      (update :reference (fn [reference]
                           (cond-> reference
                             (:actual-type reference) (update :actual-type resolve-type)
                             (:callable-type reference) (update :callable-type resolve-type))))))


(defn- resolve-node
  [node solution proofs]
  (let [node-proofs (select-keys proofs (keys (:bindings node)))
        diagnostics (into (filterv (partial retained-diagnostic? (:proofs node)) (:diagnostics node))
                          (proof-diagnostics node-proofs))
        resolve-type (partial types/resolve solution)]
    (-> node
        (update :configured-contract #(variables/map-descriptor-types resolve-type %))
        (update :remaining-contract #(variables/map-descriptor-types resolve-type %))
        (update :bindings #(into {} (map (fn [[address entry]]
                                           [address (resolve-entry resolve-type entry)])) %))
        (cond-> (:effective-contract node)
          (update :effective-contract #(variables/map-descriptor-types resolve-type %)))
        (cond-> (:signature node)
          (update :signature #(variables/resolve-signature solution %)))
        (assoc :substitution solution :proofs node-proofs :diagnostics diagnostics
               :status (if (empty? diagnostics) :checked :incomplete)))))


(def ^:private equality-fields
  (let [owner (ids/fn-id "core.logic" :equal?)]
    #{[(ids/slot-id owner :a)] [(ids/slot-id owner :b)]}))


(defn- equality-union
  [solution pair]
  (when (and (= 2 (count pair)) (= equality-fields (set (map (comp second :address) pair))))
    (let [by-mode (into {} (map (juxt (comp :mode :reference) identity)) pair)
          actual #(some->> (get-in by-mode [% :reference :actual-type]) (types/resolve solution))
          variable (:expected-type (first pair))
          narrow (actual :literal)
          broad (actual :value)]
      (when (and (keyword? narrow) (not= :any narrow)
                 (or (nil? (get solution variable)) (= narrow (get solution variable)))
                 (some #{narrow} (types/union-members broad))
                 (not (types/type-any? #{variable} broad)))
        [variable broad]))))


(defn- equality-solution
  "A literal can bind the shared operand variable locally before the other
   referenced operand has a signature. Retain an existing union if the
   literal's exact primitive type is already an arm of that operand."
  [initial entries]
  (let [operands (filter #(and (types/type-var? (:expected-type %))
                               (contains? equality-fields (second (:address %)))) entries)
        pairs (vals (group-by (juxt (comp first :address) :expected-type) operands))]
    (reduce (fn [solution pair]
              (if-let [entry (equality-union solution pair)]
                (conj solution entry)
                solution))
            initial pairs)))


(defn check-graph
  "Reconcile scoped solutions, then replay directional checks against that
   common solution. This connects constructor inputs/returns and projections
   without introducing another type engine. Literal payloads stay opaque."
  [graph check-expression]
  (let [nodes (sort-by (comp (partial mapv str) key) (:scopes graph))
        initial (reduce merge-solutions {} (map (comp :substitution val) nodes))
        entries (into []
                      (mapcat (fn [[_ node]]
                                (map (fn [[address entry]]
                                       (assoc entry :expected-type
                                              (:type (descriptor/node-at (:configured-contract node)
                                                                         (second address)))))
                                     (sort-by (comp context/address-sort-key key) (:bindings node))))) nodes)
        entries (into entries
                      (map (fn [{:keys [from source actual-type]}]
                             {:address from
                              :expected-type (:type (descriptor/node-at
                                                      (get-in graph [:scopes (second (first from)) :configured-contract])
                                                      (second from)))
                              :expression {:kind :projection :scope (first source) :path (second source)}
                              :reference {:mode :capture :actual-type actual-type}}))
                      (:input-routes graph))
        entries (into entries
                      (map (fn [[address {:keys [value actual-type expected-type]}]]
                             {:address address :expected-type expected-type
                              :expression {:kind :value :value value}
                              :reference {:mode :literal :actual-type actual-type}}))
                      (:resolver-arguments graph))
        initial (nullable-operands/reconcile (equality-solution initial entries) entries)
        first-pass (check-entries entries check-expression initial)
        final-pass (check-entries entries check-expression (:substitution first-pass))
        solution (:substitution final-pass)
        proofs (:proofs final-pass)
        old-proofs (into {} (mapcat (comp :proofs val)) nodes)
        diagnostics (mapv #(cond-> % (:expected-type %) (update :expected-type (partial types/resolve solution)))
                          (into (filterv (partial retained-diagnostic? old-proofs) (:diagnostics graph))
                                (proof-diagnostics proofs)))]
    (assoc graph :substitution solution :proofs proofs :diagnostics diagnostics
           :projections (mapv #(update % :actual-type (partial types/resolve solution)) (:projections graph))
           :input-routes (mapv #(update % :actual-type (partial types/resolve solution)) (:input-routes graph))
           :resolver-arguments (update-vals (:resolver-arguments graph)
                                            #(-> % (update :expected-type (partial types/resolve solution))
                                                 (update :actual-type (partial types/resolve solution))))
           :closures (into {} (map (fn [[address plan]]
                                     [address (update plan :callable-type (partial types/resolve solution))])) (:closures graph))
           :scopes (into {} (map (fn [[path node]] [path (resolve-node node solution proofs)])) nodes)
           :status (if (empty? diagnostics) :checked :incomplete)
           :coverage (assoc (:coverage graph) :recursive-availability
                            (not-any? #(contains? #{:capture-routing-required :reference-mode-required
                                                    :resolver-analysis-required} (:code %)) diagnostics)))))
