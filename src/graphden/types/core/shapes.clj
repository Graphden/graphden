(ns graphden.types.core.shapes
  "Type-system shape primitives — predicates, accessors, and
   secret-taint helpers. The PURE structural side of the type module:
   given a type value, answer \"what kind is it?\" and \"give me the
   inner part(s)\". No reasoning (subtyping / unification / alias
   resolution) lives here — that belongs to `graphden.types.core`.

   Loaded as a leaf-level dependency of `types.core` (and direct
   consumers that only need shape detection). Cycle-free by design:
   no fn here calls into `types.core` operations.

   Re-exported through `graphden.types.core` for backwards-compat —
   callers that say `(:require [graphden.types.core :as types])` still
   resolve `types/fn-type?` / `types/record-type?` / … as before. New
   code that ONLY needs predicates can require this ns directly."
  (:require
    [clojure.set]))


;; -----------------------------------------------------------------------------
;; Primitives

(def primitives
  "The flat enum of value-kind primitives. A superset of
   `value-kind-values` in schema/graph/schema.clj — every storage
   value-kind is a primitive here, but a few primitives are
   type-system-only and never reach the `value_kind` column
   (`:float`, `:keyword`, `:never`, `:input-stream`, `:decimal`).
   `:fn` is included as a primitive for backwards compat with
   declarations that use the bare keyword instead of a structural
   `[:fn …]`; structurally, it acts like `[:fn {} :any]` (any
   callable).

   `:never` is the BOTTOM type — the dual of `:any` (top). It is a
   subtype of every type and the type of a computation that never
   produces a value (`:throw`). In a union it is absorbed
   (`[:union :never T]` = `T`), so `(:if c (:throw …) x)` is typed
   exactly `x`. `:float` and `:keyword` are type-system-only —
   emitted by `classify-literal` for float / keyword literals — and
   never reach the storage `value_kind` column.

   `:input-stream` is the type of a transient `java.io.InputStream`
   (Ring request bodies, file streams) — values are never stored as
   data, so it's type-system-only too. `type->storage-kind` degrades
   it to `:any`.

   `:decimal` is arbitrary-precision rational (Clojure's `BigDecimal`
   / Java `java.math.BigDecimal`). Subtype of `:numeric` alongside
   `:int` and `:float`."
  #{:null :uuid :text :int :bool :numeric :timestamptz :jsonb :bytes
    :any :fn :float :keyword :sequence :never :input-stream :decimal})


(defn primitive?
  [t]
  (boolean (and (keyword? t) (primitives t))))


;; Runtime test for "is this value an instance of `tag`?", keyed on
;; the canonical type-tag. Used by the `:is-a?` base-fn (and any
;; future runtime-classification consumer).
;;
;; Covers every member of `primitives` plus a few `structural-class`
;; tags (`:map`, `:vector`) that don't appear in `primitives` because
;; the type-system's general container is `:jsonb` — but at runtime
;; the user often does want to distinguish "this is a map" from
;; "this is a vector".
;;
;; KEEP THIS IN SYNC WITH `primitives`: every primitive whose values
;; have a Clojure runtime check belongs here. A startup `assert`
;; verifies the covering — adding to `primitives` without updating
;; this map fails the assert at namespace load.
;;
;; Special cases:
;; - `:never` is BOTTOM; no value matches → always false.
;; - `:any` is TOP; every value matches → always true.
;; - `:fn` matches Clojure callables (subset of values; in
;;   structural fn-types the type-system is richer).
;; - `:jsonb` is "JSON-encodable shape" — map / vector / scalar.
;; - `:input-stream` is the transient `java.io.InputStream` carrier.
(def runtime-predicates
  (let [m {:null         nil?
           :bool         boolean?
           :int          integer?
           :float        float?
           :numeric      number?
           :text         string?
           :keyword      keyword?
           :uuid         uuid?
           :decimal      decimal?
           :jsonb        (some-fn map? vector? string? number? boolean? nil?)
           :sequence     sequential?
           :bytes        bytes?
           :timestamptz  inst?
           :fn           fn?
           :any          (constantly true)
           :never        (constantly false)
           :input-stream (fn [v] (instance? java.io.InputStream v))
           ;; Structural-class additions beyond `primitives`:
           :map          map?
           :vector       vector?}]
    (assert (every? m primitives)
            (str "types/core/shapes/runtime-predicates is missing primitives — "
                 "every member of `primitives` must have an entry here. "
                 "Missing: " (pr-str (remove m primitives))))
    m))


;; -----------------------------------------------------------------------------
;; Predicates

