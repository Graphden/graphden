(ns graphden.packages.core.logic.impls
  "Implementations for core/logic base functions.

   Migrated to `defbase` — arg symbols resolve at use site via the
   runtime helper (`rt/resolve-arg`). Laziness needs no `:lazy` flag:
   scalar `:ref` args arrive as thunks, and `:seq` args as UNCHUNKED
   lazy-seqs (`compile/resolve-seq-items`). So an impl's native Clojure
   control flow — `if` / `and` / `or` / `case` — forces only what it
   reaches; references in un-taken branches never run, side effects
   and all. `case-fn` exploits scalar laziness — force the lookup,
   defer the `default` ref-thunk.

   `cond-fn` is the exception that needs more: its flat clause
   sequence must let an un-taken RESULT be stepped past without
   running. `:cond` marks `:clauses` in `:lazy-seq-args`, so each item
   arrives as a `delay` (`compile/resolve-seq-thunks`) — `cond-fn`
   forces tests and the one winning result, `nnext`-skips the rest.

   The `:const` / `:case` / `:cond` / `:coalesce` type-rules live here
   as plain `defn`s, each wired into the `impls` map as
   `{:impl … :return-type-rule …}` and looked up by the type-checker
   through the rich-types registry."
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [graphden.types.core :as types]))


;; === Logic ===

(defbase and-fn [values]
  (every? identity values))


(defbase or-fn [values]
  (boolean (some identity values)))


(defbase not-fn [value]
  (not value))


(defbase some?-fn [value]
  (some? value))


(defbase nil?-fn [value]
  (nil? value))


;; === Conditionals ===

(defbase if-fn
  "Lazy if: only the chosen branch's ref-thunk is invoked. Clojure's
   native `if` guarantees only one branch's arg reference is evaluated,
   and `rt/resolve-arg` (injected by the macro) handles both new-style
   thunks and legacy IDeref delays."
  [test then else]
  (if test then else))


(defbase cond-fn
  "Multi-branch conditional over a FLAT clause sequence
   `[test1 result1 test2 result2 …]` — returns the first result whose
   test is truthy, nil if none match. Use a literal `true` test as the
   final else-branch.

   `:clauses` is `:cond`'s `:lazy-seq-args` slot, so every item arrives
   as a `delay` (`compile/resolve-seq-thunks`). The loop forces a test
   delay; on a falsy test it `nnext`-steps past BOTH that test and its
   result WITHOUT forcing the result — an un-taken clause's result
   (side effects and all) is never executed. Only the winning result
   delay is ever forced."
  [clauses]
  (loop [s (seq clauses)]
    (when s
      (if (force (first s))
        (force (first (next s)))
        (recur (nnext s))))))


(def ^:private case-miss
  "Unique sentinel — lets `case-fn` tell 'no clause matched' apart from
   a clause whose result is itself nil, so `default` is forced only on
   a genuine miss."
  (Object.))


(defbase case-fn
  "Dispatches on value. Clauses is a map {match-value result ...}.
   Returns result for matching value, or default if no match.

   Lazy: `default` is referenced only on the no-match branch, so its
   ref-thunk fires solely when no clause matches."
  [value clauses default]
  (let [r (get clauses value case-miss)]
    (if (identical? r case-miss) default r)))


;; === Defaults ===

(defbase coalesce [value default]
  (or value default))


;; === Constants ===

(defbase const [value]
  value)


(defbase equal?-fn [a b]
  (= a b))


;; === Type-rules ===
;; :const / :identity — `(:const :value V)` returns V. `:identity`
;; is `:parent :const` so the rule covers both via the type-checker's
;; `root-base-fn-name` walk. Used by graphden renames
;; (`:value {:as :request :type :ring-request-shape}` → `:ring-request`
;; exposes `:value` as the free arg `:request`, type flows through).
;; Without this rule the result type defaults to the polymorphic `'a`
;; and all structural propagation upstream is lost. The standard
;; polymorphic `'a → 'a` rule SHOULD work via unify, but type-var
;; binding only kicks in when the slot's `:type` is consulted at the
;; rule level — wiring it explicitly here surfaces the type-aware
;; rename without depending on subst lookup.

(defn const-return-rule
  [bindings-info default-ret]
  (or (get-in bindings-info [:value :type]) default-ret))


