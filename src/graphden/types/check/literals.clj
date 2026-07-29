(ns graphden.types.check.literals
  "Pure literal-and-refinement reasoning + shape introspection helpers.

   Split out of `graphden.types.check` because every function here is
   PURE (no dynamic vars, no global state, no recursion into the
   type-checker) and FIVE namespaces outside the type-checker call
   into this surface directly:

   - `executor.registry.core` — `literal-satisfies-refinement?` (via
     `requiring-resolve`) + `constraint-compatible-with-base?`
   - `crud.type_check` — `classify-literal`, `literal-satisfies-refinement?`
   - `crud.validation` — `constraint-compatible-with-base?`
   - `crud.value_form` — `classify-literal`, `literal-satisfies-refinement?`
   - `web.crud.impls` (defbase) — `diff-value-against-type`,
     `closed-enum-of`, `fn-type-bound-effects`

   Two sub-concerns share this file:

   1. **Literal / refinement satisfaction** —
      `classify-literal`, `diff-value-against-type`, `combine-and/or`,
      `literal-satisfies-refinement?`, `base-allowed-ops`,
      `constraint-compatible-with-base?`. The foundational
      'does this value satisfy this constraint?' surface.
   2. **Editor-surface helpers** — `fn-type-bound-effects` +
      `closed-enum-of`. Server projections of type-shape info
      consumed by the editor's provenance / mismatch popovers; they
      live next to (1) because they walk the same type AST shape
      without any of the type-checker's stateful machinery.

   Main `graphden.types.check` requires this ns and re-uses
   `classify-literal` + `literal-satisfies-refinement?` from inside
   the check loop. One-way dep — main check doesn't expose
   anything literals needs."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.types.core :as types]))


(defn classify-literal
  "Infer the type of a literal Clojure value. Recursively walks maps
   and vectors so a literal classifies into its STRUCTURAL type, not
   the flat `:jsonb` catch-all — that lets a literal map type-check
   against a declared record-type (`:security-headers-shape`, etc.)
   instead of silently failing `:jsonb ⊄ <record>`.

   - keyword-keyed non-empty map → record-type `{k (classify v) …}`
   - string-keyed map with homogeneous value type → `[:map :text V]`
     (e.g. `{\"Content-Type\" \"text/html\"}` → `[:map :text :text]`).
     Lets header-map literals type-check against `:ring-response`'s
     `[:map :text :text]` slot.
   - mixed-keyed map, or empty map → `:jsonb` (a genuine generic
     JSON object — neither shape carries enough evidence)
   - vector → `[:list T]` where T is the least-upper-bound of the
     items' types (`:any` when items disagree or the vector is empty)

   Returns nil if the shape isn't a recognised literal (callers fall
   back to `:any` or skip type-checking the binding)."
  [v]
  (cond
    (nil? v)         :null
    (boolean? v)     :bool
    (integer? v)     :int
    (float? v)       :float
    (string? v)      :text
    (keyword? v)     :keyword
    (uuid? v)        :uuid
    (map? v)         (cond
                       (empty? v) :empty-map
                       (every? keyword? (keys v))
                       (into {}
                             (map (fn [[k fv]]
                                    [k (or (classify-literal fv) :any)]))
                             v)
                       (every? string? (keys v))
                       (let [val-types (into #{} (map #(or (classify-literal %) :any))
                                             (vals v))]
                         (if (= 1 (count val-types))
                           [:map :text (first val-types)]
                           :jsonb))
                       :else :jsonb)
    (vector? v)      (let [elems (into #{} (map #(or (classify-literal %) :any)) v)]
                       [:list (if (= 1 (count elems)) (first elems) :any)])
    :else            nil))


(defn fn-type-bound-effects
  "When `expected` resolves to a 4-arity fn-type `[:fn args ret eff]`
   with a CONCRETE `eff` set (not `:any`), return the eff entries as
   a vec of plain strings (lower-case, no leading colon — same
   convention the editor's effect-chip CSS classes use). Returns nil
   for any other shape (the popover suppresses the section).

   Mirrors the editor's slot-effect-bound surface from
   `editor-provenance-popover.js` — surfaces the slot-level effect
   constraint server-side so a closed-over fn-graph doesn't have to
   re-implement the `[:fn …]`-shape parse in JS."
  [expected]
  (let [t (or (types/resolve-alias expected) expected)]
    (when (and (vector? t) (= :fn (first t)) (= 4 (count t)))
      (let [eff (nth t 3)]
        (when-not (#{:any "any" :_any} eff)
          (cond
            (sequential? eff)
            (vec (map (fn [e]
                        (let [s (if (keyword? e) (name e) (str e))]
                          (str/replace s #"^:" "")))
                      eff))
            (set? eff)
            (vec (sort (map (fn [e]
                              (let [s (if (keyword? e) (name e) (str e))]
                                (str/replace s #"^:" "")))
                            eff)))
            :else nil))))))


(defn closed-enum-of
  "When `expected` resolves to a closed-enum refinement —
   `[:refine base [:in [m₁ m₂ …]]]` — return `{:base :members}` with
   members sorted and colon-prefixed for `:keyword`-based enums.
   nil for any other shape (the popover suppresses the section).

   Mirrors the editor's `closedEnumOf` so the provenance / mismatch
   popovers can surface the allowed-values list server-side instead
   of duplicating the type-walk in JS."
  [expected]
  (let [t (or (types/resolve-alias expected) expected)]
    (when (and (vector? t) (= :refine (first t)))
      (let [base (nth t 1)
            c    (nth t 2)]
        (when (and (vector? c) (= :in (first c)) (sequential? (second c)))
          (let [keyword-base? (or (= :keyword base) (= "keyword" base))
                members       (->> (second c)
                                   (map str)
                                   sort
                                   (mapv (fn [m]
                                           (let [lit (if (and keyword-base?
                                                              (not (str/starts-with? m ":")))
                                                       (str ":" m)
                                                       m)]
                                             {:value lit :label lit}))))]
            {:base base :members members}))))))


(declare literal-satisfies-refinement?)


(defn diff-value-against-type
  "Walk a literal value against an expected type expression, returning
   the leaf-level disagreements as `[{:path :expected :actual}, …]`.
   Empty vector when the value satisfies the type. Used by the mismatch-
   explainer popover to point users at the EXACT failing field / element
   instead of leaving them to spot the diff between two structural types
   by eye. Mirrors `subtype?` on `classify-literal`'s output, but with
   leaf-level locality.

   Recurses into `:list` / `:map` / `:tuple` / record / `:refine` /
   `:union` (best-near-miss branch). `:any` / `:jsonb` return empty
   (no constraint). Primitive types check via `subtype?` on the
   classified value."
  ([value expected] (diff-value-against-type value expected ""))
  ([value expected path]
   (let [exp (or (types/resolve-alias expected) expected)
         leaf (fn [actual] [{:path path :expected exp :actual actual}])]
     (cond
       (or (= :any exp) (= :jsonb exp))
       []

       (and (vector? exp) (= :union (first exp)))
       (let [branches (rest exp)]
         (loop [bs branches best nil]
           (if (empty? bs)
             (or best [])
             (let [leaves (diff-value-against-type value (first bs) path)]
               (cond
                 (empty? leaves) []
                 (or (nil? best) (< (count leaves) (count best)))
                 (recur (rest bs) leaves)
                 :else (recur (rest bs) best))))))

       (and (vector? exp) (= :refine (first exp)))
       (let [[_ base constraint] exp
             base-leaves (diff-value-against-type value base path)]
         (if (seq base-leaves)
           base-leaves
           (let [sat (try (literal-satisfies-refinement? value constraint)
                          (catch Exception e
                            ;; A THROWING predicate is a malformed
                            ;; constraint or an evaluator bug — not
                            ;; the benign "operator we can't evaluate
                            ;; statically" case (that returns
                            ;; :unknown itself). Accept conservatively
                            ;; (never reject a value because the
                            ;; CHECKER crashed) but say so — silently
                            ;; equating a crash with a pass disabled
                            ;; the refinement with no trace.
                            (log/warn e "refinement predicate threw during literal check — accepting conservatively"
                                      {:constraint constraint :value value})
                            :unknown))]
             (if (false? sat) (leaf (classify-literal value)) []))))

       (and (vector? exp) (= :list (first exp)))
       (if-not (sequential? value)
         (leaf (classify-literal value))
         (let [[_ elem-type] exp]
           (vec (mapcat (fn [i v]
                          (diff-value-against-type v elem-type (str path "[" i "]")))
                        (range) value))))

       (and (vector? exp) (= :map (first exp)))
       (if-not (map? value)
         (leaf (classify-literal value))
         (let [v-type (nth exp 2)]
           (vec (mapcat (fn [[k v]]
                          (diff-value-against-type v v-type (str path "." (name k))))
                        value))))

       (and (vector? exp) (= :tuple (first exp)))
       (let [elems (rest exp)]
         (cond
           (not (sequential? value))
           (leaf (classify-literal value))
           (not= (count elems) (count value))
           (leaf (str "tuple of length " (count value)))
           :else
           (vec (mapcat (fn [i v t]
                          (diff-value-against-type v t (str path "[" i "]")))
                        (range) value elems))))

       (map? exp)
       (if-not (map? value)
         (leaf (classify-literal value))
         (vec (mapcat (fn [[k field-type]]
                        (if-not (contains? value k)
                          [{:path (str path "." (name k))
                            :expected field-type :actual "missing"}]
                          (diff-value-against-type (get value k) field-type
                                                   (str path "." (name k)))))
                      exp)))

       (or (keyword? exp) (string? exp))
       (let [actual (classify-literal value)]
         (if (and (some? actual) (not (types/subtype? actual exp)))
           (leaf actual)
           []))

       :else []))))


(defn- combine-and
  [results]
  ;; All true → true. Any false → false. Otherwise → :unknown
  ;; (so a partially-decidable conjunction defers).
  (cond
    (some false? results) false
    (every? true? results) true
    :else                  :unknown))


(defn- combine-or
  [results]
  (cond
    (some true? results)   true
    (every? false? results) false
    :else                  :unknown))


(defn literal-satisfies-refinement?
  "Evaluate a refinement's constraint against a known literal value.
   Returns true / false / `:unknown` (when the constraint shape isn't
   one we can decide statically — the caller should then defer to a
   runtime `:validate-refinement` rather than reject).

   Recognised constraints:
     [:>  N]   [:>= N]   [:<  N]   [:<= N]   [:=  N]   [:not= V]
     [:in #{…vs}]                            (membership)
     [:matches #\"regex\"] / [:matches \"regex\"] (text — decided vs a string
                                             literal; bad pattern → :unknown)
     [:and c1 c2 …]   [:or c1 c2 …]          (compound — eagerly decided)
   Unknown shapes → `:unknown`."
  [v constraint]
  (cond
    (not (vector? constraint))             :unknown
    (and (= 1 (count constraint))
         (#{:and :or} (first constraint)))
    ;; Empty :and is true by convention, empty :or is false.
    (case (first constraint) :and true :or false)
    (#{:and :or} (first constraint))
    (let [op (first constraint)
          children (rest constraint)
          results (mapv #(literal-satisfies-refinement? v %) children)]
      (case op
        :and (combine-and results)
        :or  (combine-or  results)))
    (= 2 (count constraint))
    (let [[op rhs] constraint]
      (case op
        :>     (and (number? v) (number? rhs) (> v rhs))
        :>=    (and (number? v) (number? rhs) (>= v rhs))
        :<     (and (number? v) (number? rhs) (< v rhs))
        :<=    (and (number? v) (number? rhs) (<= v rhs))
        :=     (= v rhs)
        :not=  (not= v rhs)
        ;; `:matches P` — decide a regex refinement against a string literal
        ;; at build time (was previously deferred as :unknown, so a bad
        ;; `:url` / `:non-blank-text` literal slipped through to runtime). `P`
        ;; is authored as a string in fns.edn (`[:matches "^https?://"]`) but
        ;; tests/callers may pass a compiled `#"…"` Pattern, so accept both. A
        ;; non-string value or an uncompilable pattern stays `:unknown` (defer
        ;; to the runtime validator) rather than risk a false rejection.
        :matches (let [pat (cond
                             (instance? java.util.regex.Pattern rhs) rhs
                             (string? rhs) (try (re-pattern rhs)
                                                (catch Exception _ nil)))]
                   (if (and pat (string? v))
                     (boolean (re-find pat v))
                     :unknown))
        ;; `:in` operands are authored as either a set or a vector
        ;; (`[:in [:get :post …]]` in app/forms + app/branches). Coerce
        ;; to a set so a valid member isn't spuriously rejected —
        ;; `contains?` on a vector tests INDEX, not membership. Mirrors
        ;; `atom-implies?`/`closed-enum-of`, which already accept `coll?`.
        :in    (and (coll? rhs) (contains? (set rhs) v))
        :unknown))
    :else                                  :unknown))


(def ^:private ^{:doc "Comparison ops only valid on ordered numeric types."}
  numeric-ops
  #{:> :>= :< :<= := :not= :in})


(def ^:private ^{:doc "Ops that only need equality / membership semantics —
  valid on any base type (text, keyword, bool, null included)."}
  equality-ops
  #{:= :not= :in})


(def ^:private ^{:doc "Ops valid for text-only constraints (regex matching)."}
  text-only-ops
  #{:matches})


(defn- base-allowed-ops
  "Which atomic constraint operators are legal on a given base type.
   Defensive default: an unknown base permits every op (no rejection
   for type-rows we don't model yet)."
  [base]
  (case base
    (:int :numeric :float :decimal)  (into numeric-ops text-only-ops)
    :text                            (into equality-ops text-only-ops)
    (:bool :keyword :null
           :uuid :timestamptz)       equality-ops
    nil                              #{} ; nil base — reject everything
    :any))


(defn constraint-compatible-with-base?
  "Sync-time check that a refinement's `:constraint` uses only ops
   the base type can support semantically. Returns `true` when every
   atomic operator under :and / :or fits the base, `false` otherwise.

   Caller (`validate-fn-def!`) raises a clear error rather than let
   the row land in storage where it'd later confuse type-checking
   and runtime narrowing.

   Examples:
     ✓ `{:base :int  :constraint [:> 0]}`
     ✓ `{:base :text :constraint [:matches #\"\\d+\"]}`
     ✗ `{:base :text :constraint [:>= 0]}`     ← `>=` undefined on text
     ✗ `{:base :bool :constraint [:< 5]}`      ← ordering on bool
     ✗ `{:base :null :constraint [:not= 1]}`   ← :null only equals :null"
  [base constraint]
  (let [allowed (base-allowed-ops base)]
    (cond
      (or (= allowed :any) (not (vector? constraint))) true
      (#{:and :or} (first constraint))
      (every? #(constraint-compatible-with-base? base %) (rest constraint))
      :else
      (contains? allowed (first constraint)))))
