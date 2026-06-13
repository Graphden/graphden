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
    [graphden.executor.registry.core :as registry]
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


;; === Type predicate ===
;;
;; ONE primitive instead of N per-type predicates. Dispatches on the
;; `:type` arg (a canonical type-tag keyword like `:keyword` / `:int`
;; / `:map`) to the runtime check in `types.core/runtime-predicates`.
;; That map is the single source of truth — it's colocated with the
;; `primitives` set in `types/core.clj` and a startup assert ensures
;; coverage, so adding a new primitive type also requires adding the
;; runtime check in one place.

(defbase is-a?-fn
  "True iff `value` is an instance of the primitive type `type`.
   `type` is a canonical type-tag from
   `graphden.types.core/runtime-predicates` — e.g. `:keyword`, `:int`,
   `:numeric`, `:text`, `:map`, `:vector`, `:sequence`, `:bool`,
   `:null`, `:any`. Throws on unknown tags so typos fail loudly."
  [value type]
  (let [pred (get types/runtime-predicates type)]
    (when-not pred
      (throw (ex-info (str "Unknown type tag: " (pr-str type))
                      {:type :execution-error/unknown-type-tag
                       :type-tag type
                       :supported (set (keys types/runtime-predicates))})))
    (boolean (pred value))))


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
        (force (fnext s))
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

;; :if — when BOTH branches are non-negative integer literals, refine
;; the union `[:union :int :int] = :int` into `:non-negative-int`
;; (`:positive-int` when both are strictly positive). Matters at
;; `:drop :count` (and similar) where the slot type is `:non-negative-int`
;; and the inherited fn-def is a literal pick — without the refinement
;; the `:int` computed return is correctly rejected as a widening, even
;; though both literal branches DO satisfy the slot's constraint. Pure
;; numeric refinement; secret-flow handled by `wrap-with-taint`.

(defn- if-literal-int-branch
  [info]
  (let [lit (:value info)]
    (when (integer? lit) lit)))