(defn type-var?
  [t]
  (symbol? t))


(defn record-type?
  [t]
  (and (map? t) (every? keyword? (keys t))))


(defn fn-type?
  "`[:fn args ret effects]` — canonical four-element form. `effects`
   is either:
     - `:any` — the slot declares no constraint; callable may have
       any effects.
     - a set of effect-category keywords (`#{}` pure-only,
       `#{:io :time}` read-only system state, …) — the bound fn's
       `:effects` must be a subset.

   `[:fn args ret]` — legacy three-element form, equivalent to the
   four-element form with `:any` as the 4th element. Accepted on
   read (storage / EDN authors may still write it); `normalise`
   canonicalises to four-element before any subtype/unify check."
  [t]
  (and (vector? t) (= :fn (first t)) (#{3 4} (count t))))


(defn callable-type?
  "True when a slot's type ACCEPTS A CALLABLE — the bare `:fn` primitive
   (any callable, no structural shape) OR a structural `[:fn args ret …]`.
   The one predicate for \"is this a HOF slot\", shared by the type-checker
   (`ref-free-args`) and the CRUD free-arg lookup (`free-args-via`) so the
   two don't each hand-roll `(or (= :fn t) (fn-type? t))`."
  [t]
  (or (= :fn t) (fn-type? t)))


(defn make-fn-type
  "Canonical constructor for a function type. Always produces the
   four-element form; `eff` defaults to `:any` (unconstrained slot)
   when omitted. New code should prefer this over hand-rolled
   `[:fn args ret …]` vectors so the wire format stays consistent."
  ([args ret]      (make-fn-type args ret :any))
  ([args ret eff]  [:fn (or args {}) ret eff]))


(defn list-type?
  [t]
  (and (vector? t) (= :list (first t)) (= 2 (count t))))


(defn refine-type?
  "`[:refine base-type constraint]` — a NAMED subtype of `base-type`
   carrying an opaque `constraint` payload (e.g. `[:gt 0]` for
   :positive-int). Phase 4 of TYPES.md.

   Subtype reasoning over constraints lives in
   `types.core/constraint-implies?` — it DOES prove implications like
   `:positive-int ⊆ [:refine :int [:>= 0]]` for the supported atom
   shapes (numeric comparisons, `:=`/`:not=`/`:in`/`:matches`,
   `:and`/`:or`); unsupported shapes fall back to structural equality,
   and widening (`B ⊄ [:refine B c]`) still requires an explicit
   `:ensure-*` refinement-narrower node."
  [t]
  (and (vector? t) (= :refine (first t)) (= 3 (count t))))


;; =============================================================================
;; Marker types — registry-driven monotone information-flow markers
;; =============================================================================
;;
;; `[<tag> <inner-type>]` where <tag> is a REGISTERED marker. The tag
;; changes nothing at runtime (a `[:secret :text]` is still a string);
;; it changes how the TYPE SYSTEM treats the value:
;;   - monotone subtyping: `T ⊆ [<tag> T']` (auto-promote on entry),
;;     `[<tag> T] ⊄ T'` (the marker cannot be stripped),
;;     `[<tag> T] ⊆ [<tag> T']` iff `T ⊆ T'` (covariant inside; a
;;     DIFFERENT tag never satisfies — `[:pii T] ⊄ [:secret T]`);
;;   - propagation: the taint propagators below carry EVERY monotone
;;     marker present on any input onto the return;
;;   - behaviour flags: `:hide-result?` drives the `/api/execute`
;;     redaction (see `crud.fn-execution.persist`).
;;
;; `:secret` is the SEEDED first instance (SECRETS.md). New markers
;; register at sync time from graph type-rows (`{:name :pii :marker
;; {:hide-result? false}}` in fns.edn) — no code change.

(def ^:private structural-heads
  "Vector heads owned by the structural grammar — a marker tag must
   never shadow one."
  #{:fn :list :map :tuple :union :refine :variant :marker-def :secret})


(defonce ^:private marker-registry
  (atom {:secret {:monotone? true :hide-result? true}}))


(defn register-marker!
  "Register `tag` as a marker type with `flags`
   (`{:monotone? bool :hide-result? bool}`). Idempotent — re-register
   replaces. v1 engine semantics are monotone-only; the flag is stored
   for honesty and future non-monotone markers are rejected loudly
   rather than silently mis-handled."
  [tag flags]
  (when (contains? (disj structural-heads :secret) tag)
    (throw (ex-info (str "marker tag shadows a structural type head: " tag)
                    {:type :types/invalid-marker :tag tag})))
  (when (false? (:monotone? flags))
    (throw (ex-info (str "non-monotone markers are not supported yet: " tag)
                    {:type :types/invalid-marker :tag tag :flags flags})))
  (swap! marker-registry assoc tag (merge {:monotone? true} flags))
  tag)


(defn unregister-marker!
  "Test/cleanup counterpart. `:secret` is seeded and never removed."
  [tag]
  (when-not (= :secret tag)
    (swap! marker-registry dissoc tag))
  nil)


(defn marker-flags
  [tag]
  (get @marker-registry tag))


(defn marker-type?
  "`[<registered-tag> <inner>]`?"
  [t]
  (and (vector? t) (= 2 (count t))
       (contains? @marker-registry (first t))))


(defn marker-tag
  [t]
  (when (marker-type? t) (nth t 0)))


(defn marker-inner
  [t]
  (when (marker-type? t) (nth t 1)))


(defn make-marker-type
  "Idempotent per TAG: wrapping a value already carrying THIS tag
   returns it unchanged; a different marker nests
   (`[:pii [:secret T]]` — both labels hold)."
  [tag inner]
  (if (and (marker-type? inner) (= tag (marker-tag inner)))
    inner
    [tag inner]))


(defn hide-result-marker-type?
  [t]
  (and (marker-type? t)
       (boolean (:hide-result? (marker-flags (marker-tag t))))))


(defn secret-type?
  "`[:secret <inner-type>]` — the seeded information-flow marker
   instance (see the marker registry above; SECRETS.md)."
  [t]
  (and (vector? t) (= :secret (first t)) (= 2 (count t))))


(defn secret-inner
  "The inner type wrapped by `[:secret T]`."
  [t]
  (when (secret-type? t) (nth t 1)))


(defn make-secret-type
  "`(make-marker-type :secret inner)` — kept for the 85 existing
   call sites."
  [inner]
  (make-marker-type :secret inner))


(defn coarse-lub
  "Coarse least-upper-bound of a collection of types: all equal → that
   type, otherwise (or empty input) → `:any`. Used where a precise
   join isn't worth computing — e.g. the element type of a
   heterogeneous list or a record's `:vals`."
  [types]
  (let [ts (set types)]
    (cond
      (empty? ts)      :any
      (= 1 (count ts)) (first ts)
      :else            :any)))


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
  "Slot-level effect constraint of a fn-type. Always returns a value
   for fn-types: the declared 4th element if present, else `:any`
   (the legacy three-element form is treated as the canonical
   four-element form with `:any` as the slot meaning — unconstrained,
   any callable passes). nil for non-fn-types.

   `effects-compatible?` reads this and handles `:any` (sup-side =
   unconstrained, sub-side = can't satisfy concrete) — see its
   docstring for the directional rule."
  [t]
  (cond
    (not (fn-type? t)) nil
    (= 4 (count t))    (nth t 3)
    :else              :any))


(defn list-elem
  [t]
  (when (list-type? t) (nth t 1)))


(defn map-type?
  "`[:map key-type val-type]` — a homogeneous map: every key is of
   `key-type`, every value of `val-type`. Distinct from a record
   (`{:field T …}`), which has a FIXED set of named fields. Subtyping
   is covariant in both key and value."
  [t]
  (and (vector? t) (= :map (first t)) (= 3 (count t))))


(defn map-key
  [t]
  (when (map-type? t) (nth t 1)))


(defn map-val
  [t]
  (when (map-type? t) (nth t 2)))


(defn tuple-type?
  "`[:tuple T1 T2 …]` — a fixed-length heterogeneous sequence: position
   i holds a value of type Tᵢ. Distinct from `[:list T]` (homogeneous,
   any length). Subtyping requires equal length and is covariant
   per-position."
  [t]
  (and (vector? t) (= :tuple (first t)) (>= (count t) 1)))


(defn tuple-elems
  [t]
  (when (tuple-type? t) (vec (rest t))))


;; -----------------------------------------------------------------------------
;; Secret-taint helpers — pure on shape, no alias / subtype dependency.

(defn child-types
  "The immediate constituent types of a compound type — the arms every
   structural fold must recurse through. Primitives / type-vars / any
   non-compound have none. THE single enumeration of a type's sub-types:
   `type-any?` (and any future structural fold) rides on it, so a new
   type-kind is covered everywhere by adding ONE arm here rather than in
   each hand-written recursion."
  [t]
  (cond
    (fn-type? t)     (conj (vec (vals (fn-args t))) (fn-ret t))
    (list-type? t)   [(list-elem t)]
    (map-type? t)    [(map-key t) (map-val t)]
    (tuple-type? t)  (vec (tuple-elems t))
    (refine-type? t) [(refine-base t)]
    (marker-type? t) [(marker-inner t)]
    (union-type? t)  (vec (union-members t))
    (record-type? t) (vec (vals t))
    :else            []))


(defn type-any?
  "True iff `pred` holds for `t` or for any type nested anywhere within
   it (recursing via `child-types`). The one traversal behind
   `contains-secret?` and the checker's `has-type-var?` — swap the leaf
   predicate, keep the recursion in ONE place so an added type-kind can't
   silently escape a fold (the historic 'a missing arm lets X slip
   through' hazard). `pred` short-circuits: a node it matches is not
   descended into (correct for `contains-secret?`, where a `[:secret …]`
   IS the hit)."
  [pred t]
  (or (boolean (pred t))
      (boolean (some #(type-any? pred %) (child-types t)))))


(defn contains-secret?
  "True iff `t` contains a `[:secret …]` anywhere in its structure.
   The `:secret` instance of `contains-marker?` — kept for the
   existing call sites."
  [t]
  (type-any? secret-type? t))


(defn contains-marker?
  "True iff `t` contains ANY registered marker anywhere in its
   structure — the generic jsonb-sink laundering guard."
  [t]
  (type-any? marker-type? t))


(defn contains-hide-result-marker?
  "True iff `t` carries a marker whose flags say `:hide-result?` —
   the `/api/execute` redaction predicate."
  [t]
  (type-any? hide-result-marker-type? t))


(defn- marker-tags-in
  "Every registered marker tag present anywhere in `t` (set)."
  [t]
  (let [acc (volatile! #{})]
    (type-any? (fn [x]
                 (when (marker-type? x)
                   (vswap! acc conj (marker-tag x)))
                 false)
               t)
    @acc))


(defn taint-with-secret-if-tainted
  "Pluggable `:return-type-rule` propagator — if ANY arg in
   `bindings-info` carries a `[:secret …]` anywhere in its type,
   wrap the static return in `[:secret …]`. Otherwise return the
   static return verbatim.

   `bindings-info` is the shape `compute-return-type` passes to a
   rule: `{slot-name {:type T :value V? :ref R? :elem-types [T …]?}}`.
   We scan BOTH `:type` AND `:elem-types`. `:elem-types` is
   load-bearing: a list binding's `:type` is `[:list (coarse-lub …)]`
   and the coarse lub of a heterogeneous list widens to `:any` — so a
   `[\"Bearer \" secret-ref]` list argument would drop its `[:secret …]`
   marker if we looked at `:type` alone. A content-passing base-fn
   (`:str` concat, `:add`, …) that consumes such a list DOES fold the
   secret element into its result, so the per-element types must be
   scanned or the taint leaks (pre-2026-08-17 bug: mixed-list secret
   silently declassified).

   Base-fns with no other return-type-rule opt into propagation by
   registering this fn directly. Base-fns that ALREADY have a
   structural return-type-rule (`:first`, `:get`, etc.) wrap their
   rule via `wrap-with-taint` so the structural computation runs
   first AND the taint propagates if applicable."
  [bindings-info default-ret]
  (let [tags (reduce (fn [acc [_slot info]]
                       (as-> acc a
                             (into a (marker-tags-in (:type info)))
                             (reduce (fn [a2 et] (into a2 (marker-tags-in et)))
                                     a
                                     (:elem-types info))))
                     #{}
                     bindings-info)]
    (if (seq tags)
      ;; Carry EVERY marker present on any input — a fn that read a
      ;; `[:pii …]` and a `[:secret …]` returns a value labelled with
      ;; both. Deterministic wrap order (sorted) so computed types are
      ;; stable across runs.
      (reduce (fn [t tag] (make-marker-type tag t))
              default-ret
              (sort tags))
      default-ret)))


(defn wrap-with-taint
  "Compose an existing `:return-type-rule` with taint propagation.
   The wrapped rule runs `rule` to produce the structural return,
   then taints the result if any input was already secret. When
   `rule` is nil, returns the bare taint propagator.

   Usage in `impls.clj`:
     :first {:impl first-fn
             :return-type-rule (types/wrap-with-taint first-return-rule)}

   Keeps the existing structural narrowing (`:first` reads the elem
   type out of `:coll`) AND adds the taint layer on top."
  [rule]
  (if rule
    (fn [bindings-info default-ret]
      (taint-with-secret-if-tainted bindings-info (rule bindings-info default-ret)))
    taint-with-secret-if-tainted))
