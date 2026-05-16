(ns graphden.packages.records.parse
  "Per-form parsers — turn one fn-def EDN entry into a vector of
   records (`fn`, `slot`, `fn-slot`, `binding`, `binding-list-item`).
   Dispatches on entry shape; composed defs walk the inheritance +
   rename chain via `slot-resolution`."
  (:require
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.slot-resolution :as slot-res]
    [graphden.packages.records.types :as types]))


;; =============================================================================
;; Per-form parsers — produce records from one fn-def EDN entry
;; =============================================================================

(defn emit-composite-records
  "For a composite type definition `{slot-name slot-type ...}` belonging
   to fn `owner-fn-id`, emit slot, fn-slot rows. If any slot's type is
   itself an inline composite, recursively emit that composite's records
   too. Returns vector of records.

   `name->id`: existing fn-name → fn-id mapping (for resolving named
   refs within the composite)."
  [owner-fn-id shape-map name->id]
  (let [entries (vec shape-map)]
    (vec
      (mapcat
        (fn [[idx [field-name field-type]]]
          (let [;; Resolve field's type. If inline composite (a pure
                ;; {field-name field-type} map with NO `:type` discriminator),
                ;; recurse to emit its anonymous fn / slot rows. Loader's
                ;; expanded `{:type T :required B}` shape is NOT an inline
                ;; composite — `resolve-type-ref` strips it back to T.
                inline-composite? (and (map? field-type)
                                       (not (types/type-spec-map? field-type)))
                [type-fn-id sub-records]
                (if inline-composite?
                  (let [inline-id (ids/anonymous-fn-id (ids/shape-hash field-type))
                        inline-fn {:kind :fn
                                   :id inline-id
                                   :name nil
                                   :namespace-id nil
                                   :parent-ids []
                                   :impl-hash nil
                                   :base-fn-id nil
                                   :element-fn-id nil
                                   :return-type-fn-id nil
                                   :anonymous-hash (ids/shape-hash field-type)
                                   :constraint nil
                                   :description nil}]
                    ;; (no :required for inline composite anon-fn rows)
                    [inline-id
                     (into [inline-fn]
                           (emit-composite-records inline-id field-type name->id))])
                  [(types/resolve-type-ref field-type name->id) []])

                slot-description (when (types/type-spec-map? field-type)
                                   (:description field-type))
                slot-required (cond
                                (types/type-spec-map? field-type)
                                (if (contains? field-type :required)
                                  (:required field-type)
                                  true)
                                :else true)
                slot (ids/slot-id owner-fn-id field-name)
                fn-slot (ids/fn-slot-id owner-fn-id slot)]
            (into sub-records
                  [{:kind :slot
                    :id slot
                    :name (name field-name)
                    :type-fn-id type-fn-id
                    :required slot-required
                    :description slot-description}
                   {:kind :fn-slot
                    :id fn-slot
                    :fn-id owner-fn-id
                    :slot-id slot
                    :position idx}])))
        (map-indexed vector entries)))))


(defn parse-base-fn
  "A fn-def with `:args` declaration and an impl is a base-fn. The
   args become slot/fn-slot rows."
  [{:keys [args return-type description]
    fn-name :name ns-id :namespace} name->id]
  (let [own-id (ids/fn-id ns-id fn-name)
        ret-id (when return-type (types/resolve-type-ref return-type name->id))
        own-fn {:kind :fn
                :id own-id
                :name (clojure.core/name fn-name)
                :namespace-id ns-id          ; placeholder — sync resolves to id
                :parent-ids []
                :impl-hash :sentinel/impl-hash    ; computed by sync from impl-source
                :base-fn-id nil
                :element-fn-id nil
                :return-type-fn-id ret-id
                :anonymous-hash nil
                :constraint nil
                :description description}
        slots-records (emit-composite-records own-id (or args {}) name->id)]
    (into [own-fn] slots-records)))


