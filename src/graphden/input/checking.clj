(ns graphden.input.checking
  "Adapters to the existing checker, including one solution across use scopes."
  (:require
    [graphden.input.context :as context]
    [graphden.input.descriptor :as descriptor]
    [graphden.input.variables :as variables]
    [graphden.types.core :as types]
    [graphden.types.core.execution :as execution]))


(defn check-entry
  [check-expression substitution {:keys [address expression expected-type reference]}]
  (cond
    (= :unresolved (:mode reference))
    {:status :deferred :reason (:reason reference) :substitution substitution}
    (= :graph-callable (:mode reference))
    {:status :deferred :reason :capture-analysis-required :substitution substitution}
    (nil? check-expression)
    {:status :deferred :reason :type-checker-required :substitution substitution}
    :else
    (check-expression {:address address :expected expected-type
                       :actual (or (:callable-type reference) (:actual-type reference))
                       :expression expression :substitution substitution})))


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


(defn- join-solutions
  [left right]
  (let [joined (reduce-kv (fn [solution variable rich]
                            (or (types/unify variable rich solution)
                                (context/reject! :incompatible-input-substitutions {})))
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
        (assoc :substitution solution :proofs node-proofs :diagnostics diagnostics
               :status (if (empty? diagnostics) :checked :incomplete)))))


(defn check-graph
  "Reconcile scoped solutions, then replay directional checks against that
   common solution. This connects constructor inputs/returns and projections
   without introducing another type engine. Literal payloads stay opaque."
  [graph check-expression]
  (let [nodes (sort-by (comp (partial mapv str) key) (:scopes graph))
        initial (reduce join-solutions {} (map (comp :substitution val) nodes))
        entries (into []
                      (mapcat (fn [[_ node]]
                                (map (fn [[address entry]]
                                       (assoc entry :expected-type
                                              (:type (descriptor/node-at (:configured-contract node)
                                                                         (second address)))))
                                     (sort-by (comp context/address-sort-key key) (:bindings node))))) nodes)
        first-pass (check-entries entries check-expression initial)
        final-pass (check-entries entries check-expression (:substitution first-pass))
        solution (:substitution final-pass)
        proofs (:proofs final-pass)
        old-proofs (into {} (mapcat (comp :proofs val)) nodes)
        diagnostics (into (filterv (partial retained-diagnostic? old-proofs) (:diagnostics graph))
                          (proof-diagnostics proofs))]
    (assoc graph :substitution solution :proofs proofs :diagnostics diagnostics
           :projections (mapv #(update % :actual-type (partial types/resolve solution)) (:projections graph))
           :scopes (into {} (map (fn [[path node]] [path (resolve-node node solution proofs)])) nodes)
           :status (if (empty? diagnostics) :checked :incomplete)
           :coverage (assoc (:coverage graph) :recursive-availability
                            (not-any? #(contains? #{:capture-routing-required :reference-mode-required
                                                    :resolver-analysis-required} (:code %)) diagnostics)))))
