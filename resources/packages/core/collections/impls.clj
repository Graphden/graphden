(ns graphden.packages.core.collections.impls
  "Implementations for core/collections base functions.

   Each base-fn's type-rule (the `compute-return-type` /
   `compute-slot-types` / `compute-nav-types` logic that used to live
   as a name-dispatched `defmethod` in `graphden.types.rules`) lives
   here as a plain `defn` next to the `defbase` it belongs to, and is
   wired into the `impls` map as `{:impl … :return-type-rule … …}`.
   Looked up by base-fn identity — no name-dispatch."
  (:require
    [clojure.math :as math]
    [clojure.walk]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.core :as types]))


;; === Validation Helpers ===

(defn- validate-non-zero!
  [value field-name message]
  (when (zero? value)
    (throw (ex-info message
                    {:type :execution-error/invalid-args
                     field-name value}))))


(defn- validate-non-negative-count!
  [n field-name message]
  (when (neg? n)
    (throw (ex-info message
                    {:type :execution-error/invalid-args
                     field-name n}))))


(defn- validate-collection-size!
  [size max-size error-type context message]
  (when (> size max-size)
    (throw (ex-info message
                    (merge {:type error-type
                            :size size
                            :max-size max-size}
                           context)))))


;; === Implementations ===

(defbase first-fn [coll]
  (first coll))


(defbase rest-fn [coll]
  (rest coll))


(defbase cons-fn [item coll]
  (cons item coll))


(defbase conj-any-fn [coll item]
  ;; Append for sequential colls — a `:seq` binding / `:list` result is
  ;; an (unchunked) lazy-seq, and `conj` would PREPEND onto a seq.
  ;; `vec` first so list-building keeps insertion order; maps / sets
  ;; take a plain `conj`.
  (if (or (nil? coll) (sequential? coll))
    (conj (vec coll) item)
    (conj coll item)))


(defbase get-fn [coll key default]
  (get coll key default))


(defbase get-in-fn [map path default]
  (get-in map path default))


(defbase assoc-any-fn [map key value]
  (assoc (or map {}) key value))


(defbase dissoc-fn [map key]
  (dissoc map key))


(defbase count-fn [coll]
  (count coll))


(defbase empty?-fn [coll]
  (empty? coll))


(defbase contains?-fn [coll key]
  (contains? coll key))


(defbase keys-fn [map]
  (keys map))


(defbase vals-fn [map]
  (vals map))


(defbase merge-fn [maps]
  (apply merge maps))


(defbase into-fn [to from]
  ;; A non-vector sequential `to` (e.g. a lazy-seq from a `:seq`
  ;; binding) would make `into` prepend each element; `vec` it so the
  ;; result keeps `from`'s order. Vectors / maps / sets pass through.
  (into (if (and (sequential? to) (not (vector? to))) (vec to) to)
        from))


(defbase assoc-in-fn [m path v]
  (assoc-in m path v))


(defbase update-in-fn [m path f]
  (update-in m path f))


