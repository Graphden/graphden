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
     `:assoc-in` / `:if` / `:into` / `:first` / `:rest` / arithmetic
     narrowing / etc — see `graphden.types.rules`.
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
    [graphden.executor.registry.core :as registry]
    [graphden.types.core :as types]
    [graphden.types.rules :as rules]))


(defn classify-literal
  "Infer the type of a literal Clojure value. Returns nil if the
   shape isn't a recognised primitive (callers can then fall back
   to `:any` or skip type-checking the binding)."
  [v]
  (cond
    (nil? v)         :null
    (boolean? v)     :bool
    (integer? v)     :int
    (float? v)       :float
    (string? v)      :text
    (keyword? v)     :keyword
    (uuid? v)        :uuid
    ;; Pending list-element inference for vector — :jsonb is the
    ;; conservative classification that flows everywhere safely.
    (or (vector? v) (map? v)) :jsonb
    :else            nil))


(declare literal-satisfies-refinement?)


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
     [:matches #\"regex\"]                   (text — currently :unknown)
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
        :in    (and (set? rhs) (contains? rhs v))
        :unknown))
    :else                                  :unknown))


(defn- ref-binding?
  "A bare keyword in fn-def args means a fn-ref to that name."
  [v]
  (keyword? v))


(defn- metadata-only-binding?
  "True iff `v` is a map binding that carries ONLY metadata
   (`:as`, `:required`, `:terminal?`, `:type`) without an explicit
   value or ref. Such bindings leave the slot logically free —
   nothing to type-check against the slot's expected type."
  [v]
  (and (map? v)
       (not (contains? v :value))
       (not (contains? v :ref))
       (or (contains? v :as)
           (contains? v :required)
           (contains? v :terminal?))))


(defn- value-binding?
  "Either a bare literal or a `{:value …}` map. We accept BOTH the
   shorthand and the explicit form here so the type-check is
   syntax-tolerant — composition.records will reject any genuinely
   malformed binding later."
  [v]
  (or (and (map? v) (contains? v :value))
      (and (not (keyword? v))
           (not (and (map? v) (or (contains? v :as)
                                  (contains? v :ref)
                                  (contains? v :required))))
           (not (metadata-only-binding? v)))))


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

   Returns `:any` (a permissive sentinel) ONLY when the registry has
   no entry for the name yet."
  [fn-name]
  (when-let [info (registry/rich-type-of fn-name)]
    (let [ret (or (:return info) :any)
          eff (:effects info)]
      (if (types/fn-type? ret)
        ;; Producer-of-callable: the inner fn-type already carries
        ;; whatever effects-constraint the slot declared. We can't
        ;; meaningfully inject the producer's own effects here.
        ret
        ;; Standard case: include effects as the 4th element when
        ;; the registry has an `:effects` key — even `#{}` (pure)
        ;; matters at the slot-effect-constraint subtype check, so
        ;; distinguish \"explicitly pure\" from \"unannotated\" via
        ;; key presence rather than seq-truthiness.
        (if (contains? info :effects)
          [:fn (or (:args info) {}) ret eff]
          [:fn (or (:args info) {}) ret])))))


(defn- has-type-var?
  "True iff `t` mentions any type variable. Polymorphic checks need
   `unify` (to find variable bindings); monomorphic ones just want
   `subtype?` to test actual ⊆ expected."
  [t]
  (cond
    (types/type-var? t)    true
    (types/fn-type? t)     (or (some has-type-var? (vals (types/fn-args t)))
                               (has-type-var? (types/fn-ret t)))
    (types/list-type? t)   (has-type-var? (types/list-elem t))
    (types/record-type? t) (some has-type-var? (vals t))
    :else                  false))


(defn- describe-binding
  "Human-readable summary of how the user wrote the binding —
   `:fn-ref → some-fn` or `(literal 42)` or `(sequence-chain N items)`.
   Drives the `hint` line of the error message."
  [b-form]
  (cond
    (and (map? b-form) (contains? b-form :value))
    (str "(literal " (pr-str (:value b-form)) ")")

    (and (map? b-form) (contains? b-form :ref))
    (str "ref → " (pr-str (:ref b-form)))

    (and (map? b-form) (contains? b-form :as))
    (str "rename via {:as " (pr-str (:as b-form)) "}")

    (keyword? b-form)
    (str "fn-ref → " (pr-str b-form))

    (vector? b-form)
    (str "(sequence-chain, " (count b-form) " item"
         (if (= 1 (count b-form)) "" "s") ")")

    :else
    (str "(literal " (pr-str b-form) ")")))


