(ns graphden.types.core.execution
  "Finite execution-profile constraints, separate from value substitutions.
   A callable requires R ⊆ S: its required profiles must be supported by the
   supplied callable. Variables keep lower and upper bounds until resolved."
  (:require
    [clojure.set :as set]
    [graphden.types.core.shapes :as shapes]))


(defn compatible?
  "Directional support check for actual support and expected requirement.
   Unknown requirements impose no restriction. Unknown support proves no
   concrete profile. Variables are solved by `constrain`, never value unify."
  [actual expected]
  (cond
    (= actual expected) true
    (= expected :unknown) true
    (shapes/execution-var? expected) true
    (set? expected) (or (empty? expected)
                        (and (set? actual) (set/subset? expected actual)))
    :else false))


(defn- bounds
  [state variable]
  (get state variable {:lower #{} :upper shapes/execution-profiles}))


(defn- propagate
  "Close variable edges over the finite profile lattice, or nil on conflict."
  [state edges]
  (loop [state state]
    (when (every? (fn [[_ {:keys [lower upper]}]] (set/subset? lower upper)) state)
      (let [next-state
            (reduce (fn [acc [required supported]]
                      (let [r (bounds acc required) s (bounds acc supported)]
                        (-> acc
                            (assoc required (update r :upper set/intersection (:upper s)))
                            (assoc supported (update s :lower set/union (:lower r))))))
                    state edges)]
        (if (= state next-state) state (recur next-state))))))


(defn constrain
  "Add expected ⊆ actual to a substitution map. Execution bounds and edges
   occupy one namespaced key, never the value-variable symbol keys. Returns
   nil if the finite constraints conflict."
  [subst expected actual]
  (let [{:keys [state edges] :or {state {} edges #{}}} (::constraints subst)
        expected-var? (shapes/execution-var? expected)
        actual-var? (shapes/execution-var? actual)
        updated
        (cond
          (or (= expected actual) (= expected :unknown)) [state edges]
          (and expected-var? actual-var?)
          [(-> state
               (assoc expected (bounds state expected))
               (assoc actual (bounds state actual)))
           (conj edges [expected actual])]
          expected-var?
          [(update (assoc state expected (bounds state expected))
                   expected update :upper set/intersection
                   (if (set? actual) actual #{})) edges]
          (and actual-var? (set? expected))
          [(update (assoc state actual (bounds state actual))
                   actual update :lower set/union expected) edges]
          (compatible? actual expected) [state edges]
          :else nil)]
    (when updated
      (let [[state edges] updated]
        (when-let [closed (propagate state edges)]
          (if (and (empty? closed) (empty? edges))
            subst
            (assoc subst ::constraints {:state closed :edges edges})))))))


(defn resolve-constraint
  "Materialize a constrained variable's greatest supported profile set.
   An unconstrained variable remains symbolic; unknown never becomes portable."
  [subst execution]
  (or (get-in subst [::constraints :state execution :upper]) execution))


(defn value-substitutions
  "The value-sort bindings, excluding the separately stored execution solver."
  [subst]
  (dissoc subst ::constraints))


(defn merge-constraints
  "Merge two independent execution solutions into `left`, or nil on conflict.
   Leaves value bindings on `left` alone; callers choose their value join."
  [left right]
  (let [{:keys [state edges]} (::constraints right)
        merged (reduce-kv
                 (fn [s variable {:keys [lower upper]}]
                   (when s
                     (some-> (constrain s variable upper)
                             (constrain lower variable))))
                 left state)]
    (reduce (fn [s [required supported]]
              (when s (constrain s required supported)))
            merged edges)))
