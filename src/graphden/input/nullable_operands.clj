(ns graphden.input.nullable-operands
  "Joint evidence for the two nullable operands of the native coalesce ABI."
  (:require
    [graphden.packages.records.ids :as ids]
    [graphden.types.core :as types]))


(def ^:private fields
  (let [id (ids/fn-id "core.logic" :coalesce)]
    #{[(ids/slot-id id :value)] [(ids/slot-id id :default)]}))


(defn- nullable-variable
  [expected]
  (when (and (types/union-type? expected) (= 2 (count (types/union-members expected)))
             (some #{:null} (types/union-members expected)))
    (some #(when (types/type-var? %) %) (types/union-members expected))))


(defn- operand-solution
  [solution pair]
  (when (and (= 2 (count pair)) (= fields (set (map (comp second :address) pair))))
    (let [by-mode (into {} (map (juxt (comp :mode :reference) identity)) pair)
          actual #(some->> (get-in by-mode [% :reference :actual-type]) (types/resolve solution))
          variable (nullable-variable (:expected-type (first pair)))
          literal (actual :literal)
          reference (actual :value)
          non-null (if (types/union-type? reference)
                     (types/make-union (remove #{:null} (types/union-members reference)))
                     reference)]
      (when (and literal non-null (= literal (get solution variable))
                 (not (#{:any :null :never} non-null))
                 (not (types/type-any? #(or (= :any %) (types/type-var? %)) non-null))
                 (not= literal non-null) (types/subtype? literal non-null))
        [variable non-null]))))


(defn reconcile
  "When a local literal first fixed a shared variable, retain the wider
   non-null result already proved on its opposite operand. Every binding
   in the graph is subsequently rechecked against the shared solution."
  [initial entries]
  (let [pairs (group-by (juxt (comp first :address) :expected-type)
                        (filter #(and (fields (second (:address %))) (nullable-variable (:expected-type %))) entries))]
    (reduce (fn [solution pair]
              (if-let [binding (operand-solution solution pair)] (conj solution binding) solution))
            initial (vals pairs))))