(defn- hint-for-actual
  "One-line explanation of WHY the actual type is what it is — the
   ref's return-type, the literal's classified type, etc. Helps the
   user trace back to the source of the mismatch instead of staring
   at two lines of structural types."
  [b-form actual]
  (cond
    (keyword? b-form)
    (str (pr-str b-form) "'s computed signature is " (pr-str actual))

    (and (map? b-form) (contains? b-form :ref))
    (str (pr-str (:ref b-form)) "'s computed signature is " (pr-str actual))

    (vector? b-form)
    (str "sequence's element type resolves to " (pr-str actual))

    :else
    (str "the literal value classifies as " (pr-str actual))))


(defn- format-message
  "Build the multi-line error message. Reads cleanly in REPL output,
   `bb deploy` logs, and HTTP error responses. When the ctx map
   carries `:source-file` / `:source-line`, the location is prepended
   so editor / IDE / log readers can jump straight to the EDN entry."
  [{:keys [fn-name parent-name arg-name expected actual
           source-file source-line]
    b-form :binding}]
  (str (when source-file
         (str "  at " source-file
              (when source-line (str ":" source-line))
              "\n"))
       "Type-check failed in fn-def " (pr-str fn-name) "\n"
       "  arg "        (pr-str arg-name) " ← " (describe-binding b-form) "\n"
       "  parent "     (pr-str parent-name) " expects: " (pr-str expected) "\n"
       "  actual:                "                (pr-str actual) "\n"
       "  hint: "      (hint-for-actual b-form actual)))