(defbase range-fn [start end step]
  (let [max-size sp/*max-range-size*]
    (validate-non-zero! step :step "step cannot be zero (would cause infinite loop)")
    (let [range-size (if (or (and (pos? step) (< start end))
                             (and (neg? step) (> start end)))
                       (long (math/ceil (/ (abs (double (- end start)))
                                           (abs (double step)))))
                       0)]
      (validate-collection-size! range-size max-size
                                 :execution-error/range-too-large
                                 {:start start :end end :step step}
                                 (str "range would produce " range-size " elements, max allowed " max-size))
      (vec (range start end step)))))


(defbase repeat-fn [count item]
  (let [max-size sp/*max-repeat-size*]
    (validate-non-negative-count! count :count "repeat count cannot be negative")
    (validate-collection-size! count max-size :execution-error/repeat-too-large {:count count}
                               (str "repeat count " count " exceeds max allowed " max-size))
    (vec (repeat count item))))


(defbase take-fn [count coll]
  (vec (take count coll)))


(defbase drop-fn [count coll]
  (vec (drop count coll)))


(defbase reverse-fn [coll]
  (vec (reverse coll)))


(defbase sort-fn [coll]
  (vec (sort coll)))


(defbase concat-fn [colls]
  (into [] cat colls))


(defbase flatten-fn [coll]
  (vec (flatten coll)))


(defbase distinct-fn [coll]
  (vec (distinct coll)))


(defbase select-keys-fn [m ks]
  (select-keys m ks))


(defbase zipmap-fn [keys vals]
  (zipmap keys vals))


(defbase update-vals-fn [m f]
  (update-vals m f))


(defbase update-keys-fn [m f]
  (update-keys m f))


(defbase postwalk-fn
  "Walk `coll` bottom-up, applying `f` to every form. Atomic wrapper
   around `clojure.walk/postwalk`. The HOF callable receives each node
   under the convention free-arg name `:value` and returns the
   replacement."
  [f coll]
  (clojure.walk/postwalk f coll))


;; === Sequence primitives ===
;; The executor resolves a `:seq` binding into an unchunked lazy-seq
;; (`compile/resolve-seq-items`). `list-fn` returns it as-is — `vec`
;; would force every element, defeating the per-element laziness that
;; lets `:cond` clauses (built via `:list`) short-circuit.

(defbase list-fn [items]
  items)


(defbase vec-fn
  "Coerce any sequential / collection to a vector. Idempotent on
   vectors. Used by graph composition to normalise a possibly-lazy
   `:seq` binding before handing it to operations that depend on
   vector semantics (e.g. `into`'s append-on-vector vs prepend-on-seq
   behaviour)."
  [coll]
  (vec coll))


(defbase pairs->map-fn [entries]
  ;; `into {}` needs each entry to be a vector / map-entry; a pair
  ;; built via `:list` is a lazy-seq, so coerce each with `vec`.
  ;; Graph decomposition attempted + reverted — see the blocker note
  ;; on the fns.edn declaration.
  (into {} (map vec) entries))


(defn vec-return-rule
  "When the input is a known `[:list T]`, preserve the element type;
   otherwise fall back to the declared `[:list :any]`. Coercing a
   typed sequence to a vector is identity at the type level — `vec`
   just changes the runtime container, not the element story."
  [bindings-info default-ret]
  (let [coll-type (get-in bindings-info [:coll :type])]
    (cond
      (types/list-type? coll-type) coll-type
      :else                        default-ret)))


(defbase position-in-fn
  "Index of the first occurrence of `:value` in `:coll`, or nil if
   absent. Wraps `java.util.List/.indexOf`; the seq is `vec`'d first
   so a lazy `:seq` binding works too. Companion to `:get` for
   indexed-lookup style traversal."
  [coll value]
  (let [v (if (vector? coll) coll (vec coll))
        idx (java.util.List/.indexOf ^java.util.List v value)]
    (when-not (neg? idx) idx)))


;; === Type-rules ===
;; Per-base-fn rules — moved verbatim from graphden.types.rules.
;; Each `*-rule` is wired into the `impls` map below and looked up by
;; the type-checker through the rich-types-registry.

;; --- Shared helpers ---------------------------------------------------------

(defn- field-keyword-from-literal
  "Coerce a literal key value into a record field keyword. Keywords
   and strings work; anything else returns nil → degrade."
  [v]
  (cond
    (keyword? v) (keyword (name v))
    (string? v)  (keyword v)
    :else        nil))


(defn- path-seg-key
  "Literal field-keyword a sequence-path segment addresses, or nil
   when the segment is dynamic (a fn-ref / computed value) and can't
   be statically checked. Sequence-item bindings carry literal keys
   as `{:value :k}` maps — a BARE keyword in a sequence position is
   auto-resolved as a fn-ref, hence dynamic."
  [seg]
  (cond
    (and (map? seg) (contains? seg :value))
    (field-keyword-from-literal (:value seg))

    (string? seg) (field-keyword-from-literal seg)
    :else         nil))


;; --- :assoc -----------------------------------------------------------------
;; `(:assoc :map {…} :key "field" :value <value>)` returns the map's
;; record type with `{field-keyword (type-of v)}` assoc'd in, when
;; `:key` is a literal naming the field.
;;
;; Coverage:
;;   :map's type      :key (literal)      :value's type →   computed return
;;   {…} (record)     "name"              :text         →   {…name :text}
;;   :jsonb / :any    "name"              :text         →   (assoc default-ret
;;                                                            name :text) when
;;                                                            default-ret is a
;;                                                            record, else
;;                                                            {name :text}
;;   anything         (ref/computed)      anything      →   :jsonb (degrade)
;;
;; Using `default-ret` (the inherited return) as the base when `:map`
;; isn't a visible record is what keeps a descendant of a record-typed
;; assoc-chain — e.g. `:ring-response`, which declares
;; `:ring-response-shape` — from collapsing back to a fresh one-field
;; record when this rule re-fires further down the chain (where `:map`
;; surfaces as `:any`/`:jsonb`). A return-type rule must never widen
;; the type it inherited.
;;
;; `:assoc-fn` (the `:value`-as-fn fn-def variant, `:parent :assoc`)
;; inherits this rule through the type-checker's `root-base-fn-name`
;; walk — no separate rule needed.

(defn assoc-return-rule
  [bindings-info default-ret]
  (let [m-type   (get-in bindings-info [:map :type])
        k-value  (get-in bindings-info [:key :value])
        v-type   (get-in bindings-info [:value :type])
        field-kw (field-keyword-from-literal k-value)
        base     (cond (types/record-type? m-type)      m-type
                       (types/record-type? default-ret) default-ret
                       :else                            nil)]
    (cond
      ;; Computed key — can't name the field; can't refine soundly.
      (nil? field-kw) :jsonb

      base            (assoc base field-kw (or v-type :any))

      ;; Homogeneous `[:map K V]` source — preserve the shape but
      ;; widen the value-type to include the new field's type. Every
      ;; value in the result is either an original V or the new
      ;; `v-type`, so `[:union V v-type]` covers them. Without this
      ;; arm a record-as-headers builder over a `[:map :text :text]`
      ;; source would collapse to a fresh one-field record, losing
      ;; the open-map shape downstream consumers (`:ring-response`,
      ;; etc.) expect.
      (types/map-type? m-type)
      [:map (types/map-key m-type)
       (types/make-union [(types/map-val m-type) (or v-type :any)])]

      ;; Neither `:map` nor the inherited return is a known record —
      ;; start a fresh one-field record.
      :else           {field-kw (or v-type :any)})))


;; --- :dissoc ----------------------------------------------------------------
;; Remove a literal key from a record. Anything else → :jsonb.

(defn dissoc-return-rule
  [bindings-info default-ret]
  (let [m-type   (get-in bindings-info [:map :type])
        k-value  (get-in bindings-info [:key :value])
        field-kw (field-keyword-from-literal k-value)]
    (cond
      (nil? field-kw)
      :jsonb

      (types/record-type? m-type)
      (dissoc m-type field-kw)

      ;; Homogeneous `[:map K V]` — removing one entry leaves a map
      ;; with the same K and V (every remaining entry was already a
      ;; K → V mapping).
      (types/map-type? m-type)
      m-type

      :else default-ret)))


;; --- :get -------------------------------------------------------------------
;; Look up a field by literal key. Returns the field's type when `:m`
;; is a known record AND the field exists.

(declare get-return-rule)


(defn- get-return-for-coll-type
  "Per-branch `:get` typing — given ONE non-union `coll-type`, compute
   the result-type for `(get coll key default)`. Pulled out so the
   top-level rule can iterate union members and union their results
   without losing record-narrowing on each branch.

   Union-branch context (`:in-union? true`) suppresses the
   missing-field-on-known-record throw — inside a union, the absent
   field is legitimately covered by another branch, so the lookup is
   structurally valid; the result for THIS branch is the default's
   type (or `:null` when no default is bound)."
  [coll-type default-ret default-bound? dflt field-kw in-union?]
  (cond
    ;; `:null` coll → returns the default's type (or `:null`). This
    ;; mirrors Clojure's `(get nil k d)` → `d`. Most useful inside
    ;; unions like `[:union :null record]`.
    (= :null coll-type)
    (if default-bound? (or (:type dflt) default-ret) :null)

    ;; Homogeneous map — no fixed fields, so key presence is
    ;; unknowable at sync time: a lookup is value-or-default, or
    ;; value-or-nil when no default is bound.
    (types/map-type? coll-type)
    (let [v (types/map-val coll-type)]
      (if default-bound?
        (types/make-union [v (or (:type dflt) :any)])
        (types/make-union [:null v])))

    (nil? field-kw) default-ret

    (and (types/record-type? coll-type) (contains? coll-type field-kw))
    (get coll-type field-kw)

    ;; Field missing from a KNOWN record BUT `:default` is bound —
    ;; absence is explicitly handled, so the lookup is intentional,
    ;; not a typo. The result is unconditionally the default's type.
    (and (types/record-type? coll-type) default-bound?)
    (or (:type dflt) default-ret)

    ;; Field missing from a KNOWN record, no `:default`. Top-level:
    ;; that's a typo, throw with the field list. Union-branch:
    ;; another union member presumably covers this, so fall back to
    ;; `:null` (the implicit no-default semantics).
    (types/record-type? coll-type)
    (if in-union?
      :null
      (throw (ex-info (str ":get — field "
                           (pr-str field-kw)
                           " not found in record. Available: "
                           (pr-str (sort (keys coll-type))))
                      {:type :types/check-failed
                       :rule :get
                       :reason :missing-field
                       :field field-kw
                       :record coll-type})))

    :else default-ret))


(defn get-return-rule
  [bindings-info default-ret]
  (let [coll-type (get-in bindings-info [:coll :type])
        k-value   (get-in bindings-info [:key :value])
        field-kw  (field-keyword-from-literal k-value)
        dflt      (get bindings-info :default)
        ;; `:default` is genuinely BOUND only when its entry carries a
        ;; value or a ref. Parent-arg fallback injects a bare
        ;; `{:type T :value nil}` entry for every `:get` child, so
        ;; `(contains? bindings-info :default)` is ALWAYS true and
        ;; can't tell "bound" from "free" — using it silently killed
        ;; the missing-field typo throw below.
        default-bound? (boolean (and dflt
                                     (or (some? (:value dflt))
                                         (some? (:ref dflt)))))]
    (if (types/union-type? coll-type)
      ;; Union — narrow each member individually and union the
      ;; results. E.g. `:coll [:union :null record]` with a literal
      ;; key looking up a record field, `:default nil` →
      ;; `[:union :null <field-type>]`. Without this, the entire
      ;; union opaqued out to `default-ret` (`:any`) because the
      ;; record-type? checks never fired on the union itself.
      (->> (types/union-members coll-type)
           (mapv (fn [member]
                   (get-return-for-coll-type member default-ret
                                             default-bound? dflt field-kw
                                             true)))
           types/make-union)
      (get-return-for-coll-type coll-type default-ret
                                default-bound? dflt field-kw false))))


;; --- :merge -----------------------------------------------------------------
;; N-ary; union the fields of every input record, later items winning
;; on key collisions (matches clojure.core/merge semantics).
;; `:elem-types` carries the per-item types when `:maps` is bound to a
;; literal vector of refs/values; if every item is a record we can
;; rebuild the exact merged shape. Otherwise falls through to the
;; declared return — `[:map :any :any]` — because we don't track
;; per-key narrowing on heterogeneous merges. (The previous fallback
;; `(or maps-type default-ret)` returned `[:list T]` — the slot's own
;; type — which is structurally wrong: `:merge` produces a map, never
;; a list.)

(defn merge-return-rule
  [bindings-info default-ret]
  (let [elem-types (get-in bindings-info [:maps :elem-types])]
    (cond
      (not (and (sequential? elem-types) (seq elem-types)))
      default-ret

      ;; All record-types → rebuild the exact merged shape.
      (every? types/record-type? elem-types)
      (reduce merge {} elem-types)

      ;; All homogeneous `[:map K V]` with the same K and V → preserve.
      ;; This narrows e.g. `(merge {\"A\" \"B\"} {\"C\" \"D\"})` from
      ;; the declared `[:map :any :any]` to the actual `[:map :text :text]`,
      ;; so action-headers / merge-in-result flows downstream keep the
      ;; tighter shape they actually have at runtime.
      (and (every? types/map-type? elem-types)
           (apply = (map types/map-key elem-types))
           (apply = (map types/map-val elem-types)))
      (first elem-types)

      :else default-ret)))


;; --- :select-keys -----------------------------------------------------------
;; When `:m` is a known record and `:ks` is a literal vector of
;; `{:value :kw}` items, the result is the subset record carrying
;; exactly those fields. For a homogeneous `[:map K V]` source the
;; result is the same `[:map K V]` (key/val types unchanged).
;; Anything else degrades to the declared `[:map :keyword :any]`.

(defn select-keys-return-rule
  [bindings-info default-ret]
  (let [m-type (get-in bindings-info [:m :type])
        ks-form (get-in bindings-info [:ks :value])
        literal-kws (when (vector? ks-form)
                      (mapv (fn [item]
                              (when (and (map? item) (contains? item :value))
                                (field-keyword-from-literal (:value item))))
                            ks-form))
        all-literal? (and (vector? ks-form)
                          (seq ks-form)
                          (every? some? literal-kws))]
    (cond
      (and (types/record-type? m-type) all-literal?)
      (select-keys m-type literal-kws)

      (types/map-type? m-type) m-type

      :else default-ret)))


;; --- :update-vals -----------------------------------------------------------
;; `(update-vals m f)` keeps `m`'s key shape, replacing each value
;; with `(f val)`. When the input is a homogeneous `[:map K V]` and
;; the HOF callback `:f` has a known return type W, the result is
;; `[:map K W]`. Record inputs would need per-field independent
;; unification of `:f`'s `value` arg against each field's type —
;; we don't (yet) widen the HOF's lambda-param across heterogeneous
;; inputs, so fall back to the declared shape for that case.

(defn update-vals-return-rule
  [bindings-info default-ret]
  (let [m-type (get-in bindings-info [:m :type])
        f-type (get-in bindings-info [:f :type])
        f-ret  (when (types/fn-type? f-type) (types/fn-ret f-type))]
    (cond
      (and (types/map-type? m-type) f-ret)
      [:map (types/map-key m-type) f-ret]

      :else default-ret)))


(defn update-keys-return-rule
  "`(update-keys m f)` keeps values, transforms keys. For a
   homogeneous `[:map K V]` input and a HOF callback with a known
   return type W, the result is `[:map W V]` (the W transform may
   collide multiple K → same W, but the resulting value type is
   still V — `update-keys` keeps the latter on collision)."
  [bindings-info default-ret]
  (let [m-type (get-in bindings-info [:m :type])
        f-type (get-in bindings-info [:f :type])
        f-ret  (when (types/fn-type? f-type) (types/fn-ret f-type))]
    (cond
      (and (types/map-type? m-type) f-ret)
      [:map f-ret (types/map-val m-type)]

      :else default-ret)))


;; --- :zipmap ----------------------------------------------------------------
;; When `:keys` is a literal vector of `{:value <kw-or-string>}` items,
;; the result is a record-type whose fields are exactly those keys and
;; whose value-types come from `:vals`'s per-item `:elem-types`. This
;; is the canonical Ring-response builder pattern
;; (`:zipmap :keys [{:value :status} {:value :body}] :vals [...]`) —
;; preserving the record shape lets downstream `:assoc` / `:merge`
;; chains keep per-field type info instead of collapsing the inherited
;; `[:map :keyword :any]` declared return.

(defn zipmap-return-rule
  [bindings-info default-ret]
  (let [keys-form (get-in bindings-info [:keys :value])
        val-elems (get-in bindings-info [:vals :elem-types])
        literal-kws (when (vector? keys-form)
                      (mapv (fn [item]
                              (when (and (map? item) (contains? item :value))
                                (field-keyword-from-literal (:value item))))
                            keys-form))]
    (if (and (vector? keys-form)
             (seq keys-form)
             (every? some? literal-kws)
             (sequential? val-elems)
             (= (count literal-kws) (count val-elems)))
      (zipmap literal-kws val-elems)
      default-ret)))


;; --- :update-in / :merge-in -------------------------------------------------
;; Preserve the input map's shape.
;;
;; `:update-in :args {:m :M :path :P :f :F}` returns m with the value
;; at path replaced by `(f (get-in m path))`. The TOP-LEVEL shape
;; (which fields exist at the root) doesn't change — only one nested
;; value is replaced — so the returned record has the SAME field set
;; as :m. Without a rule, the inferred return is `:any`, which breaks
;; the structural-typing chain on Ring handlers built from
;; `:router-ring-response` (`:merge-in` with `:m :router-result`).
;;
;; `:merge-in` inherits from `:update-in`, so this rule covers both
;; via `root-base-fn-name`'s walk.

(defn update-in-return-rule
  [bindings-info default-ret]
  (let [m-type   (get-in bindings-info [:m :type])
        path-val (get-in bindings-info [:path :value])]
    ;; Validate the literal path against m's structure: every segment
    ;; that navigates a KNOWN record must name one of its fields. A
    ;; segment naming an absent field is a typo (mirrors `:get`'s
    ;; field check, extended to a multi-level path). Descent stops
    ;; once the structure is no longer a known record (`:jsonb` /
    ;; `:any` — deeper keys can't be validated) or a segment is
    ;; dynamic (a fn-ref — value unknown at sync time).
    (when (sequential? path-val)
      (loop [t m-type, segs (seq path-val)]
        (when (and segs (types/record-type? t))
          (let [k (path-seg-key (first segs))]
            (cond
              (nil? k)        nil
              (contains? t k) (recur (get t k) (next segs))
              :else
              (throw (ex-info (str ":update-in — path segment "
                                   (pr-str k)
                                   " not found in record. Available: "
                                   (pr-str (sort (keys t))))
                              {:type :types/check-failed
                               :rule :update-in
                               :reason :missing-field
                               :field k
                               :record t})))))))
    (or m-type default-ret)))


;; When `:m` is a known record, `:update-in`'s `:path` navigates
;; record structure — every segment that lands on a record addresses
;; a field KEYWORD (graphden records are keyword-keyed). Narrow the
;; `:path` slot from the generic `[:list :any]` to `[:list :keyword]`
;; so the editor chip reads `[keyword]` instead of `[any]`. Per-
;; position precision (which keys are valid at segment N, and whether
;; an N+1 segment exists at all) is the editor's job — it walks the
;; `:m` structure handed over by `update-in-nav-rule` below.
(defn update-in-slot-rule
  [bindings-info]
  (if (types/record-type? (get-in bindings-info [:m :type]))
    {:path [:list :keyword]}
    {}))


;; `:update-in`'s `:path` items index INTO `:m`'s record shape. Hand
;; the editor that structure (keyed by the `:path` slot) so it can
;; walk it against the live path: a record level yields a closed
;; key-set for the picker, a `:jsonb` sub-map a free keyword, a
;; scalar means no further segment is valid. `:merge-in` inherits
;; this via `root-base-fn-name`.
(defn update-in-nav-rule
  [bindings-info]
  (if (types/record-type? (get-in bindings-info [:m :type]))
    {:path (get-in bindings-info [:m :type])}
    {}))


;; --- List-shape rules -------------------------------------------------------
;; :first, :rest, :cons, :take, :drop, :reverse, :sort, :distinct.
;;
;; All read `:coll` and either lift the elem-type or preserve the same
;; `[:list T]`. Two helpers cover both shapes; each rule is then a
;; single-line dispatch.
;;
;; The fn IS allowed to return nil for empty collections at runtime;
;; that's covered by `:null ⊆ :any` and the type-check's leniency for
;; null actuals. Returning the precise elem type rather than `:any`
;; lets downstream uses (e.g. `(:add (:first ints) 1)`) type-check
;; instead of degrading to :jsonb.

(defn- list-elem-of-arg
  "If the named arg is bound to a `[:list T]`, return T; else nil."
  [bindings-info arg-name]
  (let [t (get-in bindings-info [arg-name :type])]
    (when (types/list-type? t) (types/list-elem t))))


(defn- list-of-arg
  "If the named arg is bound to a `[:list T]`, return that whole
   `[:list T]`; else nil."
  [bindings-info arg-name]
  (let [t (get-in bindings-info [arg-name :type])]
    (when (types/list-type? t) t)))


(defn- preserve-coll-list
  "All same-shape ops on `:coll` simply preserve `[:list T]`."
  [b d]
  (or (list-of-arg b :coll) d))


;; :first lifts `:coll`'s elem-type — but an empty / nil list yields
;; nil, so the result is `[:union :null T]`, not bare `T`.
(defn first-return-rule
  [b d]
  (if-let [t (list-elem-of-arg b :coll)]
    (types/make-union [:null t])
    d))


(defn rest-return-rule
  [b d]
  (preserve-coll-list b d))


(defn cons-return-rule
  [b d]
  (preserve-coll-list b d))


(defn take-return-rule
  [b d]
  (preserve-coll-list b d))


(defn drop-return-rule
  [b d]
  (preserve-coll-list b d))


(defn reverse-return-rule
  [b d]
  (preserve-coll-list b d))


(defn sort-return-rule
  [b d]
  (preserve-coll-list b d))


(defn distinct-return-rule
  [b d]
  (preserve-coll-list b d))


;; --- :keys / :vals ----------------------------------------------------------
;; When `:map` is a known record, the result is a list of either the
;; key keywords or the field-types respectively. We don't track
;; keyword-singleton types, so `:keys` falls back to `[:list :keyword]`.
;; `:vals` is more useful — `[:list (lub of vals)]` gives the precise
;; elem-type when all field types agree, else degrades to `:any`.

(defn keys-return-rule
  [b d]
  (let [m-type (get-in b [:map :type])]
    (cond
      (types/record-type? m-type) [:list :keyword]
      (types/map-type? m-type)    [:list (types/map-key m-type)]
      :else                       d)))


(defn vals-return-rule
  [b d]
  (let [m-type (get-in b [:map :type])]
    (cond
      (types/record-type? m-type) [:list (types/coarse-lub (vals m-type))]
      (types/map-type? m-type)    [:list (types/map-val m-type)]
      :else                       d)))


;; --- :concat ----------------------------------------------------------------
;; Flattens a sequence of lists. When `:colls` is itself
;; `[:list [:list T]]`, the result is `[:list T]`. We don't track that
;; nesting unless the literal-vector inference walked one level;
;; degrades to default otherwise.

(defn concat-return-rule
  [b d]
  (let [colls-type (get-in b [:colls :type])]
    (if (and (types/list-type? colls-type)
             (types/list-type? (types/list-elem colls-type)))
      (types/list-elem colls-type)
      d)))


(defn flatten-return-rule
  "One-level unnesting at the type level: `[:list [:list T]]` →
   `[:list T]`. `flatten` recurses deeper at runtime but we only
   model the common (and statically-trackable) one-level case;
   deeper nesting falls back to the declared `[:list :any]`."
  [bindings-info default-ret]
  (let [coll-type (get-in bindings-info [:coll :type])]
    (if (and (types/list-type? coll-type)
             (types/list-type? (types/list-elem coll-type)))
      (types/list-elem coll-type)
      default-ret)))


;; --- :list ------------------------------------------------------------------
;; `(:list :items [a b c …])` constructs a vector. The binding-info
;; upstream already lubs the elem types into the `:items :type` (it's
;; a `:sequence`-typed slot, so the vector binding rewrites it to
;; `[:list (lub …)]`). Lift that here so the rule returns the precise
;; list-shape instead of bare `:jsonb`.

(defn list-return-rule
  [b d]
  (let [items-type (get-in b [:items :type])]
    (if (types/list-type? items-type)
      items-type
      d)))


;; --- :range / :repeat -------------------------------------------------------
;; `:range` always builds an integer vector. `:repeat` builds a vector
;; of copies of its `:item`, so the result is `[:list <item-type>]`.

(defn range-return-rule
  [_b _d]
  [:list :int])


(defn repeat-return-rule
  [b _d]
  [:list (or (get-in b [:item :type]) :any)])


;; --- :conj ------------------------------------------------------------------
;; `(:conj :coll C :item X)` adds X to C. When C is a known `[:list T]`
;; and X's type ⊆ T, the result is the same `[:list T]`; when X widens
;; the element type, the rule returns `[:list (lub …)]` so callers see
;; the broadened shape.

(defn conj-return-rule
  [b d]
  (let [coll-type (get-in b [:coll :type])
        item-type (get-in b [:item :type])]
    (cond
      (and (types/list-type? coll-type) item-type)
      (let [old (types/list-elem coll-type)]
        (if (= old item-type)
          coll-type
          [:list (types/coarse-lub [old item-type])]))

      (types/list-type? coll-type) coll-type
      :else d)))


;; --- :into ------------------------------------------------------------------
;; Preserve the destination collection's type. `:into` returns the
;; same SHAPE as `:to`:
;; - `[:list T]` → `[:list T]` regardless of `:from`.
;; - `[:map K V]` / record → same map shape.
;; - `:empty-map` (literal `{}`) → `[:map :any :any]` (a fresh map
;;   that downstream may narrow when keys/values become known; the
;;   common case is `:into {} <pairs>`).
;; Without these arms `:into`'s declared `[:union [:map a :any]
;; [:list :any]]` leaks the polymorphic union into every downstream
;; consumer that bound `:to` to a literal map (e.g. `:parse-form-body`
;; via `:_qs-pairs-as-map`'s `:to {:value {}}`).

(defn into-return-rule
  [bindings-info default-ret]
  (let [to-type (get-in bindings-info [:to :type])]
    (cond
      (or (types/list-type? to-type)
          (types/map-type? to-type)
          (types/record-type? to-type)) to-type
      (= to-type :empty-map)            [:map :any :any]
      :else                             default-ret)))


;; --- :assoc-in --------------------------------------------------------------
;; Walk a literal key-path through nested records and replace the
;; deepest field's type with `:v`'s. Falls back to default if the path
;; can't be statically resolved (non-literal path or segments not
;; present in a known record).

(defn assoc-in-return-rule
  [bindings-info default-ret]
  (let [m-type   (get-in bindings-info [:m :type])
        path-val (get-in bindings-info [:path :value])
        v-type   (get-in bindings-info [:v :type])]
    (if (and (sequential? path-val)
             (every? #(or (keyword? %) (string? %)) path-val)
             (seq path-val))
      ;; `[:map K V]` intermediates / leaves preserve their shape:
      ;; `(assoc-in [:map K V] [k] v)` is still `[:map K V]` (homogeneous
      ;; map, we don't track per-key widening). A missing intermediate
      ;; in a map is nil-safe at runtime (`(assoc-in nil [k] v)` builds
      ;; a fresh `{k v}`), so the result is non-null either way.
      (letfn [(set-deep
                [t segs]
                (let [k (field-keyword-from-literal (first segs))
                      rest-segs (rest segs)]
                  (cond
                    (nil? k) nil
                    (empty? rest-segs)
                    (cond
                      (types/record-type? t) (assoc t k (or v-type :any))
                      (types/map-type? t)    t
                      (or (= t :any) (= t :jsonb) (nil? t))
                      {k (or v-type :any)}
                      :else nil)
                    :else
                    (let [child (cond (types/record-type? t) (get t k :any)
                                      (types/map-type? t)    (types/map-val t)
                                      :else                  :any)
                          updated-child (set-deep child rest-segs)]
                      (when updated-child
                        (cond
                          (types/record-type? t) (assoc t k updated-child)
                          (types/map-type? t)    t
                          :else                  {k updated-child}))))))]
        (or (set-deep m-type path-val) default-ret))
      default-ret)))


;; --- :get-in ----------------------------------------------------------------
;; Walk a record by literal-key path. When the path is a sequence of
;; literal keys AND every intermediate is a record, return the
;; deeply-nested field type. Any non-record intermediate or non-literal
;; key falls back to default.
;;
;; The `:path` arg is a `:sequence`-typed slot — bindings-info has its
;; literal items collected by check-fn-def!. We accept either a vector
;; value (rare here, since sequence args travel through arg-chain) or
;; fall through to default.

(defn- get-in-walk
  "Walk one non-union `t` through `segs` (a sequence of literal
   keyword/string keys). Returns the deeply-nested type, or
   `default-ret` on the first non-record / non-map / missing-key
   step. `nullable?` accumulates: any `[:map K V]` intermediate
   makes the final result nullable (key may be absent there).

   Encountering a union mid-walk fans out: each member is walked
   with the remaining segs and the results are unioned. `:null`
   members of the union narrow to a `:null` leaf — `(get-in nil
   anything)` is nil regardless of the path."
  [t segs nullable? default-ret]
  (cond
    (empty? segs)
    (if nullable? (types/make-union [:null t]) t)

    (= :null t) :null

    (types/union-type? t)
    (->> (types/union-members t)
         (mapv #(get-in-walk % segs nullable? default-ret))
         types/make-union)

    (types/record-type? t)
    (let [k (field-keyword-from-literal (first segs))]
      (if (and k (contains? t k))
        (get-in-walk (get t k) (rest segs) nullable? default-ret)
        default-ret))

    (types/map-type? t)
    (get-in-walk (types/map-val t) (rest segs) true default-ret)

    :else default-ret))


;; --- :get-in ----------------------------------------------------------------
;; Walk a record by literal-key path. When the path is a sequence of
;; literal keys AND every intermediate is a record, return the
;; deeply-nested field type. Any non-record intermediate or non-literal
;; key falls back to default.
;;
;; The `:path` arg is a `:sequence`-typed slot — bindings-info has its
;; literal items collected by check-fn-def!. We accept either a vector
;; value (rare here, since sequence args travel through arg-chain) or
;; fall through to default.

(defn get-in-return-rule
  [bindings-info default-ret]
  (let [m-type    (get-in bindings-info [:map :type])
        path-val  (get-in bindings-info [:path :value])]
    (if (and (sequential? path-val)
             (every? #(or (keyword? %) (string? %)) path-val))
      ;; `nullable?` becomes true once the walk traverses a
      ;; `[:map K V]` intermediate — the key MAY be absent there, so
      ;; the final result is `[:union :null T]`. Records (closed,
      ;; total) keep nullability false; navigating off a known record
      ;; (missing field) falls through to default — the typo path
      ;; the per-record rule already exercises.
      (get-in-walk m-type path-val false default-ret)
      default-ret)))


;; === Registry ===

;; A value is either a bare impl fn or a `{:impl … :*-rule …}` map
;; carrying the base-fn's type-rule(s). The loader normalises both.
;; Every base-fn here is content-passing — a collection op that pulls
;; an item out of a coll, packs it back in, or projects across it.
;; Anything that takes a coll-of-secret or a secret-key/value must
;; mark its result `[:secret …]` so the downstream type-check can't
;; drop the marker. We compose each existing structural rule with the
;; `:secret`-propagator via the registry's `:taint-propagate?` flag
;; (applied centrally by the checker); entries with no
;; previous rule become a bare propagator.
(def impls
  {:first {:impl first-fn :return-type-rule first-return-rule :taint-propagate? true}
   :rest {:impl rest-fn :return-type-rule rest-return-rule :taint-propagate? true}
   :cons {:impl cons-fn :return-type-rule cons-return-rule :taint-propagate? true}
   :conj {:impl conj-any-fn :return-type-rule conj-return-rule :taint-propagate? true}
   :get {:impl get-fn :return-type-rule get-return-rule :taint-propagate? true}
   :get-in {:impl get-in-fn :return-type-rule get-in-return-rule :taint-propagate? true}
   :assoc {:impl assoc-any-fn :return-type-rule assoc-return-rule :taint-propagate? true}
   :dissoc {:impl dissoc-fn :return-type-rule dissoc-return-rule :taint-propagate? true}
   :count {:impl count-fn :taint-propagate? true}
   :empty? {:impl empty?-fn :taint-propagate? true}
   :contains? {:impl contains?-fn :taint-propagate? true}
   :keys {:impl keys-fn :return-type-rule keys-return-rule :taint-propagate? true}
   :vals {:impl vals-fn :return-type-rule vals-return-rule :taint-propagate? true}
   :merge {:impl merge-fn :return-type-rule merge-return-rule :taint-propagate? true}
   :into {:impl into-fn :return-type-rule into-return-rule :taint-propagate? true}
   :assoc-in {:impl assoc-in-fn :return-type-rule assoc-in-return-rule :taint-propagate? true}
   :range {:impl range-fn :return-type-rule range-return-rule :taint-propagate? true}
   :repeat {:impl repeat-fn :return-type-rule repeat-return-rule :taint-propagate? true}
   :take {:impl take-fn :return-type-rule take-return-rule :taint-propagate? true}
   :drop {:impl drop-fn :return-type-rule drop-return-rule :taint-propagate? true}
   :reverse {:impl reverse-fn :return-type-rule reverse-return-rule :taint-propagate? true}
   :sort {:impl sort-fn :return-type-rule sort-return-rule :taint-propagate? true}
   :concat {:impl concat-fn :return-type-rule concat-return-rule :taint-propagate? true}
   :flatten {:impl flatten-fn :return-type-rule flatten-return-rule :taint-propagate? true}
   :distinct {:impl distinct-fn :return-type-rule distinct-return-rule :taint-propagate? true}
   :select-keys {:impl select-keys-fn :return-type-rule select-keys-return-rule :taint-propagate? true}
   :zipmap {:impl zipmap-fn :return-type-rule zipmap-return-rule :taint-propagate? true}
   :update-vals {:impl update-vals-fn :return-type-rule update-vals-return-rule :taint-propagate? true}
   :update-keys {:impl update-keys-fn :return-type-rule update-keys-return-rule :taint-propagate? true}
   :postwalk {:impl postwalk-fn :taint-propagate? true}
   :update-in {:impl update-in-fn
               :return-type-rule update-in-return-rule :taint-propagate? true
               :slot-types-rule update-in-slot-rule
               :nav-types-rule update-in-nav-rule}
   :list {:impl list-fn :return-type-rule list-return-rule :taint-propagate? true}
   :vec {:impl vec-fn :return-type-rule vec-return-rule :taint-propagate? true}
   :pairs->map {:impl pairs->map-fn :taint-propagate? true}
   :position-in {:impl position-in-fn :taint-propagate? true}})