(defn parse-record-type
  "`{:name :foo :type {:k T …}}` — record-type with the given fields."
  [{:keys [description]
    fn-name :name ns-id :namespace shape :type} name->id]
  (let [own-id (ids/fn-id ns-id fn-name)
        own-fn {:kind :fn
                :id own-id
                :name (clojure.core/name fn-name)
                :namespace-id ns-id
                :parent-ids []
                :impl-hash nil
                :base-fn-id nil
                :element-fn-id nil
                :return-type-fn-id nil
                :anonymous-hash nil
                :constraint nil
                :description description}
        slots-records (emit-composite-records own-id shape name->id)]
    (into [own-fn] slots-records)))


(defn parse-refinement
  "`{:name :foo :refine {:base T :constraint C}}` — refinement-type.

   Emits a fn-row with `base-fn-id` + `constraint` set, plus a single
   `:value` slot whose type points at the base. The synthesised impl
   in the registry layer reads `(:value args)`, so the slot must exist
   for composed children to bind it via `:args {:value …}`."
  [{:keys [refine description]
    fn-name :name ns-id :namespace} name->id]
  (let [own-id (ids/fn-id ns-id fn-name)
        base-id (types/resolve-type-ref (:base refine) name->id)
        value-slot (ids/slot-id own-id "value")
        value-fn-slot (ids/fn-slot-id own-id value-slot)]
    [{:kind :fn
      :id own-id
      :name (clojure.core/name fn-name)
      :namespace-id ns-id
      :parent-ids []
      :impl-hash nil
      :base-fn-id base-id
      :element-fn-id nil
      :return-type-fn-id nil
      :anonymous-hash nil
      :constraint (:constraint refine)
      :description description}
     {:kind :slot
      :id value-slot
      :name "value"
      :type-fn-id base-id
      :required true
      :description nil}
     {:kind :fn-slot
      :id value-fn-slot
      :fn-id own-id
      :slot-id value-slot
      :position 0}]))


(defn parse-list-type
  "`{:name :foo :list T}` — list-type with the given element type.

   Emits a fn-row plus a single `:items` slot (list-typed) so children
   can bind concrete sequence content via `:args {:items {:append [...]}}`."
  [{:keys [description]
    fn-name :name ns-id :namespace element-type :list} name->id]
  (let [own-id (ids/fn-id ns-id fn-name)
        element-id (types/resolve-type-ref element-type name->id)
        items-slot (ids/slot-id own-id "items")
        items-fn-slot (ids/fn-slot-id own-id items-slot)]
    [{:kind :fn
      :id own-id
      :name (clojure.core/name fn-name)
      :namespace-id ns-id
      :parent-ids []
      :impl-hash nil
      :base-fn-id nil
      :element-fn-id element-id
      :return-type-fn-id nil
      :anonymous-hash nil
      :constraint nil
      :description description}
     {:kind :slot
      :id items-slot
      :name "items"
      :type-fn-id (ids/primitive-fn-id :sequence)
      :required true
      :description nil}
     {:kind :fn-slot
      :id items-fn-slot
      :fn-id own-id
      :slot-id items-slot
      :position 0}]))


(defn parse-union
  "`{:name :foo :union [T1 T2 …]}` — union type. Stored as a fn-row
   with the branch list serialised into `:constraint` (a `[:union …]`
   vector that the type-checker reads). No slots — the row's role is
   pure type metadata."
  [{:keys [union description]
    fn-name :name ns-id :namespace} _name->id]
  (let [own-id (ids/fn-id ns-id fn-name)]
    [{:kind :fn
      :id own-id
      :name (clojure.core/name fn-name)
      :namespace-id ns-id
      :parent-ids []
      :impl-hash nil
      :base-fn-id nil
      :element-fn-id nil
      :return-type-fn-id nil
      :anonymous-hash nil
      :constraint (into [:union] union)
      :description description}]))


