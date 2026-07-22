(ns graphden.packages.records.parse
  "Per-form parsers — turn one fn-def EDN entry into a vector of
   records (`fn`, `slot`, `fn-slot`, `binding`, `binding-list-item`).
   Dispatches on entry shape; composed defs walk the inheritance +
   rename chain via `slot-resolution`."
  (:require
    [clojure.tools.logging :as log]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.slot-resolution :as slot-res]
    [graphden.packages.records.types :as types]))


;; =============================================================================
;; Per-form parsers — produce records from one fn-def EDN entry
;; =============================================================================

(defn- inline-composite-type?
  "True iff `t` is an inline composite shape `{:k T …}` — a map with
   no `:type` discriminator, used to declare an anonymous record type.
   The loader's expanded `{:type T :required B}` wrapper does NOT
   qualify (it's a type-spec map, not a composite)."
  [t]
  (and (map? t)
       (not (types/type-spec-map? t))
       (seq t)))


(declare ^:private emit-composite-records)


(defn- emit-anon-composite-fn
  "Build the anon `:fn` row + recursive slot rows for an inline
   composite shape. Returns `[fn-id records]`. Both the dangling
   `return-type-fn-id` and dangling slot `type-fn-id` bugs trace back
   to this emission being skipped — centralising it here keeps every
   inline-composite site in sync."
  [shape name->id]
  (let [inline-id (ids/anonymous-fn-id (ids/shape-hash shape))
        inline-fn {:kind :fn
                   :id inline-id
                   :name nil
                   :namespace-id nil
                   :parent-ids []
                   :base-fn-id nil
                   :element-fn-id nil
                   :return-type-fn-id nil
                   :anonymous-hash (ids/shape-hash shape)
                   :constraint nil
                   :description nil}]
    [inline-id (into [inline-fn]
                     (emit-composite-records inline-id shape name->id))]))


(defn- emit-composite-records
  "For a composite type definition `{slot-name slot-type ...}` belonging
   to fn `owner-fn-id`, emit slot, fn-slot rows. If any slot's type is
   itself an inline composite — either directly (`field-type = {:k T}`)
   or wrapped (`field-type = {:type {:k T} :required ?}`) — recursively
   emit that composite's records too. Returns vector of records.

   `name->id`: existing fn-name → fn-id mapping (for resolving named
   refs within the composite)."
  [owner-fn-id shape-map name->id]
  (let [entries (vec shape-map)]
    (vec
      (mapcat
        (fn [[idx [field-name field-type]]]
          (let [;; Inline composite can appear directly OR inside a
                ;; `{:type T :required B}` wrapper. Pre-fix the wrapped
                ;; case slipped through — `:_seq-remove-load-item :args
                ;; {:parsed {:type {:item-id :uuid}}}` resolved to an
                ;; anon-fn-id but never emitted the corresponding fn-row.
                inner-type (if (types/type-spec-map? field-type)
                             (:type field-type)
                             field-type)
                [type-fn-id sub-records]
                (if (inline-composite-type? inner-type)
                  (emit-anon-composite-fn inner-type name->id)
                  [(types/resolve-type-ref field-type name->id) []])

                slot-description (when (types/type-spec-map? field-type)
                                   (:description field-type))
                slot-required (if (types/type-spec-map? field-type)
                                (if (contains? field-type :required)
                                  (:required field-type)
                                  true)
                                true)
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


(defn- inline-record-rows-for-return-type
  "When a fn-def's `:return-type` is an inline composite map
   (`{:status :int :body :text …}`, no `:type` discriminator),
   `resolve-type-ref` returns an anonymous-fn-id keyed by shape-hash
   but the parser used to NEVER emit the corresponding fn / slot
   rows — leaving a dangling `return-type-fn-id` FK that the editor
   couldn't dereference. Returns a vector of records, or empty when
   `return-type` is anything else (named ref, primitive, structural)."
  [return-type name->id]
  (if (inline-composite-type? return-type)
    (second (emit-anon-composite-fn return-type name->id))
    []))


(defn- parse-base-fn
  "A fn-def with `:args` declaration and an impl is a base-fn. The
   args become slot/fn-slot rows."
  [{:keys [args return-type description]
    fn-name :name ns-id :namespace} name->id]
  (let [own-id (ids/fn-id ns-id fn-name)
        ;; A base-fn ALWAYS carries a return-type-fn-id — it is THE
        ;; structural discriminator vs a record-type (which has none).
        ;; Default to the `:any` primitive when `:return-type` is omitted.
        ret-id (types/resolve-type-ref (or return-type :any) name->id)
        own-fn {:kind :fn
                :id own-id
                :name (clojure.core/name fn-name)
                :namespace-id ns-id          ; placeholder — sync resolves to id
                :parent-ids []
                :base-fn-id nil
                :element-fn-id nil
                :return-type-fn-id ret-id
                :anonymous-hash nil
                :constraint nil
                :description description}
        slots-records (emit-composite-records own-id (or args {}) name->id)
        return-rows (inline-record-rows-for-return-type return-type name->id)]
    (into [own-fn] (concat slots-records return-rows))))


(defn- parse-record-type
  "`{:name :foo :type {:k T …}}` — record-type with the given fields."
  [{:keys [description]
    fn-name :name ns-id :namespace shape :type} name->id]
  (let [own-id (ids/fn-id ns-id fn-name)
        own-fn {:kind :fn
                :id own-id
                :name (clojure.core/name fn-name)
                :namespace-id ns-id
                :parent-ids []
                :base-fn-id nil
                :element-fn-id nil
                :return-type-fn-id nil
                :anonymous-hash nil
                :constraint nil
                :description description}
        slots-records (emit-composite-records own-id shape name->id)]
    (into [own-fn] slots-records)))


(defn- parse-refinement
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


(defn- parse-list-type
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


(defn- parse-union
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
      :base-fn-id nil
      :element-fn-id nil
      :return-type-fn-id nil
      :anonymous-hash nil
      :constraint (into [:union] union)
      :description description}]))


(defn- parse-map
  "`{:name :foo :map {:key K :value V}}` — homogeneous-map type. Like
   `:union`, stored as a fn-row whose `:constraint` carries `[:map K V]`
   for the type-checker; no slots — the row is pure type metadata."
  [{:keys [description]
    fn-name :name ns-id :namespace map-spec :map} _name->id]
  (let [own-id (ids/fn-id ns-id fn-name)]
    [{:kind :fn
      :id own-id
      :name (clojure.core/name fn-name)
      :namespace-id ns-id
      :parent-ids []
      :base-fn-id nil
      :element-fn-id nil
      :return-type-fn-id nil
      :anonymous-hash nil
      :constraint [:map (:key map-spec) (:value map-spec)]
      :description description}]))


(defn- parse-tuple
  "`{:name :foo :tuple [T1 T2 …]}` — fixed-length heterogeneous tuple.
   Like `:union`, stored as a fn-row whose `:constraint` carries
   `[:tuple T1 T2 …]`; no slots — pure type metadata."
  [{:keys [tuple description]
    fn-name :name ns-id :namespace} _name->id]
  (let [own-id (ids/fn-id ns-id fn-name)]
    [{:kind :fn
      :id own-id
      :name (clojure.core/name fn-name)
      :namespace-id ns-id
      :parent-ids []
      :base-fn-id nil
      :element-fn-id nil
      :return-type-fn-id nil
      :anonymous-hash nil
      :constraint (into [:tuple] tuple)
      :description description}]))


(defn- parse-variant
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
        :base-fn-id nil
        :element-fn-id nil
        :return-type-fn-id nil
        :anonymous-hash nil
        :constraint (into [:variant] variant)
        :description description}])))


(defn- resolve-parent-list
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
      (mapv (fn [parent-name]
              (or (get name->id parent-name)
                  (throw (ex-info (str "Unknown parent: " (pr-str parent-name))
                                  {:type :records/unknown-parent
                                   :name (:name fn-def)
                                   :parent parent-name}))))
            parent-names))))


(defn- map-arg-value->binding-fields
  "Map-shaped arg-value branch of `arg-value->binding-fields`. Carries
   every recognised key (`:as`, `:ref`, `:value`, `:type`, `:append`,
   `:closed`, `:required`, `:terminal`, `:secret-path`) and emits the
   corresponding binding columns. `:terminal true` seals the slot (§4.3 `validation/terminal-rej`).
   Falls back to `:value <whole-map>` when none of the recognised keys
   are present (literal map binding).

   `:required true` narrows an inherited optional slot to required at
   this level — a one-way ratchet (descendants can't widen back).
   `:required false` is rejected at sync time by the type-checker
   (widening forbidden); we still pass it through here so the diagnostic
   fires on the actual binding row, not as a silent drop."
  [arg-value name->id]
  (let [{:keys [as value append closed required terminal secret-path]
         ref-name :ref type-ref :type} arg-value
        has-required? (contains? arg-value :required)
        has-terminal? (contains? arg-value :terminal)
        has-secret-path? (contains? arg-value :secret-path)
        ;; A `:ref` key names a fn; `name->id` is complete for every
        ;; legitimately-referenceable fn at parse time (own module +
        ;; dependency-loaded ancestors), so an unresolved one is an
        ;; author typo. Fail loud instead of dropping to an empty
        ;; binding — mirrors the orphan-slot validator + the
        ;; `item->record` list-item path.
        _ (when (and ref-name (not (contains? name->id ref-name)))
            (throw (ex-info (str "Unresolved binding ref: " ref-name)
                            {:type :packages/unresolved-ref
                             :ref ref-name})))
        override-fn-id (when type-ref
                         (try (types/resolve-type-ref type-ref name->id)
                              (catch Exception e
                                ;; Surface unresolvable type-overrides instead
                                ;; of silently dropping the user's annotation —
                                ;; a typo in `:type :rng-resp-shape` (missing 'e')
                                ;; would otherwise turn the binding into a plain
                                ;; ref with no type-override and the user would
                                ;; never know their override was ignored.
                                (log/warn e "Binding :type override silently lost"
                                          {:type-ref type-ref})
                                nil)))
        ;; Phase 6c — `:as` no longer writes to `binding.rename-to`.
        ;; The renamed-view slot row (emitted by
        ;; `build-rename-slot-records`) carries the FK link and the
        ;; new name; the binding now only describes the value/ref/
        ;; metadata applied to the SOURCE slot.
        fields (cond-> {}
                 (and ref-name (contains? name->id ref-name))
                 (assoc :ref-fn-id (get name->id ref-name))

                 (and (contains? arg-value :value) (not (contains? arg-value :ref)))
                 (assoc :value value :value-present true)

                 ;; `{:secret-path "kv/path"}` — a vault-path binding.
                 ;; The path is stored in `binding.value` with
                 ;; `:override-kind :secret-path`; the executor derefs
                 ;; the path via the vault client at arg-resolution
                 ;; time (the secret VALUE never enters graph storage).
                 ;; This is the round-trip twin of the exporter's
                 ;; `:secret-path` emission in `packages/export.clj`.
                 ;; NOTE: sync is an operator-trusted path — the CRUD
                 ;; gate (`validation/secret-path-rej`, which refuses
                 ;; `:secret-path` on slots whose rich-type lacks
                 ;; `:secret`) runs on the API write path, not here;
                 ;; the type-check sweep still rejects a secret-typed
                 ;; ref flowing into a plain slot.
                 has-secret-path?
                 (assoc :value secret-path
                        :value-present true
                        :override-kind :secret-path)

                 override-fn-id (assoc :type-override-fn-id override-fn-id)
                 (or append closed) (assoc :list-append (boolean append)
                                           :list-closed (boolean closed))
                 has-required? (assoc :required (boolean required))
                 has-terminal? (assoc :terminal (boolean terminal))
                 (not (or ref-name (contains? arg-value :value) as type-ref
                          append closed has-required? has-terminal?
                          has-secret-path?))
                 (assoc :value arg-value :value-present true))]
    {:fields fields
     :items (vec (when (vector? append) append))}))


(defn- arg-value->binding-fields
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
     `{:value v}`                  → literal `:value` (bypasses
                                     bare-keyword fn-ref resolution)
     `{:ref :name}`                → `:ref-fn-id`
     `{:secret-path p}`            → vault-path binding (`:override-kind
                                     :secret-path`, path in `:value`)
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
    {:fields {:value arg-value :value-present true} :items []}

    (map? arg-value)
    (map-arg-value->binding-fields arg-value name->id)

    :else
    {:fields {:value arg-value :value-present true} :items []}))


(defn- item->record
  "Translate one element of an `:append [...]` vector into a
   binding-list-item record. Recognised shapes mirror
   `arg-value->binding-fields`:
     keyword that names a fn       → `:ref-fn-id`
     other value (kw, str, num, …) → literal `:value`
     `{:ref :n}` map               → `:ref-fn-id`
     `{:value v}`                  → literal `:value`

   The storage `:literal` column stays nil here. CRUD's keyword-
   wire-restore path (`entities.clj`) is the only writer that sets
   `:literal true` — there it disambiguates `\":foo\"`-shaped JSON
   strings (keyword on the wire) from plain text on read-back."
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
      (let [{:keys [value] ref-name :ref} item]
        (cond
          (and ref-name (contains? name->id ref-name))
          (assoc base :ref-fn-id (get name->id ref-name))

          ;; A `:ref` key names a fn — an unresolved one is an author
          ;; typo, not literal data. Fail loud (mirrors the main-binding
          ;; path) instead of silently storing `{:ref :typo}` as a
          ;; scalar list element that only trips a confusing downstream
          ;; type error.
          ref-name
          (throw (ex-info (str "Unresolved list-item ref: " ref-name)
                          {:type :packages/unresolved-ref
                           :ref ref-name}))

          (contains? item :value)
          (assoc base :value value)

          :else
          (assoc base :value item)))

      :else
      (assoc base :value item))))


