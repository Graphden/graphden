(ns graphden.types.reference
  "Type-driven reference dispatch shared by structured analysis and compilation.
   Classifying an edge never executes its target or grants execution support."
  (:require
    [clojure.set :as set]
    [graphden.types.core :as types]))


(defn produces-callable?
  [return-type]
  (some? (types/callable-signature return-type)))


(defn callable-contract
  "The existing checker/picker contract: a producer exposes the returned
   callable signature, retaining markers on its invocation result. Other
   graphs expose their remaining inputs and call-time effects, excluding
   construction-only effects. Missing execution evidence remains unknown."
  [target-info]
  (when target-info
    (let [ret (or (:return target-info) :any)]
      (or (types/callable-signature ret)
          (types/make-fn-type (or (:args target-info) {}) ret
                              (or (:call-time-effects target-info) (:effects target-info))
                              (get target-info :execution :unknown))))))


(defn- dispatch-base
  [expected]
  (cond
    (types/marker-type? expected) (recur (types/marker-inner expected))
    (types/refine-type? expected) (recur (types/refine-base expected))
    :else expected))


(defn expected-callable
  "Invocation shape of a callable input, preserving the existing marker
   projection. A refined callable needs its own policy proof, not unwrapping."
  [expected]
  (or (types/callable-signature expected)
      (when (= :fn expected) :fn)
      (when (types/marker-type? expected)
        (expected-callable (types/marker-inner expected)))))


(defn classify
  "expected and target-info must belong to the same resolved type snapshot.
   Identity edges need no target signature. Callable producers evaluate their
   construction graph; its inputs are not the produced callable's parameters."
  [expected target-info]
  (let [ret (:return target-info)
        base (dispatch-base expected)]
    (cond
      (= :fn-ref base) {:mode :identity :actual-type :fn-ref}
      (types/union-type? base) {:mode :unresolved :reason :ambiguous-reference-mode}
      (and (types/callable-type? base) (nil? (expected-callable expected)))
      {:mode :unresolved :reason :refined-callable-policy}
      (nil? target-info) {:mode :unresolved :reason :missing-target-signature}
      (nil? ret) {:mode :unresolved :reason :missing-target-return}
      (nil? (expected-callable expected)) {:mode :value :actual-type ret}
      (produces-callable? ret)
      {:mode :callable-producer :actual-type ret :callable-type (types/callable-signature ret)}
      :else
      {:mode :graph-callable
       :actual-type (callable-contract target-info)})))


(defn call-site-contract
  "Project a graph callable to the existing checker call-site contract.
   Authored :lambda-params (including []) partitions captures before
   structural inference. Captured argument effects leave the invocation
   contract; unknown effect/execution evidence remains unknown. This type
   projection does not resolve runtime capture sources or parameter routes."
  [expected actual target-info]
  (let [call-site-keys (set (keys (types/fn-args expected)))
        raw-args       (types/fn-args actual)
        args-after-var (if (and (empty? raw-args)
                                (seq call-site-keys))
                         (types/fn-args expected)
                         raw-args)
        declared       (:lambda-params target-info)
        extras         (if (some? declared)
                         (remove (set declared) (keys args-after-var))
                         (when (> (count args-after-var)
                                  (count call-site-keys))
                           (remove call-site-keys (keys args-after-var))))
        stripped       (if (seq extras)
                         (apply dissoc args-after-var extras)
                         args-after-var)
        final-args     (if (and (empty? stripped) (seq call-site-keys))
                         (types/fn-args expected)
                         stripped)
        stripped-keys  (set extras)
        actual-effects (types/fn-effects actual)
        per-arg        (or (:arg-effects target-info)
                           {})
        capture-eff    (reduce into #{}
                               (vals (select-keys per-arg stripped-keys)))
        ;; `:any` in effect position is the canonical "no effect
        ;; constraint declared" marker (parse-fn-type-decl's 4th
        ;; element), not a set — stripping captures from an
        ;; unconstrained contract leaves it unconstrained.
        final-effects  (if (and (seq stripped-keys) (set? actual-effects))
                         (set/difference actual-effects capture-eff)
                         actual-effects)]
    (if (and (= final-args raw-args)
             (= final-effects actual-effects))
      actual
      (types/make-fn-type final-args (types/fn-ret actual) final-effects
                          (types/fn-execution actual)))))


(defn select-call-site-params
  "The existing runtime's deterministic lambda selection, over resolved names.
   Authored declarations are validated by the caller. Collections may be delays
   so zero-argument/explicit cases do not force unrelated graph walks."
  [{:keys [structural-names declared free-names candidate-names own-names ambiguous!]}]
  (cond
    (some? declared) declared
    (nil? structural-names) (vec (force candidate-names))
    (empty? structural-names) []
    (= 1 (count structural-names))
    (let [name (first structural-names)]
      (if (some #{name} (force free-names))
        [name]
        (let [candidates (vec (force candidate-names))]
          (cond
            (empty? candidates) []
            (and (= 1 (count candidates)) (contains? (set (force own-names)) (first candidates))) candidates
            ambiguous! (ambiguous! candidates)
            :else (throw (ex-info "Ambiguous callable parameters; declare lambda-params"
                                  {:type :compile/ambiguous-lambda-params :candidates candidates}))))))
    :else (filterv (set structural-names) (force free-names))))
