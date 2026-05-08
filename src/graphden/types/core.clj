(ns graphden.types.core
  "Type system core — representation, subtyping, unification.

   Phase 2 of the plan in docs/TYPES.md. Pure data + pure functions —
   no storage, no executor coupling. Other layers (registry, executor)
   call into these primitives.

   ## Representation

   A type is one of:

     keyword  :int :text :bool :float :keyword :jsonb :any :null :uuid
              :timestamptz :bytes :numeric                        — primitive
     symbol   'a 'b                                               — type variable
     map      {:field-name field-type}                            — record (open: more fields = subtype)
     vector   [:list elem-type]                                   — parameterised list
              [:fn args-map ret-type]                             — function: args-map is {arg-name arg-type}

   Compound types nest freely:
     [:fn {:f [:fn {:x 'a} 'b], :coll [:list 'a]} [:list 'b]]    — type of `map`

   ## Why these choices

   - Keywords are already used throughout for primitives — backwards
     compatible with the existing :type enum field.
   - Symbols cleanly distinguish type vars from primitive keywords.
     Clojure prints `'a` as `a` which is unambiguous in the doc.
   - Vectors-with-keyword-head match Clojure's spec/malli convention
     for parameterised constructors. Easy to dispatch on `(first t)`.
   - Records as plain Clojure maps — no constructor wrapper — because
     a record IS a structural map; field-name → field-type is exactly
     what we need.

   ## Subtyping rules (from docs/TYPES.md)

     t ⊆ :any                                              always
     primitive ⊆ :jsonb                                    one-way
     record ⊆ :jsonb                                       one-way
     list ⊆ :jsonb                                         one-way
     {a :int b :text} ⊆ {a :int}                           more fields = subtype
     [:list :a] ⊆ [:list :b]  iff  :a ⊆ :b                 covariant
     [:fn args ret] ⊆ [:fn args' ret']  iff
         every arg-name in args' has args ⊇ args' on it    contravariant
         and ret ⊆ ret'                                    covariant
     :jsonb ⊄ any concrete type                            requires explicit conversion"
  (:refer-clojure :exclude [resolve]))


;; -----------------------------------------------------------------------------
;; Predicates

(def primitives
  "The flat enum of value-kind primitives. Mirrors
   `value-kind-values` in schema/graph/schema.clj — keep these in
   sync (validated by registry sync-time check). `:fn` is included
   as a primitive for backwards compat with declarations that use
   the bare keyword instead of a structural `[:fn …]`; structurally,
   it acts like `[:fn {} :any]` (any callable). `:float` and
   `:keyword` are reserved for upcoming use."
  #{:null :uuid :text :int :bool :numeric :timestamptz :jsonb :bytes
    :any :fn :float :keyword :sequence})


(defn primitive?
  [t]
  (boolean (and (keyword? t) (primitives t))))


(defn type-var?
  [t]
  (symbol? t))


(defn record-type?
  [t]
  (and (map? t) (every? keyword? (keys t))))


(defn fn-type?
  "`[:fn args ret]` — three-element form, no effect constraint
   (callback may have any effects, traditional behaviour).

   `[:fn args ret effects]` — four-element form with a slot-level
   effect constraint. `effects` is a set of effect-category keywords
   (e.g. `#{}` for pure-only, `#{:io :time}` for read-only system
   state). At binding time the bound fn's `:effects` must be a
   subset of `effects`. Lets HOF slots like `:filter :pred` declare
   they require pure callbacks instead of silently letting effects
   bubble through `compute-effects` union."
  [t]
  (and (vector? t) (= :fn (first t)) (#{3 4} (count t))))


(defn list-type?
  [t]
  (and (vector? t) (= :list (first t)) (= 2 (count t))))


(defn refine-type?
  "`[:refine base-type constraint]` — a NAMED subtype of `base-type`
   carrying an opaque `constraint` payload (e.g. `[:gt 0]` for
   :positive-int). Phase 4 of TYPES.md.

   The constraint is opaque to subtype reasoning — two refinement
   types are subtype-related ONLY when they share the same
   constraint AND base. The system does NOT prove that `:positive-int`
   is a subtype of `[:refine :int [:gte 0]]`; it forces an explicit
   `:validate-refinement` conversion node instead."
  [t]
  (and (vector? t) (= :refine (first t)) (= 3 (count t))))


(defn union-type?
  "`[:union T1 T2 …]` — a sum / disjoint type. A value of union type
   is either a T1 or a T2 or … No tagged constructor: callers
   discriminate by runtime check (the executor's lenient :null /
   :any handling provides the practical escape).

   Unlike refinements, unions DO compose with subtyping rules:
     T ⊆ [:union …] iff T ⊆ Tᵢ for some i
     [:union …] ⊆ S iff every Tᵢ ⊆ S"
  [t]
  (and (vector? t) (= :union (first t)) (>= (count t) 2)))


(defn refine-base
  [t]
  (when (refine-type? t) (nth t 1)))


(defn refine-constraint
  [t]
  (when (refine-type? t) (nth t 2)))


(defn union-members
  "The list of branch types of a union (or nil for non-unions).
   Unions are flattened on construction (see `make-union`), so
   members are never themselves unions."
  [t]
  (when (union-type? t) (vec (rest t))))


(defn fn-args
  "{arg-name arg-type} of a function type."
  [t]
  (when (fn-type? t) (nth t 1)))


(defn fn-ret
  [t]
  (when (fn-type? t) (nth t 2)))


(defn fn-effects
  "Slot-level effect constraint of a fn-type. Returns the declared set
   when the 4-element form `[:fn args ret effects]` is in use; nil
   for the 3-element form (no constraint — backward compat: any
   effects allowed). nil and the universal sentinel `:any` both mean
   \"unconstrained\" at the subtype/unify check sites."
  [t]
  (when (and (fn-type? t) (= 4 (count t)))
    (nth t 3)))


(defn list-elem
  [t]
  (when (list-type? t) (nth t 1)))


;; -----------------------------------------------------------------------------
;; Validity check — anything else is malformed

(declare aliases-snapshot)


;; Validation-time scratch view. While `register-type-aliases-batch`
;; (or self-recursive `register-type-alias!`) is checking a body
;; with forward / mutual / self references, every pending name is
;; pre-bound here so `well-formed?`'s keyword check passes for
;; references that have no entry in the live atom yet. nil ⇒ fall
;; through to the live registry (the normal lookup path).
(def ^:dynamic *alias-view* nil)


(defn well-formed?
  "True iff `t` is a syntactically valid type. Use this as a guard at
   the boundary between user input (fns.edn parsing, API form data)
   and the rest of the type module.

   A non-primitive keyword is well-formed iff it names a registered
   alias — this lets compound types reference other named types
   (`[:fn {:request :ring-request} :ring-response]`) without first
   substituting them, as long as the inner names are known."
  [t]
  (cond
    (or (primitive? t) (type-var? t)) true
    (keyword? t)     (boolean (get (aliases-snapshot) t))
    (record-type? t) (every? well-formed? (vals t))
    (fn-type? t)     (and (map? (fn-args t))
                          (every? keyword? (keys (fn-args t)))
                          (every? well-formed? (vals (fn-args t)))
                          (well-formed? (fn-ret t))
                          (let [eff (fn-effects t)]
                            (or (nil? eff)
                                (= eff :any)
                                (and (set? eff)
                                     (every? keyword? eff)))))
    (list-type? t)   (well-formed? (list-elem t))
    (refine-type? t) (well-formed? (refine-base t))
    (union-type? t)  (every? well-formed? (union-members t))
    :else            false))


(defn make-union
  "Smart constructor for `[:union …]`. Flattens nested unions, drops
   duplicates, and collapses singletons (a 1-member union IS that
   member). Result: either a non-union type or a `[:union T1 T2 …]`
   with ≥2 distinct flattened members.

   Use this instead of building `[:union …]` literals directly so
   the rest of the system can rely on the canonical shape."
  [members]
  (let [flat (mapcat (fn [m] (if (union-type? m) (union-members m) [m]))
                     members)
        ;; Stable order to keep the canonical form deterministic for
        ;; equality. `pr-str` sorts heterogeneous keywords/vectors
        ;; without confusing comparators.
        unique (vec (sort-by pr-str (distinct flat)))]
    (cond
      (= 1 (count unique)) (first unique)
      (some #{:any} unique) :any        ; :any absorbs everything
      :else (into [:union] unique))))


(defn desugar-variant
  "Desugar `[:variant tag1 T1 tag2 T2 …]` to its structural union form
   per docs/TYPES.md § Tagged Variants:

     [:union {:tag [:refine :keyword [:= :tag1]] :value T1}
             {:tag [:refine :keyword [:= :tag2]] :value T2}
             …]

   Each branch is a record pinned by an equality refinement on the
   `:tag` slot, making the variant discriminable at runtime via
   `(:variant-is? v :tag …)`. Accepts the alternate input shape
   `[tag1 T1 tag2 T2 …]` (without the leading `:variant` marker) so
   callers don't have to strip it.

   Returns nil if the payload is malformed (odd count, non-keyword
   tags, empty list) so the caller can surface it as an alias-body
   well-formedness failure."
  [t]
  (let [pairs-raw (cond
                    (and (vector? t) (= :variant (first t))) (vec (rest t))
                    (sequential? t)                          (vec t)
                    :else                                    nil)]
    (when (and pairs-raw
               (seq pairs-raw)
               (even? (count pairs-raw))
               (every? keyword? (take-nth 2 pairs-raw)))
      (let [pairs (partition 2 pairs-raw)
            branches (mapv (fn [[tag-kw branch-t]]
                             {:tag [:refine :keyword [:= tag-kw]]
                              :value branch-t})
                           pairs)]
        (make-union branches)))))


;; -----------------------------------------------------------------------------
;; Type aliases — keyword → structural type
;;
;; Lets a base-fn / fn-def declare `:type :positive-int` and have
;; the system resolve it to `[:refine :int [:> 0]]`. Reuses the
;; existing keyword surface in `:type` slots; primitives shadow
;; aliases when the same keyword names both (so registering a
;; bogus alias for `:int` is a no-op).

(defonce ^:private type-aliases (atom {}))


(declare register-type-aliases-batch)


(defn register-type-alias!
  "Bind `alias-name` to a structural type. Throws if `alias-name` is
   already a primitive (would shadow it confusingly) or if `t` isn't
   well-formed. Idempotent — re-registering the same name replaces the
   binding.

   Self-recursive bodies (e.g. `[:list :tree]` for `alias-name = :tree`)
   are valid here: validation runs against a view that includes the
   name being registered, so `:tree` resolves as a known reference
   inside its own body. For mutual recursion across names, batch
   them via `register-type-aliases-batch`."
  [alias-name t]
  (when (primitives alias-name)
    (throw (ex-info (str "type-alias name shadows a primitive: " (pr-str alias-name))
                    {:type :types/invalid-alias
                     :name alias-name})))
  (binding [*alias-view* (assoc @type-aliases alias-name :any)]
    (when-not (well-formed? t)
      (throw (ex-info (str "type-alias body is not well-formed: " (pr-str t))
                      {:type :types/invalid-alias
                       :name alias-name :body t}))))
  (swap! type-aliases assoc alias-name t)
  alias-name)


(defn resolve-alias
  "Recursive keyword alias resolution. Primitives pass through;
   registered aliases expand to their structural body; other
   keywords (unknown — typically user error caught later) pass
   through too. Compound types ARE recursed into so an alias that
   appears inside `[:fn args ret]`, `[:list T]`, `{:k T}`, etc.
   gets expanded to its structural form — otherwise
   `well-formed?` would reject the wrapper for unknown keywords.

   `seen` tracks alias names already followed in the current chain
   so a circular alias (`:a → :b → :a`) breaks out instead of
   stack-overflowing — the offending name passes through
   unresolved, and the surrounding `well-formed?` check turns it
   into a clean error."
  ([t] (resolve-alias t #{}))
  ([t seen]
   (cond
     (or (primitive? t) (type-var? t)) t
     (keyword? t)     (if (contains? seen t)
                        t
                        (if-let [target (@type-aliases t)]
                          (resolve-alias target (conj seen t))
                          t))
     (fn-type? t)     (let [base [:fn
                                  (into {} (map (fn [[k v]] [k (resolve-alias v seen)])) (fn-args t))
                                  (resolve-alias (fn-ret t) seen)]
                            eff (fn-effects t)]
                        ;; Effect constraint passes through verbatim —
                        ;; it's a set of category keywords, not types
                        ;; that need resolution.
                        (if eff (conj base eff) base))
     (list-type? t)   [:list (resolve-alias (list-elem t) seen)]
     (record-type? t) (into {} (map (fn [[k v]] [k (resolve-alias v seen)])) t)
     (refine-type? t) [:refine (resolve-alias (refine-base t) seen) (refine-constraint t)]
     (union-type? t)  (make-union (mapv #(resolve-alias % seen) (union-members t)))
     :else            t)))


(defn aliases-snapshot
  "Returns the current `{name struct-type}` map. Test convenience.
   During batch registration the dynamic `*alias-view*` shadows
   the atom — see `register-type-aliases-batch`."
  []
  (or *alias-view* @type-aliases))


(defn register-type-aliases-batch
  "Register a batch of `[alias-name body]` pairs that may reference
   each other or themselves — typical for record-types with
   mutual / recursive shape (Tree, LinkedList, Person↔Address).

   Validation runs against a view of the alias registry pre-extended
   with EVERY pending name (bound to `:any` as a placeholder), so
   `well-formed?`'s keyword check accepts forward references inside
   the batch. After all bodies pass, the actual bodies are committed
   atomically to the live atom.

   Individual failures don't abort the batch — they're collected and
   returned. Caller decides whether to log / surface them. Returns
   `{:registered #{names} :failed [{:name n :body b :reason r} ...]}`."
  [pairs]
  (let [proposed-names (into #{} (keep first) pairs)
        scratch (merge @type-aliases
                       (zipmap proposed-names (repeat :any)))
        classify
        (fn [[nm body]]
          (cond
            (nil? nm)              {:nm nm :body body :reason "alias name is nil"}
            (primitives nm)        {:nm nm :body body
                                    :reason (str "name " (pr-str nm)
                                                 " shadows a primitive")}
            (not (well-formed? body)) {:nm nm :body body
                                       :reason "body not well-formed"}
            :else                  {:nm nm :body body :ok true}))
        results (binding [*alias-view* scratch]
                  (mapv classify pairs))
        ok-pairs (->> results (filter :ok) (mapv (juxt :nm :body)))
        failed   (->> results (remove :ok)
                      (mapv (fn [r] (select-keys r [:nm :body :reason]))))]
    (when (seq ok-pairs)
      (swap! type-aliases
             (fn [m]
               (reduce (fn [acc [nm body]] (assoc acc nm body))
                       m ok-pairs))))
    {:registered (into #{} (map first) ok-pairs)
     :failed     failed}))


(defn type->storage-kind
  "Reduce a (possibly structured) type to the primitive enum tag the
   DB's `value-kind` column accepts. Storage-side enums don't carry
   structure; the rich type lives in the in-memory rich-types registry
   and degrades to one of these primitives on the wire.

       :int            → :int
       'a              → :any        (type vars are storage-untagged)
       [:fn …]         → :fn
       [:list …]       → :sequence   (canonical chain shape)
       {…}             → :jsonb      (records are jsonb-shaped on the wire)
       [:refine B c]   → storage-kind of B (the constraint lives only
                                            in the type system)
       [:union …]      → :any        (no single storage tag fits a union)
       <alias keyword> → resolves through `resolve-alias` first, then
                          recurses on the structural body."
  [t]
  (let [t' (resolve-alias t)]
    (cond
      (primitive? t')   t'
      (type-var? t')    :any
      (fn-type? t')     :fn
      (list-type? t')   :sequence
      (record-type? t') :jsonb
      (refine-type? t') (recur (refine-base t'))
      (union-type? t')  :any
      :else             nil)))


(defn clear-aliases!
  "Drop every registered alias. Test convenience — production
   registers aliases via `register-alias!` calls driven by
   `:type` / `:refine` / `:list` / `:union` / `:variant` fn-defs in
   the package loader."
  []
  (reset! type-aliases {}))


;; -----------------------------------------------------------------------------
;; Substitutions — type-var → type binding map

(defn resolve
  "Walk a substitution chain to a fixed point. If `t` is a type
   variable bound in `subst`, follow the binding; recurse on compound
   types so nested vars are also resolved."
  [subst t]
  (cond
    (type-var? t) (if-let [v (get subst t)]
                    (if (= v t) t (resolve subst v))
                    t)
    (fn-type? t) (let [base [:fn
                             (into {} (map (fn [[k v]] [k (resolve subst v)])) (fn-args t))
                             (resolve subst (fn-ret t))]
                       eff (fn-effects t)]
                   ;; Preserve the effect constraint when present.
                   ;; Without this, type-var substitution silently
                   ;; drops the 4th element and the slot-effect-
                   ;; constraint check at unify-time gets nil instead
                   ;; of the declared set.
                   (if eff (conj base eff) base))
    (list-type? t)   [:list (resolve subst (list-elem t))]
    (record-type? t) (into {} (map (fn [[k v]] [k (resolve subst v)])) t)
    (union-type? t)  (make-union (mapv #(resolve subst %) (union-members t)))
    :else t))


(defn- occurs?
  "Standard occurs-check — does `v` appear inside `t` after applying
   `subst`? Required to keep unification sound; without it
   `unify('a, [:list 'a])` would build an infinite type."
  [v t subst]
  (let [t' (resolve subst t)]
    (cond
      (= v t')         true
      (type-var? t')   false
      (fn-type? t')    (or (some #(occurs? v % subst) (vals (fn-args t')))
                           (occurs? v (fn-ret t') subst))
      (list-type? t')  (occurs? v (list-elem t') subst)
      (record-type? t') (some #(occurs? v % subst) (vals t'))
      (union-type? t') (some #(occurs? v % subst) (union-members t'))
      :else            false)))


;; -----------------------------------------------------------------------------
;; Subtyping

(declare subtype?)


(defn- record-subtype?
  "Open record subtyping: sub is a subtype of sup if it has every
   field sup requires, with sub's field type ⊆ sup's. Extra fields
   on sub are allowed."
  [sub sup]
  (every? (fn [[k st]]
            (when-let [t (get sub k)]
              (subtype? t st)))
          sup))


(defn- list-subtype?
  [sub sup]
  (subtype? (list-elem sub) (list-elem sup)))


(defn- effects-compatible?
  "Slot-effect-constraint check. Sub's effects must be a subset of
   sup's effects (the slot's allowed set). Both nil/`:any` mean
   unconstrained — any effects allowed."
  [sub-eff sup-eff]
  (cond
    (or (nil? sup-eff) (= sup-eff :any)) true
    (or (nil? sub-eff) (= sub-eff :any)) false
    (set? sup-eff) (every? sup-eff (or sub-eff #{}))
    :else true))


(defn- fn-subtype?
  "Function subtyping under graphden's hof-wrap semantics.

   Contravariant args, covariant return — but `args` is interpreted
   the graphden way: a fn-graph's compile-time free-args list every
   slot a downstream binding might consult; at the call site,
   hof-wrap fills those that aren't lambda-params from the
   environment (chain-bound names, defaults). The CONSUMER (sup)
   declares only the keys it actually plans to provide per call;
   the CALLEE (sub) may declare strictly more, knowing the rest
   get captured.

   Rules:
   - `sub-ret ⊆ sup-ret` (covariant return — unchanged).
   - `(keys sup-args) ⊆ (keys sub-args)` — every key the consumer
     names must exist in the callee's free args. Extra keys on the
     callee are fine; hof-wrap sources them from the environment.
   - For shared keys, `sup-arg ⊆ sub-arg` (caller passes a narrower
     value than the callee accepts — standard contravariance).
   - `sub-effects ⊆ sup-effects` (callable-side covariant effect
     constraint — bound fn must NOT exceed the slot's allowed
     effect set; `nil`/`:any` on the slot means \"unconstrained\")."
  [sub sup]
  (and (subtype? (fn-ret sub) (fn-ret sup))
       (effects-compatible? (fn-effects sub) (fn-effects sup))
       (let [a (fn-args sub)
             b (fn-args sup)]
         (and (every? (fn [k] (contains? a k)) (keys b))
              (every? (fn [[k bt]]
                        (when-let [at (get a k)]
                          (subtype? bt at)))
                      b)))))


(defn- normalise
  "Canonicalise a type before subtype/unify reasoning. Two passes:

   1. Storage-primitive ↔ structural-type rewrite. `:sequence` (the
      wire-level primitive) IS `[:list :any]` (the typed view of the
      same shape). Without this, a composed fn-def whose inherited
      slot stores `:sequence` can't unify against a base-fn slot
      annotated `[:list :any]`, even though they describe the same
      runtime value. Single canonical list shape post-#16 already
      enforces that mapping at storage write-time; this is the
      read-time equivalent.

   2. Alias resolution. Keyword aliases like `:nullable-int`,
      `:positive-int`, `:result-text` are sugar for structural
      types (`[:union :null :int]`, `[:refine :int [:> 0]]`,
      `[:union {:tag … :value …} {:tag … :value …}]`). Without this
      step, every `subtype?` / `unify` caller had to remember to
      pre-resolve via `resolve-alias` — most did, but the
      `/api/types/compatible` and `/api/types/candidates` endpoints
      did NOT, leaving `:nullable-int ⊆ :int`, `:positive-int ⊆ :int`,
      and (post-Phase 7) `:result-text` matching against a
      tag-pinned record all returning the wrong answer. Fold the
      resolution into `normalise` so the property is universal.

   `resolve-alias` recurses into compound types and is idempotent on
   already-structural inputs, so chaining the two passes is safe."
  [t]
  (let [t (cond (= t :sequence) [:list :any]
                :else           t)]
    (resolve-alias t)))


;; Primitive subtype hierarchy. Doc-aligned:
;;   :int ⊆ :numeric  ⊆ :jsonb  ⊆ :any
;;   :float ⊆ :numeric
;; (`:numeric` is the doc's wider numeric supertype — runtime
;; arbitrary-precision values, plus integers and floats.)
(def ^:private primitive-supers
  {:int   #{:numeric}
   :float #{:numeric}})


(defn- primitive-subtype?
  "Walk the primitive-supers map to see if `sub` is `sup` or any of
   its transitive supers. Used in `subtype?`'s primitive case."
  [sub sup]
  (or (= sub sup)
      (some #(primitive-subtype? % sup) (primitive-supers sub))))


(declare constraint-implies?)


(defn- atom-implies?
  "Decide whether the leaf constraint `a` implies `b` for the
   numeric-comparison shapes graphden actually uses
   (`[:>` / `[:>=` / `[:<` / `[:<=` / `[:=` / `[:not= …]`). Returns
   true iff every value satisfying `a` also satisfies `b`. nil for
   shapes outside the supported set so the caller falls back to
   structural equality."
  [a b]
  (when (and (vector? a) (vector? b))
    (let [[ak av] [(first a) (when (>= (count a) 2) (nth a 1))]
          [bk bv] [(first b) (when (>= (count b) 2) (nth b 1))]
          numeric? (and (number? av) (number? bv))]
      (cond
        ;; Identical leaves trivially imply each other.
        (= a b) true

        ;; Equality on the LHS is a singleton — easy to check against
        ;; any RHS comparison.
        (and (= ak :=) numeric?)
        (case bk
          := (= av bv)
          :>  (> av bv)
          :>= (>= av bv)
          :<  (< av bv)
          :<= (<= av bv)
          :not= (not= av bv)
          nil)

        (and numeric? (= ak bk))
        (case ak
          ;; Tightening lower bound: [:> a] ⊆ [:> b] iff a >= b.
          (:> :>=) (>= av bv)
          ;; Tightening upper bound symmetrically.
          (:< :<=) (<= av bv)
          nil)

        ;; Strict-vs-loose comparison crosses:
        ;; [:> a] ⊆ [:>= b] iff a >= b  (every x>a is also >=b when a>=b)
        ;; [:>= a] ⊆ [:> b] iff a > b   (need strictly above b's threshold)
        (and numeric? (= ak :>) (= bk :>=))   (>= av bv)
        (and numeric? (= ak :>=) (= bk :>))   (> av bv)
        (and numeric? (= ak :<) (= bk :<=))   (<= av bv)
        (and numeric? (= ak :<=) (= bk :<))   (< av bv)

        :else nil))))


(defn constraint-implies?
  "True iff every value satisfying refinement constraint `a` also
   satisfies `b`. Drives subtype reasoning across refinement types
   (`:positive-int ⊆ :non-negative-int`, `:user-port ⊆ :port`,
   `:probability ⊆ :percent`).

   Recurses through `[:and …]` (intersection — narrower than any
   branch) and `[:or …]` (union — wider than every branch). Falls
   back to structural equality for unknown shapes so legacy
   refinements with custom constraints behave as before."
  [a b]
  (cond
    ;; Trivial identity / open universal.
    (or (= a b) (= b [:any])) true
    (or (nil? a) (nil? b))    false

    ;; AND on RHS: a must imply EVERY component of b.
    (and (vector? b) (= :and (first b)))
    (every? #(constraint-implies? a %) (rest b))

    ;; OR on LHS: every branch of a must imply b (worst-case widens
    ;; LHS to its widest member).
    (and (vector? a) (= :or (first a)))
    (every? #(constraint-implies? % b) (rest a))

    ;; AND on LHS: any single component implying b is enough — the
    ;; intersection is at least as narrow as that component.
    (and (vector? a) (= :and (first a)))
    (boolean (some #(constraint-implies? % b) (rest a)))

    ;; OR on RHS: a must imply at least one branch.
    (and (vector? b) (= :or (first b)))
    (boolean (some #(constraint-implies? a %) (rest b)))

    :else
    (or (atom-implies? a b)
        ;; Unknown leaf shape — fall back to structural equality.
        false)))


(defn subtype?
  "Is `sub` a subtype of `sup`? Reflexive, transitive, anti-symmetric.
   Type variables compare by identity for now — unification handles
   the substitution case before subtype check is meaningful.

   Refinement rules (Phase 4):
   - `[:refine B c] ⊆ B`            (refinement is a subtype of base)
   - `B ⊄ [:refine B c]`             (no implicit narrowing — caller
                                       must insert `:validate-refinement`)
   - `[:refine B c1] ⊆ [:refine B c2]` iff `c1 = c2` (no constraint
                                       reasoning — equality only;
                                       SMT-style logic is out of scope
                                       per TYPES.md).

   Union rules:
   - `[:union T1 T2 …] ⊆ S` iff every Tᵢ ⊆ S          (disjunction)
   - `T ⊆ [:union T1 T2 …]` iff T ⊆ Tᵢ for some i      (membership)"
  [sub sup]
  (let [sub (normalise sub)
        sup (normalise sup)]
    (cond
      (or (= sub sup) (= sup :any))            true
      (= sub :any)                             false

      ;; Union LHS: every member must subtype.
      (union-type? sub)
      (every? #(subtype? % sup) (union-members sub))

      ;; Union RHS: at least one member accepts.
      (union-type? sup)
      (boolean (some #(subtype? sub %) (union-members sup)))

      (or (and (primitive? sub) (primitive? sup)
               (primitive-subtype? sub sup))
          (and (= sup :jsonb)
               (or (primitive? sub)
                   (record-type? sub)
                   (list-type? sub)
                   (refine-type? sub)))) true
      (= sub :jsonb)                           false

      ;; Refinement: SUB carries a refinement, SUP is its base or any —
      ;; pass; SUP carries a refinement → constraint inclusion.
      ;;
      ;; Constraint inclusion (`constraint-implies?`) covers the
      ;; common numeric-comparison shapes plus `:and` / `:or`
      ;; combinators, so e.g. `:positive-int ⊆ :non-negative-int`
      ;; (`[:> 0] ⊆ [:>= 0]`) and `:user-port ⊆ :port` (one nested
      ;; `:and` inside another) hold. Falls back to structural
      ;; equality for shapes outside the supported set, preserving
      ;; the prior behaviour for refinements with custom constraints.
      (refine-type? sub)
      (cond
        (refine-type? sup)
        (and (constraint-implies? (refine-constraint sub)
                                  (refine-constraint sup))
             (subtype? (refine-base sub) (refine-base sup)))
        :else
        (subtype? (refine-base sub) sup))

      (refine-type? sup)
      ;; SUB is not a refinement, SUP is — must NOT widen.
      false

      (and (record-type? sub) (record-type? sup))
      (record-subtype? sub sup)
      (and (list-type? sub) (list-type? sup))  (list-subtype? sub sup)
      (and (fn-type? sub) (fn-type? sup))      (fn-subtype? sub sup)
      :else                                    false)))


;; -----------------------------------------------------------------------------
;; Unification — Robinson with occurs check

(declare unify)


(defn- bind-var
  [v t subst]
  (cond
    (= v t)              subst
    (occurs? v t subst)  ::fail
    :else                (assoc subst v t)))


(defn- unify-fn
  "Unify two function types positionally — argument NAMES are
   documentation only, what matters at the type level is arity and
   per-position type. Args are zipped after sorting by key so that
   the order is deterministic regardless of the underlying map
   implementation. Cardinality must match.

   Why positional, not name-based: a fn-typed slot like
   `[:fn {:item a} b]` should accept any one-arg callable returning
   `b` over any `a`, regardless of how the callable named its own
   parameter (`:string`, `:x`, `:input`, …). HOF binding sites and
   their referenced fn-graphs rarely agree on names, but they must
   agree on shape.

   When BOTH sides carry effect constraints, the bound fn's effects
   must be a subset of the slot's allowed set. Either side
   nil/`:any` skips the check. Mirrors `fn-subtype?`'s
   `effects-compatible?` rule."
  [a b subst]
  (let [a-args (fn-args a)
        b-args (fn-args b)]
    (if (not= (count a-args) (count b-args))
      ::fail
      (let [a-types (mapv val (sort-by key a-args))
            b-types (mapv val (sort-by key b-args))
            arg-step (reduce (fn [s i]
                               (if (= s ::fail)
                                 (reduced ::fail)
                                 (unify (get a-types i) (get b-types i) s)))
                             subst
                             (range (count a-types)))
            ret-step (when-not (= arg-step ::fail)
                       (unify (fn-ret a) (fn-ret b) arg-step))
            ;; Effects: caller convention is `(unify expected
            ;; actual subst)` so `a` is the slot's expected type
            ;; (sup) and `b` is the bound fn's actual type (sub).
            ;; The slot constraint must hold one-way: actual's
            ;; effects ⊆ expected's allowed effects.
            effects-ok? (effects-compatible? (fn-effects b) (fn-effects a))]
        (if (or (= arg-step ::fail)
                (= ret-step ::fail)
                (not effects-ok?))
          ::fail
          ret-step)))))


(defn- unify-record
  [a b subst]
  (if (not= (set (keys a)) (set (keys b)))
    ::fail
    (reduce (fn [s k]
              (if (= s ::fail)
                (reduced ::fail)
                (unify (get a k) (get b k) s)))
            subst
            (keys a))))


(defn unify
  "Unify two types. Returns an updated substitution, or `::fail` if
   they cannot be made equal. Substitution is a `{type-var type}` map.
   3-arg form takes a starting substitution; 2-arg form starts empty.

   Subtype-aware leniency: when EITHER side resolves to `:any`, the
   unification succeeds without further constraint. Pure HM unify
   would reject `:any` ↔ `:int` even though `:int ⊆ :any` makes them
   compatible in the subtyping sense; rejecting would force every
   polymorphic slot to pick one concrete type and stick to it across
   all uses, which is too strict when the user has deliberately
   declared a slot as `:any` (the doc's escape hatch)."
  ([t1 t2] (unify t1 t2 {}))
  ([t1 t2 subst]
   (let [a (normalise (resolve subst t1))
         b (normalise (resolve subst t2))]
     (cond
       (or (= a b) (= a :any) (= b :any)) subst
       ;; Unions: HM unifier doesn't naturally pick a branch (multiple
       ;; valid choices), so defer to subtype? — succeed without
       ;; binding when the relation holds in either direction. This
       ;; matches the lenient :any handling above and the practical
       ;; reality that unions appear as DECLARED slot types, not as
       ;; type-var bindings.
       (or (union-type? a) (union-type? b))
       (if (or (subtype? a b) (subtype? b a)) subst ::fail)
       ;; Subtype-aware unification — succeeds without further binding
       ;; when one of the relations holds:
       ;;
       ;; - Primitive subtype within the numeric hierarchy
       ;;   (`:int ↔ :numeric` is fine because `:int ⊆ :numeric`).
       ;; - Records / lists / refinements ↔ `:jsonb`. They ARE
       ;;   jsonb-shaped on the wire (matches the subtype rule
       ;;   `record ⊆ :jsonb`). A slot whose declared type narrowed
       ;;   from a type-var to `:jsonb` (because earlier in the chain
       ;;   it bound to a jsonb-typed value) needs to unify against
       ;;   a more-precisely-typed record at a later call site.
       ;;
       ;; The pinned type-var keeps its first binding; subsequent
       ;; compatible refinements flow through unchanged.
       (or (and (primitive? a) (primitive? b)
                (or (primitive-subtype? a b) (primitive-subtype? b a)))
           (and (= a :jsonb)
                (or (record-type? b) (list-type? b) (refine-type? b)))
           (and (= b :jsonb)
                (or (record-type? a) (list-type? a) (refine-type? a))))
       subst
       (type-var? a)        (bind-var a b subst)
       (type-var? b)        (bind-var b a subst)
       (and (fn-type? a) (fn-type? b))         (unify-fn a b subst)
       (and (list-type? a) (list-type? b))     (unify (list-elem a) (list-elem b) subst)
       (and (record-type? a) (record-type? b)) (unify-record a b subst)
       :else                ::fail))))


(defn unified?
  [r]
  (not= r ::fail))


(defn fail?
  [r]
  (= r ::fail))


;; -----------------------------------------------------------------------------
;; Type-var freshening — let-polymorphism support
;;
;; When a fn-def lifts free-args from multiple refs into its own
;; surface, every ref carries its own scope of type-vars. Naïvely
;; merging would conflate ALL `'a`s into one variable — wrong, since
;; each ref instantiates `'a` independently at call time. The fix:
;; per ref, walk its types and rename each `'a` to a unique
;; `'a-<n>` BEFORE merging.

(defonce ^:private fresh-counter (atom 0))


(defn- next-fresh-suffix
  "Monotonic integer for gensym-style type-var naming."
  []
  (swap! fresh-counter inc))


(declare freshen)


(defn- freshen*
  "Walk `t`, renaming each type-var via the `subst` atom (filled
   on demand). Recurse into compound shapes."
  [t subst]
  (cond
    (type-var? t)
    (or (get @subst t)
        ;; Strip any prior suffix (`a-1` → `a`) before appending the
        ;; new counter so deep transitive lifts produce `a-N`, not
        ;; `a-1-2-3`. The base name stays traceable; equality between
        ;; freshened vars still relies on the unique counter.
        (let [n (name t)
              dash (String/.indexOf ^String n "-")
              base (if (neg? dash) n (subs n 0 dash))
              fresh (symbol (str base "-" (next-fresh-suffix)))]
          (swap! subst assoc t fresh)
          fresh))
    (fn-type? t)     [:fn
                      (into {} (map (fn [[k v]] [k (freshen* v subst)])) (fn-args t))
                      (freshen* (fn-ret t) subst)]
    (list-type? t)   [:list (freshen* (list-elem t) subst)]
    (record-type? t) (into {} (map (fn [[k v]] [k (freshen* v subst)])) t)
    (refine-type? t) [:refine (freshen* (refine-base t) subst)
                      (refine-constraint t)]
    (union-type? t)  (make-union (mapv #(freshen* % subst) (union-members t)))
    :else            t))


(defn freshen-args
  "Rename every type-var across the given `args-map` ({arg-name type})
   with a SINGLE shared substitution scope — so `'a` appearing in
   multiple slot types within the SAME ref stays linked, but two
   different calls to `freshen-args` produce DIFFERENT vars (the
   essential let-polymorphism move)."
  [args-map]
  (let [subst (atom {})]
    (into {} (map (fn [[k v]] [k (freshen* v subst)])) args-map)))
