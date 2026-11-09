(ns graphden.input.invocation
  "Pure call-site routing for checked closure plans. No captured value reads."
  (:require
    [graphden.input.ir :as ir]))


(defn call-values
  "Route the existing 0/1/multi callable ABI's name map to invocation leaves.
   Nested extraction needs lazy whole-record ingress and is rejected by IR.
   Explicit nil/false is present; missing optional parameters remain absent."
  [{:keys [parameters call-sites]} lambda-args]
  (let [lambda-args (or lambda-args {})
        accepted (set parameters)]
    (when-not (map? lambda-args) (ir/reject! :call-arguments-map-required {}))
    (when (some #(not (contains? accepted %)) (keys lambda-args))
      (ir/reject! :unknown-call-argument {}))
    (into {} (keep (fn [{:keys [parameter target]}]
                     (when (contains? lambda-args parameter) [target (get lambda-args parameter)])))
          call-sites)))


(defn supplied-values
  "A new invocation shadows only its declared call-site addresses. Captured
   input values remain in the enclosing snapshot; cells are retained separately."
  [parent-supplied plan lambda-args]
  (merge (apply dissoc parent-supplied (map :target (:call-sites plan)))
         (call-values plan lambda-args)))
