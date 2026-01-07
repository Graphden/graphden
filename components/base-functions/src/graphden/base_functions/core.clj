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

   All functions are defined using the defbase macro which handles
   automatic argument dereferencing. Arguments are passed as delays
   and automatically deref'd unless marked as :lazy.

   HOF functions receive fn-id (not callable) for :fn type args and use
   make-single-arg-callable to create appropriate callables.

   Registration and storage sync should be done by consuming components
   using fn-registry."
  (:require
    [clojure.string :as str]
    [graphden.executor.interface :as exec]
    [graphden.fn-registry.macros :refer [defbase]]))


;; === Arithmetic ===
;; Note: add, sub, mul, div accept lists like Clojure's +, -, *, /

(defbase add-fn
  {:args {:nums :jsonb}
   :return-type :numeric}
  (apply + nums))


(defbase sub-fn
  {:args {:nums :jsonb}
   :return-type :numeric}
  (when (empty? nums)
    (throw (ex-info "Subtraction requires at least one number"
                    {:type :execution-error/invalid-args
                     :nums nums})))
  (apply - nums))


(defbase mul-fn
  {:args {:nums :jsonb}
   :return-type :numeric}
  (apply * nums))


(defbase div-fn
  {:args {:nums :jsonb}
   :return-type :numeric}
  (when (empty? nums)
    (throw (ex-info "Division requires at least one number"
                    {:type :execution-error/invalid-args
                     :nums nums})))
  ;; Check all divisors (rest nums) for zero
  ;; (/ a b c) = (/ (/ a b) c), so b and c must be non-zero
  (when-let [zero-divisor (some #(when (zero? %) %) (rest nums))]
    (throw (ex-info "Division by zero"
                    {:type :execution-error/division-by-zero
                     :nums nums
                     :zero-at zero-divisor})))
  (apply / nums))


(defbase mod-fn
  {:args {:a :numeric, :b :numeric}
   :return-type :numeric}
  (when (zero? b)
    (throw (ex-info "Modulo by zero"
                    {:type :execution-error/modulo-by-zero
                     :a a :b b})))
  (mod a b))


(defbase neg-fn
  {:args {:n :numeric}
   :return-type :numeric}
  (- n))


(defbase abs-fn
  {:args {:n :numeric}
   :return-type :numeric}
  (abs n))


(def arithmetic-defs
  {:add add-fn
   :sub sub-fn
   :mul mul-fn
   :div div-fn
   :mod mod-fn
   :neg neg-fn
   :abs abs-fn})


;; === Comparison ===
;; Note: comparison functions accept lists like Clojure's =, <, >, etc.
;; (eq [1 1 1]) => true, (lt [1 2 3]) => true (ascending)

(defbase eq-fn
  {:args {:values :jsonb}
   :return-type :bool}
  (apply = values))


(defbase neq-fn
  {:args {:values :jsonb}
   :return-type :bool}
  (apply not= values))


(defbase lt-fn
  {:args {:nums :jsonb}
   :return-type :bool}
  (apply < nums))


(defbase lte-fn
  {:args {:nums :jsonb}
   :return-type :bool}
  (apply <= nums))


(defbase gt-fn
  {:args {:nums :jsonb}
   :return-type :bool}
  (apply > nums))


(defbase gte-fn
  {:args {:nums :jsonb}
   :return-type :bool}
  (apply >= nums))


(def comparison-defs
  {:eq eq-fn
   :neq neq-fn
   :lt lt-fn
   :lte lte-fn
   :gt gt-fn
   :gte gte-fn})


;; === Logic ===
;; Short-circuit works naturally - deref happens at usage sites

(defbase and-fn
  {:args {:a :bool, :b :bool}
   :return-type :bool}
  (and a b))


(defbase or-fn
  {:args {:a :bool, :b :bool}
   :return-type :bool}
  (or a b))


(defbase not-fn
  {:args {:x :bool}
   :return-type :bool}
  (not x))


(def logic-defs
  {:and and-fn
   :or or-fn
   :not not-fn})


;; === Conditionals ===
;; Short-circuit works naturally - deref happens at usage sites

(defbase if-fn
  {:args {:condition :bool, :then :any, :else :any}
   :return-type :any}
  (if condition then else))


(defbase cond-fn
  {:args {:pairs :jsonb, :default :any}
   :return-type :any}
  ;; pairs is a vector of {:pred bool :result value}
  (loop [ps pairs]
    (if (empty? ps)
      default
      (let [{:keys [pred result]} (first ps)]
        (if pred result (recur (rest ps)))))))


(def conditional-defs
  {:if if-fn
   :cond cond-fn})


;; === Strings ===

(defbase str-fn
  {:args {:args :jsonb}
   :return-type :text}
  (str/join args))


(defbase subs-fn
  {:args {:s :text, :start :int, :end {:type :int :required false}}
   :return-type :text}
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
      (subs s start))))