;; :case — `(get clauses value default)`. A `:case` returns whichever
;; clause result matched, or `:default` on a miss. The base-fn declares
;; `:return-type a` shared with `:default`, so without a rule a `:case`
;; whose CLAUSES yield a type different from `:default`'s is mis-typed
;; as just `:default`'s type. When `:clauses` is a literal map (it
;; classifies as a record) or a homogeneous `[:map K V]`, the rule
;; unions every reachable result — each clause value PLUS `:default` —
;; so the return covers what the fn can actually produce. Falls back
;; to the declared shape when `:clauses` is opaque (`:jsonb`, or a
;; ref whose return isn't a map).

(defn case-return-rule
  [bindings-info default-ret]
  (let [clauses-t   (get-in bindings-info [:clauses :type])
        clause-rets (cond
                      (types/record-type? clauses-t) (vec (vals clauses-t))
                      (types/map-type? clauses-t)     [(types/map-val clauses-t)]
                      :else                           nil)]
    (if (seq clause-rets)
      (types/make-union (conj clause-rets
                              (get-in bindings-info [:default :type] :any)))
      default-ret)))


;; :cond — flat `[test1 result1 test2 result2 …]`. The whole `:cond`
;; evaluates to whichever RESULT branch matched, so its return type is
;; the union of every result-position type — exactly `:if`'s
;; `[:union then else]`, generalised to N branches. Results sit at the
;; ODD indices of `:clauses`' `:elem-types`.
;;
;; A `:cond` whose tests are all falsy yields nil, so `:null` joins
;; the union UNLESS the cond is exhaustive — i.e. some even-index test
;; is the literal `true` (the else-branch marker), which guarantees a
;; clause always fires. When `:clauses` is an opaque ref (no per-item
;; types) the return falls back to the declared `:any`.

(defn- literal-true?
  "True iff a clause-test item is the literal boolean `true` — the
   `:cond` else-branch marker that makes the cond exhaustive."
  [item]
  (or (true? item)
      (and (map? item) (true? (:value item)))))


(defn cond-return-rule
  [bindings-info default-ret]
  (let [info       (get bindings-info :clauses)
        elem-types (:elem-types info)
        clause-val (:value info)
        results    (when (sequential? elem-types)
                     (vec (keep-indexed (fn [i t] (when (odd? i) t))
                                        elem-types)))
        exhaustive? (and (sequential? clause-val)
                         (boolean
                           (some literal-true?
                                 (keep-indexed
                                   (fn [i item] (when (even? i) item))
                                   clause-val))))]
    (if (seq results)
      (types/make-union (cond-> results (not exhaustive?) (conj :null)))
      default-ret)))


;; :coalesce — `(or value default)`, the null-eliminator. `:value` is
;; `:any` (you can coalesce any maybe-nil value); the rule strips
;; `:null` from `:value`'s type and unions the result with
;; `:default`'s. So a `[:union :null T]` value coalesced with a `T`
;; default is typed `T` — the explicit point where a nullable type
;; narrows back to non-null. Falls back to the declared `a` when a
;; binding type is unavailable.

(defn coalesce-return-rule
  [bindings-info default-ret]
  (let [vt (get-in bindings-info [:value :type])
        dt (get-in bindings-info [:default :type])]
    (cond
      (not (and vt dt)) default-ret
      ;; `:value` statically always nil → the result is always default.
      (= vt :null)      dt
      (types/union-type? vt)
      (types/make-union (conj (vec (remove #{:null} (types/union-members vt)))
                              dt))
      :else             (types/make-union [vt dt]))))


;; === Registry ===
;; A value is either a bare impl fn or a `{:impl … :*-rule …}` map.

(def impls
  "Map of fn-name → impl-fn (or `{:impl … :*-rule …}`)"
  {:and and-fn
   :or or-fn
   :not not-fn
   :some? some?-fn
   :nil? nil?-fn
   :if if-fn
   :cond {:impl cond-fn
          :return-type-rule cond-return-rule
          :lazy-seq-args #{:clauses}}
   :case {:impl case-fn :return-type-rule case-return-rule}
   :coalesce {:impl coalesce :return-type-rule coalesce-return-rule}
   :const {:impl const :return-type-rule const-return-rule}
   :equal? equal?-fn})