(defn- resolve-owner-fn-id
  "Map an owner fn-name to its fn-id. Prefer `name->id` (pre-built
   from the module's fn-defs + base-fn bootstrap); fall back to
   reconstructing `(ids/fn-id ns owner-name)` from the def in
   `defs-by-name`. Returns nil when neither lookup succeeds."
  [owner-name name->id defs-by-name]
  (or (get name->id owner-name)
      (some-> (get defs-by-name owner-name)
              :namespace
              (ids/fn-id owner-name))))


(defn- composed-own-fn
  "Top-level fn-row record for a composed fn-def — no type-row markers
   (those are owned by base-fn / type-row branches
   of the parser). `return-type-fn-id` may be set when the composed
   def explicitly narrows the inherited return type (e.g.
   `:return-type :ring-response-shape` on a child of `:update-in`)."
  [own-id fn-name ns-id parent-ids description return-type-fn-id]
  {:kind :fn
   :id own-id
   :name (clojure.core/name fn-name)
   :namespace-id ns-id
   :parent-ids parent-ids
   :base-fn-id nil
   :element-fn-id nil
   :return-type-fn-id return-type-fn-id
   :anonymous-hash nil
   :constraint nil
   :description description})


(def ^:private blank-binding-row
  "Default values for every nullable column on the binding entity.
   Merged under `arg-value->binding-fields`'s overrides so the row
   shape matches the schema even when only one column is set.

   `:value-present` defaults to `false`; the field-emitting branches
   in `arg-value->binding-fields` / `map-arg-value->binding-fields`
   overlay `true` whenever they actually write `:value`. This is what
   lets the executor distinguish `{:default nil}` (pinned to literal
   nil) from `{:as :x}` or a pure-ref binding (slot remains free)."
  {:kind :binding
   :value nil
   :value-present false
   :ref-fn-id nil
   :override-kind :fixed
   :type-override-fn-id nil
   :description nil
   :list-append nil
   :list-closed nil
   :required nil})