(defn parse-variant
  "`{:name :foo :variant [:tag1 T1 :tag2 T2 …]}` — discriminated union.
   Like union, stored as a fn-row whose `:constraint` carries the
   variant payload for the type-checker to inspect.

   Rejects duplicate tag keywords — a variant whose `:ok` appears
   twice is structurally ambiguous (two different value-types for the
   same tag) and silently corrupting it would surface much later as
   confusing runtime mismatches."
  [{:keys [variant description]
    fn-name :name ns-id :namespace} _name->id]
  (let [pairs (partition 2 variant)
        tags (map first pairs)
        dup-tag (->> tags frequencies (some (fn [[k n]] (when (> n 1) k))))]
    (when dup-tag
      (throw (ex-info (str "Variant " (pr-str fn-name)
                           " declares tag " (pr-str dup-tag) " twice — "
                           "each tag must be unique.")
                      {:type :invalid-variant-duplicate-tag
                       :fn-name fn-name
                       :tag dup-tag
                       :variant variant})))
    (let [own-id (ids/fn-id ns-id fn-name)]
      [{:kind :fn
        :id own-id
        :name (clojure.core/name fn-name)
        :namespace-id ns-id
        :parent-ids []
        :impl-hash nil
        :base-fn-id nil
        :element-fn-id nil
        :return-type-fn-id nil
        :anonymous-hash nil
        :constraint (into [:variant] variant)
        :description description}])))


(defn resolve-parent-list
  "Pulls the parent fn-ids from a composed fn-def. Accepts either
   `:parent :foo` (single) or `:parents [:a :b]` (multi-inheritance).
   Throws on unknown names."
  [fn-def name->id]
  (let [single (:parent fn-def)
        many (:parents fn-def)
        parent-names (cond
                       (seq many)              (vec many)
                       (and single (keyword? single)) [single]
                       :else                   nil)]
    (when (seq parent-names)
      (mapv (fn [pn]
              (or (get name->id pn)
                  (throw (ex-info (str "Unknown parent: " (pr-str pn))
                                  {:type :records/unknown-parent
                                   :name (:name fn-def)
                                   :parent pn}))))
            parent-names))))


