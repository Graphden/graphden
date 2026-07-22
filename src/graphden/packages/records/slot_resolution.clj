(ns graphden.packages.records.slot-resolution
  "Slot resolution through the inheritance + rename chain. Given a
   composed fn-def's `:args` entry, walks the parent / ref tree at
   parse time to find the ancestor that DECLARED the slot, and emits
   the rename-view slot rows for `{:as X}` renames."
  (:require
    [clojure.tools.logging :as log]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.types :as types]))


;; =============================================================================
;; Slot resolution through the rename chain
;; =============================================================================
;;
;; A composed fn-def's `:args` entry binds a slot exposed by the
;; inheritance chain under that arg-name. The slot itself, in the
;; new model, is owned by some ancestor that DECLARED it — either
;; an explicit `:type {…}` / `:refine {…}` / `:list T` declaration,
;; or a base-fn with an `:args` map (the args become slots), or an
;; ancestor with `{:as :exposed-name}` rename that re-surfaces a
;; deeper slot under a new name.
;;
;; For each composed-def we resolve `arg-name → [owner-fn-id slot-name]`
;; by walking up the inheritance chain looking for either:
;;
;;   1. A type-row owner (record-type, base-fn) that has a slot named
;;      arg-name in its own slot list — we found it.
;;
;;   2. A composed-def ancestor whose `:args` contains a binding with
;;      `:as arg-name` — switch to the ancestor's binding's own
;;      arg-name and recurse from the ancestor's parent.
;;
;; The walk uses the input fn-defs (not storage), so the algorithm is
;; pure and runs at parse time.

(defn- own-slot-arg-names
  "Arg-names declared as PB' own-slots on a composed fn-def —
   entries whose shape is `{:type T :description D? :required R?}`
   with no binding markers (`:value` / `:ref` / `:as` / `:append` /
   `:closed`). These mirror base-fn arg declarations: they add new
   free pins this fn-def exposes on top of inheritance."
  [fn-def]
  (into #{}
        (keep (fn [[arg-name v]]
                (when (and (map? v)
                           (contains? v :type)
                           (not-any? #(contains? v %)
                                     [:value :ref :as :append :closed]))
                  arg-name)))
        (:args fn-def)))


