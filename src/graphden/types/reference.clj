(ns graphden.types.reference
  "Type-driven reference dispatch shared by structured analysis and compilation.
   Classifying an edge never executes its target or grants execution support."
  (:require
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


(defn classify
  "expected and target-info must belong to the same resolved type snapshot.
   Identity edges need no target signature. Callable producers evaluate their
   construction graph; its inputs are not the produced callable's parameters."
  [expected target-info]
  (let [ret (:return target-info)]
    (cond
      (= :fn-ref expected) {:mode :identity :actual-type :fn-ref}
      (nil? target-info) {:mode :unresolved :reason :missing-target-signature}
      (not (types/callable-type? expected)) {:mode :value :actual-type ret}
      (produces-callable? ret)
      {:mode :callable-producer :actual-type ret :callable-type (types/callable-signature ret)}
      :else
      {:mode :graph-callable
       :actual-type (callable-contract target-info)})))
