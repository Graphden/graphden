(ns graphden.types.rules
  "Type-rules — base-fn-specific logic that COMPUTES a fn-def's
   effective return type from its bindings. Phase 3 of TYPES.md.

   ## Why type-rules

   In graphden, argument values are often concrete literals stored in
   the DB (because the graph stores composition descriptions, not
   runtime programs). When a user writes `(:assoc :m {} :k \"name\"
   :v \"Alice\")`, the system KNOWS the key is `\"name\"` at sync
   time. That's enough to compute the resulting record type
   `{:name :text}` precisely — no SMT solver needed.

   This is what makes graphden's type system stronger than a plain
   Hindley-Milner: dependent-type-like behaviour over literal keys.
   Mismatches like `(:get-field :obj user :field \"emial\")` (typo'd
   field name) become save-time errors.

   ## Shape

   Each rule is a `defmethod` of `compute-return-type` keyed by the
   base-fn name. It receives:

     base-fn-name    — keyword (the dispatch key, also passed as 1st arg)
     bindings-info   — `{arg-name {:type … :value …}}` per bound arg
                       :type is the inferred type after type-check;
                       :value is the original literal (when known)
                       — nil when the binding is a ref or computed.
     default-ret     — the static `:return-type` from the parent's
                       signature; the rule returns this unchanged
                       when it can't refine.

   Returns the COMPUTED return-type for the fn-def. Callers stash
   this into the registry so downstream uses see the precise shape.

   ## Defaults

   The `:default` method passes `default-ret` through unchanged —
   i.e. base-fns without a custom rule behave exactly as they did
   before Phase 3."
  (:require
    [graphden.types.core :as types]))