(defn type-row-arg-names
  "Set of arg-names a fn-def directly declares slots for. Covers:
   - type-row primitives (`:type` / `:refine` / `:list`)
   - base-fns (`:args` without `:parent` / `:parents`)
   - composed fn-defs with PB' own-slot decls (`{:type T}` entries
     inside `:args` alongside ordinary bindings)"
  [fn-def]
  (cond
    (:type fn-def)   (set (keys (:type fn-def)))
    (:refine fn-def) #{:value}
    (:list fn-def)   #{:items}
    (and (:args fn-def) (not (:parent fn-def)) (not (:parents fn-def)))
    (set (keys (:args fn-def)))
    (:args fn-def)   (own-slot-arg-names fn-def)
    :else            #{}))


(defn arg-spec-type
  "Pull the type keyword out of a base-fn's `:args` value — recognises
   bare keywords, `{:type T :required B}` spec maps, and falls back
   to `:any` when the shape is unfamiliar."
  [arg-spec]
  (cond
    (keyword? arg-spec) arg-spec
    (and (map? arg-spec) (:type arg-spec)) (:type arg-spec)
    :else :any))


(defn- rename-spec-type
  "If `(:args fd)` contains an `{:as owner-arg :type T …}` rename
   declaration (scalar `{X {:as owner-arg :type T}}` OR positional
   list-item `:items [{:as owner-arg :type T} …]`), return T. Used
   by `slot-type-of` to find the type of a RENAME slot — the slot
   exists on `fd` under the rename's `:as` name, but `(:args fd)`'s
   key is the source arg-name, not the rename target."
  [fd owner-arg]
  (some
    (fn [[_ binding-value]]
      (cond
        ;; Scalar rename: `{X {:as owner-arg :type T}}`.
        (and (map? binding-value)
             (= owner-arg (some-> (:as binding-value) keyword))
             (:type binding-value))
        (:type binding-value)

        ;; Positional list-item rename: `:items [{:as owner-arg :type T}]`.
        (vector? binding-value)
        (some (fn [item]
                (when (and (map? item)
                           (= owner-arg (some-> (:as item) keyword))
                           (:type item))
                  (:type item)))
              binding-value)))
    (:args fd)))


(defn slot-type-of
  "Find the declared type of `[owner-name owner-arg]`. Inspects the
   owner's type-row / base-fn / record / refinement / list shape.
   Returns a type keyword like `:sequence` / `:int` / `:any` / nil.

   Also recognises rename slots — when `owner-arg` is the target of
   an `{:as owner-arg :type T}` rename on `fd` (scalar OR positional
   list-item), returns T. Without this, rename slots like
   `:ring-response :body` (from `{:value {:as :body :type :text}}`)
   would resolve to nil because `(:args fd)` is keyed by the source
   arg-name (`:value`), not the rename target."
  [owner-name owner-arg defs-by-name]
  (let [fd (get defs-by-name owner-name)]
    (cond
      (nil? fd) nil
      (:type fd) (arg-spec-type (get (:type fd) owner-arg))
      (:list fd) :sequence
      (:refine fd) (:base (:refine fd))
      ;; Base-fn or composed with own :args declarations.
      (and (:args fd) (contains? (:args fd) owner-arg))
      (arg-spec-type (get (:args fd) owner-arg))
      ;; Rename slot — the slot name doesn't appear as an :args key,
      ;; but a binding's `:as` does, with the type pinned alongside.
      :else (rename-spec-type fd owner-arg))))


(defn rename-target
  "If `fn-def` has a binding `arg-name → {:as exposed-name}` where the
   rename actually CHANGES the name (not a no-op like
   `{:value {:as :value :type :fn}}`), return the original `arg-name`
   so the resolver can switch to looking up `arg-name` from the
   ancestor's parents.

   Also recognises POSITIONAL renames inside list bindings —
   `:items [{:as :path} :method-map]` exposes `:path` as a free arg
   of the binding's owner, so we treat it like an ordinary rename
   (with the owner being this fn's binding).

   Returns the original arg-name (the binding's own slot name) for a
   match, nil otherwise."
  [fn-def exposed-name]
  (when-let [args (:args fn-def)]
    (some (fn [[ancestor-arg-name binding-value]]
            (when (or (and (map? binding-value)
                           (= exposed-name (some-> (:as binding-value) keyword))
                           (not= ancestor-arg-name exposed-name))
                      ;; Positional rename inside a sequence binding.
                      (and (vector? binding-value)
                           (some (fn [item]
                                   (and (map? item)
                                        (= exposed-name (some-> (:as item) keyword))))
                                 binding-value)))
              ancestor-arg-name))
          args)))


(defn chain-of
  "Inheritance chain (BFS) of names for `fn-name` traced through the
   `defs-by-name` index. The chain stops at any name not present in
   the index (external base-fn already resolved via `name->id`)."
  [fn-name defs-by-name]
  (loop [acc [], seen #{}, queue [fn-name]]
    (if (empty? queue)
      acc
      (let [n (first queue) rest-q (subvec queue 1)]
        (if (contains? seen n)
          (recur acc seen rest-q)
          (let [fd (get defs-by-name n)
                next-parents (when fd
                               (concat (when-let [p (:parent fd)] [p])
                                       (:parents fd)))]
            (recur (conj acc n)
                   (conj seen n)
                   (into rest-q next-parents))))))))


(defn ref-targets-of
  "Yield the fn-name keywords that `fn-def` references through ref
   bindings (`:ref X`, bare keyword, OR list-item refs inside a
   `:items [...]` / `:entries [...]` style sequence binding).

   Used to follow the data-flow tree alongside the inheritance tree
   when resolving slot ownership — sequence bindings expose their
   items' free-arg surfaces outward, so a deep rename inside one of
   the items needs to be reachable from the outer binder."
  [fn-def defs-by-name]
  (vec
    (mapcat (fn [[_ v]]
              (cond
                (and (keyword? v) (contains? defs-by-name v)) [v]
                (and (map? v) (contains? defs-by-name (:ref v))) [(:ref v)]
                ;; Sequence binding: walk items.
                (vector? v)
                (keep (fn [item]
                        (cond
                          (and (keyword? item) (contains? defs-by-name item)) item
                          (and (map? item) (contains? defs-by-name (:ref item))) (:ref item)
                          :else nil))
                      v)
                ;; `{:append [items]}` shape too.
                (and (map? v) (vector? (:append v)))
                (keep (fn [item]
                        (cond
                          (and (keyword? item) (contains? defs-by-name item)) item
                          (and (map? item) (contains? defs-by-name (:ref item))) (:ref item)
                          :else nil))
                      (:append v))
                :else nil))
            (:args fn-def))))


(defn rename-passthrough-ref
  "If `fn-def` has a binding `{X {:as arg-name :ref RefFn}}`, the
   rename is a PASSTHROUGH — it just re-exposes a slot defined deeper
   in `RefFn`'s tree. Returns the ref's name so the resolver can
   recurse on `arg-name` from there. Returns nil when no such
   passthrough binding exists."
  [fn-def arg-name]
  (when-let [args (:args fn-def)]
    (some (fn [[_ binding-value]]
            (when (and (map? binding-value)
                       (= arg-name (some-> (:as binding-value) keyword))
                       (:ref binding-value))
              (:ref binding-value)))
          args)))


(defn resolve-slot-owner-strict
  "Same as `resolve-slot-owner` but returns nil when no concrete
   inheritance/ref hit is found. Used by recursive calls that must
   NOT fall back to a primary-parent guess; the OUTER call applies
   that fallback only once."
  [composed-fn-name arg-name defs-by-name seen]
  (when (and (not (contains? seen [composed-fn-name arg-name]))
             (get defs-by-name composed-fn-name))
    (let [seen' (conj seen [composed-fn-name arg-name])
          chain (chain-of composed-fn-name defs-by-name)
          inheritance-hit
          (some (fn [name-in-chain]
                  (let [ancestor (get defs-by-name name-in-chain)]
                    (cond
                      (contains? (type-row-arg-names ancestor) arg-name)
                      [name-in-chain arg-name]

                      ;; Passthrough rename `{:as X :ref Y}`: arg-name
                      ;; is just being re-exposed from Y's tree. Recurse
                      ;; into Y so the actual deepest owner wins.
                      (rename-passthrough-ref ancestor arg-name)
                      (resolve-slot-owner-strict
                        (rename-passthrough-ref ancestor arg-name)
                        arg-name defs-by-name seen')

                      ;; Pure `{:as X}` rename (no ref) — this ancestor
                      ;; owns the rename slot.
                      (rename-target ancestor arg-name)
                      [name-in-chain arg-name]

                      :else nil)))
                chain)]
      (or inheritance-hit
          ;; Walk ref-targets from FURTHEST ancestor (parent chain)
          ;; INWARD. The slot is more likely to be defined on a base-fn
          ;; that an ancestor refs into than on a deep ref-tree of the
          ;; composed-def itself. E.g. `:_app-ring-response :args
          ;; {:func :_router}` — :func is defined by `:invoke` which
          ;; :router-ring-response (an ANCESTOR) refs through
          ;; :router-result. Walking own ref-targets first would dive
          ;; into the routes tree and hit some unrelated `:map`/`:reduce`
          ;; fn-row's :func slot.
          (some (fn [name-in-chain]
                  (let [ancestor (get defs-by-name name-in-chain)]
                    (some (fn [ref-name]
                            (resolve-slot-owner-strict ref-name arg-name
                                                       defs-by-name seen'))
                          (ref-targets-of ancestor defs-by-name))))
                (reverse chain))))))


(defn- resolve-slot-owner-strict-inheritance
  "Inheritance pass only — same as `resolve-slot-owner-strict`'s
   first pass but without falling through to the ref-targets pass.
   Used by `resolve-slot-owner` to collect inheritance + ref hits
   independently for type-based disambiguation."
  [composed-fn-name arg-name defs-by-name seen]
  (when (and (not (contains? seen [composed-fn-name arg-name]))
             (get defs-by-name composed-fn-name))
    (let [seen' (conj seen [composed-fn-name arg-name])
          chain (chain-of composed-fn-name defs-by-name)]
      (some (fn [name-in-chain]
              (let [ancestor (get defs-by-name name-in-chain)]
                (cond
                  (contains? (type-row-arg-names ancestor) arg-name)
                  [name-in-chain arg-name]

                  (rename-passthrough-ref ancestor arg-name)
                  (resolve-slot-owner-strict
                    (rename-passthrough-ref ancestor arg-name)
                    arg-name defs-by-name seen')

                  (rename-target ancestor arg-name)
                  [name-in-chain arg-name]

                  :else nil)))
            chain))))


(defn- resolve-slot-owner-strict-refs
  "Ref-targets pass only — finds slot owners reachable via the
   data-flow tree (`:ref` bindings, `:items [:fn-ref]`). Used by
   `resolve-slot-owner` to collect candidates separately from
   inheritance for type-based disambiguation."
  [composed-fn-name arg-name defs-by-name seen]
  (when (and (not (contains? seen [composed-fn-name arg-name]))
             (get defs-by-name composed-fn-name))
    (let [seen' (conj seen [composed-fn-name arg-name])
          chain (chain-of composed-fn-name defs-by-name)]
      (some (fn [name-in-chain]
              (let [ancestor (get defs-by-name name-in-chain)]
                (some (fn [ref-name]
                        (resolve-slot-owner-strict ref-name arg-name
                                                   defs-by-name seen'))
                      (ref-targets-of ancestor defs-by-name))))
            (reverse chain)))))


(defn- value-return-type
  "Extract the declared `:return-type` of a binding value, or nil
   when the value is a literal / unresolvable.

   `:body :_contact-demo-page-body` → look up `:_contact-demo-page-body`
   in defs-by-name → return its `:return-type` (or infer from
   `:parent`'s return-type for un-annotated composed fn-defs).

   Returns the type keyword (`:hiccup-node` / `:text` / `:int` / …)
   or nil when the value isn't a ref to a known fn-def."
  [binding-value defs-by-name]
  (let [ref-name (cond
                   (keyword? binding-value) binding-value
                   (and (map? binding-value) (keyword? (:ref binding-value)))
                   (:ref binding-value))]
    (when (and ref-name (get defs-by-name ref-name))
      ;; Walk the parent chain so `{:parent :hiccup}` (which declares
      ;; `:return-type :hiccup-node`) propagates to descendants that
      ;; don't re-state it.
      (some (fn [name-in-chain]
              (when-let [fd (get defs-by-name name-in-chain)]
                (:return-type fd)))
            (chain-of ref-name defs-by-name)))))


(defn resolve-slot-owner
  "Find `[owner-name slot-name]` for the slot that `composed-fn-name`
   targets when binding `arg-name`.

   Two-pass walk:
   - Pass 1: inheritance chain (parent-ids). Direct slot-name match
     on a type-row / base-fn ancestor wins, OR a `{X {:as arg-name}}`
     rename surfaces a rename slot owned by the ancestor.
   - Pass 2: data-flow tree (ref-fn-id). Refs propagate the
     ref-target's renamed free args outward, so the slot may live
     deep in the ref tree.

   When BOTH passes find different owners (ambiguity), `binding-value`
   (if supplied + a ref to a known fn-def) drives type-based
   disambiguation: each candidate slot's declared type is compared
   against the binding value's `:return-type`. If exactly one slot's
   type matches, that owner wins. Otherwise the inheritance-pass hit
   wins (existing behavior). This is what lets `:_contact-demo-page-
   handler :body :_contact-demo-page-body` resolve to the INNER
   page-body slot (type `:hiccup-node`) instead of the OUTER Ring-
   body slot (type `:text`) — semantically distinct slots that happen
   to share an ext-name.

   Throws `:packages/orphan-slot-binding` when both passes are
   exhausted AND the primary parent is in `defs-by-name` but doesn't
   declare `arg-name` — that combination produces a binding row
   targeting a non-existent slot-id (silent no-op at runtime).

   Falls back to `[primary-parent arg-name]` only when the primary
   parent is OUTSIDE `defs-by-name` — covers legacy bindings on slots
   whose owner is an external base-fn not registered through
   `extra-defs`."
  ([composed-fn-name arg-name defs-by-name]
   (resolve-slot-owner composed-fn-name arg-name defs-by-name nil))
  ([composed-fn-name arg-name defs-by-name binding-value]
   (let [fd (get defs-by-name composed-fn-name)
         primary-parent (or (when fd (or (:parent fd) (first (:parents fd))))
                            composed-fn-name)
         inh-hit (resolve-slot-owner-strict-inheritance
                   composed-fn-name arg-name defs-by-name #{})
         ref-hit (resolve-slot-owner-strict-refs
                   composed-fn-name arg-name defs-by-name #{})
         strict-hit
         (cond
           ;; Type-based disambiguation: both passes hit and we have a
           ;; value-return-type to compare against the two slots'
           ;; declared types.
           ;; Both passes hit DIFFERENT owners for this ext-name. In the
           ;; common case these are the SAME logical shared arg exposed by
           ;; sibling composed refs (e.g. `:coll` on both `:count` and
           ;; `:get` in `:_er-list-total-count` — the value propagates to
           ;; both via the shared ext-name), often with sibling base-fns
           ;; declaring subtype-related types. Inheritance-wins is a sound
           ;; DEFAULT here, backstopped two ways so a genuinely-wrong pick
           ;; can't pass silently:
           ;;   - when a typed binding VALUE is present, it disambiguates
           ;;     (each candidate slot's type vs the value's return-type);
           ;;   - when the arg is free (no value), the sweep type-checker
           ;;     is the backstop — a free arg cannot satisfy two
           ;;     genuinely-incompatible slot types, so a real mis-pairing
           ;;     surfaces as a type-check failure, not a silent mis-bind.
           ;; (A parse-time hard-fail on "different types" was tried and
           ;; reverted: it false-positives on legitimate shared args whose
           ;; sibling slots are merely subtype-related, not distinct.)
           (and inh-hit ref-hit (not= inh-hit ref-hit))
           (if-let [vt (value-return-type binding-value defs-by-name)]
             (let [inh-slot-type (apply slot-type-of (conj inh-hit defs-by-name))
                   ref-slot-type (apply slot-type-of (conj ref-hit defs-by-name))
                   inh-match? (and inh-slot-type (= vt inh-slot-type))
                   ref-match? (and ref-slot-type (= vt ref-slot-type))]
               (cond
                 (and inh-match? (not ref-match?)) inh-hit
                 (and ref-match? (not inh-match?)) ref-hit
                 :else inh-hit))
             inh-hit)

           inh-hit inh-hit
           ref-hit ref-hit
           :else nil)]
     (or strict-hit
         (let [parent-def (get defs-by-name primary-parent)]
           (when (and parent-def
                      (not (contains? (type-row-arg-names parent-def) arg-name)))
             (throw (ex-info
                      (str "Binding `" arg-name "` on fn-def `"
                           composed-fn-name "` targets a non-existent slot — "
                           "the resolved owner `" primary-parent "` doesn't "
                           "declare `" arg-name "`. Check for a typo against "
                           "the parent's `:args` keys (canonical example: "
                           "`:n` on `:take` should be `:count`).")
                      {:type :packages/orphan-slot-binding
                       :fn-name composed-fn-name
                       :arg-name arg-name
                       :primary-parent primary-parent
                       :parent-args (some-> parent-def :args keys vec)})))
           [primary-parent arg-name])))))


(defn build-defs-by-name
  "Map of {fn-name → fn-def} from the input vector for parse-time
   slot resolution. DUAL-keyed (bare + `:ns.path/name` qualified) —
   ambiguous PARENT refs arrive already qualified from
   `normalize-qualified-refs`, so their shape lookups hit the precise
   key; a bare key under per-ns duplicates is last-write and is only
   consulted for unique names."
  [module-fn-defs]
  (reduce
    (fn [m fd]
      (if-not (:name fd)
        m
        (cond-> (assoc m (:name fd) fd)
          (:namespace fd)
          (assoc (keyword (:namespace fd) (name (:name fd))) fd))))
    {}
    module-fn-defs))


(defn ancestor-type-pin
  "If any ancestor binding on `arg-name` carried `:type T`, return T —
   covers MI patterns like `:assoc-handler :parents [:assoc-fn …]
   :args {:value {:as :handler}}` where `:assoc-fn`'s `:value
   {:as :value :type :fn}` no-op rename pins the type."
  [fn-name arg-name defs-by-name]
  (some (fn [ancestor-name]
          (when-let [ad (get defs-by-name ancestor-name)]
            (when-let [v (get (:args ad) arg-name)]
              (when (map? v) (:type v)))))
        (chain-of fn-name defs-by-name)))


(defn collect-exposed-names
  "Names exposed by `{:as X}` renames in this fn-def's args. Returns a
   set of `[exposed-name type-spec-or-nil source-arg-name-or-nil]`
   triples. Both scalar `{X {:as Y}}` AND positional list-item
   `{:as Y}` markers count.

   `source-arg-name` is the original arg-name being renamed FROM —
   used by `build-rename-slot-records` to resolve the source slot's
   id and emit it as `:source-slot-id` on the new slot record. nil
   for positional list-item renames (their source is a position
   inside a list-typed slot, not a named arg)."
  [args fn-name defs-by-name]
  (reduce
    (fn [acc [arg-name arg-value]]
      (cond
        (and (map? arg-value) (:as arg-value)
             (not= arg-name (some-> (:as arg-value) keyword)))
        (conj acc [(some-> (:as arg-value) keyword)
                   (or (:type arg-value)
                       (ancestor-type-pin fn-name arg-name defs-by-name))
                   arg-name])

        (vector? arg-value)
        (into acc
              (keep (fn [item]
                      (when (and (map? item) (:as item))
                        [(some-> (:as item) keyword) (:type item) nil])))
              arg-value)

        :else acc))
    #{}
    args))


(defn- resolve-source-slot-id
  "For a scalar rename `{source-arg {:as exposed}}` on `composed-fn-name`,
   find the slot id that the rename is shadowing. Walks the inheritance
   chain (and `:as` renames upstream) via `resolve-slot-owner`, then
   composes the deterministic `slot-id(owner-fn-id, owner-arg)`. Returns
   nil for positional renames (source-arg-name=nil) — the source there
   is a list position, which has no slot id of its own."
  [composed-fn-name source-arg-name defs-by-name name->id]
  (when source-arg-name
    (let [[owner-name owner-arg] (resolve-slot-owner
                                   composed-fn-name source-arg-name defs-by-name)
          owner-def (get defs-by-name owner-name)
          owner-fn-id (or (get name->id owner-name)
                          (when owner-def
                            (ids/fn-id (:namespace owner-def) owner-name)))]
      (when owner-fn-id
        (ids/slot-id owner-fn-id owner-arg)))))


(defn build-rename-slot-records
  "For each `[exposed type-spec source-arg]` triple, emit the slot +
   fn-slot rows exposing the rename under `own-id`. A `{:as X}` rename
   creates a NEW logical slot owned by the renaming fn so descendants
   binding X target this slot rather than the underlying base slot.

   `:source-slot-id` semantics:

   - **Scalar `{Y {:as :X}}` renames** populate it with the source
     slot's id (slot Y of the inheritance closure). The FK link
     replaces the legacy `binding.rename-to` text and powers
     `compile/lookups :rename-for-slot` + frontend
     `getEffectiveSlotName`.

   - **Positional `:items [{:as :X}]` renames** leave it nil — and
     this is INTENTIONAL, not a follow-up gap. A positional
     own-slot is a NEW slot identity tied to a list position
     inside the parent's list-typed slot; it is not a rename of
     any single source slot. There is no FK to set without lying
     about the relationship: the parent's `:items` slot is the
     LIST, not the slot for position 0. Binding resolution works
     fine via `slot-by-fn-name` (the position's own slot name is
     stored directly on the slot row); descendants binding `:X`
     find the slot identity through that index, not through the
     source chain."
  ([composed-fn-name exposed-names own-id name->id defs-by-name]
   (build-rename-slot-records composed-fn-name exposed-names own-id
                              name->id defs-by-name 0))
  ([composed-fn-name exposed-names own-id name->id defs-by-name pos-offset]
   ;; `pos-offset` lets the caller park rename `fn-slot` rows AFTER
   ;; any PB' own-slot rows emitted ahead of them — without it, every
   ;; rename hardcoded `:position 0` and any fn with multiple renames
   ;; (or a mix of PB' + renames) ended up with colliding positions,
   ;; making `fn-slots-by-fn` order non-deterministic.
   (vec
     (map-indexed
       (fn [idx [exposed type-spec source-arg]]
         (let [slot-name (clojure.core/name exposed)
               sid (ids/slot-id own-id slot-name)
               fsid (ids/fn-slot-id own-id sid)
               type-fn-id (or (when type-spec
                                (try (types/resolve-type-ref type-spec name->id)
                                     (catch Exception e
                                       ;; A typo in `:type` on a renamed slot
                                       ;; would otherwise silently downgrade
                                       ;; the slot to `:any` — the most
                                       ;; permissive type — and the user gets
                                       ;; no signal that their constraint was
                                       ;; lost.
                                       (log/warn e
                                                 "Renamed-slot :type silently downgraded to :any"
                                                 {:fn-name composed-fn-name
                                                  :slot-name (clojure.core/name exposed)
                                                  :type-spec type-spec})
                                       nil)))
                              (ids/primitive-fn-id :any))
               source-sid (resolve-source-slot-id composed-fn-name source-arg
                                                  defs-by-name name->id)]
           [{:kind :slot
             :id sid
             :name slot-name
             :type-fn-id type-fn-id
             :required false
             :description nil
             :source-slot-id source-sid}
            {:kind :fn-slot
             :id fsid
             :fn-id own-id
             :slot-id sid
             :position (+ pos-offset idx)}]))
       exposed-names))))
