(ns graphden.base-functions.core
  "Base function definitions.

   This component defines the standard library of base functions:
   - Arithmetic: add, sub, mul, div, mod, neg, abs
   - Comparison: eq, neq, lt, lte, gt, gte
   - Logic: and, or, not
   - Conditionals: if, cond
   - Strings: str, subs, str-len, str-upper, str-lower, str-trim, str-split, str-join
   - Collections: first, rest, cons, conj, get, assoc, dissoc, count, empty?, etc.
   - HOF: map, filter, reduce, some, every?, find-first, group-by, sort-by, apply

   Registration and storage sync should be done by consuming components
   using fn-registry."
  (:require
    [clojure.string :as str]
    [graphden.executor.interface :as exec]))


;; === Arithmetic ===
;; Note: add, sub, mul, div accept lists like Clojure's +, -, *, /

(def arithmetic-defs
  {:add {:args {:nums :jsonb}
         :return-type :numeric
         :impl (fn [{:keys [nums]} _ctx]
                 (apply + nums))}

   :sub {:args {:nums :jsonb}
         :return-type :numeric
         :impl (fn [{:keys [nums]} _ctx]
                 (apply - nums))}

   :mul {:args {:nums :jsonb}
         :return-type :numeric
         :impl (fn [{:keys [nums]} _ctx]
                 (apply * nums))}

   :div {:args {:nums :jsonb}
         :return-type :numeric
         :impl (fn [{:keys [nums]} _ctx]
                 (when-let [zero-divisor (some #(when (zero? %) %) (rest nums))]
                   (throw (ex-info "Division by zero"
                                   {:type :execution-error/division-by-zero
                                    :nums nums
                                    :zero-at zero-divisor})))
                 (apply / nums))}

   :mod {:args {:a :numeric, :b :numeric}
         :return-type :numeric
         :impl (fn [{:keys [a b]} _ctx]
                 (when (zero? b)
                   (throw (ex-info "Modulo by zero"
                                   {:type :execution-error/modulo-by-zero
                                    :a a :b b})))
                 (mod a b))}

   :neg {:args {:n :numeric}
         :return-type :numeric
         :impl (fn [{:keys [n]} _ctx] (- n))}

   :abs {:args {:n :numeric}
         :return-type :numeric
         :impl (fn [{:keys [n]} _ctx] (abs n))}})


;; === Comparison ===
;; Note: comparison functions accept lists like Clojure's =, <, >, etc.
;; (eq [1 1 1]) => true, (lt [1 2 3]) => true (ascending)

(def comparison-defs
  {:eq  {:args {:values :jsonb}
         :return-type :bool
         :impl (fn [{:keys [values]} _ctx]
                 (apply = values))}

   :neq {:args {:values :jsonb}
         :return-type :bool
         :impl (fn [{:keys [values]} _ctx]
                 (apply not= values))}

   :lt  {:args {:nums :jsonb}
         :return-type :bool
         :impl (fn [{:keys [nums]} _ctx]
                 (apply < nums))}

   :lte {:args {:nums :jsonb}
         :return-type :bool
         :impl (fn [{:keys [nums]} _ctx]
                 (apply <= nums))}

   :gt  {:args {:nums :jsonb}
         :return-type :bool
         :impl (fn [{:keys [nums]} _ctx]
                 (apply > nums))}

   :gte {:args {:nums :jsonb}
         :return-type :bool
         :impl (fn [{:keys [nums]} _ctx]
                 (apply >= nums))}})


;; === Logic ===
;; Note: and/or need lazy evaluation for short-circuit behavior

(def logic-defs
  {:and {:args {:a :bool, :b :bool}
         :lazy-args #{:b}  ; b is lazy for short-circuit
         :return-type :bool
         :impl (fn [{:keys [a b]} ctx]
                 (and a (exec/force-value b ctx)))}

   :or  {:args {:a :bool, :b :bool}
         :lazy-args #{:b}  ; b is lazy for short-circuit
         :return-type :bool
         :impl (fn [{:keys [a b]} ctx]
                 (or a (exec/force-value b ctx)))}

   :not {:args {:x :bool}
         :return-type :bool
         :impl (fn [{:keys [x]} _ctx] (not x))}})


;; === Conditionals ===
;; Note: if needs lazy evaluation - only one branch is evaluated

(def conditional-defs
  {:if   {:args {:condition :bool, :then :any, :else :any}
          :lazy-args #{:then :else}
          :return-type :any
          :impl (fn [{:keys [condition then else]} ctx]
                  (if condition
                    (exec/force-value then ctx)
                    (exec/force-value else ctx)))}

   :cond {:args {:pairs :jsonb, :default :any}
          :lazy-args #{:default}
          :return-type :any
          :impl (fn [{:keys [pairs default]} ctx]
                  ;; pairs is a vector of {:pred bool :result value}
                  (loop [ps pairs]
                    (if (empty? ps)
                      (when default (exec/force-value default ctx))
                      (let [{:keys [pred result]} (first ps)]
                        (if pred result (recur (rest ps)))))))}})


;; === Strings ===

(def string-defs
  {:str       {:args {:args :jsonb}
               :return-type :text
               :impl (fn [{:keys [args]} _ctx] (str/join args))}

   :subs      {:args {:s :text, :start :int, :end {:type :int :required false}}
               :return-type :text
               :impl (fn [{:keys [s start end]} _ctx]
                       (let [len (count s)]
                         (when (neg? start)
                           (throw (ex-info "start index cannot be negative"
                                           {:type :execution-error/invalid-index
                                            :start start :string-length len})))
                         (when (> start len)
                           (throw (ex-info "start index out of bounds"
                                           {:type :execution-error/index-out-of-bounds
                                            :start start :string-length len})))
                         (if end
                           (do
                             (when (< end start)
                               (throw (ex-info "end index cannot be less than start"
                                               {:type :execution-error/invalid-index
                                                :start start :end end})))
                             (when (> end len)
                               (throw (ex-info "end index out of bounds"
                                               {:type :execution-error/index-out-of-bounds
                                                :end end :string-length len})))
                             (subs s start end))
                           (subs s start))))}

   :str-len   {:args {:s :text}
               :return-type :int
               :impl (fn [{:keys [s]} _ctx] (count s))}

   :str-upper {:args {:s :text}
               :return-type :text
               :impl (fn [{:keys [s]} _ctx] (str/upper-case s))}

   :str-lower {:args {:s :text}
               :return-type :text
               :impl (fn [{:keys [s]} _ctx] (str/lower-case s))}

   :str-trim  {:args {:s :text}
               :return-type :text
               :impl (fn [{:keys [s]} _ctx] (str/trim s))}

   :str-split {:args {:s :text, :sep :text}
               :return-type :jsonb
               :impl (fn [{:keys [s sep]} _ctx]
                       (try
                         (str/split s (re-pattern sep))
                         (catch java.util.regex.PatternSyntaxException e
                           (throw (ex-info "Invalid regex pattern in separator"
                                           {:type :execution-error/invalid-regex
                                            :separator sep
                                            :cause (Throwable/.getMessage e)})))))}

   :str-join  {:args {:coll :jsonb, :sep {:type :text :required false}}
               :return-type :text
               :impl (fn [{:keys [coll sep]} _ctx]
                       (str/join (or sep "") coll))}})


;; === Collections ===

(def collection-defs
  {:first     {:args {:coll :jsonb}
               :return-type :any
               :impl (fn [{:keys [coll]} _ctx] (first coll))}

   :rest      {:args {:coll :jsonb}
               :return-type :jsonb
               :impl (fn [{:keys [coll]} _ctx] (rest coll))}

   :cons      {:args {:x :any, :coll :jsonb}
               :return-type :jsonb
               :impl (fn [{:keys [x coll]} _ctx] (cons x coll))}

   :conj      {:args {:coll :jsonb, :x :any}
               :return-type :jsonb
               :impl (fn [{:keys [coll x]} _ctx] (conj coll x))}

   :get       {:args {:coll :jsonb, :k :any, :default :any}
               :return-type :any
               :impl (fn [{:keys [coll k default]} _ctx] (get coll k default))}

   :assoc     {:args {:m :jsonb, :k :any, :v :any}
               :return-type :jsonb
               :impl (fn [{:keys [m k v]} _ctx] (assoc m k v))}

   :dissoc    {:args {:m :jsonb, :k :any}
               :return-type :jsonb
               :impl (fn [{:keys [m k]} _ctx] (dissoc m k))}

   :count     {:args {:coll :jsonb}
               :return-type :int
               :impl (fn [{:keys [coll]} _ctx] (count coll))}

   :empty?    {:args {:coll :jsonb}
               :return-type :bool
               :impl (fn [{:keys [coll]} _ctx] (empty? coll))}

   :contains? {:args {:coll :jsonb, :k :any}
               :return-type :bool
               :impl (fn [{:keys [coll k]} _ctx] (contains? coll k))}

   :keys      {:args {:m :jsonb}
               :return-type :jsonb
               :impl (fn [{:keys [m]} _ctx] (keys m))}

   :vals      {:args {:m :jsonb}
               :return-type :jsonb
               :impl (fn [{:keys [m]} _ctx] (vals m))}

   :merge     {:args {:maps :jsonb}
               :return-type :jsonb
               :impl (fn [{:keys [maps]} _ctx] (apply merge maps))}

   :into      {:args {:to :jsonb, :from :jsonb}
               :return-type :jsonb
               :impl (fn [{:keys [to from]} _ctx] (into to from))}

   :range     {:args {:start :int, :end :int, :step :int}
               :return-type :jsonb
               :impl (fn [{:keys [start end step]} _ctx]
                       (range (or start 0) end (or step 1)))}

   :repeat    {:args {:n :int, :x :any}
               :return-type :jsonb
               :impl (fn [{:keys [n x]} _ctx] (vec (repeat n x)))}

   :take      {:args {:n :int, :coll :jsonb}
               :return-type :jsonb
               :impl (fn [{:keys [n coll]} _ctx] (vec (take n coll)))}

   :drop      {:args {:n :int, :coll :jsonb}
               :return-type :jsonb
               :impl (fn [{:keys [n coll]} _ctx] (vec (drop n coll)))}

   :reverse   {:args {:coll :jsonb}
               :return-type :jsonb
               :impl (fn [{:keys [coll]} _ctx] (vec (reverse coll)))}

   :sort      {:args {:coll :jsonb}
               :return-type :jsonb
               :impl (fn [{:keys [coll]} _ctx] (vec (sort coll)))}

   :concat    {:args {:colls :jsonb}
               :return-type :jsonb
               :impl (fn [{:keys [colls]} _ctx] (vec (apply concat colls)))}

   :flatten   {:args {:coll :jsonb}
               :return-type :jsonb
               :impl (fn [{:keys [coll]} _ctx] (vec (flatten coll)))}

   :distinct  {:args {:coll :jsonb}
               :return-type :jsonb
               :impl (fn [{:keys [coll]} _ctx] (vec (distinct coll)))}})


;; === Higher-Order Functions ===
;; Note: :fn type args receive fn-id (from LazyFnThunk.force-value)

(def hof-defs
  {:map        {:args {:f :fn, :coll :jsonb}
                :return-type :jsonb
                :impl (fn [{:keys [f coll]} ctx]
                        (mapv (fn [item]
                                (exec/execute-with-named-args ctx f {:item item}))
                              coll))}

   :filter     {:args {:pred :fn, :coll :jsonb}
                :return-type :jsonb
                :impl (fn [{:keys [pred coll]} ctx]
                        (filterv (fn [item]
                                   (exec/execute-with-named-args ctx pred {:item item}))
                                 coll))}

   :reduce     {:args {:f :fn, :init :any, :coll :jsonb}
                :return-type :any
                :impl (fn [{:keys [f init coll]} ctx]
                        (reduce (fn [acc item]
                                  (exec/execute-with-named-args ctx f {:acc acc :item item}))
                                init coll))}

   :some       {:args {:pred :fn, :coll :jsonb}
                :return-type :any
                :impl (fn [{:keys [pred coll]} ctx]
                        (some (fn [item]
                                (when-let [result (exec/execute-with-named-args ctx pred {:item item})]
                                  result))
                              coll))}

   :every?     {:args {:pred :fn, :coll :jsonb}
                :return-type :bool
                :impl (fn [{:keys [pred coll]} ctx]
                        (every? (fn [item]
                                  (exec/execute-with-named-args ctx pred {:item item}))
                                coll))}

   :find-first {:args {:pred :fn, :coll :jsonb}
                :return-type :any
                :impl (fn [{:keys [pred coll]} ctx]
                        (first (filter (fn [item]
                                         (exec/execute-with-named-args ctx pred {:item item}))
                                       coll)))}

   :group-by   {:args {:key-fn :fn, :coll :jsonb}
                :return-type :jsonb
                :impl (fn [{:keys [key-fn coll]} ctx]
                        (group-by (fn [item]
                                    (exec/execute-with-named-args ctx key-fn {:item item}))
                                  coll))}

   :sort-by    {:args {:key-fn :fn, :coll :jsonb}
                :return-type :jsonb
                :impl (fn [{:keys [key-fn coll]} ctx]
                        (vec (sort-by (fn [item]
                                        (exec/execute-with-named-args ctx key-fn {:item item}))
                                      coll)))}

   :apply      {:args {:f :fn, :args :jsonb}
                :return-type :any
                :impl (fn [{:keys [f args]} ctx]
                        (exec/execute-with-named-args ctx f args))}

   :identity   {:args {:x :any}
                :return-type :any
                :impl (fn [{:keys [x]} _ctx] x)}

   :constantly {:args {:x :any}
                :return-type :any
                :impl (fn [{:keys [x]} _ctx] x)}})


;; === Introspection ===

(defn get-all-defs
  "Returns all base function definitions with metadata."
  []
  (merge arithmetic-defs
         comparison-defs
         logic-defs
         conditional-defs
         string-defs
         collection-defs
         hof-defs))