;; Source-location of the fn-def currently being checked. Set in a
;; binding around `check-fn-def!` so every nested `check-binding!`
;; throw can report `file:line` without threading the info through
;; every recursive call. Resolves to {:source-file path :source-line N}
;; or empty when we're called outside check-fn-def! (tests, etc.).
(def ^:dynamic *source-info* {})


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
  (or (and (map? b-form) (contains? b-form :value))
      (not (or (map? b-form) (keyword? b-form) (vector? b-form)))))


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
        satisfied (literal-satisfies-refinement?
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
    ;; `:any` is the doc's static escape hatch; `:null` is the
    ;; runtime escape hatch — nil is a legal value for every
    ;; Clojure type at runtime, and graphden has no nullable-vs-
    ;; non-null distinction yet. Both pass through silently.
    (or (= actual :any) (= actual :null))
    subst

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
    (types/subtype? actual expected)
    subst

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


(defn- merge-mi-parent-infos
  "Combine N parent registry entries into one parent-info for an MI
   fn-def. Returns nil when none of the parents have a registry entry
   (defer like the pre-MI behaviour did). With one parent the entry
   is returned verbatim."
  [parent-list]
  (let [infos (mapv registry/rich-type-of parent-list)]
    (cond
      (every? nil? infos) nil
      (= 1 (count infos)) (first infos)
      :else
      ;; Merge with `or` defaults so a missing parent (registered
      ;; later, or never) doesn't poison the union.
      {:return  (or (:return (first infos)) :any)
       :args    (apply merge (mapv #(:args % {}) infos))
       :effects (reduce into #{} (mapv #(:effects % #{}) infos))})))


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
  (and (map? b)
       (contains? b :as)
       (not (contains? b :value))
       (not (contains? b :ref))))


;; -----------------------------------------------------------------------------
;; Pre-Phase: structural check for required-narrowing widening.

(defn- check-required-widening!
  "Reject any binding map that carries `:required false`. The `:required`
   field on a binding is a one-way ratchet — descendants may narrow an
   inherited optional slot to required (`:required true`), but never
   widen a required slot back to optional. The slot's own `:required`
   on the base-fn declaration is the only way to declare optionality.
   Throws `:bindings/widening-required` on violation; pure pass otherwise."
  [{fn-name :name :as fn-def} parent-name]
  (doseq [[arg-name b-form] (:args fn-def)]
    (when (and (map? b-form)
               (contains? b-form :required)
               (false? (:required b-form)))
      (throw (ex-info
               (str "Type-check failed in fn-def " (pr-str fn-name)
                    "\n  arg " (pr-str arg-name) " ← :required false"
                    "\n  parent " (pr-str parent-name)
                    "\n  reason: bindings cannot widen `:required true` back to false."
                    "\n  Optionality lives on the slot itself; descendants may only"
                    "\n  narrow optional → required, never the reverse.")
               (merge {:fn-name fn-name
                       :parent-name parent-name
                       :parent parent-name
                       :arg-name arg-name
                       :arg arg-name
                       :binding b-form
                       :type :bindings/widening-required}
                      *source-info*))))))


;; -----------------------------------------------------------------------------
;; Phase 1: type-check each binding against the parent's expected slot.

(defn- sequence-item-actual-type
  "Classify ONE item from a literal-vector binding into its actual
   type. Mirrors classify-literal but also unwraps `{:value …}` /
   `{:ref :fn}` / bare keyword-refs / `{:as :name}`. Items whose
   shape leaves the type unknown surface as `:any`."
  [item]
  (cond
    (and (map? item) (contains? item :value))
    (or (classify-literal (:value item)) :any)

    (and (map? item) (contains? item :ref))
    (or (:return (registry/rich-type-of (:ref item))) :any)

    ;; Item-level rename — type unknown without seeing the caller.
    (and (map? item) (contains? item :as))
    :any

    (keyword? item)
    (or (:return (registry/rich-type-of item)) :any)

    :else
    (or (classify-literal item) :any)))


(defn- check-sequence-items
  "Walk every item in a literal-vector binding and unify against the
   slot's element type. `[1 \"two\" 3]` against `[:list :int]` fails
   on the second item. A slot with `:any` elem-type accepts anything."
  [primary-parent fn-name arg-name expected items subst]
  (let [elem-type (if (types/list-type? expected)
                    (types/list-elem expected)
                    :any)]
    (if (= :any elem-type)
      subst
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
   here. See check-one-binding for the full breakdown."
  [expected b-form]
  (or (and (sequence-slot? expected)
           (not (vector? b-form)))
      (and (map? b-form)
           (or (contains? b-form :as)
               (and (contains? b-form :ref)
                    (not (contains? b-form :value)))))
      ;; A metadata-only binding (e.g. `{:required true}`) carries no
      ;; value to check — defer to the post-pass that validates the
      ;; metadata itself (`check-required-widening!` for `:required`).
      (metadata-only-binding? b-form)
      (vector? b-form)))


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
      (and (types/fn-type? expected) (ref-binding? b-form))
      (if-let [actual (assemble-fn-type b-form)]
        (check-binding! primary-parent arg-name expected actual subst fn-name b-form)
        subst)

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
      (let [actual (or (:return (registry/rich-type-of b-form)) :any)]
        (check-binding! primary-parent arg-name expected actual subst fn-name b-form))

      (value-binding? b-form)
      (let [actual (or (classify-literal (literal-binding-value b-form)) :any)]
        (check-binding! primary-parent arg-name expected actual subst fn-name b-form))

      :else subst)))


(defn- type-check-bindings
  "Reduce over the fn-def's args; for each, type-check the binding
   against the parent's expected type. Returns the final substitution."
  [fn-def primary-parent parent-args]
  (reduce-kv (fn [subst arg-name b-form]
               (check-one-binding primary-parent (:name fn-def)
                                  parent-args subst arg-name b-form))
             {}
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


(defn- ref-free-args
  "Per-ref freshening: each fn-ref binding brings its own scope of
   type-vars into the fn-def's surface. Without freshening, two refs
   both exposing `'a` would collide. `types/freshen-args` renames
   each var to a unique `'a-<n>` while keeping sharing within one ref."
  [args]
  (let [fresh (fn [fn-name]
                (when-let [ents (:args (registry/rich-type-of fn-name))]
                  (types/freshen-args ents)))]
    (reduce-kv (fn [acc _ b-form]
                 (cond
                   (keyword? b-form)
                   (merge acc (or (fresh b-form) {}))

                   (and (map? b-form) (contains? b-form :ref))
                   (merge acc (or (fresh (:ref b-form)) {}))

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
        real-bound (into #{}
                         (keep (fn [[a b]]
                                 (when-not (rename-binding? b) a)))
                         args)
        renamed-original-names (into #{}
                                     (keep (fn [[k b]]
                                             (when (rename-binding? b) k)))
                                     args)
        renamed (into {}
                      (keep (fn [[a b]]
                              (when (rename-binding? b)
                                ;; A rename can locally override the slot's
                                ;; type via `{:as :name :type T}` — used by
                                ;; `:assoc-fn` (pinning `:value` to `:fn`)
                                ;; and by record-constructor templates that
                                ;; want each lifted field to carry the
                                ;; record's field-type. Without honouring
                                ;; `:type`, the free-arg surface would show
                                ;; the parent's looser slot type.
                                [(:as b)
                                 (or (some-> (:type b) types/resolve-alias)
                                     (types/resolve subst
                                                    (or (get parent-args a) :any)))])))
                      args)
        local-free (into renamed
                         (keep (fn [[a t]]
                                 (when-not (or (contains? real-bound a)
                                               (contains? renamed-original-names a))
                                   [a (types/resolve subst t)])))
                         parent-args)]
    (merge (ref-free-args args) local-free)))


;; -----------------------------------------------------------------------------
;; Phase 3: bindings-info for the type-rule.

(defn- vector-binding-elem-types
  "Walk a literal-vector binding and produce the elem types — keyword
   items lift the ref's :return, map items handle :value/:ref, plain
   items go through classify-literal."
  [items]
  (mapv (fn [item]
          (cond
            (keyword? item)
            (or (:return (registry/rich-type-of item)) :any)
            (and (map? item) (contains? item :value))
            (or (classify-literal (:value item)) :any)
            (and (map? item) (contains? item :ref))
            (or (:return (registry/rich-type-of (:ref item))) :any)
            :else
            (or (classify-literal item) :any)))
        items))


(defn- lub-elem-type
  "Coarse least-upper-bound of a vector of types: agree → that type,
   disagree → :any. Empty input also degrades to :any."
  [ts]
  (let [s (set ts)]
    (cond
      (empty? s)      :any
      (= 1 (count s)) (first s)
      :else           :any)))


(declare effective-ref-return root-base-fn-name)


(defn- effective-binding-type
  "The type of a bindings-info entry, resolved with caller-context
   `combined-bindings` overlaid. For ref-bindings, re-fires the
   ref's root-rule against the combined bindings — this is what
   makes `:_app-ring-response` see `:m`'s type as the structural
   ring-response-shape (because `:func` is now bound to `:_router`)
   rather than the `:any` `:router-result` recorded in isolation.

   `seen` and `depth` cap unbounded recursion through
   self/cyclic refs."
  [info combined-bindings seen depth]
  (or (when-let [ref-name (:ref info)]
        (effective-ref-return ref-name combined-bindings seen depth))
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
    (when-let [info (registry/rich-type-of ref-name)]
      (let [seen' (conj seen ref-name)
            ref-bindings (:resolved-bindings info {})
            ;; Caller's bindings shadow the ref's own — closer-fn-wins.
            combined (merge ref-bindings caller-bindings)
            inner-info (into {}
                             (map (fn [[k v]]
                                    [k {:type (effective-binding-type v combined seen' (inc depth))
                                        :value (:value v)}]))
                             combined)
            root-base (root-base-fn-name ref-name)
            static (or (:return info) :any)
            recomputed (rules/compute-return-type root-base inner-info static)]
        ;; Prefer the recomputed answer when it's strictly more
        ;; informative than the static one (`:any` is the
        ;; "uninformative" sentinel).
        (if (= recomputed :any) static recomputed)))))


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
                          [arg-name
                           (cond
                             ;; Bare rename `{:as :name}` — slot stays free with
                             ;; the renamed name. If `:type T` is also given, the
                             ;; rule should see T (matches the rename's type-
                             ;; override semantics in `collect-free-args`).
                             (rename-binding? b-form)
                             {:type (or (some-> (:type b-form) types/resolve-alias) :any)
                              :value nil}

                             (and (map? b-form) (contains? b-form :value))
                             {:type (or (classify-literal (:value b-form)) :any)
                              :value (:value b-form)}

                             (ref-binding? b-form)
                             {:type (or (:return (registry/rich-type-of b-form)) :any)
                              :value nil
                              :ref b-form}

                             (vector? b-form)
                             {:type [:list (lub-elem-type (vector-binding-elem-types b-form))]
                              :value b-form}

                             (keyword? b-form)
                             {:type :any :value b-form}

                             :else
                             {:type (or (classify-literal b-form) :any)
                              :value b-form})]))
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

(defn- compute-effects
  "Effects are tainted: parent ∪ every ref-binding's effects. Once
   any link in the composition reads/writes I/O the tag flows into
   the fn-def — caching / parallelism / docs all read this single
   source of truth."
  [args parent-info]
  (let [ref-effects
        (reduce-kv (fn [acc _ b-form]
                     (reduce (fn [a r]
                               (into a (or (:effects (registry/rich-type-of r)) #{})))
                             acc
                             (ref-targets b-form)))
                   #{}
                   args)]
    (into ref-effects (or (:effects parent-info) #{}))))


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

   `:primary-parent` is the immediate parent name — `root-base-fn-name`
   walks this to find the base-fn that `compute-return-type` should
   dispatch on. Without it, a chain `child :parent :assoc-empty :parent
   :assoc` would dispatch on `:assoc-empty` (no rule) and silently
   fall through to `:default` instead of using `:assoc`'s rule.

   `:resolved-bindings` (NEW) — accumulated `{slot → {:type … :value …}}`
   for slots BOUND at any level of this fn-def's chain. Type-rules
   on descendants can read a slot's type even when the binding lives
   deeper up. Closer-fn-wins (own bindings shadow parent's)."
  [fn-name fn-def primary-parent parent-info free-args
   computed-return effects own-resolved]
  (let [expected (some-> fn-def :expects-effects set)
        resolved (merge (:resolved-bindings parent-info {}) own-resolved)]
    (registry/record-rich-types-raw!
      fn-name
      (cond-> (merge {:return computed-return :args free-args}
                     (source-info-for fn-def))
        (seq resolved)  (assoc :resolved-bindings resolved)
        primary-parent (assoc :primary-parent primary-parent)
        (seq effects)  (assoc :effects effects)
        expected       (assoc :expects-effects expected)))))


;; -----------------------------------------------------------------------------
;; Top-level: compose the phases.

(defn- root-base-fn-name
  "Walk the registry's `:primary-parent` chain to find the base-fn at
   the root of an inheritance chain. Used to dispatch
   `compute-return-type` on the BASE fn's name even when the
   immediate parent is a composed fn-def — e.g. `:_jvm-section
   :parent :assoc-empty` should still benefit from the `:assoc`
   rule. `seen` guards an unexpected cycle (registration order
   bugs, manual rich-type tampering)."
  [fn-name]
  (loop [cur fn-name, seen #{}]
    (if (or (nil? cur) (contains? seen cur))
      cur
      (let [info (registry/rich-type-of cur)
            parent (:primary-parent info)]
        (if parent
          (recur parent (conj seen cur))
          cur)))))


(defn- compute-return-type
  "Run the ROOT base-fn's type-rule (e.g. `:assoc` record-builder) on
   `static-ret` to produce the rich, possibly-narrowed return shape.
   Walking to the root via `root-base-fn-name` lets rules fire even
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
  (rules/compute-return-type (root-base-fn-name primary-parent)
                             (bindings-info-for-rule (:args fn-def)
                                                     parent-args)
                             static-ret))


(defn- enforce-declared-return!
  "When a fn-def pins `:return-type T`, verify the computed return is a
   subtype of T. The declared T is then what the registry stores —
   downstream consumers see the declared contract, not the
   possibly-tighter computed shape. Returns the recorded return."
  [fn-name fn-def computed-return]
  (let [declared (some-> fn-def :return-type types/resolve-alias)]
    (when (and declared (not (types/subtype? computed-return declared)))
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
    (or declared computed-return)))


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
      (check-required-widening! fn-def primary-parent)
      (let [parent-args (:args parent-info)
            ;; Effective parent-args includes resolved bindings from
            ;; further up the chain — gives rules a transitive view of
            ;; slot types AND the ref-name (when bound to a fn-ref) so
            ;; ref re-firing can pick up call-site narrowing.
            effective-parent
            (merge (into {} (map (fn [[k v]] [k v]))
                         (:resolved-bindings parent-info {}))
                   (into {}
                         (map (fn [[k t]] [k {:type t :value nil}]))
                         parent-args))
            subst (type-check-bindings fn-def primary-parent parent-args)
            free-args (collect-free-args fn-def parent-args subst)
            static-ret (types/resolve subst (or (:return parent-info) :any))
            own-bindings (bindings-info-for-rule (:args fn-def))
            computed-return (compute-return-type fn-def primary-parent
                                                 effective-parent static-ret)
            recorded-return (enforce-declared-return! fn-name fn-def computed-return)
            effects (compute-effects (:args fn-def) parent-info)]
        (check-effects-policy! fn-name fn-def effects)
        (record-result! fn-name fn-def primary-parent parent-info
                        free-args recorded-return effects own-bindings)
        subst))))


(defn check-all-defs!
  "Run type-check on every fn-def. Stops at the first mismatch.
   Returns the per-fn substitution map (for diagnostic / future
   computed-type cache) on success."
  [fn-defs]
  (into {}
        (map (fn [fn-def]
               [(:name fn-def) (check-fn-def! fn-def)]))
        fn-defs))
