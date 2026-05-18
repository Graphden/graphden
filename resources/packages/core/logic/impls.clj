(ns graphden.packages.core.logic.impls
  "Implementations for core/logic base functions.

   Migrated to `defbase` — arg symbols resolve at use site via the
   runtime helper (`rt/resolve-arg`). Laziness needs no `:lazy` flag:
   scalar `:ref` args arrive as thunks, and `:seq` args as UNCHUNKED
   lazy-seqs (`compile/resolve-seq-items`). So an impl's native Clojure
   control flow — `if` / `and` / `or` / `cond` / `case` — forces only
   what it reaches; references in un-taken branches never run, side
   effects and all. `cond-fn` / `case-fn` are written to exploit this
   (force the test / lookup, defer the result / default).

   `:const`'s type-rule (moved verbatim from `graphden.types.rules`)
   lives here as a `defn` and is wired into the `impls` map as
   `{:impl … :return-type-rule …}`."
  (:require
    [graphden.executor.defbase :refer [defbase]]))


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
  "Evaluates clauses as [[test1 result1] [test2 result2] ...].
   Returns first result where test is truthy, or nil if none match.

   Lazy: `clauses` is an unchunked lazy-seq, and each clause likewise.
   The loop forces a clause's test via `(first clause)`; the result
   `(second clause)` is forced ONLY in the branch that wins. A
   side-effecting un-taken clause is never run."
  [clauses]
  (loop [remaining (seq clauses)]
    (when remaining
      (let [clause (first remaining)]
        (if (first clause)
          (second clause)
          (recur (next remaining)))))))


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
   :cond cond-fn
   :case case-fn
   :coalesce coalesce
   :const {:impl const :return-type-rule const-return-rule}
   :equal? equal?-fn})
