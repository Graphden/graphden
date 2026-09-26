(ns graphden.types.check.gradual
  "The gradual view of one binding — what `check-binding!` judges
   AFTER `unify` bound the type variables.

   `unify` is a symmetric relation whose leniency arms accept either
   direction (`:int ↔ :numeric`, `T ↔ [:refine T c]`, the union
   chain's `(or (subtype? a b) (subtype? b a))`), so a binding with one
   type variable in it let a WIDER concrete sibling through:
   `{:x :int :y a}` took `{:x :numeric :y :text}`, `[:fn {:item :int} b]`
   took a callee that only accepts `:positive-int`. The variable is what
   the unify fallback is for; the concrete parts must be judged the way
   every var-free binding is — `actual ⊆ expected`, one direction.

   Two of unify's arms are NOT direction holes but the checker's
   escape hatches, and `subtype?` alone would refuse them: `:any`
   either side, and `:jsonb` against anything jsonb-representable
   (`:parse-json-body`'s `:jsonb` into `:get`'s `[:union :null [:list
   :any] [:map a :any]]` is the corpus's ordinary gradual flow). A type
   variable that unify left unbound is unconstrained the same way. A
   literal is a third: its classified type loses the value
   (`{:status 204}` classifies `{:status :int}`), while the value itself
   may satisfy a refined field — the value decides, as it does for a
   top-level refinement.

   `erase` walks `actual` and `expected` in parallel and fills every
   such hole from the other side; `(subtype? actual' expected')` on
   the result then judges only the concrete parts. The walk covers
   the shapes `unify` walks: record fields (open on both sides, as
   `unify-record` is), list elem, map key / val, tuple positions, fn
   args (positional, like `unify-fn`) + ret, refine base, marker
   inner, and unions member-wise."
  (:require
    [graphden.types.check.literals :as lit]
    [graphden.types.core :as types]))


(defn any-shape?
  "True iff `t` is `:any` OR a structural form whose every reasoned
   position is `:any` — `[:map :any :any]`, `[:list :any]`, a fn-type
   of `:any`s. Such a value carries no more information than the bare
   `:any` does, so the `(= actual :any) → silent pass` escape hatch
   extends to it: a `:get`'s `:any` return propagates through `:merge`
   / `:update-in` to `[:map :any :any]` / `:any` returns, which would
   otherwise strict-reject against tighter slot types downstream."
  [t]
  (cond
    (= t :any)             true
    (types/list-type? t)   (any-shape? (types/list-elem t))
    (types/map-type? t)    (and (any-shape? (types/map-key t))
                                (any-shape? (types/map-val t)))
    (types/tuple-type? t)  (every? any-shape? (types/tuple-elems t))
    (types/fn-type? t)     (and (every? any-shape? (vals (types/fn-args t)))
                                (any-shape? (types/fn-ret t)))
    :else                  false))


(defn- hole?
  "A node that says nothing about its side: an `:any` shape, a type
   variable unify left unbound, or `:jsonb` where the other side is
   jsonb-representable (mirrors unify's `:jsonb ↔ record / list / map
   / tuple / refine / primitive` arm)."
  [t other]
  (or (any-shape? t)
      (types/type-var? t)
      (and (= t :jsonb)
           (not (types/contains-marker? other))
           (types/subtype? other :jsonb))))


(defn- values-at-key
  "The literal samples for record field `k` — every sample must be a
   map carrying it, else no information."
  [values k]
  (if (and (seq values) (every? #(and (map? %) (contains? % k)) values))
    (mapv #(get % k) values)
    []))


(defn- element-values
  "The literal samples for a list / tuple element position — every
   sample must be sequential, else no information. `i` picks a tuple
   position; nil pools every element."
  [values i]
  (if (and (seq values) (every? sequential? values))
    (if i
      (into [] (keep #(when (< i (count %)) (nth % i))) values)
      (into [] cat values))
    []))


(defn- literal-satisfies?
  "Every literal sample satisfies the refinement constraint `c` — a
   decided `true`, never `:unknown`."
  [values c]
  (and (seq values)
       (every? #(true? (lit/literal-satisfies-refinement? % c)) values)))


(declare erase erase*)


(defn- erase-record
  [actual expected values]
  (let [shared (filter #(contains? actual %) (keys expected))
        pairs (into {} (map (fn [k]
                              [k (erase (get actual k) (get expected k)
                                        (values-at-key values k))]))
                    shared)
        actual' (reduce-kv (fn [acc k [a' _]] (assoc acc k a')) actual pairs)
        expected' (reduce-kv (fn [acc k [_ e']] (assoc acc k e')) expected pairs)
        ;; Open on both sides, as `unify-record` is: a field the actual
        ;; lacks is taken from the expected, one the expected lacks
        ;; rides along on the actual.
        missing (remove #(contains? actual %) (keys expected'))]
    [(reduce (fn [acc k] (assoc acc k (get expected' k))) actual' missing)
     expected']))


(defn- erase-fn
  [actual expected values]
  (let [a-args (sort-by key (types/fn-args actual))
        e-args (sort-by key (types/fn-args expected))
        pairs (mapv (fn [[_ a] [_ e]] (erase a e values)) a-args e-args)
        [ret-a ret-e] (erase (types/fn-ret actual) (types/fn-ret expected) [])
        rebuild (fn [t args ret]
                  (let [base [:fn args ret]]
                    (if-let [eff (types/fn-effects t)] (conj base eff) base)))]
    [(rebuild actual (into {} (map (fn [[k _] [a' _]] [k a']) a-args pairs)) ret-a)
     (rebuild expected (into {} (map (fn [[k _] [_ e']] [k e']) e-args pairs)) ret-e)]))


(defn erase
  "`[actual' expected']` — the two sides with every gradual hole
   filled from the other side, ready for one directional `subtype?`.
   `values` is the vector of literal samples known for this position
   (empty when the binding is a ref) — it decides a refined expected
   node the way `check-refinement-on-literal` decides a top-level one."
  [actual expected values]
  (let [actual (types/resolve-alias actual)
        expected (types/resolve-alias expected)]
    (erase* actual expected values)))


(defn- erase*
  [actual expected values]
  (cond
    ;; Do not unwrap a marked destination and then accept jsonb as its
    ;; inner type: that would bypass the core's marker boundary.
    (and (= actual :jsonb) (types/contains-marker? expected)) [actual expected]

    (hole? actual expected) [expected expected]
    (hole? expected actual) [actual actual]

    ;; A refined expected node against a literal: the value decides.
    (and (types/refine-type? expected)
         (not (types/refine-type? actual))
         (types/subtype? actual (types/refine-base expected))
         (literal-satisfies? values (types/refine-constraint expected)))
    [expected expected]

    (types/union-type? actual)
    [(types/make-union (mapv #(first (erase % expected [])) (types/union-members actual)))
     expected]

    (types/union-type? expected)
    (or (some (fn [m]
                (let [[a' m'] (erase actual m values)]
                  (when (types/subtype? a' m')
                    [a' (types/make-union (replace {m m'} (types/union-members expected)))])))
              (types/union-members expected))
        [actual expected])

    (and (types/record-type? actual) (types/record-type? expected))
    (erase-record actual expected values)

    (and (types/record-type? actual) (types/map-type? expected))
    [(reduce-kv (fn [acc k v]
                  (assoc acc k (first (erase v (types/map-val expected) (values-at-key values k)))))
                actual actual)
     expected]

    (and (types/list-type? actual) (types/list-type? expected))
    (let [[a' e'] (erase (types/list-elem actual) (types/list-elem expected)
                         (element-values values nil))]
      [[:list a'] [:list e']])

    (and (types/tuple-type? actual) (types/list-type? expected))
    [(into [:tuple]
           (map-indexed (fn [i x]
                          (first (erase x (types/list-elem expected) (element-values values i)))))
           (types/tuple-elems actual))
     expected]

    (and (types/tuple-type? actual) (types/tuple-type? expected)
         (= (count (types/tuple-elems actual)) (count (types/tuple-elems expected))))
    (let [pairs (map-indexed (fn [i [a e]] (erase a e (element-values values i)))
                             (map vector (types/tuple-elems actual) (types/tuple-elems expected)))]
      [(into [:tuple] (map first) pairs) (into [:tuple] (map second) pairs)])

    (and (types/map-type? actual) (types/map-type? expected))
    (let [[ka ke] (erase (types/map-key actual) (types/map-key expected) [])
          [va ve] (erase (types/map-val actual) (types/map-val expected)
                         (if (and (seq values) (every? map? values))
                           (into [] (mapcat vals) values)
                           []))]
      [[:map ka va] [:map ke ve]])

    (and (types/fn-type? actual) (types/fn-type? expected)
         (= (count (types/fn-args actual)) (count (types/fn-args expected))))
    (erase-fn actual expected values)

    (and (types/refine-type? actual) (types/refine-type? expected))
    (let [[a' e'] (erase (types/refine-base actual) (types/refine-base expected) values)]
      [[:refine a' (types/refine-constraint actual)]
       [:refine e' (types/refine-constraint expected)]])

    (types/marker-type? expected)
    (if (and (types/marker-type? actual)
             (= (types/marker-tag actual) (types/marker-tag expected)))
      (let [[a' e'] (erase (types/marker-inner actual) (types/marker-inner expected) values)]
        [[(types/marker-tag actual) a'] [(types/marker-tag expected) e']])
      (let [[a' e'] (erase actual (types/marker-inner expected) values)]
        [a' [(types/marker-tag expected) e']]))

    :else [actual expected]))


(defn fits?
  "The directional judgement of a binding after unification:
   `actual ⊆ expected` once the gradual holes are filled (`erase`)."
  [actual expected values]
  (let [[a' e'] (erase actual expected values)]
    (types/subtype? a' e')))