(defmulti compute-return-type
  "Multimethod dispatched on base-fn name.
   See `(doc graphden.types.rules)` for the contract."
  {:arglists '([base-fn-name bindings-info default-ret])}
  (fn [base-fn-name _bindings-info _default-ret] base-fn-name))


(defmethod compute-return-type :default
  [_ _ default-ret]
  default-ret)


;; -----------------------------------------------------------------------------
;; :assoc — `(:assoc :map {…} :key "field" :value <value>)` returns
;; `(merge m {field-keyword (type-of v)})` when `:key` is a literal
;; that names the field. Falls back to `:jsonb` otherwise.
;;
;; Coverage:
;;   :map's type      :key (literal)      :value's type →   computed return
;;   {…} (record)     "name"              :text         →   {…name :text}
;;   :jsonb           "name"              :text         →   {name :text}
;;   :any             "name"              :text         →   {name :text}
;;   anything         (ref/computed)      anything      →   :jsonb (degrade)
;;
;; The `:key` binding's `:value` is what dictates the field name. When
;; `:key` is keyword-shaped (`:name`), the field name is its `name`;
;; when it's a string, the same. Anything else degrades.

(defn- field-keyword-from-literal
  "Coerce a literal key value into a record field keyword. Keywords
   and strings work; anything else returns nil → degrade."
  [v]
  (cond
    (keyword? v) (keyword (name v))
    (string? v)  (keyword v)
    :else        nil))


(defn- assoc-record-builder
  "Shared body for `:assoc` / `:assoc-fn`. They differ only in the
   declared type of the `:value` slot (`:any` vs `:fn`); the return-
   type rule is the same — extend or build a record from the literal
   key + value's type."
  [bindings-info]
  (let [m-type   (get-in bindings-info [:map :type])
        k-value  (get-in bindings-info [:key :value])
        v-type   (get-in bindings-info [:value :type])
        field-kw (field-keyword-from-literal k-value)]
    (cond
      (nil? field-kw)
      ;; Computed key — value unknown at sync time, can't refine.
      :jsonb

      (types/record-type? m-type)
      (assoc m-type field-kw (or v-type :any))

      ;; `:map` is `:any`, `:jsonb`, etc. — start a fresh record.
      :else
      {field-kw (or v-type :any)})))


(defmethod compute-return-type :assoc
  [_ bindings-info _default-ret]
  (assoc-record-builder bindings-info))


;; :assoc-fn — `:value`-as-fn variant; same record-building semantics
;; for the return-type. Without this rule, route-builder chains
;; (`:assoc-handler` → `:assoc-fn` → `:assoc-empty`) lose the record
;; structure the moment a route's `:handler` slot is bound.
(defmethod compute-return-type :assoc-fn
  [_ bindings-info _default-ret]
  (assoc-record-builder bindings-info))


;; -----------------------------------------------------------------------------
;; :dissoc — remove a literal key from a record. Anything else → :jsonb.

(defmethod compute-return-type :dissoc
  [_ bindings-info default-ret]
  (let [m-type   (get-in bindings-info [:map :type])
        k-value  (get-in bindings-info [:key :value])
        field-kw (field-keyword-from-literal k-value)]
    (cond
      (nil? field-kw)
      :jsonb

      (types/record-type? m-type)
      (dissoc m-type field-kw)

      :else default-ret)))


;; -----------------------------------------------------------------------------
;; :get / :get-field — look up a field by literal key. Returns the
;; field's type when `:m` is a known record AND the field exists.

(defmethod compute-return-type :get
  [_ bindings-info default-ret]
  (let [coll-type (get-in bindings-info [:coll :type])
        k-value   (get-in bindings-info [:key :value])
        field-kw  (field-keyword-from-literal k-value)]
    (cond
      (nil? field-kw) default-ret

      (and (types/record-type? coll-type) (contains? coll-type field-kw))
      (get coll-type field-kw)

      ;; Field literally missing from a KNOWN record. The user wrote a
      ;; literal key that doesn't exist in the record's known fields —
      ;; that's a typo, not a runtime case. Throw with the available
      ;; field list so the user can spot the misspelling.
      (types/record-type? coll-type)
      (throw (ex-info (str ":get — field "
                           (pr-str field-kw)
                           " not found in record. Available: "
                           (pr-str (sort (keys coll-type))))
                      {:type :types/check-failed
                       :rule :get
                       :reason :missing-field
                       :field field-kw
                       :record coll-type}))

      :else default-ret)))


;; -----------------------------------------------------------------------------
;; :merge — N-ary; just OR the fields of every input record.
;; Falls back to :jsonb when any input is non-record.

(defmethod compute-return-type :merge
  [_ bindings-info default-ret]
  (let [maps-type (get-in bindings-info [:maps :type])]
    ;; :maps is a sequence; we can't peek at items here without
    ;; deeper integration. Defer to default for now.
    (or maps-type default-ret)))


;; -----------------------------------------------------------------------------
;; :update-in / :merge-in — preserve the input map's shape.
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

(defmethod compute-return-type :update-in
  [_ bindings-info default-ret]
  (or (get-in bindings-info [:m :type]) default-ret))


;; -----------------------------------------------------------------------------
;; :invoke — `(:invoke :func F :arg A)` calls F on A and returns
;; whatever F returns. F is `:fn`-typed; if its rich-type is known
;; (callee resolved at sync time) we can lift its return.
;;
;; Without this rule, every `:invoke` chain (router-result, etc.)
;; degrades to :any, severing structural propagation.

(defmethod compute-return-type :invoke
  [_ bindings-info default-ret]
  (let [func-type (get-in bindings-info [:func :type])]
    (cond
      ;; Best case: the bound :func has a structural fn-type and we
      ;; can read its return directly.
      (types/fn-type? func-type)
      (types/fn-ret func-type)
      :else default-ret)))


;; -----------------------------------------------------------------------------
;; :const / :identity — `(:const :value V)` returns V. `:identity`
;; is `:parent :const` so dispatch on `:const` covers both. Used by
;; graphden renames (`:value {:as :request :type :ring-request-shape}`
;; → `:ring-request` exposes `:value` as the free arg `:request`,
;; type flows through). Without this rule the result type defaults
;; to the polymorphic `'a` and all structural propagation upstream
;; is lost. The standard polymorphic `'a → 'a` rule SHOULD work via
;; unify, but type-var binding only kicks in when the slot's `:type`
;; is consulted at the rule level — wiring it explicitly here
;; surfaces the type-aware rename without depending on subst lookup.

(defmethod compute-return-type :const
  [_ bindings-info default-ret]
  (or (get-in bindings-info [:value :type]) default-ret))


;; -----------------------------------------------------------------------------
;; List-shape rules — :first, :rest, :cons, :take, :drop, :reverse,
;; :sort, :distinct.
;;
;; All read `:coll` (or `:colls`) and either lift the elem-type or
;; preserve the same `[:list T]`. Two helpers cover both shapes; each
;; defmethod is then a single-line dispatch.
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


;; :first lifts the elem-type out of `:coll`.
(defmethod compute-return-type :first
  [_ b d]
  (or (list-elem-of-arg b :coll) d))


;; All same-shape ops on `:coll` simply preserve `[:list T]` —
;; differ only in name; one helper covers them.
(defn- preserve-coll-list
  [b d]
  (or (list-of-arg b :coll) d))


(defmethod compute-return-type :rest     [_ b d] (preserve-coll-list b d))
(defmethod compute-return-type :cons     [_ b d] (preserve-coll-list b d))
(defmethod compute-return-type :take     [_ b d] (preserve-coll-list b d))
(defmethod compute-return-type :drop     [_ b d] (preserve-coll-list b d))
(defmethod compute-return-type :reverse  [_ b d] (preserve-coll-list b d))
(defmethod compute-return-type :sort     [_ b d] (preserve-coll-list b d))
(defmethod compute-return-type :distinct [_ b d] (preserve-coll-list b d))


;; -----------------------------------------------------------------------------
;; :keys / :vals — when `:map` is a known record, the result is a list
;; of either the key keywords or the field-types respectively. We
;; don't track keyword-singleton types, so `:keys` falls back to
;; `[:list :keyword]`. `:vals` is more useful — `[:list (lub of vals)]`
;; gives the precise elem-type when all field types agree, else
;; degrades to `:any`.

(defn- lub
  "Coarse least-upper-bound: all equal → that type, else `:any`."
  [types]
  (let [ts (set types)]
    (cond
      (empty? ts)      :any
      (= 1 (count ts)) (first ts)
      :else            :any)))


(defmethod compute-return-type :keys
  [_ b d]
  (let [m-type (get-in b [:map :type])]
    (if (types/record-type? m-type) [:list :keyword] d)))


(defmethod compute-return-type :vals
  [_ b d]
  (let [m-type (get-in b [:map :type])]
    (if (types/record-type? m-type) [:list (lub (vals m-type))] d)))


;; -----------------------------------------------------------------------------
;; :count — already declares `:return-type :int` statically; nothing
;; to add here. Listed for documentation completeness:
;;   no rule needed — static `:int` is already precise.


;; -----------------------------------------------------------------------------
;; :concat — flattens a sequence of lists. When `:colls` is itself
;; `[:list [:list T]]`, the result is `[:list T]`. We don't track
;; that nesting unless the literal-vector inference walked one level;
;; degrades to default otherwise.

(defmethod compute-return-type :concat
  [_ b d]
  (let [colls-type (get-in b [:colls :type])]
    (if (and (types/list-type? colls-type)
             (types/list-type? (types/list-elem colls-type)))
      (types/list-elem colls-type)
      d)))


;; -----------------------------------------------------------------------------
;; Arithmetic narrowing — when every input is `:int`, the result of an
;; integer-preserving op stays `:int`. Falls back to the parent's
;; declared :numeric otherwise.
;;
;; :div is omitted: in Clojure `(/ 10 3)` returns a rational, which
;; we represent as :numeric. Narrowing it to :int would be wrong.

(defn- nums-elem-type
  "When `:nums` (the sequence-arg every arithmetic base-fn carries)
   is bound to a typed list, return the element type. Otherwise nil."
  [bindings-info]
  (let [t (get-in bindings-info [:nums :type])]
    (when (types/list-type? t) (types/list-elem t))))


(defn- narrow-numeric-to-int
  [bindings-info default-ret]
  (if (= :int (nums-elem-type bindings-info)) :int default-ret))


(defmethod compute-return-type :add [_ b d] (narrow-numeric-to-int b d))
(defmethod compute-return-type :sub [_ b d] (narrow-numeric-to-int b d))
(defmethod compute-return-type :mul [_ b d] (narrow-numeric-to-int b d))


(defmethod compute-return-type :mod
  [_ bindings-info default-ret]
  ;; :mod takes named scalar args, not :nums.
  (let [a (get-in bindings-info [:dividend :type])
        b (get-in bindings-info [:divisor :type])]
    (if (and (= :int a) (= :int b)) :int default-ret)))


(defmethod compute-return-type :neg
  [_ bindings-info default-ret]
  (if (= :int (get-in bindings-info [:number :type])) :int default-ret))


(defmethod compute-return-type :abs
  [_ bindings-info default-ret]
  (if (= :int (get-in bindings-info [:number :type])) :int default-ret))


;; -----------------------------------------------------------------------------
;; :into — preserve the destination collection's type. If `:to` is
;; `[:list T]`, the result is `[:list T]` regardless of `:from`.
;; If `:to` is a known record, `:into` won't typically be used (it
;; would conj key-value pairs), so we degrade.