(defn- root-base-fn-name
  "Walk a fn-ref's `:primary-parent` chain to the root base-fn. Mirrors
   `graphden.types.check/root-base-fn-name` but reusing the registry
   only — no internal dependency on the type-checker namespace
   (which would create a cycle: types.check → core/logic via the rule)."
  [name]
  (loop [n name seen #{}]
    (cond
      (or (nil? n) (contains? seen n)) n
      :else (let [parent (:primary-parent (registry/rich-type-of n))]
              (if (or (nil? parent) (= parent n))
                n
                (recur parent (conj seen n)))))))


(defn- predicate-of-ref
  "If `test-ref` (a fn-ref keyword) computes `(:some? :_x)` or
   `(:nil? :_x)`, return `[:some? ref-keyword]` or
   `[:nil? ref-keyword]`. Otherwise nil.

   Walks the test-ref's `primary-parent` chain to find the root
   base-fn (must be `:some?` / `:nil?`), then reads its `:value`
   slot from `:resolved-bindings` and pulls the ref keyword from
   `:ref`. The accumulated `:resolved-bindings` already inherits
   from every parent in the chain (closer-fn-wins, per
   `record-result!` in types/check.clj), so a multi-step shim like
   `:_my-pred :parent :_some-x?-shim :parent :some?` is found
   correctly at the deepest level."
  [test-ref]
  (when test-ref
    (when-let [info (registry/rich-type-of test-ref)]
      (let [root (root-base-fn-name test-ref)
            rb (:resolved-bindings info {})
            value-binding (get rb :value)
            target (:ref value-binding)]
        (when (and target (#{:some? :nil?} root))
          [root target])))))


(defn- strip-null-from-union
  "Remove `:null` members from a top-level union. For non-union
   inputs that are exactly `:null`, return `:never`. Anything else
   passes through unchanged. Conservative: a `[:secret …]`-wrapped
   nullable would NOT have `:null` at the top level (the secret
   marker wraps the whole union), so this function leaves it intact
   — no chance of accidentally stripping the taint marker."
  [t]
  (cond
    (types/union-type? t)
    (let [members (vec (remove #{:null} (types/union-members t)))]
      (cond
        (empty? members) :never
        (= 1 (count members)) (first members)
        :else (types/make-union members)))
    (= t :null) :never
    :else t))


(defn if-return-rule
  [bindings-info default-ret]
  (let [then-info (get bindings-info :then)
        else-info (get bindings-info :else)
        then-lit (if-literal-int-branch then-info)
        else-lit (if-literal-int-branch else-info)]
    (cond
      (and (integer? then-lit) (integer? else-lit) (pos? then-lit) (pos? else-lit))
      :positive-int

      (and (integer? then-lit) (integer? else-lit) (not (neg? then-lit)) (not (neg? else-lit)))
      :non-negative-int

      :else
      ;; Flow-sensitive narrowing via `:some?` / `:nil?` predicate.
      ;; When `:test` is `(:some? :_x)` AND `:then` is bound to
      ;; `:_x` directly, narrow `:then`'s type by stripping `:null`
      ;; (`:_x` is provably non-nil in the truthy branch). Symmetric
      ;; for `:else`. Same for `:nil?`, flipped.
      ;;
      ;; Laziness is preserved — `if-fn` itself only evaluates the
      ;; taken branch; this rule reasons about types only. Secret-
      ;; tainted values stay tainted: a `[:secret [:union :null T]]`
      ;; wraps `:null` inside the marker, so `strip-null-from-union`
      ;; never reaches it (it only strips top-level union members).
      (let [test-ref (:ref (get bindings-info :test))
            pred (predicate-of-ref test-ref)]
        (if pred
          (let [[pred-kind target-ref] pred
                then-ref (:ref then-info)
                else-ref (:ref else-info)
                narrowed-then (when (= then-ref target-ref)
                                (if (= pred-kind :some?)
                                  (strip-null-from-union (:type then-info))
                                  :null))
                narrowed-else (when (= else-ref target-ref)
                                (if (= pred-kind :some?)
                                  :null
                                  (strip-null-from-union (:type else-info))))]
            (if (or narrowed-then narrowed-else)
              (types/make-union [(or narrowed-then (:type then-info))
                                 (or narrowed-else (:type else-info))])
              default-ret))
          default-ret)))))


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


(defn- narrow-clause-result
  "Per-clause flow-sensitive narrowing. When the clause's predicate
   is `(:some? :_x)` / `(:nil? :_x)` AND the result-form is the same
   `:_x` ref directly, narrow the recorded result type accordingly
   (strip `:null` for `:some?`-truthy / `:nil?`-falsy, replace with
   `:null` for `:nil?`-truthy / `:some?`-falsy). Otherwise return
   the recorded type unchanged.

   Same conservatism as `if-return-rule`'s narrowing: secret-tainted
   nullables (`[:secret [:union :null T]]`) never have `:null` at
   the top level, so `strip-null-from-union` won't reach inside the
   marker. Laziness is preserved — `:cond` short-circuits at the
   IMPL level (`:lazy-seq-args` on `:clauses`); this rule is types-
   only."
  [pred-form result-form recorded-t]
  (if (and (keyword? pred-form) (keyword? result-form))
    (if-let [[pred-kind target-ref] (predicate-of-ref pred-form)]
      (if (= target-ref result-form)
        (case pred-kind
          :some? (strip-null-from-union recorded-t)
          :nil?  :null)
        recorded-t)
      recorded-t)
    recorded-t))


(defn cond-return-rule
  [bindings-info default-ret]
  (let [info       (get bindings-info :clauses)
        elem-types (:elem-types info)
        clause-val (:value info)]
    ;; `:cond :clauses` is a flat `[test1 result1 test2 result2 …]`
    ;; sequence — odd-length silently behaves as "last item is a
    ;; test with no corresponding result", which `cond-fn` then
    ;; forces via `(force (second s))` on a nil tail → all
    ;; runtime returns become nil regardless of the test value.
    ;; Empty-length silently always returns nil at runtime — also a
    ;; bug (zero clauses is never what the author meant). Catch
    ;; both at sync-time when the clause-val is a literal vector we
    ;; can count statically.
    (when (and (sequential? clause-val)
               (or (zero? (count clause-val))
                   (odd? (count clause-val))))
      (throw (ex-info
               (str ":cond :clauses must have an even non-zero number of items"
                    " — saw " (count clause-val)
                    " (a flat [test1 result1 test2 result2 …] sequence)."
                    (cond
                      (zero? (count clause-val))
                      " Zero clauses always returns nil at runtime."
                      :else
                      " Last item is treated as a test with no result and every match silently returns nil at runtime."))
               {:type :bindings/cond-bad-clause-count
                :clause-count (count clause-val)
                :clauses clause-val})))
    (let [;; Per-clause narrowing: at odd index i (result position),
          ;; the predicate is the binding-form at (i-1). When that
          ;; predicate is a `:some?`/`:nil?` of the same ref the
          ;; result is, the result's recorded type narrows.
          results    (when (and (sequential? elem-types) (sequential? clause-val))
                       (vec (keep-indexed
                              (fn [i t]
                                (when (odd? i)
                                  (narrow-clause-result
                                    (nth clause-val (dec i) nil)
                                    (nth clause-val i nil)
                                    t)))
                              elem-types)))
          exhaustive? (and (sequential? clause-val)
                           (boolean
                             (some literal-true?
                                   (keep-indexed
                                     (fn [i item] (when (even? i) item))
                                     clause-val))))]
      (if (seq results)
        (types/make-union (cond-> results (not exhaustive?) (conj :null)))
        default-ret))))


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

;; Every logic op is content-passing — `:and` / `:or` / `:coalesce`
;; return their input; `:if` / `:cond` / `:case` pick a branch and
;; expose its result; `:not` / `:some?` / `:nil?` / `:equal?` return
;; bools but the SHAPE of the bool is sensitive to secret content.
;; All wrap their existing rule (or get a bare propagator) so a
;; tainted input lifts the result into `[:secret …]`.
(def impls
  {:and {:impl and-fn :return-type-rule (types/wrap-with-taint nil)}
   :or {:impl or-fn :return-type-rule (types/wrap-with-taint nil)}
   :not {:impl not-fn :return-type-rule (types/wrap-with-taint nil)}
   :some? {:impl some?-fn :return-type-rule (types/wrap-with-taint nil)}
   :nil? {:impl nil?-fn :return-type-rule (types/wrap-with-taint nil)}
   :if {:impl if-fn :return-type-rule (types/wrap-with-taint if-return-rule)}
   :cond {:impl cond-fn
          :return-type-rule (types/wrap-with-taint cond-return-rule)
          :lazy-seq-args #{:clauses}}
   :case {:impl case-fn :return-type-rule (types/wrap-with-taint case-return-rule)}
   :coalesce {:impl coalesce :return-type-rule (types/wrap-with-taint coalesce-return-rule)}
   :const {:impl const :return-type-rule (types/wrap-with-taint const-return-rule)}
   :equal? {:impl equal?-fn :return-type-rule (types/wrap-with-taint nil)}
   :is-a? {:impl is-a?-fn :return-type-rule (types/wrap-with-taint nil)}})
