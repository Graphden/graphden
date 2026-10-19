(ns graphden.types.validator
  "Build a boundary validator from the same declarative type used by checking.
   Unsupported declarations fail at construction; errors contain paths only."
  (:require
    [graphden.types.check.literals :as literals]
    [graphden.types.core :as types]))


(defn- unsupported!
  [path]
  (throw (ex-info "Cannot build a validator for this type definition"
                  {:type :types/unsupported-validator :path path})))


(defn- constraint-supported?
  [c]
  (and (vector? c)
       (let [[op rhs] c]
         (cond
           (#{:and :or} op) (every? constraint-supported? (rest c))
           (not= 2 (count c)) false
           (#{:> :>= :< :<=} op) (number? rhs)
           (#{:= :not=} op) true
           (= :in op) (or (set? rhs) (vector? rhs))
           (= :matches op) (and (string? rhs)
                                (try (re-pattern rhs) true (catch Exception _ false)))
           :else false))))


(defn- error
  [path code]
  [{:path path :code code}])


(declare compile-type)


(defn- compile-record
  [t path seen]
  (let [fields (mapv (fn [[k child]] [k (compile-type child (conj path k) seen)]) t)]
    (fn [value p]
      (if (map? value)
        (into [] (mapcat (fn [[k check]]
                           (if (contains? value k)
                             (check (get value k) (conj p k))
                             (error (conj p k) :missing-field)))) fields)
        (error p :type-mismatch)))))


(defn- compile-list
  [t path seen]
  (let [check (compile-type (types/list-elem t) (conj path :element) seen)]
    (fn [value p]
      (if (sequential? value)
        (into [] (mapcat (fn [[i v]] (check v (conj p i)))) (map-indexed vector value))
        (error p :type-mismatch)))))


(defn- compile-map
  [t path seen]
  (let [key-check (compile-type (types/map-key t) (conj path :key) seen)
        val-check (compile-type (types/map-val t) (conj path :value) seen)]
    (fn [value p]
      (if (map? value)
        ;; Dynamic keys may themselves be secret input. Use entry positions,
        ;; not key contents, in diagnostics.
        (into [] (mapcat (fn [[i [k v]]]
                           (concat (key-check k (conj p i :key))
                                   (val-check v (conj p i :value)))))
              (map-indexed vector value))
        (error p :type-mismatch)))))


(defn- compile-union
  [t path seen]
  (let [checks (mapv #(compile-type % (conj path :branch) seen) (types/union-members t))]
    (when (empty? checks) (unsupported! path))
    (fn [value p]
      (let [results (mapv #(% value p) checks)]
        (if (some empty? results)
          []
          (apply min-key count results))))))


(defn- compile-refinement
  [t path seen]
  (let [c (types/refine-constraint t)
        check (compile-type (types/refine-base t) path seen)]
    (when-not (or (nil? c) (constraint-supported? c)) (unsupported! path))
    (fn [value p]
      (let [base-errors (check value p)]
        (cond
          (seq base-errors) base-errors
          (or (nil? c) (true? (literals/literal-satisfies-refinement? value c))) []
          :else (error p :refinement-mismatch))))))


(defn- compile-type
  [t path seen]
  (when (contains? seen t) (unsupported! path))
  (let [seen (conj seen t)
        resolved (if (keyword? t) (types/resolve-alias t) t)]
    (cond
      (not= t resolved) (compile-type resolved path seen)
      (types/marker-type? t) (compile-type (types/marker-inner t) path seen)
      (types/refine-type? t) (compile-refinement t path seen)
      (types/record-type? t) (compile-record t path seen)
      (types/list-type? t) (compile-list t path seen)
      (types/map-type? t) (compile-map t path seen)
      (types/union-type? t) (compile-union t path seen)
      (and (vector? t) (= :variant (first t))) (compile-type (types/desugar-variant t) path seen)
      (and (keyword? t) (types/primitive? t)
           (not (#{:fn :fn-ref :input-stream} t)))
      (let [pred (get types/runtime-predicates t)]
        (fn [value p] (if (pred value) [] (error p :type-mismatch))))
      :else (unsupported! path))))


(defn build
  "Construct a validator returning T unchanged or throwing path-only errors.
   Construction refuses unsupported types/refinements, including structural
   callables: testing a runtime fn cannot prove its execution contract.
   Validation does not grant authority or remove any static secrecy label."
  [t]
  (let [check (compile-type t [] #{})]
    (fn [value]
      (let [errors (check value [])]
        (when (seq errors)
          (throw (ex-info "Value does not match the required type"
                          {:type :execution-error/type-mismatch :errors errors})))
        value))))


(defn validate!
  "Validate one external value against a declarative type; return it unchanged."
  [t value]
  ((build t) value))
