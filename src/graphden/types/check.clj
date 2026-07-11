(ns graphden.types.check
  "Save-time type checking for fn-defs.

   Given a fn-def with `:parent` (single) or `:parents` (MI), the
   checker walks the inheritance chain, unifies type variables,
   computes the resulting return-type via type-rules, propagates
   effects, and verifies that every binding's actual type is a
   subtype of the expected slot type. On any mismatch, throws an
   `ex-info` with `:type :types/check-failed` so the syncer refuses
   the save before anything hits storage.

   Topo-sort runs first (see `composition.deps`), so a fn-def's
   parents and refs are guaranteed to be in the registry by the
   time it's checked.

   ## What's covered

   - Single-parent and multi-inheritance (`:parents [...]`) fn-defs.
   - Multi-level chains (parent itself a fn-def).
   - Literal value bindings (numbers / strings / booleans / nil /
     keywords / uuids / maps / vectors).
   - Fn-ref bindings (`:other-fn`) checked against the registry's
     return type.
   - Sequence-arg item-by-item type check (each vector item
     independently).
   - HOF / structural fn-typed slots: assemble `[:fn args ret]` from
     the ref's free-args and unify positionally.
   - Type-rules for `:assoc` / `:dissoc` / `:get` / `:get-in` /
     `:assoc-in` / `:into` / `:first` / `:rest` / `:case` / arithmetic
     narrowing / etc — each registered per-base-fn in that package's
     `impls.clj` as a `:return-type-rule` (no central namespace) and
     looked up through the rich-types registry. (`:if` carries no
     rule — its `[:union then else]` return is plain declared-var
     polymorphism.)
   - Refinement constraints on literal values (compound `:and` / `:or`
     supported).
   - Effect categories: `:effects` set propagates parent ∪ refs;
     `:expects-effects` declarations log a sync-time WARN on drift.
   - `:source-file` / `:source-line` from EDN metadata threaded into
     every error message via the `*source-info*` dynamic var.

   ## Known limitations

   - `[:fn ...]` slots whose binding is NEITHER a fn-ref NOR a vector
     defer (no structural shape we can compare statically).
   - `{:as :name}` rename-only bindings defer (they keep the slot
     free under a new name; the next caller is what gets type-checked).
   - Refinement-on-refinement subtype comparison is constraint
     equality only — no SMT-style narrowing reasoning."
  (:require
    [clojure.set :as set]
    [clojure.tools.logging :as log]
    [graphden.executor.registry.core :as registry]
    [graphden.types.check.literals :as lit]
    [graphden.types.core :as types]))


(defn- binding-shape
  "Classify an AST binding form (the value in a fn-def's `:args` map)
   into one of six coarse shapes. Drives the predicates below and the
   inline classifiers in `describe-binding` / `hint-for-actual` /
   `vector-binding-elem-types` — one source of truth for 'what shape
   is this AST binding'.

   - `:fn-ref`     bare keyword (`:my-fn`) — fn-reference by name
   - `:seq-vec`    bare vector — sequence-chain literal
   - `:value-map`  `{:value v …}` — explicit literal
   - `:ref-map`    `{:ref :name …}` — explicit fn-reference
   - `:meta-map`   map with `:as`/`:type`/`:required` but no `:value`/`:ref`
   - `:scalar`    anything else — bare literal (numbers, strings, …)"
  [b]
  (cond
    (keyword? b) :fn-ref
    (vector? b) :seq-vec
    (not (map? b)) :scalar
    (contains? b :value) :value-map
    (contains? b :ref) :ref-map
    (or (contains? b :as)
        (contains? b :type)
        (contains? b :required)) :meta-map
    :else :scalar))


(defn- ref-binding?
  "A bare keyword in fn-def args means a fn-ref to that name."
  [v]
  (= :fn-ref (binding-shape v)))


(defn- metadata-only-binding?
  "True iff `v` is a map binding that carries ONLY metadata
   (`:as`, `:required`, `:type`) without an explicit value or ref.
   Such bindings leave the slot logically free — nothing to
   value-type-check against the slot's expected type (the `:type`
   override itself is monotonicity-checked separately by
   `check-binding-monotonicity!`)."
  [v]
  (= :meta-map (binding-shape v)))


