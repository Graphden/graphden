(ns graphden.types.check.execution
  "Execution support for one native-root stage and its effective bindings.
   Resolved bindings use closer-wins semantics before this module runs; an
   overridden parent's callback cannot constrain the child's stage."
  (:require
    [clojure.set :as set]
    [graphden.executor.capabilities :as capabilities]
    [graphden.types.core :as types]))


(defn- intersect-support
  [supports]
  (cond
    (some #(= #{} %) supports) #{}
    (every? set? supports) (reduce set/intersection types/execution-profiles supports)
    :else :unknown))


(defn- reference-support
  [ref-info target]
  (get (ref-info target) :execution :unknown))


(defn- binding-support
  [root arg-name expected binding ref-info]
  (let [target (or (:ref binding) (:bound-ref binding))
        target-info (when target (ref-info target))
        callable? (or (types/callable-type? expected)
                      (some? (types/callable-signature (:type binding))))
        invoked (capabilities/invoked-slots (:fn-id root))
        constructor? (some? (types/callable-signature (:return target-info)))
        callback (types/callable-signature (:type binding))]
    (cond
      (= expected :fn-ref) types/execution-profiles
      callable?
      (cond
        (nil? invoked) :unknown
        (contains? invoked arg-name)
        (intersect-support [(if callback (types/fn-execution callback) :unknown)
                            (if constructor? (reference-support ref-info target)
                                types/execution-profiles)])
        ;; The browser ABI currently implements callable bindings only at
        ;; its declared invocation slots. Passing a callback does not call
        ;; it, but cannot establish browser support for an unsupported
        ;; closure/capture representation (for example const/value).
        :else :unknown)
      target (reference-support ref-info target)
      (seq (:execution-refs binding))
      (intersect-support (map #(reference-support ref-info %) (:execution-refs binding)))
      :else types/execution-profiles)))


(defn derive
  "Compute a stage from root capability and effective binding facts.
   Unknown slot provenance (such as an unresolved rename) stays unknown.
   `ref-info` is the current registry view, never a name dispatch table."
  [root bindings ref-info]
  (let [args (:args root)
        unknown-keys (remove #(contains? args %) (keys bindings))]
    (if (seq unknown-keys)
      :unknown
      (intersect-support
        (cons (get root :execution :unknown)
              (map (fn [[arg-name expected]]
                     (binding-support root arg-name expected (get bindings arg-name) ref-info))
                   args))))))
