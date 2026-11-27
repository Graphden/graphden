(ns graphden.input.nullable-operands
  "Joint evidence for the two nullable operands of the native coalesce ABI."
  (:require
    [graphden.packages.records.ids :as ids]
    [graphden.types.core :as types]))


(def ^:private coalesce-id (ids/fn-id "core.logic" :coalesce))
(def ^:private arguments [:value :default])
(def ^:private paths (into {} (map #(vector % [(ids/slot-id coalesce-id %)])) arguments))
(def ^:private fields (set (vals paths)))


(defn- nullable-variable
  [expected]
  (when (and (types/union-type? expected) (= 2 (count (types/union-members expected)))
             (some #{:null} (types/union-members expected)))
    (some #(when (types/type-var? %) %) (types/union-members expected))))


(defn evaluates-reference?
  "With this exact native coalesce ABI, a generic nullable operand always
   evaluates its reference as a value. Its type still uses the stock checker."
  [{:keys [base-fn-id root-entry argument-paths]} path expected]
  (and (fields path) (nullable-variable expected)
       (= coalesce-id base-fn-id (:fn-id root-entry)) (= "core.logic" (:namespace root-entry))
       (= :coalesce (:name root-entry)) (= 'a (:return root-entry)) (= paths argument-paths)
       (= (zipmap arguments (repeat '[:union :null a])) (:args root-entry))))


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
      (cond
        (and (not (contains? solution variable))
             (every? #(= {:mode :value :actual-type :any} (select-keys (:reference %) [:mode :actual-type])) pair)
             (= :any (types/resolve {variable :any} (:expected-type (first pair)))))
        [variable :any]

        (and literal non-null (= literal (get solution variable))
             (not (#{:any :null :never} non-null))
             (not (types/type-any? #(or (= :any %) (types/type-var? %)) non-null))
             (not= literal non-null) (types/subtype? literal non-null))
        [variable non-null]))))


(defn reconcile
  "When a local literal first fixed a shared variable, retain the wider
   non-null result proved on the other operand. Two independently evaluated
   :any returns can instantiate the same open variable. The graph rechecks
   both bindings against the resulting shared solution."
  [initial entries]
  (let [pairs (group-by (juxt (comp first :address) :expected-type)
                        (filter #(and (fields (second (:address %))) (nullable-variable (:expected-type %))) entries))]
    (reduce (fn [solution pair]
              (if-let [binding (operand-solution solution pair)] (conj solution binding) solution))
            initial (vals pairs))))