(defn- value-binding?
  "Either a bare literal or a `{:value …}` map. We accept BOTH the
   shorthand and the explicit form here so the type-check is
   syntax-tolerant — composition.records will reject any genuinely
   malformed binding later. Sequence chains (bare vectors) count as
   value-shaped here so the elem-walker handles them downstream."
  [v]
  (contains? #{:value-map :scalar :seq-vec} (binding-shape v)))


(defn- assemble-fn-type
  "Build a structural `[:fn args-map ret effects]` from a fn-name's
   registry entry — its REMAINING free args (including transitive
   ones lifted through every ref-binding) become the callable's
   parameters, its computed return is the result, and its `:effects`
   set becomes the slot-effect-constraint check input.

   When the ref's `:return` is ITSELF a fn-type (the fn-graph
   PRODUCES a callable, like `:_router → ring-handler`), surface
   that inner fn-type directly. The wrapped `[:fn frees [:fn ...]]`
   form would mismatch cardinality at the binding site (a callable-
   producer's COMPILE-time free-args don't represent the produced
   callable's RUNTIME signature). Pairs with the executor's
   `:produces-callable?` detection in compile/bindings.clj — same
   `(types/fn-type? (:return info))` predicate.

   Always emits the canonical 4-element form. `compute-effects` is
   total — every fn-def has a computed set, possibly `#{}` (pure) —
   so the 4th element is always the registry's `:effects` (or `#{}`
   if absent, which is the same as computed-pure). Producing 3-elem
   on the sub side would be ambiguous (callee pure vs callee
   unconstrained) — `normalise` would rewrite to `:any` (slot
   meaning), which is wrong for a sub.

   Returns `:any` (a permissive sentinel) ONLY when the registry has
   no entry for the name yet."
  [fn-name]
  (when-let [info (registry/rich-type-of fn-name)]
    (let [ret (or (:return info) :any)
          ;; `:effects` is unconditionally stored — by record-rich-types!
          ;; AND record-rich-types-raw! (both default `#{}` for pure
          ;; fns post-P8). Direct lookup is safe.
          ;;
          ;; `:call-time-effects` (when present) is the per-invocation
          ;; subset — bound-arg ref-effects subtracted. HOF slots
          ;; (`:filter :pred #{}`, `:map :func #{}`) measure against
          ;; per-invocation purity, NOT against the fn's full
          ;; construction-time effect set; prefer the call-time
          ;; subset when recorded. Falls back to `:effects` for
          ;; base-fns / older raw entries without the split.
          eff (or (:call-time-effects info) (:effects info))]
      (if (types/fn-type? ret)
        ;; Producer-of-callable: the inner fn-type already carries
        ;; whatever effects-constraint the slot declared. We can't
        ;; meaningfully inject the producer's own effects here.
        ret
        (types/make-fn-type (or (:args info) {}) ret eff)))))


(defn- has-type-var?
  "True iff `t` mentions any type variable. Polymorphic checks need
   `unify` (to find variable bindings); monomorphic ones just want
   `subtype?` to test actual ⊆ expected.

   Must cover every compound shape `types/core` defines — a missing
   arm here causes `check-binding!` to skip the `unify` fallback for
   that shape and reject a binding whose unification would have
   succeeded (e.g. record ↔ `[:map a :any]`)."
  [t]
  (types/type-any? types/type-var? t))


(defn- any-shape?
  "True iff `t` is `:any` OR a structural form whose every reasoned
   position is `:any`. Such a value carries no more information than
   the bare `:any` does, so the type-system's existing
   `(= actual :any) → silent pass` escape hatch extends to it
   consistently:
   - `[:map :any :any]` — output of `merge` when sources disagree on
     inner types; the runtime might be tighter (the author's intent),
     but statically the shape is uninformative.
   - `[:list :any]` — same story for sequence-producing rules that
     widen when sources disagree.
   - structural fn-type / tuple — analogous.

   Without this hatch, a `:get`'s `:any` return (default when source
   shape is unknown) propagates through `:merge` / `:update-in` to
   produce `[:map :any :any]` / `:any` returns, which then strict-
   reject against tighter declared / slot types downstream
   (`[:map :text :text]`, `:ring-response-shape`). The author already
   has an out for the bare-`:any` case; structural-any plugs the
   remaining surface where return-rule chains land short of the
   real runtime shape."
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


(defn- describe-binding
  "Human-readable summary of how the user wrote the binding —
   `:fn-ref → some-fn` or `(literal 42)` or `(sequence-chain N items)`.
   Drives the `hint` line of the error message."
  [b-form]
  (case (binding-shape b-form)
    :value-map (str "(literal " (pr-str (:value b-form)) ")")
    :ref-map   (str "ref → " (pr-str (:ref b-form)))
    :meta-map  (if (contains? b-form :as)
                 (str "rename via {:as " (pr-str (:as b-form)) "}")
                 (str "(literal " (pr-str b-form) ")"))
    :fn-ref    (str "fn-ref → " (pr-str b-form))
    :seq-vec   (str "(sequence-chain, " (count b-form) " item"
                    (if (= 1 (count b-form)) "" "s") ")")
    :scalar    (str "(literal " (pr-str b-form) ")")))


(defn- hint-for-actual
  "One-line explanation of WHY the actual type is what it is — the
   ref's return-type, the literal's classified type, etc. Helps the
   user trace back to the source of the mismatch instead of staring
   at two lines of structural types."
  [b-form actual]
  (case (binding-shape b-form)
    :fn-ref  (str (pr-str b-form) "'s computed signature is " (pr-str actual))
    :ref-map (str (pr-str (:ref b-form)) "'s computed signature is " (pr-str actual))
    :seq-vec (str "sequence's element type resolves to " (pr-str actual))
    (str "the literal value classifies as " (pr-str actual))))


(defn- source-suffix
  "`  at packages/foo/fns.edn:42` for a fn-name's registry entry, or
   empty when the fn has no source info recorded (base-fns, dynamically-
   created fns)."
  [fn-name]
  (when-let [info (and fn-name (registry/rich-type-of fn-name))]
    (let [{:keys [source-file source-line]} info]
      (cond
        (and source-file source-line) (str " (" source-file ":" source-line ")")
        source-file                   (str " (" source-file ")")
        :else                         ""))))


(defn- actual-source
  "When the actual type comes from a ref-binding, return that ref's
   source-info suffix so the error can point at the fn that produced
   the offending return type. nil when actual is from a literal value."
  [b-form]
  (let [ref-name (cond
                   (keyword? b-form)                                  b-form
                   (and (map? b-form) (contains? b-form :ref))        (:ref b-form))]
    (some-> ref-name source-suffix)))


(defn- format-message
  "Build the multi-line error message. Reads cleanly in REPL output,
   `bb deploy` logs, and HTTP error responses. When the ctx map
   carries `:source-file` / `:source-line`, the location is prepended
   so editor / IDE / log readers can jump straight to the EDN entry.

   `parent` and `actual` lines carry inline source attribution where
   available — so the user sees not just the type mismatch but which
   ancestor introduced the expectation and which fn produced the
   actual return."
  [{:keys [fn-name parent-name arg-name expected actual
           source-file source-line]
    b-form :binding}]
  (let [parent-src (source-suffix parent-name)
        actual-src (or (actual-source b-form) "")]
    (str (when source-file
           (str "  at " source-file
                (when source-line (str ":" source-line))
                "\n"))
         "Type-check failed in fn-def " (pr-str fn-name) "\n"
         "  arg "        (pr-str arg-name) " ← " (describe-binding b-form) "\n"
         "  parent "     (pr-str parent-name) parent-src
         " expects: " (pr-str expected) "\n"
         "  actual:                "                (pr-str actual) actual-src "\n"
         "  hint: "      (hint-for-actual b-form actual))))


;; Source-location of the fn-def currently being checked. Set in a
;; binding around `check-fn-def!` so every nested `check-binding!`
;; throw can report `file:line` without threading the info through
;; every recursive call. Resolves to {:source-file path :source-line N}
;; or empty when we're called outside check-fn-def! (tests, etc.).
(def ^:dynamic *source-info* {})


;; Caller-context narrowings (Phase α'). Built by `build-caller-
;; narrowings` after Pass 1; Pass 3 binds it per-fn-def. The rename
;; branch in `bindings-info-for-rule` and `collect-free-args`
;; reads the entry for the current `:as`-name when no author
;; `:type` is pinned.
;;
;; Soundness rests on a parser-level fix landed alongside (see
;; `packages/records/parse.clj :: anon-fn-name`): inline anons are
;; now named per use-site, so two unrelated flows whose anons have
;; identical shape no longer share a registry entry. Narrowing an
;; anon for one flow cannot leak into another.
(def ^:dynamic *caller-narrowings* nil)


;; Phase #170 — control-flow ref-return overrides. Keyed by
;; FN-NAME (not slot AS-name): `{narrowed-fn-name → narrowed-type}`.
;; Bound during Pass 3 per fn-def, populated by
;; `build-ref-return-overrides` for fn-defs reachable from a
;; provably-non-null `:if`/`:cond` branch. Consulted by
;; `effective-ref-return` AND by every direct
;; `(:return (registry/rich-type-of ref-name))` site that feeds the
;; type-check (sequence items, ref-bindings, `bindings-info-for-rule`'s
;; ref entries) — see `ref-return-narrowed`.
(def ^:dynamic *ref-return-overrides* nil)


(defn- ref-return-narrowed
  "Static return of `ref-name`, possibly narrowed by the active
   `*ref-return-overrides*`. Single entry point for every Pass 3 site
   that needs the ref's return-as-seen-by-this-check."
  [ref-name]
  (or (and *ref-return-overrides*
           (get *ref-return-overrides* ref-name))
      (some-> (registry/rich-type-of ref-name) :return)))


(defn- mismatch-context
  "Build the structured `ctx` map every type-error throw shares —
   merges in `*source-info*` so file:line gets stamped automatically."
  [parent-name fn-name arg-name b-form expected actual]
  (merge {:fn-name fn-name :parent-name parent-name
          :arg-name arg-name :binding b-form
          :expected expected :actual actual}
         *source-info*))


(defn- throw-mismatch!
  "Standard `:types/check-failed` ex-info from a context map. `extra`
   merges in additional ex-data (e.g. `:reason :refinement-violation`)
   and `prefix` (optional) prepends a one-line label before the
   multi-line `format-message` body — used for refinement violations."
  ([ctx]
   (throw-mismatch! ctx nil nil))
  ([ctx prefix extra]
   (let [msg (cond->> (format-message ctx)
               prefix (str prefix "\n\n"))]
     (throw (ex-info msg
                     (merge (assoc ctx :type :types/check-failed
                                   :parent (:parent-name ctx)
                                   :arg (:arg-name ctx))
                            extra))))))


(defn- literal-binding?
  "True iff `binding` is a literal-shaped binding: a `{:value v}` map,
   or a bare scalar (anything that isn't a keyword fn-ref, a vector
   sequence chain, or another map shape like `{:as :name}`)."
  [b-form]
  (contains? #{:value-map :scalar} (binding-shape b-form)))


(defn- literal-binding-value
  "Extract the literal value from a binding — `(:value m)` if it's a
   `{:value v}` map, the binding itself otherwise."
  [b-form]
  (if (and (map? b-form) (contains? b-form :value))
    (:value b-form)
    b-form))


(defn- check-refinement-on-literal
  "Refinement-vs-literal check: subtype against the base type AND
   evaluate the constraint. Returns the input subst on pass, throws
   on fail. `:unknown` constraints defer (runtime catches them)."
  [parent-name arg-name expected actual subst fn-name b-form v]
  (let [base-ok? (types/subtype? actual (types/refine-base expected))
        satisfied (lit/literal-satisfies-refinement?
                    v (types/refine-constraint expected))
        ctx (mismatch-context parent-name fn-name arg-name b-form expected actual)]
    (cond
      (not base-ok?)
      (throw-mismatch! ctx)

      (or (= satisfied :unknown) (true? satisfied))
      subst

      :else
      (throw-mismatch!
        ctx
        (str "Refinement constraint failed: "
             (pr-str v) " doesn't satisfy "
             (pr-str (types/refine-constraint expected)))
        {:reason :refinement-violation
         :constraint (types/refine-constraint expected)}))))


(defn- check-binding!
  "For a single binding, verify `actual ⊆ expected`. When `expected`
   carries type variables, unify instead so the variables get bound.

   Throws `:types/check-failed` with a multi-line, fn-def-named
   message on mismatch. ex-info `:data` includes the structured
   pieces (`:fn-name`, `:parent`, `:arg`, `:binding`, `:expected`,
   `:actual`) so callers can format their own diagnostics if
   needed."
  [parent-name arg-name expected actual subst fn-name b-form]
  (cond
    ;; Type-var on EITHER side + `:any`-shaped actual — bind the var
    ;; via unify FIRST. Without this, `:if`'s `:then a` slot bound to
    ;; an actual `[:map :any :any]` would fall into the silent-pass
    ;; below, leaving `a` unbound; `:if`'s declared `[:union a b]`
    ;; return then surfaces a literal `"a"` typevar string in the
    ;; registry, polluting downstream consumers (the structural-any
    ;; from the binding IS information — that's the bind path, not
    ;; a no-op).
    (and (or (has-type-var? expected) (has-type-var? actual))
         (any-shape? actual))
    (let [next-subst (types/unify expected actual subst)]
      (if (types/fail? next-subst) subst next-subst))

    ;; `:any` (or structural-`:any` like `[:map :any :any]` /
    ;; `[:list :any]`) — the doc's static escape hatch. Carries no
    ;; static information either way, so silent-pass. (`:null` used
    ;; to pass too, as a blanket runtime escape hatch. Now that
    ;; nil-producing base-fns are typed `[:union :null T]`, a bare
    ;; `:null` actual is checked normally: it satisfies a nullable /
    ;; `:any` / type-var slot via subtype? / unify, and is correctly
    ;; REJECTED by a concrete non-null slot.)
    (any-shape? actual)
    subst

    ;; `:never` (bottom) actual — a divergent `:throw` branch. It fits
    ;; any expected type, but we unify rather than pass silently so a
    ;; type-var expected (e.g. `:if`'s `:then` slot) BINDS to `:never`.
    ;; Without the binding the branch union keeps a free var instead
    ;; of letting `make-union` absorb the bottom. `unify` with a
    ;; `:never` operand always succeeds, so the result is never a fail.
    (= actual :never)
    (let [s (types/unify expected actual subst)]
      (if (types/fail? s) subst s))

    ;; Refinement on a LITERAL value: if we know the literal AND
    ;; can evaluate the constraint, accept-or-reject inline.
    (and (types/refine-type? expected)
         (some? b-form)
         (literal-binding? b-form))
    (check-refinement-on-literal parent-name arg-name expected actual
                                 subst fn-name b-form
                                 (literal-binding-value b-form))

    ;; Subtype FIRST — it handles container-into-jsonb (`[:list 'b] ⊆
    ;; :jsonb`) regardless of inner type-vars, since the outer `list-
    ;; type?` arm of the :jsonb sink rule doesn't recurse into the
    ;; element type. Trying unify first would reject these
    ;; (`unify [:list 'b] :jsonb = ::fail`).
    ;;
    ;; When typevars ARE present, also run unify after a successful
    ;; subtype to extract bindings. subtype? is lenient on typevar-sup
    ;; (`map-subtype?` / `list-subtype?` / `fn-subtype?`'s sup-side
    ;; typevar arms) so the structural check passes WITHOUT carrying
    ;; the binding forward. Without this follow-up, e.g. `:try`'s
    ;; `:body [:fn {} a]` slot bound to a fn returning `[:map :keyword
    ;; :any]` passes subtype but leaves `a` free; downstream consumers
    ;; of `:try`'s declared `[:union a b]` see typevars instead of the
    ;; resolved `[:map :keyword :any]`.
    (types/subtype? actual expected)
    (if (or (has-type-var? expected) (has-type-var? actual))
      (let [s (types/unify expected actual subst)]
        (if (types/fail? s) subst s))
      subst)

    ;; Subtype rejected — but if EITHER side carries a type-var,
    ;; the rejection may be because `subtype?` doesn't reason about
    ;; type-vars (they only equal themselves). Fall back to `unify`
    ;; so e.g. `'a ⊆ :jsonb` succeeds by binding `'a := :jsonb`.
    (or (has-type-var? expected) (has-type-var? actual))
    (let [next-subst (types/unify expected actual subst)]
      (if (types/fail? next-subst)
        (throw-mismatch! (mismatch-context parent-name fn-name arg-name
                                           b-form expected actual))
        next-subst))

    :else
    (throw-mismatch! (mismatch-context parent-name fn-name arg-name
                                       b-form expected actual))))


;; -----------------------------------------------------------------------------
;; check-fn-def! is split into named phase helpers; the top-level fn
;; below just composes them.
;; -----------------------------------------------------------------------------


(defn- ref-targets
  "Yield the list of fn-name keywords this binding directly references —
   bare keyword, `{:ref name}`, or vector-of-items shapes. Used by the
   ref-effects union and the transitive free-args lift."
  [b-form]
  (cond
    (keyword? b-form) [b-form]
    (and (map? b-form) (contains? b-form :ref)) [(:ref b-form)]
    (vector? b-form) (keep (fn [item]
                             (cond
                               (keyword? item) item
                               (and (map? item) (contains? item :ref)) (:ref item)
                               :else nil))
                           b-form)
    :else []))


(defn- mi-slot-type-conflict
  "Across the registry entries of an MI fn-def's parents, find the
   first slot where the parents declare INCOMPATIBLE types — neither
   is a subtype of the other under `subtype?`. Returns `[slot
   distinct-ts]` for the conflict, or nil when every shared slot has a
   compatible chain (identical / primitive subtype / record extras /
   typevars). The MI merge would silently keep the last-listed
   parent's type otherwise; surfacing the conflict lets the author
   pick a pinning override at the child."
  [infos]
  (let [parent-args-list (mapv #(:args % {}) (filter some? infos))
        slot-types-by-name
        (reduce (fn [m args]
                  (reduce-kv (fn [acc k v]
                               (update acc k (fnil conj []) v))
                             m args))
                {}
                parent-args-list)]
    (some (fn [[slot ts]]
            (let [distinct-ts (distinct ts)]
              (when (> (count distinct-ts) 1)
                (let [related? (some (fn [a]
                                       (every? (fn [b]
                                                 (or (= a b)
                                                     (types/subtype? a b)
                                                     (types/subtype? b a)))
                                               distinct-ts))
                                     distinct-ts)]
                  (when-not related?
                    [slot distinct-ts])))))
          slot-types-by-name)))


(defn- mi-slot-value-conflict
  "Across the registry entries of an MI fn-def's parents, find the
   first slot bound to DIFFERENT values (literal `:value` or fn-`:ref`)
   by multiple parents. Returns `[slot pins]` for the conflict, or nil
   when every binding agrees (or only one parent binds the slot).

   PB' own-slot declarations (`{:type T}` with no `:value` or `:ref`)
   surface in `:resolved-bindings` as `{:type T :value nil}` — the
   `:value-present` flag distinguishes the PB' decl from a genuine
   `{:value nil}` binding so the latter still participates in conflict
   detection while the former defers to its sibling's real binding."
  [infos]
  (let [actual-binding? (fn [binding]
                          (or (contains? binding :ref)
                              (true? (:value-present binding))))
        rbs-by-slot
        (reduce (fn [acc info]
                  (reduce-kv (fn [a slot binding]
                               (let [pin (select-keys binding [:value :ref])]
                                 (if (and (actual-binding? binding)
                                          (not (contains? (a slot) pin)))
                                   (update a slot (fnil conj #{}) pin)
                                   a)))
                             acc
                             (:resolved-bindings info {})))
                {}
                (filter some? infos))]
    (some (fn [[slot pins]] (when (> (count pins) 1) [slot pins]))
          rbs-by-slot)))


(defn- check-mi-conflicts!
  "Throw on the first MI parent conflict found (slot type, then slot
   value). No-op when both pass."
  [infos parent-list]
  (when-let [[slot ts] (mi-slot-type-conflict infos)]
    (throw (ex-info
             (str "MI parent slot type conflict on " (pr-str slot)
                  ": parents declare incompatible types "
                  (pr-str (vec ts))
                  " — neither is a subtype of the other. The merged"
                  " contract would silently keep just the last"
                  " parent's type. Pick parents with compatible"
                  " slot types or override the slot at the MI"
                  " child to pin one contract.")
             {:type :bindings/mi-slot-type-conflict
              :slot-name slot
              :conflicting-types (vec ts)
              :parents parent-list})))
  (when-let [[slot pins] (mi-slot-value-conflict infos)]
    (throw (ex-info
             (str "MI parent slot value conflict on " (pr-str slot)
                  ": parents bind the slot to "
                  (count pins) " different values "
                  (pr-str (vec pins))
                  " — the merged `:resolved-bindings` would"
                  " silently keep only the last-listed parent's"
                  " binding. Either override the slot at the MI"
                  " child to pin the intended value, or choose"
                  " parents that don't both bind the same slot.")
             {:type :bindings/mi-slot-value-conflict
              :slot-name slot
              :conflicting-bindings (vec pins)
              :parents parent-list}))))


(defn- merge-mi-parent-infos
  "Combine N parent registry entries into one parent-info for an MI
   fn-def. Returns nil when none of the parents have a registry entry
   (defer like the pre-MI behaviour did). With one parent the entry
   is returned verbatim.

   Free args (`:args`): a slot is free in the MI child iff NO parent
   binds it. Each parent's `:args` already excludes that parent's own
   bindings, but a slot bound by parent A and left free by parent B
   would survive a naive `merge` of the maps — closer-fn-wins MI means
   A's binding applies to the child, so the slot is NOT free. We
   subtract the union of every parent's `:resolved-bindings` keys (the
   slots bound anywhere in each parent's chain) from the merged
   free-arg map.

   `:resolved-bindings`: merged across parents (later parent wins on a
   collision — closer-fn-wins). Without this an MI fn-def loses the
   bindings its parents accumulated up the chain, so a return-type
   rule re-firing on it (or a descendant) can't see slots bound deeper
   up — e.g. the `:assoc` record-builder loses `:map`/`:key`/`:value`
   and collapses the response record to `:jsonb`.

   Pre-merge conflict detection (`check-mi-conflicts!`) surfaces type
   and value conflicts so they're not silently masked by `apply merge`."
  [parent-list]
  (let [infos (mapv registry/rich-type-of parent-list)]
    (cond
      (every? nil? infos) nil
      (= 1 (count infos)) (first infos)
      :else
      (do
        (check-mi-conflicts! infos parent-list)
        (let [bound (into #{}
                          (mapcat #(keys (:resolved-bindings % {})))
                          infos)]
          {:return   (or (:return (first infos)) :any)
           :args     (reduce dissoc
                             (apply merge (mapv #(:args % {}) infos))
                             bound)
           :effects  (into #{} (mapcat #(:effects % #{})) infos)
           :resolved-bindings (apply merge
                                     (mapv #(:resolved-bindings % {}) infos))})))))


(defn- resolve-parent-info
  "Build a uniform parent-info for both single-parent and MI fn-defs.
   Returns `{:primary-parent :name :parent-info {…}}` or nil when no
   parent is set (e.g. base-fn) or none of the parents are registered."
  [{:keys [parent] parent-vec :parents}]
  (let [parent-list (cond
                      (seq parent-vec) (vec parent-vec)
                      (and parent (keyword? parent)) [parent]
                      :else nil)]
    (when-let [primary (first parent-list)]
      (when-let [info (merge-mi-parent-infos parent-list)]
        {:primary-parent primary :parent-info info}))))


(defn- source-info-for
  "Pull `:source-file` / `:source-line` off a fn-def, dropping nil keys."
  [fn-def]
  (cond-> {}
    (:source-file fn-def) (assoc :source-file (:source-file fn-def))
    (:source-line fn-def) (assoc :source-line (:source-line fn-def))))


(defn- rename-binding?
  "True iff `b` is `{:as :x}` style — slot stays free under a new name."
  [b]
  (and (= :meta-map (binding-shape b))
       (contains? b :as)))


(defn- type-only-binding?
  "True iff `b` is `{:type T}` style — pins the slot's static type but
   leaves the slot itself free (no `:value`, no `:ref`, no `:as`).
   Useful when an author wants to narrow an inherited generic slot
   (e.g. `:invoke`'s `[:fn {:arg a} b]`) without supplying a value at
   this fn-def level."
  [b]
  (and (= :meta-map (binding-shape b))
       (contains? b :type)
       (not (contains? b :as))))


;; -----------------------------------------------------------------------------
;; Pre-Phase: structural monotonicity checks on bindings.
;;
;; Two narrowing channels feed the same one-way ratchet:
;;   - `:required` — boolean optional→required, never the reverse.
;;   - `:type`     — structural T ⊆ inherited slot type, never wider.
;;
;; Both fail with the same `:bindings/widening-*` error category so
;; downstream tooling (UI, /api/types/compatible) reads them through
;; one branch.

(defn- throw-widening!
  [fn-name parent-name arg-name b-form direction reason]
  (throw (ex-info
           (str "Type-check failed in fn-def " (pr-str fn-name)
                "\n  arg " (pr-str arg-name) " ← " (pr-str b-form)
                "\n  parent " (pr-str parent-name) (source-suffix parent-name)
                "\n  reason: " reason)
           (merge {:fn-name fn-name
                   :parent-name parent-name
                   :parent parent-name
                   :arg-name arg-name
                   :arg arg-name
                   :binding b-form
                   :type (keyword "bindings" (str "widening-" (name direction)))}
                  *source-info*))))


(defn- nearest-slot-suggestion
  "When the user binds an unknown slot name, suggest the closest match
   from the available slot set by simple Levenshtein-ish similarity:
   prefer same-length names, then names sharing the longest common
   substring prefix. Returns the best candidate keyword (or nil if no
   slots available)."
  [unknown-kw available-kws]
  (when (seq available-kws)
    (let [u (name unknown-kw)
          score (fn [k]
                  (let [n (name k)
                        ;; Shared prefix length.
                        prefix-len (count (take-while true?
                                                      (map = u n)))
                        ;; Length-difference penalty.
                        len-diff (Math/abs (- (count u) (count n)))]
                    [(- prefix-len) len-diff]))]
      (->> available-kws
           (sort-by score)
           first))))


(defn- ref-free-arg-names
  "Collect free-arg names of every fn-ref reachable from a binding-
   form, transitively walking sequence bindings and inline-anon refs.

   Closure-capture pattern: a sibling binding like `:parsed
   :_parsed-result` doesn't necessarily bind a slot of the parent —
   it SEEDS a closure-captured free arg consumed by ANOTHER sibling
   ref (e.g. a `:cond :clauses` ref typed `{:as :parsed}`). The
   seeded name must appear as a free arg in some sibling ref's
   rich-type to be a valid seed; otherwise it's a typo (the seed
   binding does nothing and silently disappears at runtime)."
  [b-form]
  (cond
    (keyword? b-form)
    (set (keys (:args (registry/rich-type-of b-form) {})))

    (and (map? b-form) (contains? b-form :ref))
    (set (keys (:args (registry/rich-type-of (:ref b-form)) {})))

    (vector? b-form)
    (apply clojure.set/union #{} (map ref-free-arg-names b-form))

    :else #{}))


(def ^:private known-effect-categories
  "Effect tags graphden's runtime knows how to record. Each base-fn's
   side effects map to one of these via `cr/record-effect!`. A
   `:expects-effects` / `:effects` declaration carrying a tag outside
   this set is almost always a typo (`:do` for `:db`, a misspelled
   `:network` etc.); reject at sync time so the contract isn't a
   silent no-op.

   Mirrors the docstring of `compile-runtime/record-effect!` plus
   `:process` from the service registry (services declare
   `:expects-effects #{:process}` to opt into supervisor reconciliation)
   and `:raw-sql` for the raw SQL escape hatches (`:pg-query` /
   `:pg-execute` / `:pg-tx` / `:sql-query` / `:sql-exec`) that bypass
   the org-scoped storage protocol."
  #{:db :env :io :network :time :random :process :raw-sql})


(defn- check-effect-categories!
  "Reject any `:effects` or `:expects-effects` set that names an
   unknown category. Without this, a typo like
   `:expects-effects #{:do}` is silently treated as an extra
   contracted category — drift-checking never fires (computed
   effects can't drift INTO `:do`) and the editor's effect-chip
   strip displays a bogus tag."
  [{fn-name :name :as fn-def}]
  (doseq [[field tag-set] [[:effects (:effects fn-def)]
                           [:expects-effects (:expects-effects fn-def)]]]
    (when tag-set
      (let [bad (remove known-effect-categories tag-set)]
        (when (seq bad)
          (throw (ex-info
                   (str "Type-check failed in fn-def " (pr-str fn-name)
                        "\n  unknown effect category in " field
                        ": " (pr-str (set bad))
                        "\n  known categories: "
                        (pr-str (vec (sort known-effect-categories))))
                   (merge {:fn-name fn-name
                           :field field
                           :unknown-categories (set bad)
                           :known-categories known-effect-categories
                           :type :bindings/unknown-effect-category}
                          *source-info*))))))))


(defn- check-unknown-slots!
  "Reject any `:args` key that doesn't correspond to a slot reachable
   through the parent's inheritance chain OR a closure-capture seed
   consumed by some sibling ref. Catches the `:assoc :m :_x` (typo
   for `:map`) class of bugs: previously the binding was silently
   dropped because `:m` isn't a slot of `:assoc`, and the
   per-binding type-check treats unknown slots as 'no expected
   type → defer'.

   Valid keys are:
   - `parent-args` keys — slots still unbound on the parent (so the
     child can bind them, OR descendants can pre-bind a free arg
     propagated up).
   - `:resolved-bindings` keys — slots ALREADY bound somewhere in the
     parent chain. The child can shadow these (closer-fn-wins).
   - Any sibling ref's free arg name — the binding seeds a value for
     a closure-captured arg the sibling consumes. Common pattern in
     `:cond :clauses` chains where `:parsed` / `:fn-in-use-reason` /
     etc. seed the per-clause refs.

   Anything outside that union is a typo or a stale name."
  [{fn-name :name :as fn-def} parent-name parent-args parent-info]
  (let [own-args (:args fn-def)
        ;; Closure-capture seeds: a sibling binding's free args
        ;; whitelist this fn-def's bindings of the same name.
        sibling-free-args (apply clojure.set/union #{}
                                 (map ref-free-arg-names (vals own-args)))
        ;; `parent-args` already includes type-row record-fields when
        ;; the parent is a type-row (merged by `check-fn-def!` ahead
        ;; of this call). The `:resolved-bindings` keys cover slots
        ;; shadowed earlier in the parent chain.
        valid (-> (set (keys parent-args))
                  (into (keys (:resolved-bindings parent-info {})))
                  (into sibling-free-args))
        unknown (remove valid (keys own-args))]
    (when (seq unknown)
      (let [arg-name (first unknown)
            b-form (get own-args arg-name)
            suggestion (nearest-slot-suggestion arg-name valid)]
        (throw (ex-info
                 (str "Type-check failed in fn-def " (pr-str fn-name)
                      "\n  arg " (pr-str arg-name) " ← " (pr-str b-form)
                      "\n  parent " (pr-str parent-name) (source-suffix parent-name)
                      "\n  reason: " (pr-str arg-name)
                      " is neither a slot of " (pr-str parent-name)
                      " nor a closure-capture seed consumed by a sibling ref."
                      (when suggestion (str " Did you mean " (pr-str suggestion) "?"))
                      "\n  available names: "
                      (pr-str (vec (sort valid))))
                 (merge {:fn-name fn-name
                         :parent-name parent-name
                         :parent parent-name
                         :arg-name arg-name
                         :arg arg-name
                         :binding b-form
                         :suggestion suggestion
                         :available-slots (vec (sort valid))
                         :type :bindings/unknown-slot}
                        *source-info*)))))))


(defn- check-ref-type-overrides!
  "Reject `{:ref :_x :type T}` bindings where T is NOT a subtype of
   `:_x`'s declared return. The override is the author's
   `narrowed-contract claim` (`I know :_x's nullable return is
   actually non-nil in this guarded path; treat it as :text here`).
   Widening claims (`treat :_x's :text return as :int here`) silently
   pass the call-site check while leaving the runtime to drift —
   downstream consumers reading this binding see the LIE.

   Same lie-detection for `{:value V :type T}` — the literal V's
   classified type must be a subtype of the override T. `{:value
   \"hello\" :type :int}` previously slipped through because
   monotonicity only checks T vs the inherited slot type.

   Typevar on either side defers — typevars are unification
   placeholders and can bind to anything; the override pins one
   specific instantiation, which is monotonic by construction."
  [{fn-name :name :as fn-def} parent-name]
  (doseq [[arg-name b-form] (:args fn-def)]
    (when (and (map? b-form) (contains? b-form :type))
      (let [override (some-> (:type b-form) types/resolve-alias)
            ;; ref-binding → check against ref's return.
            ;; value-binding → check against classified value type.
            [actual-source actual]
            (cond
              (contains? b-form :ref)
              [:ref-return
               (some-> (registry/rich-type-of (:ref b-form))
                       :return
                       types/resolve-alias)]
              (contains? b-form :value)
              [:literal-value
               (lit/classify-literal (:value b-form))]
              :else nil)]
        (when (and actual override actual-source
                   (not (has-type-var? actual))
                   (not (has-type-var? override))
                   ;; Narrowing direction: override must be a SUBTYPE
                   ;; of the actual (more-specific claim about a
                   ;; less-specific value). Equality is allowed.
                   ;; Failure here means the author is widening or
                   ;; making an incompatible claim — error.
                   (not (types/subtype? override actual)))
          (throw (ex-info
                   (str "Type-check failed in fn-def " (pr-str fn-name)
                        "\n  arg " (pr-str arg-name) " ← " (pr-str b-form)
                        "\n  parent " (pr-str parent-name) (source-suffix parent-name)
                        "\n  reason: the binding's `:type` override "
                        (pr-str override) " contradicts the "
                        (name actual-source) " "
                        (pr-str actual) ". The override is the author's"
                        " narrowed-contract claim (e.g. asserting non-nil"
                        " in a guarded path) — widening claims silently"
                        " propagate a wrong type to downstream consumers.")
                   (merge {:fn-name fn-name
                           :parent-name parent-name
                           :arg-name arg-name
                           :binding b-form
                           :actual-source actual-source
                           :actual actual
                           :override override
                           :type :bindings/type-override-widens}
                          *source-info*))))))))


(defn- check-branch-local-monotonicity!
  "Reject any fn-def that widens an effective-true `:branch-local?`
   inherited from a parent. `:branch-local?` is the identity-level
   flag stamped on `:fn` rows; widening it would let a descendant
   propagate runtime config across branches even though its ancestor
   marked it sticky-local.

   `record-rich-types!` stashes the effective (own ∨ ancestors) on
   every parent before this fn-def is checked, so the lookup is just
   `(:branch-local? (registry/rich-type-of parent))` per declared
   parent. Multi-inheritance: any single true parent is enough.

   Mirror of the `:required` widening guard in
   `check-binding-monotonicity!` — sync-time signal, never silent."
  [fn-def]
  (when (and (contains? fn-def :branch-local?)
             (false? (:branch-local? fn-def)))
    (let [parents (or (seq (:parents fn-def))
                      (when (:parent fn-def) [(:parent fn-def)])
                      [])
          true-parent (some (fn [p]
                              (when (true? (:branch-local?
                                             (registry/rich-type-of p)))
                                p))
                            parents)]
      (when true-parent
        (throw (ex-info
                 (str "Type-check failed in fn-def "
                      (pr-str (:name fn-def))
                      "\n  reason: cannot set `:branch-local? false` —"
                      " inherited effective-true from "
                      (pr-str true-parent)
                      (source-suffix true-parent)
                      "\n  `:branch-local?` is monotonic: once an"
                      " ancestor marks itself sticky-local, descendants"
                      " can only stay local. Either drop the flag from"
                      " this fn-def (it'll inherit true), or re-parent"
                      " off the local ancestor.")
                 (merge {:fn-name (:name fn-def)
                         :parent-name true-parent
                         :type :types/branch-local-widening-forbidden}
                        *source-info*)))))))


(defn- check-binding-monotonicity!
  "Reject any binding that widens an inherited narrowing. Two cases
   share one pre-pass:

   1. `:required false` — descendants may narrow optional → required,
      but never widen a required slot back to optional. The slot's
      own `:required` on the base-fn declaration is the only way to
      declare optionality.

   2. `:type T` where T is NOT a subtype of the inherited slot type —
      descendants may narrow a wider type (e.g. `:int` → `:positive-
      int`), but never widen it back. Without this check a descendant
      could relax the contract callers depend on.

   `parent-args` is the parent's resolved free-arg type map (the
   inherited slot types). nil entries (slot not in parent) skip the
   type check — they're not narrowings of anything."
  [{fn-name :name :as fn-def} parent-name parent-args]
  (doseq [[arg-name b-form] (:args fn-def)]
    (when (map? b-form)
      ;; (1) required widening.
      (when (and (contains? b-form :required)
                 (false? (:required b-form)))
        (throw-widening!
          fn-name parent-name arg-name b-form :required
          (str "bindings cannot widen `:required true` back to false. "
               "Optionality lives on the slot itself; descendants may only "
               "narrow optional → required, never the reverse.")))
      ;; (2) type widening — author wrote `:type T` (alone, with
      ;;     `:value`, with `:ref`, or alongside `:as`). T must be ⊆
      ;;     the inherited slot type.
      (when-let [override (and (contains? b-form :type) (:type b-form))]
        (let [inherited (some-> (get parent-args arg-name) types/resolve-alias)
              override' (types/resolve-alias override)]
          (when (and inherited
                     ;; Type variables get unified later; skip ANY
                     ;; type-var presence on either side (bare or
                     ;; structural). A concrete override against an
                     ;; inherited `[:fn {:arg a} b :any]` is a
                     ;; narrowing via unify, but `subtype?` can't
                     ;; reason about typevars so it would reject —
                     ;; defer to the per-binding unify pass below.
                     (not (has-type-var? inherited))
                     (not (has-type-var? override'))
                     (not (types/subtype? override' inherited)))
            (throw-widening!
              fn-name parent-name arg-name b-form :type
              (str "cannot widen the inherited type "
                   (pr-str inherited)
                   " to " (pr-str override') ". "
                   "Descendants may narrow (subtype) an inherited slot type, "
                   "but never widen it — callers of the parent rely on the "
                   "tighter contract."))))))))


;; -----------------------------------------------------------------------------
;; Phase 1: type-check each binding against the parent's expected slot.

(defn- sequence-item-actual-type
  "Classify ONE item from a literal-vector binding into its actual
   type. Mirrors classify-literal but also unwraps `{:value …}` /
   `{:ref :fn}` / bare keyword-refs / `{:as :name}`. Items whose
   shape leaves the type unknown surface as `:any`.

   Refs are FRESHENED per item — every keyword fn-ref brings its own
   scope of `'a`/`'b`/`'v`. Without that, two siblings whose computed
   return both name `v` (e.g. `[:_list-branches-count :_list-
   branches-json]` against `:zipmap :vals [:list v]`) collapse into
   a single shared `v` that the outer slot's typevar then refuses to
   bind to via the occurs check."
  [item]
  (cond
    (and (map? item) (contains? item :value))
    (or (lit/classify-literal (:value item)) :any)

    (and (map? item) (contains? item :ref))
    (or (some-> (:type item) types/resolve-alias)
        (some-> (ref-return-narrowed (:ref item)) types/freshen)
        :any)

    ;; Item-level rename — type unknown without seeing the caller,
    ;; unless the author pinned `:type` to assert the expected shape.
    (and (map? item) (contains? item :as))
    (or (some-> (:type item) types/resolve-alias) :any)

    (keyword? item)
    (or (some-> (registry/rich-type-of item) :return types/freshen)
        :any)

    :else
    (or (lit/classify-literal item) :any)))


(defn- check-sequence-items
  "Walk every item in a literal-vector binding and unify against the
   slot's element type. `[1 \"two\" 3]` against `[:list :int]` fails
   on the second item. A slot with `:any` elem-type accepts anything.

   When the elem-type is a bare type-variable, classify every item
   first and unify the var with the LEAST-UPPER-BOUND (a union) in
   ONE pass — a per-item reduce would bind the var to the first
   item's type and then reject any heterogeneous later item
   (`[1 nil 2]` against `[:list a]` would fail on the nil even
   though `a := [:union :int :null]` is the natural binding)."
  [primary-parent fn-name arg-name expected items subst]
  (let [elem-type (if (types/list-type? expected)
                    (types/list-elem expected)
                    :any)]
    (cond
      (= :any elem-type)
      subst

      (types/type-var? elem-type)
      (let [joined (types/make-union (mapv sequence-item-actual-type items))]
        (check-binding! primary-parent arg-name elem-type
                        joined subst fn-name {:value items}))

      :else
      (reduce (fn [s item]
                (check-binding! primary-parent arg-name elem-type
                                (sequence-item-actual-type item)
                                s fn-name item))
              subst
              items))))


(defn- sequence-slot?
  [t]
  (or (= :sequence t) (types/list-type? t)))


(defn- deferred-binding?
  "Shapes that fall through to defer rather than being type-checked
   here. See check-one-binding for the full breakdown.

   Sequence-slot + ref-binding used to be deferred under the loose
   notion 'sequence slot expects a vector'. But a ref-binding to a
   sequence slot IS statically checkable: assemble the ref's
   `:return` and verify subtype against the slot's `[:list T]`
   shape. Skipping that check was a silent footgun — a typevar
   conflict like `:filter :pred :int-pred :coll :text-list-fn`
   (`'a` binds to `:int` via pred, then `[:list :int]` ⊄ `[:list
   :text]` for the coll ref) used to slip through. Same for literal
   value bindings: `{:value [1 2 3]}` classifies as `[:list :int]`
   and IS comparable against `[:list :text]` — we should check, not
   defer. Only rename / metadata / vector chains still defer."
  [_expected b-form]
  (or (and (map? b-form)
           (or (contains? b-form :as)
               (and (contains? b-form :ref)
                    (not (contains? b-form :value)))))
      ;; A metadata-only binding (e.g. `{:required true}` or
      ;; `{:type T}`) carries no value to check — defer to the
      ;; pre-pass that validates the metadata itself
      ;; (`check-binding-monotonicity!` handles both `:required` and
      ;; `:type` widening).
      (metadata-only-binding? b-form)
      (vector? b-form)))


(defn- strip-closure-captures
  "Given the slot's `expected` `[:fn …]` type and the ref's assembled
   `actual` `[:fn …]` type, return the `actual` reduced to the slot's
   call-site contract — extra positional args drop out, captured-arg
   effects (per-arg slots on the rich-type registry of `b-form`) drop
   out.

   Three special cases survive on the boundary so `unify-fn`'s
   cardinality check passes without losing return/effect coverage:

   - **Variadic-ignore**: a 0-arg callable accepts any call-site
     arity; the executor's hof-wrap drops the supplied args at
     invocation. Adopt the slot's args so cardinality matches.
   - **Closure-capture strip**: actual carries MORE args than the
     slot declares. The runtime's hof-wrap closes the surplus over
     from the OUTER binding-chain — they don't participate in the
     slot's per-invocation contract. Drop args whose names aren't in
     the slot's call-site set (docs/CLOSURE_CAPTURE.md).
   - **Stripped-to-empty**: every actual arg was a closure capture →
     the residual `{}` is the variadic-ignore shape; re-adopt the
     slot's args.

   If neither args nor effects changed, return `actual` unchanged
   (avoids needless allocation in the common path)."
  [expected actual b-form]
  (let [call-site-keys (set (keys (types/fn-args expected)))
        raw-args       (types/fn-args actual)
        args-after-var (if (and (empty? raw-args)
                                (seq call-site-keys))
                         (types/fn-args expected)
                         raw-args)
        extras         (when (> (count args-after-var)
                                (count call-site-keys))
                         (remove call-site-keys (keys args-after-var)))
        stripped       (if (seq extras)
                         (apply dissoc args-after-var extras)
                         args-after-var)
        final-args     (if (and (empty? stripped) (seq call-site-keys))
                         (types/fn-args expected)
                         stripped)
        stripped-keys  (set extras)
        actual-effects (types/fn-effects actual)
        per-arg        (or (some-> (registry/rich-type-of b-form) :arg-effects)
                           {})
        capture-eff    (reduce into #{}
                               (vals (select-keys per-arg stripped-keys)))
        final-effects  (if (seq stripped-keys)
                         (set/difference actual-effects capture-eff)
                         actual-effects)]
    (if (and (= final-args raw-args)
             (= final-effects actual-effects))
      actual
      (types/make-fn-type final-args (types/fn-ret actual) final-effects))))


(defn- throw-literal-bound-to-fn-slot!
  "A `:fn`-typed slot received `{:value <literal>}`. The runtime would
   either throw on the call OR silently wrap the literal into a
   constant-returning thunk depending on the call site — either way
   the author meant SOMETHING else (a fn-ref or an inline anon).
   Catch at sync time with a directly-actionable hint."
  [primary-parent fn-name arg-name b-form expected]
  (let [actual (lit/classify-literal (:value b-form))]
    (throw (ex-info
             (str "Type-check failed in fn-def " (pr-str fn-name)
                  "\n  arg "        (pr-str arg-name) " ← " (describe-binding b-form)
                  "\n  parent "     (pr-str primary-parent)
                  (source-suffix primary-parent)
                  " expects a callable shape: " (pr-str expected)
                  "\n  actual:                " (pr-str actual)
                  " (literal value, not a callable)"
                  "\n  hint: bind a fn-ref (`:_my-fn`) or an inline"
                  " `{:parent :some-fn :args {…}}`; a literal of"
                  " primitive type cannot be invoked as a HOF arg.")
             (merge {:fn-name fn-name
                     :parent-name primary-parent
                     :arg-name arg-name
                     :binding b-form
                     :expected expected
                     :actual actual
                     :type :types/literal-bound-to-fn-slot}
                    *source-info*)))))


(defn- check-one-binding
  [primary-parent fn-name parent-args subst arg-name b-form]
  (let [expected (get parent-args arg-name)]
    (cond
      ;; No expected (slot not in parent) OR primitive `:fn` slot
      ;; (runtime accepts a fn-id literal OR a fn-ref; no structural
      ;; shape to compare against — defer to the runtime).
      (or (nil? expected) (= :fn expected))
      subst

      ;; STRUCTURAL fn-type slot, fn-ref binding. Assemble the ref's
      ;; own structural shape and unify positionally — catches HOF
      ;; mismatches like `:filter :pred :add-10` (add-10 returns
      ;; :int, filter expects :bool).
      ;;
      ;; Closure-capture awareness: the ref's free args partition into
      ;; (a) CALL-SITE args (those whose names match the slot's
      ;; structural `[:fn {ARGS} …]`) — executor's hof-wrap supplies
      ;; them per invocation; and (b) CAPTURED args (everything else)
      ;; — closed over at wrap time from the OUTER fn-def's
      ;; binding-chain (docs/CLOSURE_CAPTURE.md). The capture-side
      ;; doesn't participate in the slot's callable contract, so
      ;; strip those keys from the actual fn-type before unifying.
      ;; Without this, `:_apply-update-record-type-body`'s computed
      ;; `[:fn {:parsed _, :journal _} _]` rejects against `:try
      ;; :body [:fn {} a :any]` — the two captured args are
      ;; legitimately closure-captured per the runtime contract.
      (and (types/fn-type? expected) (ref-binding? b-form))
      (if-let [actual (assemble-fn-type b-form)]
        (let [actual' (strip-closure-captures expected actual b-form)]
          (check-binding! primary-parent arg-name expected actual' subst fn-name b-form))
        subst)

      ;; Structural fn-type slot, LITERAL value binding — a fn-typed
      ;; slot expects a callable but the binding is a primitive (text,
      ;; int, bool, …) that can't be invoked. The runtime would either
      ;; throw on the call OR silently wrap the literal into a
      ;; constant-returning thunk depending on the call site —
      ;; either way the author meant SOMETHING else (a fn-ref or an
      ;; inline anon). Catch at sync time.
      ;;
      ;; `{:as :name}` renames (with or without `:type`) still defer
      ;; — the slot becomes a free arg, the caller supplies the
      ;; callable. Vector bindings to fn slots aren't a thing
      ;; (sequence-slot branch below handles them), but the explicit
      ;; defer keeps the cases independent.
      (and (types/fn-type? expected)
           (map? b-form)
           (contains? b-form :value)
           (let [classified (lit/classify-literal (:value b-form))]
             (and (some? classified) (not= classified :any))))
      (throw-literal-bound-to-fn-slot! primary-parent fn-name arg-name b-form expected)

      ;; Structural fn-type slot, non-ref binding — runtime will
      ;; hof-wrap whatever's there. No structural info; defer.
      (types/fn-type? expected)
      subst

      ;; Sequence slot, vector binding — walk every item.
      (and (sequence-slot? expected) (vector? b-form))
      (check-sequence-items primary-parent fn-name arg-name expected b-form subst)

      ;; Deferred cases (see `deferred-binding?` for the breakdown).
      (deferred-binding? expected b-form)
      subst

      (ref-binding? b-form)
      ;; Freshen the ref's return-type so its `'a`/`'b` don't collide
      ;; with the caller's free typevars. Without this an actual like
      ;; `[:union :null a]` (from a fn-def whose computed return is
      ;; nullable-and-polymorphic) gets stuck against the slot's bare
      ;; `'a` via the occurs check — `bind-var 'a [:union :null 'a]`
      ;; fails because the typevar appears in its own value. After
      ;; freshening the actual carries `'a-N` (fresh per use site) so
      ;; unify cleanly binds `'a := [:union :null 'a-N]`.
      (let [actual (or (some-> (ref-return-narrowed b-form) types/freshen)
                       :any)]
        (check-binding! primary-parent arg-name expected actual subst fn-name b-form))

      (value-binding? b-form)
      (let [actual (or (lit/classify-literal (literal-binding-value b-form)) :any)]
        (check-binding! primary-parent arg-name expected actual subst fn-name b-form))

      :else subst)))


(defn- type-check-bindings
  "Reduce over the fn-def's args; for each, type-check the binding
   against the parent's expected type. Returns the final substitution.

   `init-subst` seeds the reduction — `check-fn-def!` passes the
   backward-unification result here (declared `:return-type` already
   bound to the parent's return type-var), so a slot typed by that
   same var is checked against the narrowed type."
  [fn-def primary-parent parent-args init-subst]
  (reduce-kv (fn [subst arg-name b-form]
               (check-one-binding primary-parent (:name fn-def)
                                  parent-args subst arg-name b-form))
             (or init-subst {})
             (:args fn-def)))


;; -----------------------------------------------------------------------------
;; Phase 2: collect free args (parent's MINUS bound + renames + ref-lifted).

(defn- item-free-args
  "Free args introduced by ONE sequence-item: a keyword fn-ref or
   `{:ref X}` lifts the ref's args; an `{:as :name}` introduces a
   named free slot of unknown type."
  [item]
  (cond
    (keyword? item)
    (or (:args (registry/rich-type-of item)) {})

    (and (map? item) (contains? item :ref))
    (or (:args (registry/rich-type-of (:ref item))) {})

    (and (map? item) (contains? item :as)
         (not (contains? item :value))
         (not (contains? item :ref)))
    {(:as item) :any}

    :else {}))


(defn- hof-slot?
  "True iff slot `arg-name` is declared as a callable — the bare `:fn`
   primitive or a structural `[:fn …]`. A ref bound to such a slot is
   HOF-wrapped: the executor's `hof-wrap` consumes the slot's
   declared call-site args per-invocation. The OTHER free args of the
   wrapped fn-graph (captured args, per docs/CLOSURE_CAPTURE.md) DO
   propagate as free args of the outer fn-def — see `ref-free-args`."
  [parent-args arg-name]
  (let [t (get parent-args arg-name)]
    (or (= :fn t) (types/fn-type? t))))


(defn- hof-call-site-arg-names
  "Set of keys in the slot's structural `[:fn {ARGS} RET EFFS]` shape
   — the call-site args supplied by the parent fn's impl at every
   invocation. The bare `:fn` primitive has no structural shape (the
   slot accepts any callable), so call-site is empty there — every
   free arg of the wrapped fn-graph becomes captured."
  [parent-args arg-name]
  (let [t (get parent-args arg-name)]
    (set (keys (or (types/fn-args t) {})))))


(defn- ref-free-args
  "Per-ref freshening: each fn-ref binding brings its own scope of
   type-vars into the fn-def's surface. Without freshening, two refs
   both exposing `'a` would collide. `types/freshen-args` renames
   each var to a unique `'a-<n>` while keeping sharing within one ref.

   For a ref bound to a HOF slot, the lifted set is the ref's free
   args MINUS the slot's structural call-site arg names. Closure-
   capture semantics (docs/CLOSURE_CAPTURE.md § Implementation
   Contract): call-site args are supplied per invocation by the
   parent's impl; captured args must come from the outer binding-
   chain and therefore widen the calling fn-def's free-arg surface."
  [args parent-args]
  (let [fresh (fn [fn-name]
                (when-let [ents (:args (registry/rich-type-of fn-name))]
                  (types/freshen-args ents)))
        lift-for-slot (fn [arg-name ref-args]
                        (if (hof-slot? parent-args arg-name)
                          (apply dissoc ref-args
                                 (hof-call-site-arg-names parent-args arg-name))
                          ref-args))]
    (reduce-kv (fn [acc arg-name b-form]
                 (cond
                   (keyword? b-form)
                   (merge acc (lift-for-slot arg-name (or (fresh b-form) {})))

                   (and (map? b-form) (contains? b-form :ref))
                   (merge acc (lift-for-slot arg-name
                                             (or (fresh (:ref b-form)) {})))

                   ;; List items aren't HOF-wrapped — they're inlined
                   ;; into the outer evaluation context, so ALL their
                   ;; free args lift regardless of the slot's HOF-ness.
                   (vector? b-form)
                   (reduce (fn [a item] (merge a (item-free-args item)))
                           acc b-form)

                   :else acc))
               {}
               args)))


(defn- collect-free-args
  "Compute the fn-def's remaining free-arg surface: parent's args
   MINUS those locally bound, PLUS renames (as new names), PLUS
   transitive ref-free args (freshened per ref)."
  [fn-def parent-args subst]
  (let [args (:args fn-def)
        ;; Single pass over `args` partitions every binding into the
        ;; four categories the rest of the algorithm needs.
        ;; Pre-fix this iterated `args` FOUR times (once per `into`
        ;; over a `keep`). Type-checker runs once per fn-def at sync
        ;; AND per call on /api/types/candidates + /api/types/compatible.
        ;;
        ;; Rename-bindings and type-only bindings BOTH leave the slot
        ;; free at this fn-def level — neither carries a `:value` /
        ;; `:ref`, they only annotate (new name / pinned type). Keep
        ;; them out of `real-bound` so the slot still surfaces on the
        ;; free-arg interface.
        ;;
        ;; A rename can locally override the slot's type via
        ;; `{:as :name :type T}` — used by `:assoc-fn` (pinning
        ;; `:value` to `:fn`) and by record-constructor templates that
        ;; want each lifted field to carry the record's field-type.
        ;; Without honouring `:type`, the free-arg surface would show
        ;; the parent's looser slot type.
        ;;
        ;; Type-only bindings pin the free-arg's type to the author's
        ;; override (same idea as rename-with-:type, but the public
        ;; name stays the same — no rename involved).
        {:keys [real-bound renamed-original-names renamed type-pinned]}
        (reduce-kv
          (fn [acc a b]
            (cond
              (rename-binding? b)
              (let [t (or (some-> (:type b) types/resolve-alias)
                          (some-> *caller-narrowings* (get (:as b)))
                          (types/resolve subst (or (get parent-args a) :any)))]
                (-> acc
                    (update :renamed-original-names conj a)
                    (update :renamed assoc (:as b) t)))

              (type-only-binding? b)
              (update acc :type-pinned assoc a (some-> (:type b) types/resolve-alias))

              :else
              (update acc :real-bound conj a)))
          {:real-bound #{} :renamed-original-names #{} :renamed {} :type-pinned {}}
          args)
        local-free (into (merge renamed type-pinned)
                         (keep (fn [[a t]]
                                 (when-not (or (contains? real-bound a)
                                               (contains? renamed-original-names a)
                                               (contains? type-pinned a))
                                   [a (types/resolve subst t)])))
                         parent-args)]
    (merge (ref-free-args args parent-args) local-free)))


;; -----------------------------------------------------------------------------
;; Phase 3: bindings-info for the type-rule.

(defn- vector-binding-elem-types
  "Walk a literal-vector binding and produce the elem types — keyword
   items lift the ref's :return, map items handle :value/:ref, plain
   items go through classify-literal.

   Closure-capture items (`{:as :captured-name ...}`) are NOT literal
   values — they're free-arg lift markers, the actual value comes
   from the caller's scope at runtime. Type is therefore unknown
   at this site (`:any`), mirroring `sequence-item-actual-type`.
   Without this branch, the map gets classified as a record
   (`{:as :keyword, :description :text}`) and downstream return-rules
   (like `merge-return-rule`'s all-records branch) build the wrong
   merged shape from these meta-fields."
  [items]
  (mapv (fn [item]
          (case (binding-shape item)
            :fn-ref    (or (ref-return-narrowed item) :any)
            :value-map (or (lit/classify-literal (:value item)) :any)
            :ref-map   (or (some-> (:type item) types/resolve-alias)
                           (ref-return-narrowed (:ref item))
                           :any)
            :meta-map  (or (:type item) :any)
            (or (lit/classify-literal item) :any)))
        items))


(declare base-fn-type-rule
         effective-ref-return
         effective-ref-return-uncached)


(defn- effective-binding-type
  "The type of a bindings-info entry, resolved with caller-context
   `combined-bindings` overlaid. For ref-bindings, re-fires the
   ref's root-rule against the combined bindings — this is what
   makes `:_app-ring-response` see `:m`'s type as the structural
   ring-response-shape (because `:func` is now bound to `:_router`)
   rather than the `:any` `:router-result` recorded in isolation.

   Author-pinned types (`{:ref :X :type :T}` in fns.edn) skip the
   re-fire — they're represented in info maps WITHOUT a `:ref` key,
   so `effective-binding-type` simply returns the pinned `:type`.
   `bindings-info-for-rule` is the single source of truth: it omits
   `:ref` from the entry when the author also wrote `:type`.

   `seen` and `depth` cap unbounded recursion through
   self/cyclic refs."
  [info combined-bindings seen depth]
  (or (when (:ref info)
        (effective-ref-return (:ref info) combined-bindings seen depth))
      (:type info)))


(defn- effective-ref-return
  "Re-compute `ref-name`'s return-type with `caller-bindings` overlaid
   onto the ref's own resolved bindings, so structural narrowing the
   caller introduces (e.g. binding `:func` to a structural fn-type)
   flows into the ref's root-rule. Falls back to the static recorded
   return when re-firing produces nothing useful."
  [ref-name caller-bindings seen depth]
  (when (and ref-name
             (not (contains? seen ref-name))
             (< depth 6))
    (if-let [override (and *ref-return-overrides*
                           (get *ref-return-overrides* ref-name))]
      ;; Phase #170: caller's control-flow guard proves a narrower
      ;; return for `ref-name` than the static registry view. Skip
      ;; re-firing the rule and use the override directly.
      override
      (effective-ref-return-uncached ref-name caller-bindings seen depth))))


(defn- effective-ref-return-uncached
  [ref-name caller-bindings seen depth]
  (when-let [info (registry/rich-type-of ref-name)]
    (let [seen' (conj seen ref-name)
          ref-bindings (:resolved-bindings info {})
          combined (merge caller-bindings ref-bindings)
          inner-info (into {}
                           (map (fn [[k v]]
                                  [k {:type (effective-binding-type v combined seen' (inc depth))
                                      :value (:value v)}]))
                           combined)
          root-base (registry/root-base-fn-name ref-name)
          static (or (:return info) :any)
          recomputed (if-let [rule (base-fn-type-rule :return-type-rule root-base)]
                       (rule inner-info static)
                       static)]
      (if (= recomputed :any) static recomputed))))


(defn- binding-info-entry
  "Classify a single fn-def binding shape into the `{:type … :value …
   :ref ?}` entry the type-rules read. See `bindings-info-for-rule`'s
   docstring for the broader contract; this is the per-binding cond
   dispatch."
  [b-form]
  (cond
    ;; Bare rename `{:as :name}` — slot stays free with the renamed
    ;; name. If `:type T` is also given, the rule should see T (matches
    ;; the rename's type-override semantics in `collect-free-args`).
    ;;
    ;; Phase α' caller-narrowing: when the outer caller has propagated
    ;; a narrowed type for this AS-name, use it. Resolution:
    ;; author-pinned > caller-narrowing > `:any`.
    (rename-binding? b-form)
    {:type (or (some-> (:type b-form) types/resolve-alias)
               (some-> *caller-narrowings* (get (:as b-form)))
               :any)
     :value nil}

    (and (map? b-form) (contains? b-form :value))
    {:type (or (some-> (:type b-form) types/resolve-alias)
               (lit/classify-literal (:value b-form))
               :any)
     :value (:value b-form)
     :value-present true}

    ;; `{:type T}` alone — author pins the static type without
    ;; supplying a value. The slot stays free at runtime; the
    ;; override flows through to consumers / rules verbatim. No `:ref`
    ;; in the info entry → `effective-binding-type` returns `:type`
    ;; directly, without re-firing any ref's rule.
    (type-only-binding? b-form)
    {:type (or (some-> (:type b-form) types/resolve-alias) :any)
     :value nil}

    (ref-binding? b-form)
    {:type (or (ref-return-narrowed b-form) :any)
     :value nil
     :ref b-form}

    ;; `{:ref :name :type T}` — explicit ref-with-type-override (parser
    ;; writes the binding's `:type-override-fn-id`). The override is
    ;; the author's narrowed contract; prefer it over the ref's
    ;; recorded return so the type-rule sees the tighter shape. When
    ;; the author pinned a `:type`, OMIT `:ref` from the info entry so
    ;; `effective-binding-type` doesn't re-fire the ref's rule and
    ;; clobber the override.
    (and (map? b-form) (contains? b-form :ref))
    (cond-> {:type (or (some-> (:type b-form) types/resolve-alias)
                       (ref-return-narrowed (:ref b-form))
                       :any)
             :value nil}
      (not (contains? b-form :type))
      (assoc :ref (:ref b-form)))

    (vector? b-form)
    ;; `:elem-types` carries the PER-ITEM types the rule may need to
    ;; look at (e.g. `:merge` walks each :maps element to union their
    ;; record fields). The lubbed `:type` keeps the shape every other
    ;; rule already reads.
    (let [et (vector-binding-elem-types b-form)]
      {:type [:list (types/coarse-lub et)]
       :elem-types et
       :value b-form
       :value-present true})

    (keyword? b-form)
    {:type :any :value b-form :value-present true}

    :else
    {:type (or (lit/classify-literal b-form) :any)
     :value b-form
     :value-present true}))


(defn- bindings-info-for-rule
  "Build the `{arg-name {:type … :value … :ref ?}}` map the type-rule
   reads. Literal values pass through; refs resolve via the registry's
   :return; literal vectors expose `[:list T]` so e.g. `:first`'s
   rule can return the precise elem-type.

   `parent-args` (optional) — the parent's resolved free-arg type
   map, consulted as a fallback for keys this fn-def doesn't directly
   bind. A child fn-def `:ring-method :parent :ring-request-field`
   doesn't list `:coll` in its own `:args` (its parent does), but
   the `:get` rule firing on `:ring-method` STILL needs `:coll`'s
   type to compute the result.

   `:ref` field on each entry (when present) names the bound fn-def —
   used by `effective-binding-type` to RE-FIRE the ref's root-rule
   with the calling fn-def's bindings overlaid, so structural
   narrowing introduced by the caller (binding `:func` to a
   structural Ring handler) flows up to consumers like
   `:update-in`'s `:m` type."
  ([args] (bindings-info-for-rule args nil))
  ([args parent-args]
   (let [own (into {}
                   (map (fn [[arg-name b-form]]
                          [arg-name (binding-info-entry b-form)]))
                   args)
         from-parent (into {}
                           (keep (fn [[arg-name arg-info]]
                                   (when (and arg-info
                                              (not (contains? own arg-name)))
                                     [arg-name (if (map? arg-info)
                                                 arg-info
                                                 {:type arg-info :value nil})])))
                           parent-args)
         merged (merge from-parent own)]
     ;; Resolve ref-binding types in the COMBINED context — each
     ;; ref's root-rule re-fires with the merged bindings overlaid.
     ;; This is the call-site narrowing missing under purely
     ;; isolated per-fn-def type-checking.
     (into {}
           (map (fn [[k v]]
                  [k (assoc v :type
                            (effective-binding-type v merged #{} 0))]))
           merged))))


;; -----------------------------------------------------------------------------
;; Phase 4: effects — parent's ∪ each ref-binding's.

(defn- compute-per-arg-effects
  "Per-arg effect contribution: `{arg-name #{effects}}`. For each arg,
   the union of effects from refs bound to that arg. Closure-capture
   strip at fn-type binding sites subtracts these when an arg is
   wrap-time-only — its effects belong to the OUTER scope's effect
   computation, not to the per-invocation callable contract."
  [args]
  (into {}
        (map (fn [[arg-name b-form]]
               [arg-name
                (reduce (fn [a r]
                          (into a (or (:effects (registry/rich-type-of r)) #{})))
                        #{}
                        (ref-targets b-form))]))
        args))


(defn- compute-effects
  "Effects are tainted: parent ∪ every ref-binding's effects. Once
   any link in the composition reads/writes I/O the tag flows into
   the fn-def — caching / parallelism / docs all read this single
   source of truth."
  [args parent-info]
  (let [ref-effects (reduce into #{} (vals (compute-per-arg-effects args)))]
    (into ref-effects (or (:effects parent-info) #{}))))


(defn- compute-call-time-effects
  "Effects that run on EVERY invocation of this fn-def when it's used
   as a callable (HOF arg). Splits:

   - PARENT's call-time effects (its body — pure for `:equal?` etc.)
   - effects of refs bound to args that REMAIN free (call-site args)

   EXCLUDES: effects of refs bound to args that are FULLY BOUND in
   this fn-def — those are wrap-time / construction-time, computed
   ONCE when the lambda is assembled, and their effects belong to
   the OUTER scope, not the per-invocation callable contract.

   This is what HOF slots check against (`:filter :pred [:fn … #{}]`
   demands pure per-invocation): a fn-def that pre-computes a DB
   value at construction time is still a pure predicate at call time.

   Free args are the arg-NAMES that survive the lift — bound args
   are everything else under `(:args fn-def)`."
  [args parent-info free-arg-names]
  (let [free-keys (set free-arg-names)
        per-arg (compute-per-arg-effects args)
        call-site-arg-effects (reduce into #{}
                                      (vals (select-keys per-arg free-keys)))
        parent-call-time (or (:call-time-effects parent-info)
                             (:effects parent-info)
                             #{})]
    (into call-site-arg-effects parent-call-time)))


(defn- check-effects-policy!
  "Compare declared `:expects-effects` with computed; throw on drift.

   `:expects-effects` is a CONTRACT — the author asserts the closure
   stays within those categories. UNDER-declaration (computed effects
   not in `:expects-effects`) is the case we reject: a hidden call
   path acquired an effect the contract didn't admit, exactly the
   bug the contract was meant to catch.

   OVER-declaration (declared effect categories not actually
   computed — `:ex-overdeclared-pure` style) is harmless and
   intentional — the editor renders the surplus as an outlined chip,
   nudging the author to either tighten the declaration or wire in
   the promised effect. We don't reject it; the surplus is a
   pending-not-yet-implemented marker, not drift.

   Throws `:types/expects-effects-drift` on rejection. Sync paths
   (`check-fn-def!` callers in `executor/composition`) bubble this
   up as a sync-time failure so EDN authors see the broken contract
   immediately on `bb rebuild`."
  [fn-name fn-def effects]
  (when-let [expected (some-> fn-def :expects-effects set)]
    (let [unexpected (set/difference effects expected)]
      (when (seq unexpected)
        (throw (ex-info (str "fn-def " fn-name " declared :expects-effects "
                             expected " but computed effects include "
                             unexpected
                             " — drift in the call graph: every effect"
                             " the closure produces must appear in the"
                             " :expects-effects set.")
                        {:type :types/expects-effects-drift
                         :fn-name fn-name
                         :declared expected
                         :computed effects
                         :unexpected unexpected}))))))


;; -----------------------------------------------------------------------------
;; Phase 5: registry record.

(defn- record-result!
  "Snapshot the fn-def's computed rich-type into the registry so
   downstream `check-fn-def!` runs can resolve refs to it.

   `:primary-parent` is the immediate parent name — `registry/root-base-fn-name`
   walks this to find the base-fn that `compute-return-type` should
   dispatch on. Without it, a chain `child :parent :assoc-empty :parent
   :assoc` would dispatch on `:assoc-empty` (no rule) and silently
   fall through to `:default` instead of using `:assoc`'s rule.

   `:resolved-bindings` (NEW) — accumulated `{slot → {:type … :value …}}`
   for slots BOUND at any level of this fn-def's chain. Type-rules
   on descendants can read a slot's type even when the binding lives
   deeper up. Closer-fn-wins (own bindings shadow parent's).

   `:slot-types` — `{slot-name → unified-type}` for slots whose
   parent-declared type-var got narrowed at THIS fn-def (e.g. a
   declared `:return-type` flowing through `:const`'s `a` into the
   `:value` slot). Only slots whose type actually changed are
   listed. The editor reads this to show the effective slot type on
   the chip instead of the parent's generic type-var.

   `:nav-types` — `{slot-name → navigable-structure}` for sequence
   slots whose items index into a known shape (`:update-in`'s `:path`
   → `:m`'s record). The editor walks this against the live path to
   type each segment position and gate the `+` append affordance."
  [fn-name fn-def primary-parent parent-info free-args
   computed-return effects own-resolved slot-types nav-types drift]
  (let [expected (some-> fn-def :expects-effects set)
        resolved (merge (:resolved-bindings parent-info {}) own-resolved)
        arg-effects (compute-per-arg-effects (:args fn-def))
        call-time-effects (compute-call-time-effects (:args fn-def)
                                                     parent-info
                                                     (keys free-args))]
    (registry/record-rich-types-raw!
      fn-name
      (cond-> (merge {:return computed-return :args free-args}
                     (source-info-for fn-def))
        (seq resolved)    (assoc :resolved-bindings resolved)
        (seq slot-types)  (assoc :slot-types slot-types)
        (seq nav-types)   (assoc :nav-types nav-types)
        primary-parent    (assoc :primary-parent primary-parent)
        (seq effects)     (assoc :effects effects)
        ;; `:arg-effects` — per-binding effect contribution. Used by
        ;; the closure-capture strip in check-binding! to subtract
        ;; wrap-time-only effects from the per-invocation callable
        ;; contract when the strip removes captured-arg keys.
        (some seq (vals arg-effects)) (assoc :arg-effects arg-effects)
        ;; `:call-time-effects` — effects per-invocation when used as
        ;; a HOF callable. Parent's body + free-arg ref-effects;
        ;; EXCLUDES bound-arg ref-effects (those are wrap-time, run
        ;; once during outer assembly). When equal to `:effects` the
        ;; assoc is redundant — only stash on divergence.
        (and (some seq (vals arg-effects))
             (not= call-time-effects effects))
        (assoc :call-time-effects call-time-effects)
        expected          (assoc :expects-effects expected)
        drift             (assoc :return-type-drift drift)
        ;; Surface description so the inline-expand panel can show
        ;; a human-readable hint under the type name.
        (and (:description fn-def)
             (seq (:description fn-def))) (assoc :description (:description fn-def))))))


;; -----------------------------------------------------------------------------
;; Top-level: compose the phases.

(defn- base-fn-type-rule
  "Look up a per-base-fn type-rule — `:return-type-rule`,
   `:slot-types-rule` or `:nav-types-rule` — from the rich-types
   registry by base-fn name. Each rule is declared at the base-fn's
   own impls.clj registration site (no name-dispatched multimethod);
   nil when the base-fn has no rule of that kind."
  [rule-key base-fn-name]
  (rule-key (registry/rich-type-of base-fn-name)))


(defn- compute-return-type
  "Run the ROOT base-fn's type-rule (e.g. `:assoc` record-builder) on
   `static-ret` to produce the rich, possibly-narrowed return shape.
   Walking to the root via `registry/root-base-fn-name` lets rules fire even
   when the immediate parent is an intermediate fn-def
   (`:assoc-empty`, `:_jvm-section`).

   `parent-args` (the parent's resolved free-arg types) flow in as a
   fallback so a rule whose key isn't bound at THIS fn-def's level
   still finds the type from a deeper-up binding — e.g. `:ring-method
   :parent :ring-request-field` doesn't itself bind `:coll`, but
   `:ring-request-field` does (`:coll :ring-request`); the `:get`
   rule on `:ring-method` should see `:coll`'s type as a record so
   it can lift `:request-method`'s primitive type out of the shape."
  [fn-def primary-parent parent-args static-ret]
  (if-let [rule (base-fn-type-rule :return-type-rule
                                   (registry/root-base-fn-name primary-parent))]
    (rule (bindings-info-for-rule (:args fn-def) parent-args) static-ret)
    static-ret))


(defn- apply-args-only-rule
  "Shared shape for the rules whose signature is `(rule
   bindings-info) → narrowed-map`: pull the rule off the ROOT
   base-fn's rich-type entry by `rule-key`, run it against the
   call-site's args-info, default to `{}` when no rule is declared.
   Wrapped by `compute-rule-slot-types` + `compute-rule-nav-types` —
   the two rules whose docs differ but bodies were byte-identical."
  [rule-key fn-def primary-parent parent-args]
  (if-let [rule (base-fn-type-rule rule-key
                                   (registry/root-base-fn-name primary-parent))]
    (rule (bindings-info-for-rule (:args fn-def) parent-args))
    {}))


(defn- compute-rule-slot-types
  "Run the ROOT base-fn's `compute-slot-types` rule — narrowed INPUT
   slot types the editor surfaces on type-chips (e.g. `:update-in`
   narrowing `:path` to `[:list :keyword]` when `:m` is a record).
   Empty for base-fns without a slot-types rule."
  [fn-def primary-parent parent-args]
  (apply-args-only-rule :slot-types-rule fn-def primary-parent parent-args))


(defn- compute-rule-nav-types
  "Run the ROOT base-fn's `compute-nav-types` rule — `{slot-name →
   navigable-structure}` for sequence slots whose items index into a
   known shape (e.g. `:update-in`'s `:path` walking `:m`'s record).
   The editor walks this against the live path. Empty for base-fns
   without a nav-types rule."
  [fn-def primary-parent parent-args]
  (apply-args-only-rule :nav-types-rule fn-def primary-parent parent-args))


(defn- enforce-declared-return!
  "When a fn-def pins `:return-type T`, verify the computed return is a
   subtype of T. The declared T is then what the registry stores —
   downstream consumers see the declared contract, not the
   possibly-tighter computed shape. Returns the recorded return.

   Secret-taint propagation is allowed past the declared base type:
   a computed `[:secret T]` against declared `T` is fine — the
   marker is propagation metadata, not a widening, and the
   *registered* return-type carries the marker so downstream
   type-check sees the taint. The declared `T` documents the
   underlying runtime contract; the rule lifts it to `[:secret T]`
   when any input was tainted."
  [fn-name fn-def computed-return]
  (let [declared (some-> fn-def :return-type types/resolve-alias)
        computed-ok? (or (types/subtype? computed-return declared)
                         (and (types/secret-type? computed-return)
                              (types/subtype? (types/secret-inner computed-return)
                                              declared))
                         ;; Author-assertion mode: when the AUTHOR
                         ;; declares a structural T (record, list,
                         ;; map, fn, refinement, …) but the rule
                         ;; chain bottoms out at `:any` /
                         ;; `[:map :any :any]` / friends, accept the
                         ;; assertion. Primitive declared types
                         ;; (`:int`, `:text`, …) DON'T get this hatch
                         ;; — those are typo-prone and the rejection
                         ;; protects against "I said :int but my
                         ;; computed is unconstrained". Structural
                         ;; declared types are the contract-by-shape
                         ;; case the author owns at runtime.
                         (and (any-shape? computed-return)
                              declared
                              (not (types/primitive? declared)))
                         ;; Author NARROWING-assertion mode: declared
                         ;; is strictly MORE specific than computed
                         ;; (declared ⊆ computed). The author commits
                         ;; to a runtime-guaranteed contract that the
                         ;; rule chain couldn't prove without help —
                         ;; the classic case is `:_create-parsed`
                         ;; whose `:entity-type` is non-null at apply
                         ;; time because validation upstream rejects
                         ;; null first. Same guardrail as any-shape
                         ;; mode: only structural declared types
                         ;; qualify; primitives stay strict to catch
                         ;; typos. `subtype? declared computed`
                         ;; verifies the relation is sound — declared
                         ;; values are a subset of what computed
                         ;; admits, so the author's narrower view is
                         ;; never wider than the rule could prove.
                         (and declared
                              (not (types/primitive? declared))
                              (types/subtype? declared computed-return)))
        ;; When declared is plain `T` but computed carries a `:secret`
        ;; anywhere (top-level OR nested in a list/record/tuple), record
        ;; the tainted form — that's what downstream consumers need to
        ;; see to refuse to drop the marker. `contains-secret?` (not the
        ;; top-level `secret-type?`) is the honest test: a computed
        ;; `[:list [:secret :text]]` against a declared `[:list :text]`
        ;; would otherwise record the declared form and lose the marker.
        recorded (if (or (nil? declared)
                         (types/contains-secret? computed-return))
                   computed-return
                   declared)]
    (when (and declared (not computed-ok?))
      (throw (ex-info
               (str "type-check failed: " fn-name
                    " declares :return-type " (pr-str (:return-type fn-def))
                    " but the computed return is not a subtype.\n"
                    "  declared: " (pr-str declared) "\n"
                    "  computed: " (pr-str computed-return))
               {:type :types/check-failed
                :reason :return-type-mismatch
                :fn-name fn-name
                :declared declared
                :computed computed-return})))
    recorded))


(defn- return-type-drift
  "Return-type drift detection — advisory, NOT a reject.

   When a fn-def author pinned `:return-type T` AND the rule-computed
   return is STRICTLY narrower (computed ⊊ declared — both
   `computed ⊆ declared` AND NOT `declared ⊆ computed`), the author
   has declared a wider contract than the actual computed shape.
   This is sometimes intentional (the author wants to keep the
   contract loose so future tightenings don't break downstream
   consumers), sometimes stale (the rule got more precise and the
   declaration was never updated).

   Returns `{:declared D :computed C}` for surfacing into the
   registry + `/api/types/drift` (consumed by the `bb types-drift`
   task and the editor). nil when no drift OR when no `:return-type`
   was declared at all.

   Type-vars on both sides are treated as opaque — a parametric
   `[:fn {:arg a} b]` doesn't drift against another parametric
   form. Drift comparison only fires when both sides are
   concrete-enough that `subtype?` decides them definitively."
  [fn-def computed-return]
  (let [declared (some-> fn-def :return-type types/resolve-alias)]
    (when (and declared
               (not= declared computed-return)
               (types/subtype? computed-return declared)
               (not (types/subtype? declared computed-return)))
      {:declared declared :computed computed-return})))


(defn- log-return-type-drift!
  "Emit a sync-time WARN for return-type drift. Mirrors the channel
   `:expects-effects` drift already uses, so authors see both kinds
   of drift in the same `bb rebuild` output. Includes file:line from
   `*source-info*` when available."
  [fn-name fn-def {:keys [declared computed]}]
  (let [{:keys [source-file source-line]} (source-info-for fn-def)
        where (cond
                (and source-file source-line) (str source-file ":" source-line)
                source-file                   source-file
                :else                         "<unknown>")]
    (log/warnf "type-drift: fn-def %s declares :return-type %s but the computed return is strictly narrower: %s — consider tightening the declaration (or accept it as a deliberately-wide contract). at %s"
               (pr-str fn-name)
               (pr-str declared)
               (pr-str computed)
               where)))


(defn- log-effects-drift!
  "Emit a sync-time WARN when a composed fn-def's declared `:effects`
   set differs from the computed set. For composed fn-defs the
   type-checker / runtime read the COMPUTED `:effects` from the
   rich-type registry — the author's declaration is then a no-op,
   not a contract. Same author-misled-by-declaration class as
   return-type drift; treat it as a soft signal so existing
   fn-defs that just redundantly redeclare what's already computed
   don't break (their declared == computed, no warning fires).

   The contract-style equivalent is `:expects-effects`, which IS
   enforced by `check-effects-policy!`. Authors who want a binding
   contract should use that field instead.

   `:db` / `:env` / `:io` / `:network` / `:time` / `:random` /
   `:process` / `:raw-sql` — same set the runtime accepts.

   `:expects-effects` is skipped here (that path has its own drift
   check)."
  [fn-name fn-def declared computed]
  (let [{:keys [source-file source-line]} (source-info-for fn-def)
        where (cond
                (and source-file source-line) (str source-file ":" source-line)
                source-file                   source-file
                :else                         "<unknown>")]
    (log/warnf "type-drift: fn-def %s declares :effects %s but the computed set is %s — the declared value is silently dropped (rich-type uses the computed set). Use :expects-effects for a binding contract, or remove the redundant :effects field. at %s"
               (pr-str fn-name)
               (pr-str declared)
               (pr-str computed)
               where)))


(defn check-fn-def!
  "Type-check a single fn-def against its parent's signature
   (whether that parent is a base-fn or a previously-checked
   composed fn-def). On success, snapshot the fn-def's computed
   rich-type into the registry so DOWNSTREAM checks can resolve
   refs to it.

   Throws `:types/check-failed` on mismatch.

   Recognised binding shapes:
     :other-fn           — fn-ref; actual = ref's return type
     {:value x}          — literal; actual = classify-literal
     bare literal        — same as {:value …}
     {:as :name}         — rename, no value flow → checked at caller
     [items …]           — sequence chain; each item type-checked
                           against the slot's element type
     {:ref :name}        — same as a bare keyword fn-ref"
  [{fn-name :name :as fn-def}]
  (when-let [{:keys [primary-parent parent-info]} (resolve-parent-info fn-def)]
    (binding [*source-info* (source-info-for fn-def)]
      ;; Type-row parents (e.g. `:Storage`, `:ResolveVersionedRowsInput`)
      ;; declare their abstract operations as record FIELDS, not as the
      ;; rich-type `:args` slot map (which is empty for type-rows).
      ;; Concrete impls (`:postgres-storage-impl :parent :Storage :args
      ;; {:query :pg-query …}`) bind those fields. Inject the resolved
      ;; fields from EVERY parent in the MI list (primary OR secondary)
      ;; so the per-binding type-check verifies that bindings satisfy
      ;; the type-row's field contracts. `(:args parent-info)` wins
      ;; on a key collision — that's the normal slot map.
      ;;
      ;; Resolving every parent (not just primary) closes the MI bug
      ;; surfaced by `:resolve-versioned-rows :parents [:filter
      ;; :ResolveVersionedRowsInput]`: the type-row is the SECONDARY
      ;; parent, so a primary-only resolver missed its fields entirely
      ;; (`:version-id-field` etc. showed up as "unknown slot").
      (let [parent-list (or (seq (:parents fn-def))
                            (when (:parent fn-def) [(:parent fn-def)]))
            type-row-fields (reduce
                              (fn [acc p]
                                (let [resolved (and (keyword? p)
                                                    (types/resolve-alias p))]
                                  (if (types/record-type? resolved)
                                    (merge acc resolved)
                                    acc)))
                              (let [ret (:return parent-info)]
                                (if (types/record-type? ret) ret {}))
                              parent-list)
            parent-args (merge type-row-fields (:args parent-info))]
        (check-effect-categories! fn-def)
        (check-unknown-slots! fn-def primary-parent parent-args parent-info)
        (check-ref-type-overrides! fn-def primary-parent)
        (check-binding-monotonicity! fn-def primary-parent parent-args)
        (check-branch-local-monotonicity! fn-def)
        (let [;; Effective parent-args includes resolved bindings from
              ;; further up the chain — gives rules a transitive view of
              ;; slot types AND the ref-name (when bound to a fn-ref) so
              ;; ref re-firing can pick up call-site narrowing.
              effective-parent
              (merge (:resolved-bindings parent-info {})
                     (into {}
                           (map (fn [[k t]] [k {:type t :value nil}]))
                           parent-args))
              ;; Backward unification: when this fn-def declares a
              ;; concrete `:return-type` and the parent's return is a
              ;; bare type-var, bind that var. Any parent slot typed by
              ;; the SAME var is then checked against (and surfaced as)
              ;; the narrowed type — e.g. `:default-security-headers`
              ;; declaring `:return-type :security-headers-shape` over
              ;; `:const` binds `a`, so the `:value` slot reads as
              ;; `:security-headers-shape` instead of generic `a`.
              parent-ret    (:return parent-info)
              declared-ret  (:return-type fn-def)
              init-subst    (if (and declared-ret (types/type-var? parent-ret))
                              {parent-ret declared-ret}
                              {})
              subst (type-check-bindings fn-def primary-parent parent-args init-subst)
              ;; Slots whose type changed once `subst` is applied (backward
              ;; unification) PLUS rule-derived narrowings (e.g. `:update-in`
              ;; narrowing `:path` from its `:m` record). Rule narrowings
              ;; win on a key collision — a rule knows more than generic
              ;; var-substitution. The editor shows these on type-chips.
              slot-types (merge
                           (into {}
                                 (keep (fn [[arg-name arg-type]]
                                         (let [resolved (types/resolve subst arg-type)]
                                           (when (not= resolved arg-type)
                                             [arg-name resolved]))))
                                 parent-args)
                           (compute-rule-slot-types fn-def primary-parent
                                                    effective-parent))
              nav-types (compute-rule-nav-types fn-def primary-parent
                                                effective-parent)
              free-args (collect-free-args fn-def parent-args subst)
              static-ret (types/resolve subst (or (:return parent-info) :any))
              own-bindings (bindings-info-for-rule (:args fn-def))
              computed-return (compute-return-type fn-def primary-parent
                                                   effective-parent static-ret)
              recorded-return (enforce-declared-return! fn-name fn-def computed-return)
              drift (return-type-drift fn-def computed-return)
              effects (compute-effects (:args fn-def) parent-info)]
          (when drift (log-return-type-drift! fn-name fn-def drift))
          (when-let [declared (some-> fn-def :effects set)]
            (when (not= declared effects)
              (log-effects-drift! fn-name fn-def declared effects)))
          (check-effects-policy! fn-name fn-def effects)
          (record-result! fn-name fn-def primary-parent parent-info
                          free-args recorded-return effects own-bindings
                          slot-types nav-types drift)
          subst)))))


(defn check-all-defs!
  "Run type-check on every fn-def. Stops at the first mismatch.
   Returns the per-fn substitution map (for diagnostic / future
   computed-type cache) on success.

   PRECONDITION: `fn-defs` must be topologically ordered,
   dependencies-first. `check-fn-def!` records each computed rich-type
   into the shared registry (`record-result!` → `record-rich-types-raw!`),
   and downstream defs read parents / ref-returns back out via
   `registry/rich-type-of` (`resolve-parent-info`, `effective-ref-return`).
   A def checked BEFORE the def it references resolves that ref to `:any`
   instead of its real type — no error, just a silently looser check.
   Callers (`sync-fn-entities-from-packages!`) topo-sort before calling;
   the narrowing passes below have the same ordering dependency."
  [fn-defs]
  (into {}
        (map (fn [fn-def]
               [(:name fn-def) (check-fn-def! fn-def)]))
        fn-defs))


;; -----------------------------------------------------------------------------
;; Phase E — sweep failure allowlist.
;;
;; The set of fn-def names that are KNOWN to fail the type-check
;; sweep due to architectural gaps documented in
;; `docs/TYPE_SYSTEM_DECISIONS.md` and `docs/TYPE_CHECK_BACKLOG.md`.
;; Each name here is a piece of known debt — runtime is unaffected,
;; the editor's effect/return strips for these names may be missing.
;;
;; The sync-time check in `system.core/sync-fn-entities-from-packages!`
;; gates on this set:
;;   - Any failure NOT in this set is a REGRESSION — throws hard.
;;   - Any name in this set that's NO LONGER failing is STALE — also
;;     throws hard, to keep the ledger honest.
;;
;; Removing a name = the architectural gap that caused it has closed
;; (e.g. Phase α' caller-context propagation, or future row poly).
;; Adding a name = a known new debt the type-system can't yet
;; express; MUST be co-justified with a roadmap update.
;;
;; Post-Phase-α' (2026-06-16). The original 10 entries (the
;; `:_X-apply-result/-do-invalidate/-do-notify` family that read
;; `(:name (:get :parsed :entity-type :default nil))`) CLOSED when
;; α' Pass-2/3 caller-context propagation landed alongside the
;; per-use-site anon naming fix in `packages/records/parse.clj`.
;;
;; The 11 entries below are nullability gaps that α'-driven
;; tighter return types now surface — each binding passes
;; `[:union :null T]` into a slot expecting `T`, where the runtime
;; is guarded by an upstream nil-check the type-checker doesn't
;; yet see through. Closing them needs Phase #170 control-flow
;; narrowing through `:if`/`:cond` guards OR per-fn-def
;; `:assert-some` annotations.
(def allowed-type-check-failures
  ;; Closed 2026-06-16 — sweep down to 0 after applying author
  ;; type-assertions for runtime-guaranteed nullability narrowings
  ;; that the type-checker can't (yet) see through control-flow
  ;; guards. See `docs/TYPE_CHECK_BACKLOG.md` for the running ledger.
  ;;
  ;; 2026-06-19 — Phase #170 extended to recognize `:is-a?` predicates
  ;; in `:if` / `:cond` clauses (`direct-predicate-of-ref` +
  ;; `is-a-tag->structural-type` + `narrowed-type-for-predicate`).
  ;; Plumbed through bare-keyword ref-bindings in `binding-info-entry`
  ;; via `ref-return-narrowed`. Execute-result decomposition's
  ;; `:_er-list-items-taken` / `:_er-record-keys` now read `:_er-
  ;; result` directly inside `:cond`-narrowed branches — narrowed to
  ;; `[:list :any]` / `[:map :any :any]` by the new overrides. Sweep
  ;; stays at 0.
  #{})


(defn assert-sweep-failures-match-allowlist!
  "Verify the sync-time type-check sweep's failure set matches
   `allowed-type-check-failures` exactly. Throws on:
   - Any actual failure NOT in the allowlist — a REGRESSION.
   - Any allowlisted name that's NO LONGER failing — STALE allowlist
     (architectural gap closed, ledger needs trimming).

   Called by `system.core/sync-fn-entities-from-packages!` after the
   sweep; exposed as a separate fn so unit tests can exercise the
   logic without bootstrapping integrant."
  [failed-names]
  (let [actual              (set failed-names)
        unexpected-failures (set/difference actual allowed-type-check-failures)
        stale-allowlist     (set/difference allowed-type-check-failures actual)]
    (when (seq unexpected-failures)
      (throw (ex-info
               (str "Type-check sweep: " (count unexpected-failures)
                    " NEW failure(s) not in allowlist. Add to"
                    " `graphden.types.check/allowed-type-check-failures`"
                    " ONLY after confirming the failure is architectural known-debt"
                    " (not a runtime bug). Failing names: "
                    (pr-str (sort unexpected-failures)))
               {:type :types/sweep-regression
                :unexpected unexpected-failures
                :allowlist allowed-type-check-failures})))
    (when (seq stale-allowlist)
      (throw (ex-info
               (str "Type-check sweep: allowlist contains "
                    (count stale-allowlist)
                    " name(s) that NO LONGER fail. Remove from"
                    " `graphden.types.check/allowed-type-check-failures`"
                    " to keep the ledger honest. Stale names: "
                    (pr-str (sort stale-allowlist)))
               {:type :types/sweep-stale-allowlist
                :stale stale-allowlist
                :allowlist allowed-type-check-failures})))
    :ok))


;; -----------------------------------------------------------------------------
;; Phase α'  caller-context narrowings + Phase #170 control-flow ref-return
;; overrides moved to `graphden.types.check.narrowing`. The dynamic vars
;; `*caller-narrowings*` + `*ref-return-overrides*` defined above stay here
;; (consumed deep inside `check-fn-def!` and bound from narrowing's
;; `check-fn-def-with-narrowings!`).
