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


(defn- proven-browser-lambda-shape?
  "Only prove a browser closure when its compiler projection is determined.
   Authored parameters must be valid and at most unary. Without a declaration,
   a nullary slot or a structural parameter present on the target is known;
   unresolved alpha-equivalent/capture projections remain unknown."
  [expected target-info]
  (let [frees (:args target-info)
        signature (types/callable-signature expected)
        structural-args (when signature (types/fn-args signature))]
    (if (contains? target-info :lambda-params)
      (let [params (:lambda-params target-info)]
        (and (vector? params) (<= (count params) 1)
             (every? #(contains? frees %) params)))
      (or (empty? frees)
          (and signature (empty? structural-args))
          (and (= 1 (count structural-args))
               (contains? frees (ffirst structural-args)))))))


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
        (or (nil? target) (nil? target-info)) :unknown
        (not (proven-browser-lambda-shape? expected target-info)) :unknown
        ;; Even at an implemented invocation slot the bounded exporter
        ;; rejects :produces-callable? bindings. Construction support is
        ;; insufficient evidence for that unsupported ABI representation.
        constructor? :unknown
        (contains? invoked arg-name)
        (if callback (types/fn-execution callback) :unknown)
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