(defn- build-binding-and-items
  "Translate one `[arg-name arg-value]` into the binding row plus its
   list-item rows. Walks inheritance to find the slot owner so the
   binding targets the canonical slot-id.

   Pure `{:as :exposed-name}` renames produce a binding row with
   empty fields (just `:id :fn-id :slot-id`) — counter-intuitively
   the executor's slot resolution REQUIRES that binding row to
   recognise the rename's source-slot-id pointer at lookup time. An
   earlier optimisation suppressed pure-rename binding rows and
   broke `storage-protocol-poc-test`'s `:func {:as :storage-query}`
   pattern; reverted. The rename mechanism is bound up with the
   binding presence — both the slot row (from
   `build-rename-slot-records`) AND the empty binding row are part
   of the contract."
  [own-id fn-name [arg-name arg-value] name->id defs-by-name]
  (let [[owner-name owner-arg] (slot-res/resolve-slot-owner fn-name arg-name
                                                            defs-by-name
                                                            arg-value)
        owner-fn-id (resolve-owner-fn-id owner-name name->id defs-by-name)
        slot (ids/slot-id owner-fn-id owner-arg)
        bid (ids/binding-id own-id slot)
        slot-type (slot-res/slot-type-of owner-name owner-arg defs-by-name)
        ;; A slot holds a list either via the `:sequence` primitive or
        ;; a structural `[:list T]` declaration — both make bare-vector
        ;; bindings sequence content. Mirrors `types.check/sequence-slot?`
        ;; so the loader and the type-checker agree on which slots are
        ;; list-shaped.
        sequence-slot? (or (= :sequence slot-type)
                           (and (vector? slot-type)
                                (= :list (first slot-type))))
        {:keys [fields items]} (arg-value->binding-fields arg-value name->id sequence-slot?)
        binding-row (merge blank-binding-row
                           {:id bid :fn-id own-id :slot-id slot}
                           fields)
        item-rows (vec (map-indexed
                         (fn [idx item] (item->record item idx bid name->id))
                         items))]
    (into [binding-row] item-rows)))