(defbase str-len-fn
  {:args {:s :text}
   :return-type :int}
  (count s))


(defbase str-upper-fn
  {:args {:s :text}
   :return-type :text}
  (str/upper-case s))


(defbase str-lower-fn
  {:args {:s :text}
   :return-type :text}
  (str/lower-case s))


(defbase str-trim-fn
  {:args {:s :text}
   :return-type :text}
  (str/trim s))


(defbase str-split-fn
  {:args {:s :text, :sep :text}
   :return-type :jsonb}
  (when (empty? sep)
    (throw (ex-info "separator cannot be empty"
                    {:type :execution-error/invalid-separator
                     :separator sep
                     :string s})))
  (try
    (vec (str/split s (re-pattern sep)))
    (catch java.util.regex.PatternSyntaxException e
      (throw (ex-info "Invalid regex pattern in separator"
                      {:type :execution-error/invalid-regex
                       :separator sep
                       :cause (Throwable/.getMessage e)})))))


(defbase str-join-fn
  {:args {:coll :jsonb, :sep {:type :text :required false}}
   :return-type :text}
  (str/join (or sep "") coll))


(def string-defs
  {:str str-fn
   :subs subs-fn
   :str-len str-len-fn
   :str-upper str-upper-fn
   :str-lower str-lower-fn
   :str-trim str-trim-fn
   :str-split str-split-fn
   :str-join str-join-fn})


;; === Collections ===

(defbase first-fn
  {:args {:coll :jsonb}
   :return-type :any}
  (first coll))


(defbase rest-fn
  {:args {:coll :jsonb}
   :return-type :jsonb}
  (rest coll))


(defbase cons-fn
  {:args {:x :any, :coll :jsonb}
   :return-type :jsonb}
  (cons x coll))


(defbase conj-fn
  {:args {:coll :jsonb, :x :any}
   :return-type :jsonb}
  (conj coll x))


(defbase get-fn
  {:args {:coll :jsonb, :k :any, :default :any}
   :return-type :any}
  (get coll k default))


(defbase assoc-fn
  {:args {:m :jsonb, :k :any, :v :any}
   :return-type :jsonb}
  (assoc m k v))


(defbase dissoc-fn
  {:args {:m :jsonb, :k :any}
   :return-type :jsonb}
  (dissoc m k))


(defbase count-fn
  {:args {:coll :jsonb}
   :return-type :int}
  (count coll))


(defbase empty?-fn
  {:args {:coll :jsonb}
   :return-type :bool}
  (empty? coll))


(defbase contains?-fn
  {:args {:coll :jsonb, :k :any}
   :return-type :bool}
  (contains? coll k))


(defbase keys-fn
  {:args {:m :jsonb}
   :return-type :jsonb}
  (keys m))


(defbase vals-fn
  {:args {:m :jsonb}
   :return-type :jsonb}
  (vals m))


(defbase merge-fn
  {:args {:maps :jsonb}
   :return-type :jsonb}
  (apply merge maps))


(defbase into-fn
  {:args {:to :jsonb, :from :jsonb}
   :return-type :jsonb}
  (into to from))


(defbase range-fn
  {:args {:start {:type :int :required false}, :end :int, :step {:type :int :required false}}
   :return-type :jsonb}
  (let [actual-step (or step 1)
        actual-start (or start 0)]
    (when (zero? actual-step)
      (throw (ex-info "step cannot be zero (would cause infinite loop)"
                      {:type :execution-error/invalid-step
                       :start actual-start :end end :step step})))
    (vec (range actual-start end actual-step))))


(defbase repeat-fn
  {:args {:n :int, :x :any}
   :return-type :jsonb}
  (vec (repeat n x)))


(defbase take-fn
  {:args {:n :int, :coll :jsonb}
   :return-type :jsonb}
  (vec (take n coll)))


(defbase drop-fn
  {:args {:n :int, :coll :jsonb}
   :return-type :jsonb}
  (vec (drop n coll)))


(defbase reverse-fn
  {:args {:coll :jsonb}
   :return-type :jsonb}
  (vec (reverse coll)))


(defbase sort-fn
  {:args {:coll :jsonb}
   :return-type :jsonb}
  (vec (sort coll)))


(defbase concat-fn
  {:args {:colls :jsonb}
   :return-type :jsonb}
  (vec (apply concat colls)))


(defbase flatten-fn
  {:args {:coll :jsonb}
   :return-type :jsonb}
  (vec (flatten coll)))


