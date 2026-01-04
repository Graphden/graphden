(ns graphden.base-functions.core
  "Core implementation of base functions.

   All base functions take a map of thunks and a context.
   They use force-value to evaluate thunks lazily."
  (:require
    [clojure.string :as str]
    [graphden.executor.interface :as exec]))


;; === Registration Helper ===

(defn- register-fns!
  "Registers multiple base functions from a name->fn map."
  [fns-map]
  (run! (fn [[fn-name f]]
          (exec/register-base-fn! fn-name f))
        fns-map))


;; === Arithmetic ===

(defn- base-add
  [{:keys [a b]} ctx]
  (+ (exec/force-value a ctx)
     (exec/force-value b ctx)))


(defn- base-sub
  [{:keys [a b]} ctx]
  (- (exec/force-value a ctx)
     (exec/force-value b ctx)))


(defn- base-mul
  [{:keys [a b]} ctx]
  (* (exec/force-value a ctx)
     (exec/force-value b ctx)))


(defn- base-div
  [{:keys [a b]} ctx]
  (let [divisor (exec/force-value b ctx)]
    (when (zero? divisor)
      (throw (ex-info "Division by zero"
                      {:type :execution-error/division-by-zero
                       :a (exec/force-value a ctx)
                       :b divisor})))
    (/ (exec/force-value a ctx) divisor)))


(defn- base-mod
  [{:keys [a b]} ctx]
  (mod (exec/force-value a ctx)
       (exec/force-value b ctx)))


(defn- base-neg
  [{:keys [n]} ctx]
  (- (exec/force-value n ctx)))


(defn- base-abs
  [{:keys [n]} ctx]
  (abs (exec/force-value n ctx)))


(def ^:private arithmetic-fns
  {:add base-add
   :sub base-sub
   :mul base-mul
   :div base-div
   :mod base-mod
   :neg base-neg
   :abs base-abs})


(defn register-arithmetic!
  []
  (register-fns! arithmetic-fns))


;; === Comparison ===

(defn- base-eq
  [{:keys [a b]} ctx]
  (= (exec/force-value a ctx)
     (exec/force-value b ctx)))


(defn- base-neq
  [{:keys [a b]} ctx]
  (not= (exec/force-value a ctx)
        (exec/force-value b ctx)))


(defn- base-lt
  [{:keys [a b]} ctx]
  (< (exec/force-value a ctx)
     (exec/force-value b ctx)))


(defn- base-lte
  [{:keys [a b]} ctx]
  (<= (exec/force-value a ctx)
      (exec/force-value b ctx)))


(defn- base-gt
  [{:keys [a b]} ctx]
  (> (exec/force-value a ctx)
     (exec/force-value b ctx)))


(defn- base-gte
  [{:keys [a b]} ctx]
  (>= (exec/force-value a ctx)
      (exec/force-value b ctx)))


(def ^:private comparison-fns
  {:eq  base-eq
   :neq base-neq
   :lt  base-lt
   :lte base-lte
   :gt  base-gt
   :gte base-gte})


(defn register-comparison!
  []
  (register-fns! comparison-fns))


;; === Logic ===
;; Note: and/or are lazy - they don't evaluate all arguments

(defn- base-and
  [{:keys [a b]} ctx]
  (and (exec/force-value a ctx)
       (exec/force-value b ctx)))


(defn- base-or
  [{:keys [a b]} ctx]
  (or (exec/force-value a ctx)
      (exec/force-value b ctx)))


(defn- base-not
  [{:keys [x]} ctx]
  (not (exec/force-value x ctx)))


(def ^:private logic-fns
  {:and base-and
   :or  base-or
   :not base-not})


(defn register-logic!
  []
  (register-fns! logic-fns))


;; === Conditionals ===
;; Note: if is lazy - only one branch is evaluated

(defn- base-if
  [{:keys [condition then else]} ctx]
  (if (exec/force-value condition ctx)
    (exec/force-value then ctx)
    (exec/force-value else ctx)))


(defn- base-cond
  "Evaluates conditions in order, returns first truthy result.
   Takes pairs of condition/result thunks.
   Args: {:pairs [{:pred c1 :result r1} {:pred c2 :result r2} ...]
          :default d}"
  [{:keys [pairs default]} ctx]
  (let [pairs-val (exec/force-value pairs ctx)]
    (loop [ps pairs-val]
      (if (empty? ps)
        (when default (exec/force-value default ctx))
        (let [{:keys [pred result]} (first ps)]
          (if (exec/force-value pred ctx)
            (exec/force-value result ctx)
            (recur (rest ps))))))))


(def ^:private conditional-fns
  {:if   base-if
   :cond base-cond})


(defn register-conditionals!
  []
  (register-fns! conditional-fns))


;; === Strings ===