(defmethod compute-return-type :into
  [_ bindings-info default-ret]
  (let [to-type (get-in bindings-info [:to :type])]
    (if (types/list-type? to-type) to-type default-ret)))


;; -----------------------------------------------------------------------------
;; :assoc-in — walk a literal key-path through nested records and
;; replace the deepest field's type with `:v`'s. Falls back to default
;; if the path can't be statically resolved (non-literal path or
;; segments not present in a known record).

(defmethod compute-return-type :assoc-in
  [_ bindings-info default-ret]
  (let [m-type   (get-in bindings-info [:m :type])
        path-val (get-in bindings-info [:path :value])
        v-type   (get-in bindings-info [:v :type])]
    (if (and (sequential? path-val)
             (every? #(or (keyword? %) (string? %)) path-val)
             (seq path-val))
      (letfn [(set-deep
                [t segs]
                (let [k (field-keyword-from-literal (first segs))
                      rest-segs (rest segs)]
                  (cond
                    (nil? k) nil
                    (empty? rest-segs)
                    (cond
                      (types/record-type? t) (assoc t k (or v-type :any))
                      (or (= t :any) (= t :jsonb) (nil? t))
                      {k (or v-type :any)}
                      :else nil)
                    :else
                    (let [child (cond (types/record-type? t) (get t k :any)
                                      :else :any)
                          updated-child (set-deep child rest-segs)]
                      (when updated-child
                        (cond
                          (types/record-type? t) (assoc t k updated-child)
                          :else                  {k updated-child}))))))]
        (or (set-deep m-type path-val) default-ret))
      default-ret)))


;; -----------------------------------------------------------------------------
;; :if — no rule needed. Both branches share the type-var `'a` in the
;; `:if` declaration, so unify pins 'a to the common type during
;; check-bindings; `static-ret` (the resolved 'a) is what we want.
;; A bespoke rule that built `[:union :then-type :else-type]` from
;; bindings-info would step on that — it sees the raw branch types
;; without the substitution applied, so `(:if test (:throw …) :int)`
;; would degrade to `[:union 'a :int]` instead of unifying to `:int`.


;; -----------------------------------------------------------------------------
;; :get-in — walk a record by literal-key path. When the path is a
;; sequence of literal keys AND every intermediate is a record, return
;; the deeply-nested field type. Any non-record intermediate or
;; non-literal key falls back to default.
;;
;; The `:path` arg is a `:sequence`-typed slot — bindings-info has its
;; literal items collected by check-fn-def! (see how :assoc handles
;; literals in `:k`). We accept either a vector value (rare here, since
;; sequence args travel through arg-chain) or fall through to default.

(defmethod compute-return-type :get-in
  [_ bindings-info default-ret]
  (let [m-type    (get-in bindings-info [:map :type])
        path-val  (get-in bindings-info [:path :value])]
    (if (and (sequential? path-val)
             (every? #(or (keyword? %) (string? %)) path-val))
      (loop [t m-type, segs path-val]
        (cond
          (empty? segs) t
          (types/record-type? t)
          (let [k (field-keyword-from-literal (first segs))]
            (if (and k (contains? t k))
              (recur (get t k) (rest segs))
              default-ret))
          :else default-ret))
      default-ret)))
