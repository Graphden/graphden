(ns graphden.executor.compile.bindings
  "Static binding analysis: classify each root slot of a fn F as
   `:value | :ref | :seq | :free`.

   In the slot/fn-slot/binding model:
   - F's root has fn-slot rows that define the impl's parameter list.
   - F itself plus every ancestor in F.parent-ids (BFS) may carry
     binding rows for those slots.
   - Closest-first wins (override semantics): a binding on F shadows the
     same slot bound by an ancestor.
   - A renamed-view slot (own slot row whose `:source-slot-id` FK
     points at an inherited slot) exposes the source slot under a
     new external name without consuming it; the slot stays free
     under the new name."
  (:require
    [graphden.executor.compile.lookups :as l]
    [graphden.types.core :as types]))


;; The registry is part of the executor but its loading transitively
;; pulls compile/bindings; avoid the cycle by resolving lazily.
(def ^:private rich-type-of-id-fn (delay (requiring-resolve 'graphden.executor.registry.core/rich-type-of-id)))


;; Binding-row shape predicates. Single source of truth for "what kind
;; of binding does this DB row carry" — siblings (`renames.clj` etc.)
;; and out-of-module callers (`layout/builder_helpers`,
;; `versioning/storage/resolution`) duplicated the underlying
;; `(some? (:value b))`/`(some? (:ref-fn-id b))` checks before these
;; were lifted into shared predicates. Mirrors the AST-level
;; `binding-shape` taxonomy in `types/check`, but classifies STORED
;; rows (parsed + persisted) rather than the user's AST.
;;
;;   value → row sets `:value` (no `:ref-fn-id`)
;;   ref   → row sets `:ref-fn-id`
;;   list  → row sets `:list-append true` (chain-append semantics)
(defn value-binding?
  "True when `b` carries an explicit literal value — including a
   literal nil. The `:value-present` flag is the authoritative
   marker: the storage column collapses literal-nil to SQL NULL, so
   `(some? (:value b))` is FALSE for both `{:value nil}` (author
   wrote `:default nil`) and `(absent)` (author wrote nothing).
   Without the flag check, literal-nil bindings fall through to
   `:free` and the slot's value is pulled from the caller's fa at
   runtime — for `:_shape-secret-path :args :default nil`, that
   surfaced the Ring request as the `:default` value, which `:get`
   returned when `:coll` was nil (orphan secret-leaf fn-rows with
   no `:secret-path` binding), producing
   `Cannot JSON encode object of class: make_shape_callable` from
   `to-json-string` on `/api/secrets`."
  [b]
  (and b (:value-present b) (nil? (:ref-fn-id b))))


(defn ref-binding?
  [b]
  (and b (some? (:ref-fn-id b))))


(defn list-binding?
  [b]
  (and b (true? (:list-append b))))


(defn- list-items-for
  "Walk parent chain to collect effective list items for `slot-id`. With
   `:list-append true` on a binding, items come AFTER the parent's
   effective items. Without append, the binding REPLACES (no parent
   items inherited). Stops at the first non-append binding (the parent
   list is its replacement)."
  [fn-id slot-id {:keys [binding-by-fn-slot items-by-binding] :as lookups}]
  (let [chain (l/inheritance-chain* fn-id lookups)
        ;; Walk from root (farthest) to F (closest), accumulating
        ;; items. Reverse the chain so root is first.
        farthest-first (reverse chain)
        result (reduce (fn [acc fid]
                         (if-let [b (get binding-by-fn-slot [fid slot-id])]
                           (let [own (vec (get items-by-binding (:id b) []))]
                             (if (true? (:list-append b))
                               (into acc own)
                               own))                ; replace
                           acc))
                       []
                       farthest-first)]
    result))


(defn- fn-typed-slot?
  "True iff the slot's effective type resolves to the `:fn` primitive.
   Checks the binding's `:type-override-fn-id` first, then walks the
   inheritance chain looking for any binding on the same slot with a
   type-override that pins :fn (this is how `:assoc-fn`'s no-op rename
   `{:value {:as :value :type :fn}}` propagates HOF-ness to
   descendants). Falls back to the slot's own `:type-fn-id`."
  [slot b-row fn-typed-fn-ids fn-id
   {:keys [binding-by-fn-slot] :as lookups}]
  (let [override (or (:type-override-fn-id b-row)
                     (some (fn [fid]
                             (when-let [b (get binding-by-fn-slot
                                               [fid (:id slot)])]
                               (:type-override-fn-id b)))
                           (l/inheritance-chain* fn-id lookups)))
        t-id (or override (:type-fn-id slot))]
    (boolean (and t-id (contains? fn-typed-fn-ids t-id)))))


(defn- find-rename-slot
  "Find the rename slot owned by `owner-fn-id` that is a renamed view
   of `source-slot-id`. Returns the slot row, or nil.

   Phase 6c: lookup is now O(1) via `:slot-by-fn-source-slot` —
   the renamed slot's `:source-slot-id` FK points back at the slot
   it renames. Pre-Phase-6 callers passed a string `rename-name`;
   the new contract takes the FK directly so the helper can answer
   without scanning fn-slots."
  [owner-fn-id source-slot-id {:keys [slot-by-fn-source-slot]}]
  (get slot-by-fn-source-slot [owner-fn-id source-slot-id]))


(defn- effective-required?
  "Effective `:required` for `slot-id` at `fn-id`. Slot's own
   `:required` (default true) is the BASELINE; any binding along the
   inheritance chain with `:required true` clamps it to required.

   Narrowing is monotonic — once any ancestor declared required, no
   descendant can widen back. We don't need a chain-direction walk
   here: `(true? own-required)` for ANY binding on the chain ⇒ true.
   The sync-time guard rejects `:required false` writes, so we never
   see a downgrade."
  [slot fn-id {:keys [binding-by-fn-slot] :as lookups}]
  (let [slot-default (if (false? (:required slot)) false true)
        chain (l/inheritance-chain* fn-id lookups)
        binding-says-required?
        (some (fn [fid]
                (true? (:required (get binding-by-fn-slot [fid (:id slot)]))))
              chain)]
    (or slot-default (boolean binding-says-required?))))


(defn- effective-binding
  "When the closest binding for `slot-id` belongs to a fn that ALSO
   owns a renamed-view slot for the same source (i.e. a rename),
   look for a descendant's value / ref binding on the rename slot —
   that binding wins. The rename's own value/ref serves as a
   DEFAULT; descendants who bind the renamed name override it.

   Phase 6c: the rename relationship is now read via
   `slot.source-slot-id` (FK) rather than `binding.rename-to`
   (text). Both sources currently agree (parser + Phase 6b ensure-
   rename-slot! emit them in lock-step); switching to the FK lets
   the helper drop a name→slot lookup."
  [fn-id slot-id {:keys [binding-by-fn-slot] :as lookups}]
  (let [primary (l/closest-binding-for-slot fn-id slot-id lookups)
        rename-slot (when primary
                      (find-rename-slot (:fn-id primary) slot-id lookups))]
    (if-not rename-slot
      primary
      (let [override (some (fn [fid]
                             (get binding-by-fn-slot [fid (:id rename-slot)]))
                           (l/inheritance-chain* fn-id lookups))]
        (if (and override
                 (or (some? (:value override))
                     (some? (:ref-fn-id override))
                     (true? (:list-append override))))
          override
          primary)))))


(defn- ref-produces-callable?
  "True iff the bound ref-fn's `:return-type` is itself a fn-type —
   i.e. evaluating the fn-graph produces a callable VALUE rather than
   the fn-graph BEING the callable. When the slot is fn-typed AND the
   ref produces a callable, the runtime thunks (evaluate to get the
   callable) instead of `hof-wrap`'ping (which would double-wrap).

   Decision is keyed on the registered return-type via the rich-types
   registry — purely type-driven, never on fn name. Any fn-def whose
   computed `:return` is a `[:fn …]` type takes this branch."
  [ref-fn-id _lookups]
  (when-let [info (@rich-type-of-id-fn ref-fn-id)]
    (types/fn-type? (:return info))))


(defn- lazy-seq-arg-names
  "Set of slot base-names the root base-fn of `fn-id` declared in its
   `:lazy-seq-args` — those `:seq` slots resolve to delay-wrapped
   ITEMS so a consumer like `cond-fn` can step past an un-taken
   element. Declared once at the base-fn's `impls.clj` registration
   site and read here by base-fn identity — never by name-dispatch."
  [fn-id {:keys [fn-map] :as lookups}]
  (some-> (l/root-fn fn-id fn-map lookups) :id
          (@rich-type-of-id-fn) :lazy-seq-args set))


(defn- classify-slot
  "Classify one root slot. Returns one of:
     {:kind :value :base-name K :ext-name K :slot-id UUID :value V}
     {:kind :ref   :base-name K :ext-name K :slot-id UUID :ref-id UUID
                   :is-fn BOOL :produces-callable? BOOL}
     {:kind :seq   :base-name K :ext-name K :slot-id UUID :items […]
                   :lazy-seq? BOOL}
     {:kind :free  :base-name K :ext-name K :slot-id UUID :required true}"
  [slot fn-id lookups fn-typed-fn-ids lazy-seq-args]
  (let [base-name (keyword (:name slot))
        slot-id (:id slot)
        ext-name (l/rename-for-slot fn-id slot-id lookups)
        b (effective-binding fn-id slot-id lookups)]
    (cond
      ;; Generic value-resolver — the stored :value is the INPUT to the
      ;; resolver graph fn at arg-resolution time ("stored → runtime").
      ;; Secret bindings ARE this (resolver = :vault-get) since the
      ;; :override-kind retirement.
      (and (value-binding? b) (:resolver-fn-id b))
      {:kind :resolved-value :base-name base-name :ext-name ext-name
       :slot-id slot-id :resolver-id (:resolver-fn-id b)
       :stored (:value b)}


      (value-binding? b)
      {:kind :value :base-name base-name :ext-name ext-name :slot-id slot-id
       :value (:value b)}

      (ref-binding? b)
      {:kind :ref :base-name base-name :ext-name ext-name :slot-id slot-id
       :ref-id (:ref-fn-id b)
       :is-fn (fn-typed-slot? slot b fn-typed-fn-ids fn-id lookups)
       :produces-callable? (ref-produces-callable? (:ref-fn-id b) lookups)}

      (list-binding? b)
      {:kind :seq :base-name base-name :ext-name ext-name :slot-id slot-id
       ;; `:binder-fn-id` — the fn that OWNS this seq binding row
       ;; (the closest ancestor that wrote `:items [...]`). seq-item
       ;; positional `{:as :name}` renames create slots on THAT fn
       ;; via the parser, so resolving the `:as`-name's slot-id at
       ;; runtime needs this fn-id (not the iterating fn-id, which
       ;; may be a descendant that just inherits the binding).
       :binder-fn-id (:fn-id b)
       :items (list-items-for fn-id slot-id lookups)
       :lazy-seq? (contains? (or lazy-seq-args #{}) base-name)}

      :else
      {:kind :free :base-name base-name :ext-name ext-name :slot-id slot-id
       :required (effective-required? slot fn-id lookups)
       :is-fn (fn-typed-slot? slot b fn-typed-fn-ids fn-id lookups)})))


(defn- compute-fn-typed-fn-ids
  "Set of fn-ids whose row identifies a HOF-callable slot. Two flavours:

   1. The bare-keyword primitive `:fn` row — its `:name` is literally
      `\"fn\"` / `:fn` (text-column codec roundtrip preserves both
      shapes, match either).
   2. Structural fn-type rows that came from EDN's `[:fn args ret]`
      declarations. Their `:constraint` is `[:fn …]`. Named ones
      (`:fn-type`) plus anonymous-by-shape rows both qualify — the
      executor treats either as a HOF marker.

   Pre-fix the only path was (1), so `[:fn args ret]` slots silently
   fell back to plain value-binding semantics — bindings to them
   weren't hof-wrapped, and the bound fn-graph was eagerly executed
   as a value. The compiled closure then tripped over the resulting
   Clojure value (e.g. a Ring response map) when it expected a
   callable."
  [{:keys [fn-map]}]
  (into #{}
        (keep (fn [[id f]]
                (when (or (#{"fn" :fn} (:name f))
                          (and (vector? (:constraint f))
                               (= :fn (first (:constraint f)))))
                  id)))
        fn-map))


(defn- collect-bindings*
  [fn-id lookups]
  (let [slots (l/root-slots fn-id lookups)
        fn-typed-fn-ids (compute-fn-typed-fn-ids lookups)
        lazy-seq-args (lazy-seq-arg-names fn-id lookups)]
    (mapv #(classify-slot % fn-id lookups fn-typed-fn-ids lazy-seq-args)
          slots)))


(defn collect-bindings
  "For F, classify every root slot. Returns a vector of binding entries
   in fn-slot position order. Memoised on `lookups`'s
   `:bindings-cache` — compile-eager + `build-ref-renames` between
   them hit this once per (fn-id, ref-binding) which can be hundreds
   of calls on the same fn-id during a single compile-all pass."
  [fn-id {:keys [bindings-cache] :as lookups}]
  (if-let [cache bindings-cache]
    (or (get @cache fn-id)
        (let [r (collect-bindings* fn-id lookups)]
          (swap! cache assoc fn-id r)
          r))
    (collect-bindings* fn-id lookups)))


(defn- own-fn-of-slot
  "Return the fn-id that OWNS `slot-id` via fn-slot junction, or nil."
  [slot-id {:keys [fn-slots-by-fn]}]
  (some (fn [[fid junctions]]
          (when (some #(= slot-id (:slot-id %)) junctions)
            fid))
        fn-slots-by-fn))


(defn collect-env-bindings
  "Bindings on F or its ancestors that target a slot which ISN'T one
   of the root's direct slots — at runtime the executor merges these
   into the free-args map so the ref tree sees the override.

   This covers two distinct patterns:
   1. Bindings on slots owned by fns outside F's inheritance chain
      (slots living on ref-targets, accessed via the data-flow tree).
   2. Bindings on RENAME slots owned by ancestors that ARE in F's
      inheritance chain — the rename slot exposes a free-arg name
      that propagates to inner sequence-items (`{:as :path}`), HOF
      lambda-params, etc. These never appear as root slots, so
      `collect-bindings` doesn't see them, but the runtime needs them
      in free-args.

   `:is-fn` for ref-bindings is derived by walking the slot-owner's
   chain for type-overrides."
  [fn-id {:keys [slot-map bindings-by-fn binding-by-fn-slot] :as lookups}]
  (let [chain (l/inheritance-chain* fn-id lookups)
        root-slot-ids (into #{}
                            (map :id)
                            (or (l/root-slots fn-id lookups) []))
        fn-typed-fn-ids (compute-fn-typed-fn-ids lookups)
        is-fn-for-slot
        (fn [slot-id slot b-row]
          (let [owner (own-fn-of-slot slot-id lookups)
                owner-chain (when owner (l/inheritance-chain* owner lookups))
                override (or (:type-override-fn-id b-row)
                             (some (fn [fid]
                                     (when-let [b (get binding-by-fn-slot
                                                       [fid slot-id])]
                                       (:type-override-fn-id b)))
                                   owner-chain))
                t-id (or override (:type-fn-id slot))]
            (boolean (and t-id (contains? fn-typed-fn-ids t-id)))))
        ;; Iterate the chain × this-fn's bindings, accumulate first
        ;; binding per env-name (ancestor-closer wins, mirroring how
        ;; `inheritance-chain` orders results). Pure reduce — no atoms.
        binding->entry
        (fn [b]
          (let [slot-id (:slot-id b)
                slot (get slot-map slot-id)
                env-name (some-> (:name slot) keyword)]
            (when (and env-name
                       (not (contains? root-slot-ids slot-id))
                       (or (value-binding? b) (ref-binding? b)))
              (cond
                (value-binding? b)
                [env-name {:kind :value :env-name env-name
                           :slot-id slot-id :value (:value b)}]
                (ref-binding? b)
                [env-name {:kind :ref :env-name env-name
                           ;; `:slot-id` + `:type-override-fn-id` carry
                           ;; enough for downstream `hof-lambda-params`
                           ;; (via `enrich-is-fn-ref`) to resolve the
                           ;; slot's structural `[:fn {ARGS} RET]` type
                           ;; — without these the helper would see a
                           ;; nil slot-id and reject the binding as a
                           ;; bare-`:fn` slot.
                           :slot-id slot-id
                           :type-override-fn-id (:type-override-fn-id b)
                           :ref-id (:ref-fn-id b)
                           :is-fn (is-fn-for-slot slot-id slot b)
                           :produces-callable? (ref-produces-callable?
                                                 (:ref-fn-id b) lookups)}]))))]
    (->> chain
         (mapcat #(get bindings-by-fn % []))
         (keep binding->entry)
         (reduce (fn [{:keys [seen out] :as acc} [env-name entry]]
                   (if (contains? seen env-name)
                     acc
                     {:seen (conj seen env-name) :out (conj out entry)}))
                 {:seen #{} :out []})
         :out)))