(defn- own-slot-declaration?
  "True iff this `:args` value is an OWN slot declaration (not a
   binding on an inherited slot). Shape: a map with `:type` and
   optionally `:description` / `:required`, but NONE of the binding
   markers (`:value`, `:ref`, `:as`, `:append`, `:closed`).

   Composed fn-defs use this to expose a NEW free pin under their own
   contract — the slot's value flows down to deeper refs by name
   match, the same way it would if the slot were declared at a
   base-fn. Without this the parser would treat `{:data {:type :jsonb}}`
   as a binding on a non-existent inherited `:data` slot."
  [arg-value]
  (and (map? arg-value)
       (contains? arg-value :type)
       (not-any? #(contains? arg-value %)
                 [:value :ref :as :append :closed])))


(defn- parse-composed
  "Composed fn-def: `:parent` (single) or `:parents` (multi). Each
   `:args` entry becomes a binding row on the inherited slot it
   targets, OR — when the entry shape is `{:type T :description D?
   :required R?}` with no binding markers — an own slot row that
   this fn-def adds on top of inheritance.

   Slot resolution for binding entries walks the inheritance chain
   (`resolve-slot-owner`) to find the actual ancestor that DECLARED
   the slot. Own slot declarations skip that walk: the slot-id is
   computed from THIS fn-def's id + the arg-name, mirroring how
   `parse-base-fn` mints fn-slots for a base-fn's args."
  [fn-def name->id defs-by-name]
  (let [{:keys [args description return-type] fn-name :name ns-id :namespace} fn-def
        own-id (ids/fn-id ns-id fn-name)
        parent-ids (resolve-parent-list fn-def name->id)
        ret-id (when return-type
                 (try (types/resolve-type-ref return-type name->id)
                      (catch Exception e
                        (log/warn e "Composed-fn :return-type silently lost"
                                  {:fn-name fn-name :return-type return-type})
                        nil)))
        own-fn (composed-own-fn own-id fn-name ns-id parent-ids description ret-id)
        ;; Partition args: own-slot declarations (shape `{:type T}`
        ;; without binding markers) vs. bindings on inherited slots.
        {own-slot-args true binding-args false} (group-by
                                                  (fn [[_ v]] (own-slot-declaration? v))
                                                  args)
        own-slot-map (into {} own-slot-args)
        binding-map (into {} binding-args)
        exposed-names (slot-res/collect-exposed-names binding-map fn-name defs-by-name)
        ;; PB' own-slot decls — emit the slot/fn-slot rows. Then
        ;; enrich each top-level slot with a `:source-slot-id` link
        ;; if the same name exists deeper in the ref-tree. The link
        ;; makes the PB' own-slot behave as a rename-view of the
        ;; underlying base-fn / rename slot — `chain-source-slot-ids`
        ;; reaches the deep slot through this FK, so downstream HOF
        ;; callbacks with `{:as :item}` renames keep their full
        ;; rename-chain working (consumer bindings route to PB'
        ;; own-slots; without this bridge the rename mechanism would
        ;; shortcircuit). The walk seeds `resolve-slot-owner-strict`
        ;; from each ref-target so the OWN PB' decl on `fn-def`
        ;; doesn't Pass-1-hit itself. Inline-composite anon slots
        ;; (emitted recursively for `{:type {…}}` shapes) skip the
        ;; enrichment — they aren't reachable through ref-targets and
        ;; would mis-link.
        own-slot-records
        (when (seq own-slot-map)
          (let [pb-slot-name-set (set (keys own-slot-map))
                find-deep-source
                (fn [arg-name]
                  (some (fn [ref-name]
                          (when-let [[owner-name owner-arg]
                                     (slot-res/resolve-slot-owner-strict
                                       ref-name arg-name defs-by-name #{})]
                            (when-let [owner-fn-id
                                       (resolve-owner-fn-id
                                         owner-name name->id defs-by-name)]
                              (ids/slot-id owner-fn-id owner-arg))))
                        (slot-res/ref-targets-of fn-def defs-by-name)))]
            (mapv (fn [r]
                    (let [arg-name (some-> (:name r) keyword)]
                      (if (and (= :slot (:kind r))
                               (contains? pb-slot-name-set arg-name)
                               (nil? (:source-slot-id r)))
                        (if-let [deep-sid (find-deep-source arg-name)]
                          (cond-> r
                            (not= deep-sid (:id r)) (assoc :source-slot-id deep-sid))
                          r)
                        r)))
                  (emit-composite-records own-id own-slot-map name->id))))
        binding+items (mapv #(build-binding-and-items own-id fn-name %
                                                      name->id defs-by-name)
                            binding-map)
        return-rows (inline-record-rows-for-return-type return-type name->id)
        ;; Park rename `:fn-slot` rows after any PB' own-slot rows so
        ;; their `:position` doesn't collide. Pre-fix EVERY rename
        ;; hardcoded position 0; mixing PB' + multiple renames produced
        ;; non-deterministic `fn-slots-by-fn` ordering.
        pb-slot-count (count (filter #(= :fn-slot (:kind %)) own-slot-records))
        rename-slot-records (slot-res/build-rename-slot-records
                              fn-name exposed-names own-id name->id
                              defs-by-name pb-slot-count)]
    (into [own-fn]
          (concat (or own-slot-records [])
                  (apply concat rename-slot-records)
                  (apply concat binding+items)
                  return-rows))))


(defn- attach-fn-meta
  "Post-process step that copies fn-def-level metadata onto the first
   record of every parser's output (which is always the `:fn` row).
   Handles authored-only columns: `:expects-effects` and
   `:branch-local?`. Both pass through verbatim — they're identity-
   level on `:fn` (not versioned), so a single write at parse-time
   suffices."
  [records fn-def]
  (cond-> records
    (:expects-effects fn-def)
    (update 0 assoc :expects-effects
            (vec (map #(if (keyword? %) (name %) (str %))
                      (:expects-effects fn-def))))

    (contains? fn-def :branch-local?)
    (update 0 assoc :branch-local? (boolean (:branch-local? fn-def)))))


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
         (:map fn-def)     (parse-map fn-def name->id)
         (:tuple fn-def)   (parse-tuple fn-def name->id)
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
             :base-fn-id nil
             :element-fn-id nil
             :return-type-fn-id nil
             :anonymous-hash nil
             ;; Canonical 4-element form: 4th = `:any` means "no
             ;; effect constraint declared" (any callable passes the
             ;; slot's subtype check). Authors that need a tighter
             ;; bound write `[:fn {…} ret #{:pure-or-whatever}]`
             ;; explicitly and parse-fn-type-decl preserves it.
             :constraint [:fn (or args {}) ret :any]
             :description description}])
         :else             (parse-base-fn fn-def name->id))
       (attach-fn-meta fn-def))))