(defn map-arg-value->binding-fields
  "Map-shaped arg-value branch of `arg-value->binding-fields`. Carries
   every recognised key (`:as`, `:ref`, `:value`, `:type`, `:append`,
   `:closed`, `:terminal?`, `:literal?`, `:required`) and emits the
   corresponding binding columns. Falls back to `:value <whole-map>`
   when none of the recognised keys are present (literal map binding).

   `:required true` narrows an inherited optional slot to required at
   this level — a one-way ratchet (descendants can't widen back).
   `:required false` is rejected at sync time by the type-checker
   (widening forbidden); we still pass it through here so the diagnostic
   fires on the actual binding row, not as a silent drop."
  [arg-value name->id]
  (let [{:keys [as value append closed terminal? literal? required]
         ref-name :ref type-ref :type} arg-value
        has-required? (contains? arg-value :required)
        override-fn-id (when type-ref
                         (try (types/resolve-type-ref type-ref name->id)
                              (catch Exception _ nil)))
        ;; Phase 6c — `:as` no longer writes to `binding.rename-to`.
        ;; The renamed-view slot row (emitted by
        ;; `build-rename-slot-records`) carries the FK link and the
        ;; new name; the binding now only describes the value/ref/
        ;; metadata applied to the SOURCE slot.
        fields (cond-> {}
                 (and ref-name (contains? name->id ref-name))
                 (assoc :ref-fn-id (get name->id ref-name))

                 (and (contains? arg-value :value) (not (contains? arg-value :ref)))
                 (assoc :value value)

                 override-fn-id (assoc :type-override-fn-id override-fn-id)
                 terminal? (assoc :terminal true)
                 (or append closed) (assoc :list-append (boolean append)
                                           :list-closed (boolean closed))
                 has-required? (assoc :required (boolean required))
                 (not (or ref-name (contains? arg-value :value) as type-ref
                          append closed terminal? literal? has-required?))
                 (assoc :value arg-value))]
    {:fields fields
     :items (vec (when (vector? append) append))}))


(defn arg-value->binding-fields
  "Translate a fn-def `:args` value into `{:value :ref-fn-id …}` fields
   plus the items vector that should be emitted as `binding-list-item`
   rows.

   `sequence-slot?` is a flag the caller computes from the resolved
   slot's type — bare vectors should be interpreted as list items
   ONLY when the target slot is `:sequence`-typed; otherwise vectors
   are literal jsonb values (e.g. hiccup data).

   Recognised shapes:
     keyword that names a fn      → `:ref-fn-id`
     bare vector + sequence slot   → `:list-append true` + items
     bare vector + scalar slot     → literal `:value`
     `{:append [items]}`           → `:list-append true` + items
     `{:as :name}` map             → `:rename-to`
     `{:as :n :type T}`            → rename + `:type-override-fn-id`
     `{:value v :literal? true}`   → bypass fn-ref resolution
     `{:ref :name}`                → `:ref-fn-id`
     `{:terminal? true}`           → `:terminal`
     anything else (incl. literal map) → `:value`"
  [arg-value name->id sequence-slot?]
  (cond
    (and (keyword? arg-value) (contains? name->id arg-value))
    {:fields {:ref-fn-id (get name->id arg-value)} :items []}

    ;; Bare vector on a sequence-typed slot → items. Bare vector on
    ;; any other slot is a literal jsonb value (e.g. hiccup data).
    (and (vector? arg-value) sequence-slot?)
    {:fields {:list-append true} :items (vec arg-value)}

    (vector? arg-value)
    {:fields {:value arg-value} :items []}

    (map? arg-value)
    (map-arg-value->binding-fields arg-value name->id)

    :else
    {:fields {:value arg-value} :items []}))


(defn item->record
  "Translate one element of an `:append [...]` vector into a
   binding-list-item record. Recognised shapes mirror
   `arg-value->binding-fields`:
     keyword that names a fn       → `:ref-fn-id`
     other value (kw, str, num, …) → literal `:value`
     `{:ref :n}` map               → `:ref-fn-id`
     `{:value v :literal? true}`   → literal `:value` plus `:literal true`
     `{:value v}`                  → literal `:value`"
  [item idx owner-binding-id name->id]
  (let [base {:kind :binding-list-item
              :id (ids/binding-list-item-id owner-binding-id idx)
              :binding-id owner-binding-id
              :position idx
              :value nil
              :ref-fn-id nil
              :literal nil}]
    (cond
      (and (keyword? item) (contains? name->id item))
      (assoc base :ref-fn-id (get name->id item))

      (map? item)
      (let [{:keys [value literal?] ref-name :ref} item]
        (cond
          (and ref-name (contains? name->id ref-name))
          (assoc base :ref-fn-id (get name->id ref-name))

          (contains? item :value)
          (cond-> (assoc base :value value)
            literal? (assoc :literal true))

          :else
          (assoc base :value item)))

      :else
      (assoc base :value item))))


(defn composed-own-fn
  "Top-level fn-row record for a composed fn-def — no impl-hash and no
   type-row markers (those are owned by base-fn / type-row branches
   of the parser). `return-type-fn-id` may be set when the composed
   def explicitly narrows the inherited return type (e.g.
   `:return-type :ring-response-shape` on a child of `:update-in`)."
  [own-id fn-name ns-id parent-ids description return-type-fn-id]
  {:kind :fn
   :id own-id
   :name (clojure.core/name fn-name)
   :namespace-id ns-id
   :parent-ids parent-ids
   :impl-hash nil
   :base-fn-id nil
   :element-fn-id nil
   :return-type-fn-id return-type-fn-id
   :anonymous-hash nil
   :constraint nil
   :description description})


(def ^:private blank-binding-row
  "Default values for every nullable column on the binding entity.
   Merged under `arg-value->binding-fields`'s overrides so the row
   shape matches the schema even when only one column is set."
  {:kind :binding
   :value nil
   :ref-fn-id nil
   :override-kind :fixed
   :type-override-fn-id nil
   :description nil
   :terminal nil
   :list-append nil
   :list-closed nil
   :required nil})


(defn build-binding-and-items
  "Translate one `[arg-name arg-value]` into the binding row plus its
   list-item rows. Walks inheritance to find the slot owner so the
   binding targets the canonical slot-id."
  [own-id fn-name [arg-name arg-value] name->id defs-by-name]
  (let [[owner-name owner-arg] (slot-res/resolve-slot-owner fn-name arg-name defs-by-name)
        owner-fn-def (get defs-by-name owner-name)
        owner-fn-id (or (get name->id owner-name)
                        (ids/fn-id (:namespace owner-fn-def) owner-name))
        slot (ids/slot-id owner-fn-id owner-arg)
        bid (ids/binding-id own-id slot)
        slot-type (slot-res/slot-type-of owner-name owner-arg defs-by-name)
        sequence-slot? (= :sequence slot-type)
        {:keys [fields items]} (arg-value->binding-fields arg-value name->id sequence-slot?)
        binding-row (merge blank-binding-row
                           {:id bid :fn-id own-id :slot-id slot}
                           fields)
        item-rows (vec (map-indexed
                         (fn [idx item] (item->record item idx bid name->id))
                         items))]
    (into [binding-row] item-rows)))


(defn parse-composed
  "Composed fn-def: `:parent` (single) or `:parents` (multi). Each
   `:args` entry becomes a binding row on the slot it targets.

   Slot resolution walks the inheritance chain (`resolve-slot-owner`)
   to find the actual ancestor that DECLARED the slot — directly via
   a base-fn / type-row, or via a `:as` rename that re-exposes a
   deeper slot under a new name. The slot-id is computed from the
   resolved owner's fn-id and the slot's original name, so siblings
   that bind the same effective slot agree on its id."
  [fn-def name->id defs-by-name]
  (let [{:keys [args description return-type] fn-name :name ns-id :namespace} fn-def
        own-id (ids/fn-id ns-id fn-name)
        parent-ids (resolve-parent-list fn-def name->id)
        ret-id (when return-type
                 (try (types/resolve-type-ref return-type name->id) (catch Exception _ nil)))
        own-fn (composed-own-fn own-id fn-name ns-id parent-ids description ret-id)
        exposed-names (slot-res/collect-exposed-names args fn-name defs-by-name)
        rename-slot-records (slot-res/build-rename-slot-records fn-name exposed-names
                                                                own-id name->id
                                                                defs-by-name)
        binding+items (mapv #(build-binding-and-items own-id fn-name %
                                                      name->id defs-by-name)
                            args)]
    (into [own-fn]
          (concat (apply concat rename-slot-records)
                  (apply concat binding+items)))))