(defn- base-str
  [{:keys [args]} ctx]
  (let [args-val (exec/force-value args ctx)]
    (str/join (map #(exec/force-value % ctx) args-val))))


(defn- base-subs
  [{:keys [s start end]} ctx]
  (let [s-val (exec/force-value s ctx)
        start-val (exec/force-value start ctx)
        end-val (when end (exec/force-value end ctx))]
    (if end-val
      (subs s-val start-val end-val)
      (subs s-val start-val))))


(defn- base-str-len
  [{:keys [s]} ctx]
  (count (exec/force-value s ctx)))


(defn- base-str-upper
  [{:keys [s]} ctx]
  (str/upper-case (exec/force-value s ctx)))


(defn- base-str-lower
  [{:keys [s]} ctx]
  (str/lower-case (exec/force-value s ctx)))


(defn- base-str-trim
  [{:keys [s]} ctx]
  (str/trim (exec/force-value s ctx)))


(defn- base-str-split
  [{:keys [s sep]} ctx]
  (str/split (exec/force-value s ctx)
             (re-pattern (exec/force-value sep ctx))))


(defn- base-str-join
  [{:keys [coll sep]} ctx]
  (let [coll-val (exec/force-value coll ctx)
        sep-val (if sep (exec/force-value sep ctx) "")]
    (str/join sep-val coll-val)))


(def ^:private string-fns
  {:str       base-str
   :subs      base-subs
   :str-len   base-str-len
   :str-upper base-str-upper
   :str-lower base-str-lower
   :str-trim  base-str-trim
   :str-split base-str-split
   :str-join  base-str-join})


(defn register-strings!
  []
  (register-fns! string-fns))


;; === Collections ===

(defn- base-first
  [{:keys [coll]} ctx]
  (first (exec/force-value coll ctx)))


(defn- base-rest
  [{:keys [coll]} ctx]
  (rest (exec/force-value coll ctx)))


(defn- base-cons
  [{:keys [x coll]} ctx]
  (cons (exec/force-value x ctx)
        (exec/force-value coll ctx)))


(defn- base-conj
  [{:keys [coll x]} ctx]
  (conj (exec/force-value coll ctx)
        (exec/force-value x ctx)))


(defn- base-get
  [{:keys [coll k default]} ctx]
  (let [coll-val (exec/force-value coll ctx)
        k-val (exec/force-value k ctx)
        default-val (when default (exec/force-value default ctx))]
    (get coll-val k-val default-val)))


(defn- base-assoc
  [{:keys [m k v]} ctx]
  (assoc (exec/force-value m ctx)
         (exec/force-value k ctx)
         (exec/force-value v ctx)))


(defn- base-dissoc
  [{:keys [m k]} ctx]
  (dissoc (exec/force-value m ctx)
          (exec/force-value k ctx)))


(defn- base-count
  [{:keys [coll]} ctx]
  (count (exec/force-value coll ctx)))


(defn- base-empty?
  [{:keys [coll]} ctx]
  (empty? (exec/force-value coll ctx)))


(defn- base-contains?
  [{:keys [coll k]} ctx]
  (contains? (exec/force-value coll ctx)
             (exec/force-value k ctx)))


(defn- base-keys
  [{:keys [m]} ctx]
  (keys (exec/force-value m ctx)))


(defn- base-vals
  [{:keys [m]} ctx]
  (vals (exec/force-value m ctx)))


(defn- base-merge
  [{:keys [m1 m2]} ctx]
  (merge (exec/force-value m1 ctx)
         (exec/force-value m2 ctx)))


(defn- base-into
  [{:keys [to from]} ctx]
  (into (exec/force-value to ctx)
        (exec/force-value from ctx)))


(defn- base-range
  [{:keys [start end step]} ctx]
  (let [start-val (if start (exec/force-value start ctx) 0)
        end-val (exec/force-value end ctx)
        step-val (if step (exec/force-value step ctx) 1)]
    (range start-val end-val step-val)))


(defn- base-repeat
  [{:keys [n x]} ctx]
  (let [n-val (exec/force-value n ctx)
        x-val (exec/force-value x ctx)]
    (vec (repeat n-val x-val))))


(defn- base-take
  [{:keys [n coll]} ctx]
  (vec (take (exec/force-value n ctx)
             (exec/force-value coll ctx))))


(defn- base-drop
  [{:keys [n coll]} ctx]
  (vec (drop (exec/force-value n ctx)
             (exec/force-value coll ctx))))


(defn- base-reverse
  [{:keys [coll]} ctx]
  (vec (reverse (exec/force-value coll ctx))))


(defn- base-sort
  [{:keys [coll]} ctx]
  (vec (sort (exec/force-value coll ctx))))


(defn- base-concat
  [{:keys [coll1 coll2]} ctx]
  (vec (concat (exec/force-value coll1 ctx)
               (exec/force-value coll2 ctx))))


(defn- base-flatten
  [{:keys [coll]} ctx]
  (vec (flatten (exec/force-value coll ctx))))


(defn- base-distinct
  [{:keys [coll]} ctx]
  (vec (distinct (exec/force-value coll ctx))))


(def ^:private collection-fns
  {:first     base-first
   :rest      base-rest
   :cons      base-cons
   :conj      base-conj
   :get       base-get
   :assoc     base-assoc
   :dissoc    base-dissoc
   :count     base-count
   :empty?    base-empty?
   :contains? base-contains?
   :keys      base-keys
   :vals      base-vals
   :merge     base-merge
   :into      base-into
   :range     base-range
   :repeat    base-repeat
   :take      base-take
   :drop      base-drop
   :reverse   base-reverse
   :sort      base-sort
   :concat    base-concat
   :flatten   base-flatten
   :distinct  base-distinct})


(defn register-collections!
  []
  (register-fns! collection-fns))


;; === Higher-Order Functions ===
;; Note: These work with fn-ids (LazyFnThunk returns fn-id, not result)

(defn- base-map
  "Maps a function over a collection.
   f is a fn-id, coll is a collection."
  [{:keys [f coll]} ctx]
  (let [f-id (exec/force-value f ctx)
        coll-val (exec/force-value coll ctx)]
    (vec (map (fn [item]
                (exec/execute ctx f-id {:item item}))
              coll-val))))


(defn- base-filter
  "Filters a collection by a predicate function.
   pred is a fn-id that returns truthy/falsy, coll is a collection."
  [{:keys [pred coll]} ctx]
  (let [pred-id (exec/force-value pred ctx)
        coll-val (exec/force-value coll ctx)]
    (filterv (fn [item]
               (exec/execute ctx pred-id {:item item}))
             coll-val)))


(defn- base-reduce
  "Reduces a collection with a function.
   f is a fn-id that takes {:acc _ :item _}, init is initial value, coll is collection."
  [{:keys [f init coll]} ctx]
  (let [f-id (exec/force-value f ctx)
        init-val (exec/force-value init ctx)
        coll-val (exec/force-value coll ctx)]
    (reduce (fn [acc item]
              (exec/execute ctx f-id {:acc acc :item item}))
            init-val
            coll-val)))


(defn- base-some
  "Returns first truthy result of applying pred to items in coll.
   pred is a fn-id, coll is a collection."
  [{:keys [pred coll]} ctx]
  (let [pred-id (exec/force-value pred ctx)
        coll-val (exec/force-value coll ctx)]
    (some (fn [item]
            (when-let [result (exec/execute ctx pred-id {:item item})]
              result))
          coll-val)))


(defn- base-every?
  "Returns true if pred is true for all items in coll.
   pred is a fn-id, coll is a collection."
  [{:keys [pred coll]} ctx]
  (let [pred-id (exec/force-value pred ctx)
        coll-val (exec/force-value coll ctx)]
    (every? (fn [item]
              (exec/execute ctx pred-id {:item item}))
            coll-val)))


(defn- base-find-first
  "Returns first item in coll for which pred returns true.
   pred is a fn-id, coll is a collection."
  [{:keys [pred coll]} ctx]
  (let [pred-id (exec/force-value pred ctx)
        coll-val (exec/force-value coll ctx)]
    (first (filter (fn [item]
                     (exec/execute ctx pred-id {:item item}))
                   coll-val))))


(defn- base-group-by
  "Groups items by result of applying key-fn.
   key-fn is a fn-id, coll is a collection."
  [{:keys [key-fn coll]} ctx]
  (let [key-fn-id (exec/force-value key-fn ctx)
        coll-val (exec/force-value coll ctx)]
    (group-by (fn [item]
                (exec/execute ctx key-fn-id {:item item}))
              coll-val)))


(defn- base-sort-by
  "Sorts collection by result of applying key-fn.
   key-fn is a fn-id, coll is a collection."
  [{:keys [key-fn coll]} ctx]
  (let [key-fn-id (exec/force-value key-fn ctx)
        coll-val (exec/force-value coll ctx)]
    (vec (sort-by (fn [item]
                    (exec/execute ctx key-fn-id {:item item}))
                  coll-val))))


(defn- base-apply
  "Applies a function to arguments.
   f is a fn-id, args is a map of arguments."
  [{:keys [f args]} ctx]
  (let [f-id (exec/force-value f ctx)
        args-val (exec/force-value args ctx)]
    (exec/execute ctx f-id args-val)))


(defn- base-identity
  "Returns its argument unchanged."
  [{:keys [x]} ctx]
  (exec/force-value x ctx))


(defn- base-constantly
  "Returns a function that always returns the same value.
   Returns the value directly since we can't create new functions dynamically."
  [{:keys [x]} ctx]
  (exec/force-value x ctx))


(def ^:private hof-fns
  {:map        base-map
   :filter     base-filter
   :reduce     base-reduce
   :some       base-some
   :every?     base-every?
   :find-first base-find-first
   :group-by   base-group-by
   :sort-by    base-sort-by
   :apply      base-apply
   :identity   base-identity
   :constantly base-constantly})


(defn register-hof!
  []
  (register-fns! hof-fns))


;; === Register All ===

(defn register-all!
  "Registers all base functions."
  []
  (register-arithmetic!)
  (register-comparison!)
  (register-logic!)
  (register-conditionals!)
  (register-strings!)
  (register-collections!)
  (register-hof!))
