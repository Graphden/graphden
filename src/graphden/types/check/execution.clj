(ns graphden.types.check.execution
  "Execution support for one native-root stage and its effective bindings.
   Resolved bindings use closer-wins semantics before this module runs; an
   overridden parent's callback cannot constrain the child's stage."
  (:refer-clojure :exclude [derive])
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
      (and (= capabilities/server-call-id (:fn-id root)) (= :func arg-name))
      (if (and target target-info (not constructor?)
               (proven-browser-lambda-shape? expected target-info)
               (set? (:execution target-info))
               (contains? (:execution target-info #{}) :jvm-server))
        types/execution-profiles
        :unknown)
      callable?
      (cond
        ;; Even at an implemented invocation slot the bounded exporter
        ;; rejects :produces-callable? bindings. Construction support is
        ;; insufficient evidence for that unsupported ABI representation.
        (or (nil? invoked) (nil? target) (nil? target-info)
            (not (proven-browser-lambda-shape? expected target-info))
            constructor?) :unknown
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


(defn- child-positions
  "Structural positions shared by a return contract and its annotation."
  [t]
  (cond
    (types/fn-type? t)
    (into [[[:return] (types/fn-ret t)]]
          (map-indexed (fn [i [k v]]
                         [[(if (= 1 (count (types/fn-args t))) :arg :args)
                           (if (= 1 (count (types/fn-args t))) i k)] v]))
          (sort-by key (types/fn-args t)))
    (types/list-type? t) [[[:element] (types/list-elem t)]]
    (types/map-type? t) [[[:key] (types/map-key t)] [[:value] (types/map-val t)]]
    (types/record-type? t) (mapv (fn [[k v]] [[:field k] v]) t)
    (types/tuple-type? t) (map-indexed (fn [i v] [[:tuple i] v]) (types/tuple-elems t))
    (types/union-type? t) (map-indexed (fn [i v] [[:union i] v]) (types/union-members t))
    (types/refine-type? t) [[[] (types/refine-base t)]]
    (types/marker-type? t) [[[] (types/marker-inner t)]]
    :else []))


(defn- executions-by-position
  [t]
  (letfn [(walk
            [t path]
            (reduce (fn [m [position child]] (into m (walk child (into path position))))
                    (if (types/fn-type? t) {path (types/fn-execution t)} {})
                    (child-positions t)))]
    (walk t [])))


(defn- execution-view
  "Ignore value assertions; uncorrelated variables get their worst support
   for this variance. Only the same scoped variable at the same structural
   position is correlated between the inferred and declared contracts."
  [t counterpart token actual?]
  (letfn [(view
            [t path positive?]
            (let [children (child-positions t)
                  views (mapv (fn [[position child]]
                                (view child (into path position)
                                      (if (and (types/fn-type? t) (not= position [:return]))
                                        (not positive?) positive?))) children)]
              (cond
                (= t :never) :never
                (not (types/type-any? types/fn-type? t)) token
                (types/fn-type? t)
                (let [execution (types/fn-execution t)
                      execution (if (types/execution-var? execution)
                                  (if (= execution (get counterpart path))
                                    :unknown
                                    (if (= actual? positive?) #{} types/execution-profiles))
                                  execution)
                      args (into {} (map (fn [[k _] v] [k v])
                                         (sort-by key (types/fn-args t)) (rest views)))]
                  (types/make-fn-type args (first views) :any execution))
                (types/list-type? t) [:list (first views)]
                (types/map-type? t) (into [:map] views)
                (types/tuple-type? t) (into [:tuple] views)
                (types/record-type? t)
                (into {} (keep (fn [[[[_ k] v] projected]]
                                 (when (types/type-any? types/fn-type? v) [k projected])))
                      (map vector children views))
                (types/union-type? t) (types/make-union views)
                (or (types/refine-type? t) (types/marker-type? t)) (first views)
                :else token)))]
    (view t [] true)))


(defn claim-compatible?
  "Value narrowing cannot invent concrete support or unbounded execution E.
   Correlated scoped variables retain their relationship; fresh generic claims
   must be safe for every profile assignment, including negative positions."
  [actual declared]
  (if-not declared
    true
    (let [actual (types/resolve-alias actual)
          declared (types/resolve-alias declared)
          informative? (fn [t]
                         (types/type-any? #(and (types/fn-type? %)
                                                (not= :unknown (types/fn-execution %))) t))]
      (or (= actual declared)
          (not (or (informative? actual) (informative? declared)))
          (let [token (keyword "graphden.types.check.execution" (str (gensym "value-")))]
            (types/subtype? (execution-view actual (executions-by-position declared) token true)
                            (execution-view declared (executions-by-position actual) token false)))))))