(defn- inline-anon-fn-def?
  "True iff `v` is an inline anonymous fn-def — a map with
   `:parent` or `:parents` in arg-value position. The parser's
   regular `map-arg-value->binding-fields` recognises only the
   `:as/:ref/:value/:type/:append/:closed/:required`
   shapes; a `{:parent X :args Y}` map falls through to
   `(assoc :value <whole-map>)` (literal map binding) unless the
   pre-pass below lifts it into a synthetic named fn-def first."
  [v]
  (and (map? v)
       (or (contains? v :parent) (contains? v :parents))))


(defn- anon-fn-name
  "Stable synthetic name for an inline anon fn-def. The hash mixes the
   anon's shape with the `host` tuple `[parent-fn-def-name parent-arg-
   name]` of the use-site — so two identical-shape anons referenced
   from DIFFERENT use-sites get DIFFERENT synthetic names (and
   therefore distinct registry entries).

   Why per-use-site: type-narrowing via Pass 2 caller-context
   propagation (Phase α') requires each anon's `:resolved-bindings`
   to be specific to its caller's chain. Dedup-by-shape (the previous
   behaviour) caused two unrelated flows that happened to have
   identical inline-anon structure (e.g. create-flow's
   `:_create-apply-entity-type-str.value` and update-flow's
   `:_update-apply-entity-type-str.value` both `(:get :coll {:as
   :parsed} :key :entity-type :default nil)`) to collapse onto the
   SAME synthetic anon — narrowing it for one flow then poisoned the
   other.

   For nested anons (anon inside anon's arg), `host` carries the
   OUTER anon's already-uniquified name + the nested arg-name; the
   uniqueness propagates down.

   The hash ALSO mixes the host fn-def's NAMESPACE (`ns-path`,
   nil-safe): host names are bare, so under per-namespace name
   uniqueness (ADR-identity-model.md) two parents sharing a name in
   different namespaces would otherwise collapse distinct anons onto
   one synthetic entry and re-introduce the Phase-α' cross-flow
   poisoning above. This closes migration stage 3 — use-site identity
   is `[namespace parent-name arg-name]`, unique under per-ns names."
  [anon-def host ns-path]
  (let [shape-with-host (assoc anon-def
                               ::_use-site host
                               ::_use-site-ns (or ns-path ""))]
    (keyword (str "_anon-" (subs (ids/shape-hash shape-with-host) 0 16)))))


(declare expand-anons-in-fn-def)


(defn- expand-anons-in-arg-value
  "Walk one arg-value. If it's an inline anon, lift it into a synthetic
   named fn-def (recursively expanding anons inside it). If it's a
   sequence-arg vector, walk each item. Returns `[new-arg-value
   extra-fn-defs]`.

   `host` is the `[parent-fn-def-name parent-arg-name]` tuple of the
   use-site; passed into `anon-fn-name` so identical-shape anons at
   different use-sites get distinct synthetic names. For sequence
   items, `host` includes the position index."
  [v ns-id host]
  (cond
    (inline-anon-fn-def? v)
    (let [;; A `:type` on the binding side is an author-pinned
          ;; type-override for THIS call-site (parser writes
          ;; `:type-override-fn-id` on the binding). It belongs on
          ;; the resulting binding-form, not on the lifted anon's
          ;; fn-def declaration — strip it before hashing so an
          ;; otherwise-identical anon doesn't get a different
          ;; synthetic name based on per-call-site overrides.
          binding-type (:type v)
          v* (dissoc v :type)
          synthetic-name (anon-fn-name v* host ns-id)
          ;; Anon inherits the outer fn-def's namespace so its fn-id
          ;; lands in the same module's namespace tree.
          named (-> v*
                    (assoc :name synthetic-name
                           :namespace ns-id
                           :description (or (:description v*)
                                            "Synthetic — extracted from an inline anon fn-def.")))
          [expanded extras] (expand-anons-in-fn-def named)
          new-arg-value (if binding-type
                          {:ref synthetic-name :type binding-type}
                          synthetic-name)]
      [new-arg-value (cons expanded extras)])

    (vector? v)
    (let [[acc-items acc-extras _]
          (reduce
            (fn [[acc-items acc-extras idx] item]
              (let [item-host (conj host idx)
                    [new-item extras] (expand-anons-in-arg-value item ns-id item-host)]
                [(conj acc-items new-item) (into acc-extras extras) (inc idx)]))
            [[] [] 0]
            v)]
      [acc-items acc-extras])

    :else
    [v []]))


(defn- expand-anons-in-fn-def
  "Walk a fn-def's `:args` tree, extracting every inline anon into a
   synthetic named fn-def. Returns `[expanded-fn-def extra-fn-defs]`
   where `expanded-fn-def` has refs in place of inline anons."
  [fn-def]
  (let [ns-id (:namespace fn-def)
        parent-name (:name fn-def)
        [new-args extras]
        (reduce-kv
          (fn [[acc-args acc-extras] k v]
            (let [host [parent-name k]
                  [new-v extras] (expand-anons-in-arg-value v ns-id host)]
              [(assoc acc-args k new-v) (into acc-extras extras)]))
          [{} []]
          (or (:args fn-def) {}))]
    [(assoc fn-def :args new-args) extras]))


(defn expand-inline-anons-in-module
  "Pre-pass before regular module parsing. Walks every fn-def's
   `:args` tree, lifts every inline `{:parent …}` map into a
   synthetic named fn-def, and returns the FLATTENED list of fn-defs
   (originals with refs in place + the new synthetics) for the
   regular parser to consume.

   Dedup: identical inline anon shapes within a module collapse to
   one synthetic name via `ids/shape-hash`. Multiple identical
   inline anons (in one fn-def OR across fn-defs in the module)
   produce the SAME synthetic name → keep only one copy. Without
   dedup, sync would fail `validate-no-duplicate-ids!` on the
   resulting storage batch. Cross-module identical shapes still
   produce separate fn-ids (different `:namespace`)."
  [module-fn-defs]
  (let [results (mapv expand-anons-in-fn-def module-fn-defs)
        expanded-defs (map first results)
        ;; Dedup synthetic anons by `:name` — identical shapes share a
        ;; name via `shape-hash`, so the first occurrence wins.
        unique-extras (->> results
                           (mapcat second)
                           (reduce (fn [acc fd]
                                     (if (contains? acc (:name fd))
                                       acc
                                       (assoc acc (:name fd) fd)))
                                   {})
                           vals)]
    (vec (concat expanded-defs unique-extras))))


(defn- normalize-qref
  "One qualified fn/type reference keyword → its bare form, validated:
   the qualified pair must be a KNOWN fn (dual-keyed `name->id`), else
   throw loud with the pair. Bare keywords pass through untouched."
  [kw name->id]
  (if (qualified-keyword? kw)
    (if (contains? name->id kw)
      (keyword (name kw))
      (throw (ex-info (str "Unresolved qualified reference: " (pr-str kw)
                           " — no fn named " (pr-str (keyword (name kw)))
                           " in namespace " (pr-str (namespace kw)))
                      {:type :packages/unresolved-ref :ref kw})))
    kw))


(declare normalize-qualified-arg-value)


(defn- normalize-qualified-type-ref
  "Type-reference positions accept keywords, inline-composite maps
   (`{field type}`), and structural vectors (`[:list T]`, `[:refine B C]`,
   `[:union …]`, `[:fn {args} ret]`, `[:map K V]`, `[:tuple …]`) —
   normalize any qualified keyword found in type position, leaving
   refinement CONSTRAINT payloads (value world) untouched."
  [t name->id]
  (cond
    (keyword? t) (normalize-qref t name->id)
    (map? t) (into {} (map (fn [[k v]] [k (normalize-qualified-type-ref v name->id)])) t)
    (vector? t)
    (case (first t)
      :refine (assoc t 1 (normalize-qualified-type-ref (nth t 1) name->id))
      (:list :union :tuple :map) (into [(first t)]
                                       (map #(normalize-qualified-type-ref % name->id))
                                       (rest t))
      :fn (cond-> t
            (map? (nth t 1 nil)) (assoc 1 (normalize-qualified-type-ref (nth t 1) name->id))
            (>= (count t) 3) (assoc 2 (normalize-qualified-type-ref (nth t 2) name->id)))
      t)
    :else t))


(defn- normalize-qualified-arg-value
  "Normalize qualified references inside ONE arg-value, mirroring the
   shapes `arg-value->binding-fields` recognises. Literal payloads
   (`:value`, refinement constraints) are deliberately untouched — a
   qualified keyword there is user DATA, not a reference."
  [v name->id]
  (cond
    (keyword? v) (normalize-qref v name->id)
    (vector? v) (mapv #(normalize-qualified-arg-value % name->id) v)
    (map? v)
    (cond-> v
      (contains? v :ref)     (update :ref #(normalize-qref % name->id))
      (contains? v :type)    (update :type #(normalize-qualified-type-ref % name->id))
      (contains? v :append)  (update :append (fn [items] (mapv #(normalize-qualified-arg-value % name->id) items)))
      ;; inline anon fn-def in arg position
      (contains? v :parent)  (update :parent #(normalize-qref % name->id))
      (contains? v :parents) (update :parents (fn [ps] (mapv #(normalize-qref % name->id) ps)))
      (contains? v :args)    (update :args (fn [args]
                                             (into {}
                                                   (map (fn [[k av]] [k (normalize-qualified-arg-value av name->id)]))
                                                   args))))
    :else v))


(defn- normalize-qualified-refs
  "Per-ns migration stage 4 (ADR-identity-model.md): accept
   `:ns.path/name`-QUALIFIED reference keywords in every fn/type
   reference position of a fn-def — parents, arg refs, `{:ref …}`
   maps, sequence items, inline-anon bodies, `:return-type` and
   type-row member positions — validate the (namespace, name) pair
   against the dual-keyed `name->id`, and rewrite to the bare form.

   Normalizing (rather than threading qualified names further) is the
   deliberate stage-4 scope: while `validate-no-name-collisions!`
   still enforces GLOBAL name uniqueness, a qualified ref is exactly
   equivalent to its bare form — the win is authoring: self-documenting
   references that fail loud on a wrong namespace instead of silently
   resolving to a same-named fn elsewhere. Namespace-aware resolution
   through the type-checker's name world is stage 5."
  [fd name->id]
  (cond-> fd
    (contains? fd :parent)  (update :parent #(normalize-qref % name->id))
    (contains? fd :parents) (update :parents (fn [ps] (mapv #(normalize-qref % name->id) ps)))
    (contains? fd :args)    (update :args (fn [args]
                                            (into {}
                                                  (map (fn [[k av]] [k (normalize-qualified-arg-value av name->id)]))
                                                  args)))
    (contains? fd :return-type) (update :return-type #(normalize-qualified-type-ref % name->id))
    (contains? fd :type)    (update :type #(normalize-qualified-type-ref % name->id))
    (contains? fd :list)    (update :list #(normalize-qualified-type-ref % name->id))
    (contains? fd :union)   (update :union (fn [ts] (mapv #(normalize-qualified-type-ref % name->id) ts)))
    (contains? fd :refine)  (update-in [:refine :base] #(normalize-qref % name->id))
    (contains? fd :variant) (update :variant (fn [ts] (mapv #(normalize-qualified-type-ref % name->id) ts)))
    (contains? fd :fn-type) (update :fn-type (fn [[args ret]]
                                               [(normalize-qualified-type-ref args name->id)
                                                (normalize-qualified-type-ref ret name->id)]))))


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
   (let [;; Stage 4 — validate + rewrite `:ns.path/name`-qualified refs
         ;; to bare BEFORE anon expansion, so a qualified and a bare
         ;; spelling of the same ref hash to the SAME anon identity.
         ;; The validation map is dual-keyed from the RAW named defs
         ;; (+ prior syncs) — synthetic anon names are never the
         ;; target of a qualified ref.
         pre-name->id (merge extra-name->id
                             (into {}
                                   (comp (filter :name)
                                         (mapcat (fn [fd]
                                                   (let [id (ids/fn-id (:namespace fd) (:name fd))]
                                                     (cons [(:name fd) id]
                                                           (when-let [ns-path (:namespace fd)]
                                                             [[(keyword ns-path (name (:name fd))) id]]))))))
                                   module-fn-defs))
         module-fn-defs (mapv #(normalize-qualified-refs % pre-name->id) module-fn-defs)
         ;; Pre-pass: lift every inline `{:parent X :args Y}` map in
         ;; arg-value position into a synthetic `_anon-<hash>` fn-def
         ;; (deterministic, dedup'd by shape). The regular parser
         ;; then sees a longer list with all the anons as ordinary
         ;; named composed fn-defs.
         module-fn-defs (expand-inline-anons-in-module module-fn-defs)
         ;; Every named fn-def — including `:fn-type` declarations —
         ;; gets a deterministic fn-id by `(:namespace, :name)`.
         ;; `:fn-type` rows carry their structural shape in
         ;; `:constraint` (see parse-fn-def), so they take the
         ;; standard name→id path.
         ;; DUAL-keyed: every named def lands under its bare name AND
         ;; its namespace-qualified form (`:core.strings/upper`), so a
         ;; qualified ref resolves through the same map + `contains?`
         ;; fail-loud path as a bare one. Qualified refs don't depend
         ;; on global name uniqueness — per-ns migration stage 4
         ;; (ADR-identity-model.md).
         own-name->id (into {}
                            (comp (filter :name)
                                  (mapcat (fn [fd]
                                            (let [id (ids/fn-id (:namespace fd) (:name fd))]
                                              (cons [(:name fd) id]
                                                    (when-let [ns-path (:namespace fd)]
                                                      [[(keyword ns-path (name (:name fd))) id]]))))))
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