(defbase distinct-fn
  {:args {:coll :jsonb}
   :return-type :jsonb}
  (vec (distinct coll)))


(def collection-defs
  {:first first-fn
   :rest rest-fn
   :cons cons-fn
   :conj conj-fn
   :get get-fn
   :assoc assoc-fn
   :dissoc dissoc-fn
   :count count-fn
   :empty? empty?-fn
   :contains? contains?-fn
   :keys keys-fn
   :vals vals-fn
   :merge merge-fn
   :into into-fn
   :range range-fn
   :repeat repeat-fn
   :take take-fn
   :drop drop-fn
   :reverse reverse-fn
   :sort sort-fn
   :concat concat-fn
   :flatten flatten-fn
   :distinct distinct-fn})


;; === Higher-Order Functions ===
;; Note: :fn type args return fn-id (UUID) after deref.
;; HOF use exec/make-single-arg-callable to create callables that accept single values.
;; This means user functions must have exactly 1 required argument (any name).
;; For reduce, user function takes a single arg which receives [acc item] vector.

(defn- make-hof-callable
  "Creates a callable for HOF. If f is already a function (for testing),
   wraps it to accept single value. If f is a UUID (fn-id), uses executor."
  [execution-ctx f]
  (if (fn? f)
    ;; Legacy/testing mode: f is already a Clojure function
    ;; Wrap it to accept single value instead of {:item item}
    (fn [value] (f {:item value}))
    ;; Production mode: f is fn-id, create callable via executor
    (exec/make-single-arg-callable execution-ctx f)))


(defn- make-reduce-callable
  "Creates a callable for reduce. If f is already a function (for testing),
   wraps it. If f is a UUID (fn-id), uses executor."
  [execution-ctx f]
  (if (fn? f)
    ;; Legacy/testing mode: f accepts {:acc a :item b}
    (fn [[acc item]] (f {:acc acc :item item}))
    ;; Production mode: f is fn-id, function takes single arg [acc item]
    (exec/make-single-arg-callable execution-ctx f)))


(defbase map-fn
  {:args {:f :fn, :coll :jsonb}
   :return-type :jsonb}
  (let [callable (make-hof-callable ctx f)]
    (mapv callable coll)))


(defbase filter-fn
  {:args {:pred :fn, :coll :jsonb}
   :return-type :jsonb}
  (let [callable (make-hof-callable ctx pred)]
    (filterv callable coll)))


(defbase reduce-fn
  {:args {:f :fn, :init :any, :coll :jsonb}
   :return-type :any}
  ;; reduce passes [acc item] as single vector to the function
  (let [callable (make-reduce-callable ctx f)]
    (reduce (fn [acc item] (callable [acc item])) init coll)))


(defbase some-base-fn
  {:args {:pred :fn, :coll :jsonb}
   :return-type :any}
  (let [callable (make-hof-callable ctx pred)]
    (some (fn [item]
            (when-let [result (callable item)]
              result))
          coll)))


(defbase every?-fn
  {:args {:pred :fn, :coll :jsonb}
   :return-type :bool}
  (let [callable (make-hof-callable ctx pred)]
    (every? callable coll)))


(defbase find-first-fn
  {:args {:pred :fn, :coll :jsonb}
   :return-type :any}
  (let [callable (make-hof-callable ctx pred)]
    (first (filter callable coll))))


(defbase group-by-fn
  {:args {:key-fn :fn, :coll :jsonb}
   :return-type :jsonb}
  (let [callable (make-hof-callable ctx key-fn)]
    (group-by callable coll)))


(defbase sort-by-fn
  {:args {:key-fn :fn, :coll :jsonb}
   :return-type :jsonb}
  (let [callable (make-hof-callable ctx key-fn)]
    (vec (sort-by callable coll))))


(defbase apply-fn
  {:args {:f :fn, :args :jsonb}
   :return-type :any}
  ;; apply passes the args directly to the function
  ;; For legacy/testing (Clojure fn): call with args as-is
  ;; For production (UUID): use make-single-arg-callable
  (if (fn? f)
    ;; Legacy mode: f is already a Clojure fn expecting args directly
    (f args)
    ;; Production mode: f is fn-id, create callable
    (let [callable (exec/make-single-arg-callable ctx f)]
      (callable args))))


(defbase identity-fn
  {:args {:x :any}
   :return-type :any}
  x)


(defbase constantly-fn
  {:args {:x :any}
   :return-type :any}
  x)


(def hof-defs
  {:map map-fn
   :filter filter-fn
   :reduce reduce-fn
   :some some-base-fn
   :every? every?-fn
   :find-first find-first-fn
   :group-by group-by-fn
   :sort-by sort-by-fn
   :apply apply-fn
   :identity identity-fn
   :constantly constantly-fn})


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