(defn attach-fn-meta
  "Post-process step that copies fn-def-level metadata onto the first
   record of every parser's output (which is always the `:fn` row).
   Today there's only one such field — `:expects-effects` — but the
   pattern is here so any future authored-only column slips into the
   first row without a per-parser tweak."
  [records fn-def]
  (if-let [ee (:expects-effects fn-def)]
    (let [normalised (vec (map #(if (keyword? %) (name %) (str %)) ee))]
      (update records 0 assoc :expects-effects normalised))
    records))


(defn parse-fn-def
  "Dispatches on EDN entry shape; returns vector of records.

   `name->id`: known fn-name → fn-id map (cross-module references).
   `defs-by-name` (optional): {fn-name → fn-def} for parse-time slot
   resolution through the inheritance + rename chain. Defaults to an
   empty map — composed defs that hit a slot redirected by an `:as`
   rename in another fn-def need this to be populated."
  ([fn-def name->id]
   (parse-fn-def fn-def name->id {}))
  ([fn-def name->id defs-by-name]
   (-> (cond
         (or (:parent fn-def) (:parents fn-def))
         (parse-composed fn-def name->id defs-by-name)
         (:type fn-def)    (parse-record-type fn-def name->id)
         (:refine fn-def)  (parse-refinement fn-def name->id)
         (:list fn-def)    (parse-list-type fn-def name->id)
         (:union fn-def)   (parse-union fn-def name->id)
         (:variant fn-def) (parse-variant fn-def name->id)
         ;; `:fn-type` declarations now produce a fn-row with the
         ;; structural `[:fn args ret]` shape stashed in `:constraint`
         ;; (mirrors how unions / variants stash their payload). The
         ;; in-memory type-alias registry still gets the keyword→shape
         ;; entry for `subtype?` / `unify` (`system/core` registers it
         ;; at init AND `compile_runtime/register-type-aliases-from-db!`
         ;; mirrors from the row's constraint), so resolution works on
         ;; both EDN and DB-driven paths.
         (:fn-type fn-def)
         (let [{fn-name :name ns-id :namespace
                description :description ft :fn-type} fn-def
               [args ret] ft]
           [{:kind :fn
             :id (ids/fn-id ns-id fn-name)
             :name (clojure.core/name fn-name)
             :namespace-id ns-id
             :parent-ids []
             :impl-hash nil
             :base-fn-id nil
             :element-fn-id nil
             :return-type-fn-id nil
             :anonymous-hash nil
             :constraint (into [:fn] [(or args {}) ret])
             :description description}])
         :else             (parse-base-fn fn-def name->id))
       (attach-fn-meta fn-def))))


(defn parse-module
  "Parse fn-defs into records. Two passes:
   1. Pre-compute `name->id` (deterministic UUIDs) and `defs-by-name`
      (for parse-time slot resolution through rename chains).
   2. Run `parse-fn-def` on each, threading both maps.

   `extra-name->id` (optional) carries names known from prior syncs
   so cross-module references resolve.

   `extra-defs-by-name` (optional) — defs from prior syncs whose
   shapes the slot resolver needs to see (base-fn `:args` declarations,
   record/refinement/list type-rows). Without these, a composed fn
   binding `:m` to a slot owned by a base-fn synced earlier wouldn't
   resolve, since the current pass's `defs-by-name` only contains the
   incoming defs."
  ([module-fn-defs] (parse-module module-fn-defs {} {}))
  ([module-fn-defs extra-name->id]
   (parse-module module-fn-defs extra-name->id {}))
  ([module-fn-defs extra-name->id extra-defs-by-name]
   (let [;; Every named fn-def — including `:fn-type` declarations —
         ;; gets a deterministic fn-id by `(:namespace, :name)`.
         ;; `:fn-type` rows now carry their structural shape in
         ;; `:constraint` (see parse-fn-def), so they take the
         ;; standard name→id path; the previous primitive-:fn
         ;; aliasing is no longer needed.
         own-name->id (into {}
                            (keep (fn [fd]
                                    (when (:name fd)
                                      [(:name fd) (ids/fn-id (:namespace fd) (:name fd))])))
                            module-fn-defs)
         name->id (merge extra-name->id own-name->id)
         defs-by-name (merge extra-defs-by-name (slot-res/build-defs-by-name module-fn-defs))
         ;; Inline `[:fn args ret]` references buried in fn-defs'
         ;; type-bearing fields produce anonymous fn-rows that the
         ;; slot's `:type-fn-id` lands on. Synthesised once per
         ;; module (deduped by id) and prepended so they exist
         ;; before any slot row references them.
         inline-fn-rows (->> module-fn-defs
                             (mapcat types/inline-fn-type-rows-from-fn-def)
                             (distinct)
                             vec)]
     (vec (concat inline-fn-rows
                  (mapcat #(parse-fn-def % name->id defs-by-name) module-fn-defs))))))
