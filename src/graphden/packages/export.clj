(ns graphden.packages.export
  "Inverse of `graphden.packages.records.parse` — turn a flat set of
   graph records (`:fn` / `:slot` / `:fn-slot` / `:binding` /
   `:binding-list-item`) back into fns.edn `fn-def` maps.

   This is the round-trip half of the package system: `parse-module`
   takes EDN → records, `records->fn-defs` takes records → EDN. We
   target RECORDS equality rather than EDN equality — the parser
   canonicalises many surface forms (a bare `10` and `{:value 10}`
   produce the same binding row; `:parent` and a single-element
   `:parents` produce the same parent-ids). The exporter emits ONE
   canonical surface form per record shape.

   ## Contract

   1. EXACT for every clean role — type-rows (record / refine / list /
      union / map / tuple / variant / fn-type), base-fns, and composed
      fns whose bindings are value / ref / type-override / list-append /
      required / PB' own-slot / scalar-rename / no-op-rename /
      positional-rename. (Verified by the unit fixtures, which assert
      `parse(export(parse(fns))) == parse(fns)`.)

   2. FIXPOINT after one round on the full package corpus:

        (= (parse-module (records->fn-defs recs))
           (parse-module (records->fn-defs (parse-module (records->fn-defs recs)))))

      i.e. the second and all later round-trips are bit-identical, and
      the exported EDN is stable. This is the property publish / install
      relies on — re-publishing an installed package never drifts.

   The first round-trip of a real graph normalises a small tail
   (~0.7% of records) in two behaviour-preserving ways:

   - **Anon-composite identity** — an inline composite whose FIELD type
     is structural (`{:started [:list :uuid] …}`) is keyed by a
     `shape-hash` of the author's pre-degradation form, but storage only
     keeps the degraded field type (`:sequence`). The anonymous fn-id is
     thus unrecoverable from records; it re-hashes to a new (equivalent)
     anonymous identity. Named types and inline `[:fn …]` (whose form is
     kept in `:constraint`) are unaffected.
   - **HOF owner-disambiguation** — `resolve-slot-owner` resolves an
     equivalent binding (`{:coll :some-filter}`) to a different OWNER
     fn-slot depending on the surrounding def set. The exported EDN is
     the author's own form; the parser simply re-picks. The binding
     still binds the same ref to the same logical arg.

   ## Why records-level, not storage-level (yet)

   Storage entities ARE records (same keyword keys), modulo three
   transforms the storage layer owns: JSONB key-keywordisation,
   `impl-hash` materialisation (sentinel → real SHA), and namespace-id
   resolution. The pure core here works on raw records so it is
   testable with no DB; a thin `graph->records` adapter (forthcoming)
   will read live entities and normalise them into this shape.

   ## Relationship to `crud.type-check/reconstruct-fn-def`

   That helper also turns stored rows into an EDN fn-def, but only for
   COMPOSED fns and only as much as the type-checker needs (it drops the
   list-closed distinction, PB' own-slots, type-rows, namespace, and
   effects). This namespace is the faithful, all-roles inverse; the two
   should eventually converge on this core."
  (:require
    [graphden.packages.records.ids :as ids]))


;; =============================================================================
;; Indexing + reverse maps
;; =============================================================================

(def ^:private prim-id->kw
  "Reverse of `ids/primitive-fn-ids` — primitive fn-id → keyword."
  (into {} (map (fn [[k v]] [v k])) (ids/primitive-fn-ids)))


(defn- index-records
  "Group a flat record seq into the lookup maps the exporter needs."
  [records]
  (let [by-kind (group-by :kind records)]
    {:fns      (into {} (map (juxt :id identity)) (:fn by-kind))
     :slots    (into {} (map (juxt :id identity)) (:slot by-kind))
     :fn-slots (group-by :fn-id (:fn-slot by-kind))
     :bindings (group-by :fn-id (:binding by-kind))
     :items    (group-by :binding-id (:binding-list-item by-kind))}))


(defn- slots-of
  "Ordered slot records exposed by `fn-id`, sorted by fn-slot position."
  [fn-id ctx]
  (->> (get-in ctx [:fn-slots fn-id])
       (sort-by :position)
       (keep (fn [fs] (get-in ctx [:slots (:slot-id fs)])))
       vec))


;; =============================================================================
;; Reverse type-reference resolution (id → EDN type ref)
;; =============================================================================

(declare ^:private reconstruct-record-shape)


(defn- id->type-ref
  "Reverse of `records.types/resolve-type-ref`: a `type-fn-id` → the
   EDN type reference an author would have written. Primitives become
   their keyword; named type-rows become a keyword ref (`:my-type`);
   anonymous `[:fn …]` rows emit their structural constraint verbatim
   (so re-parse hashes to the same anonymous id); anonymous inline
   composites reconstruct their `{field type}` shape recursively."
  [id ctx]
  (cond
    (nil? id) :any
    (contains? prim-id->kw id) (prim-id->kw id)
    :else
    (let [fnr (get-in ctx [:fns id])]
      (cond
        (nil? fnr) :any
        (:name fnr) (keyword (:name fnr))
        (and (vector? (:constraint fnr)) (= :fn (first (:constraint fnr))))
        (:constraint fnr)
        (seq (slots-of id ctx)) (reconstruct-record-shape id ctx)
        :else :any))))


(defn- reconstruct-record-shape
  "Rebuild an inline-composite `{field-name field-type …}` map from an
   anonymous record-fn's slots, in position order. Emitted as an
   array-map so re-parse assigns the same fn-slot positions (slot/
   fn-slot IDs are position-independent, but record EQUALITY includes
   `:position`)."
  [fn-id ctx]
  (apply array-map
         (mapcat (fn [s] [(keyword (:name s)) (id->type-ref (:type-fn-id s) ctx)])
                 (slots-of fn-id ctx))))


;; =============================================================================
;; Type-row emitters (record / refine / list / union / map / tuple /
;; variant / fn-type)
;; =============================================================================

(defn- arg-spec
  "Surface form for a declared slot (base-fn arg or record field): a
   bare type keyword when `required` is the default (true) and there is
   no description, else the explicit `{:type T :required B :description
   D}` map. Mirrors `emit-composite-records`' defaults so the round-trip
   is exact."
  [slot ctx]
  (let [t (id->type-ref (:type-fn-id slot) ctx)
        req (:required slot)
        desc (:description slot)]
    (if (and (not= req false) (nil? desc))
      t
      (cond-> {:type t}
        (= req false) (assoc :required false)
        (some? desc)  (assoc :description desc)))))


(defn- record-shape
  "`{field-name arg-spec …}` array-map for a record-type / base-fn's
   own slots, in position order."
  [fn-id ctx]
  (apply array-map
         (mapcat (fn [s] [(keyword (:name s)) (arg-spec s ctx)])
                 (slots-of fn-id ctx))))


(defn- pb-own-slot-spec
  "Surface form for a composed fn-def's PB' own-slot declaration. Unlike
   `arg-spec`, this ALWAYS emits the explicit `{:type T …}` map — the
   parser's `own-slot-declaration?` only recognises an own-slot when the
   arg-value is a map carrying `:type`; a bare keyword would re-parse as
   a binding on a non-existent inherited slot."
  [slot ctx]
  (cond-> {:type (id->type-ref (:type-fn-id slot) ctx)}
    (= (:required slot) false) (assoc :required false)
    (some? (:description slot)) (assoc :description (:description slot))))


(defn- export-type-row
  "Emit the type-defining key for a non-composed fn-row, dispatching on
   the role-table discriminators. Returns a partial fn-def map (without
   :name / :namespace / :description, which the caller adds)."
  [fnr ctx]
  (let [c (:constraint fnr)]
    (cond
      ;; refinement: base-fn-id + scalar constraint
      (:base-fn-id fnr)
      {:refine {:base (id->type-ref (:base-fn-id fnr) ctx) :constraint c}}

      ;; list: element-fn-id set
      (:element-fn-id fnr)
      {:list (id->type-ref (:element-fn-id fnr) ctx)}

      ;; structural constraint vectors
      (and (vector? c) (= :union (first c))) {:union (vec (rest c))}
      (and (vector? c) (= :tuple (first c))) {:tuple (vec (rest c))}
      (and (vector? c) (= :variant (first c))) {:variant (vec (rest c))}
      (and (vector? c) (= :map (first c)))
      {:map {:key (nth c 1) :value (nth c 2)}}
      (and (vector? c) (= :fn (first c)))
      {:fn-type [(nth c 1 {}) (nth c 2 nil)]}

      ;; base-fn: has impl-hash (sentinel or real). Args from slots.
      (:impl-hash fnr)
      (cond-> {:args (record-shape (:id fnr) ctx)}
        (:return-type-fn-id fnr)
        (assoc :return-type (id->type-ref (:return-type-fn-id fnr) ctx)))

      ;; record-type: own slots, no markers
      (seq (slots-of (:id fnr) ctx))
      {:type (record-shape (:id fnr) ctx)}

      :else {})))


;; =============================================================================
;; Composed fn-def reconstruction (parents + :args)
;; =============================================================================

(defn- meaningful-binding?
  "True iff a binding row carries any authored field — i.e. it is NOT
   the empty companion row that an `{:as …}` rename leaves on its
   source slot."
  [b]
  (or (:value-present b)
      (:ref-fn-id b)
      (:type-override-fn-id b)
      (:list-append b)
      (some? (:required b))))


(defn- item->edn
  "Reverse of `parse/item->record` — a binding-list-item → its EDN
   element. Refs become a bare keyword (canonical; `{:ref n}` and bare
   produce the same row).

   Map values are emitted BARE when they carry neither `:ref` nor
   `:value` at top level — this is load-bearing for POSITIONAL renames
   (`:items [… {:as :x}]`): the bare `{:as :x}` map both stores as the
   same list-item value AND lets `collect-exposed-names` re-derive the
   positional-rename slot. Maps that DO carry `:ref`/`:value` (and bare
   keyword values, which would be read as a fn-ref) are wrapped in
   `{:value …}` to survive re-parse intact. Plain scalars stay bare."
  [item ctx]
  (let [v (:value item)]
    (cond
      (:ref-fn-id item) (keyword (:name (get-in ctx [:fns (:ref-fn-id item)])))
      (and (map? v) (not (contains? v :ref)) (not (contains? v :value))) v
      (or (keyword? v) (map? v)) {:value v}
      :else v)))


(defn- binding-items
  [b ctx]
  (->> (get-in ctx [:items (:id b)])
       (sort-by :position)
       (mapv #(item->edn % ctx))))


(defn- binding-field-map
  "Binding fields in explicit MAP form (`{:ref … :value … :append … :type
   … :required …}`). Used when an `{:as …}` rename forces the map shape
   so the rename target and the binding fields can share one arg-value.
   `:type` comes from the binding's OWN type-override — NOT the rename
   slot's type, which may be an ancestor-pinned default that re-parse
   re-derives on its own (emitting it would spuriously set a binding
   type-override the author never wrote)."
  [b ctx]
  (let [type-ref (when (:type-override-fn-id b)
                   (id->type-ref (:type-override-fn-id b) ctx))]
    (cond-> {}
      (:ref-fn-id b)
      (assoc :ref (keyword (:name (get-in ctx [:fns (:ref-fn-id b)]))))
      (:value-present b) (assoc :value (:value b))
      (:list-append b) (assoc :append (binding-items b ctx))
      (and (:list-append b) (:list-closed b)) (assoc :closed true)
      type-ref (assoc :type type-ref)
      (some? (:required b)) (assoc :required (:required b)))))


(defn- binding->arg-value
  "Reverse a binding row (+ its list items) into the EDN arg-value that
   re-parses to the same record. Emits the canonical surface form for
   each field combination. `arg-name` is the target slot's name — used
   to disambiguate a type-override-only binding (which must stay a
   binding, not become a PB' own-slot)."
  [b arg-name ctx]
  (let [items (binding-items b ctx)
        type-ref (when (:type-override-fn-id b)
                   (id->type-ref (:type-override-fn-id b) ctx))]
    (cond
      ;; list-append binding. list-closed nil → bare vector (parse's
      ;; bare-vector-on-sequence-slot path leaves list-closed nil);
      ;; false/true → explicit {:append … :closed …} map.
      (:list-append b)
      (if (nil? (:list-closed b))
        items
        (cond-> {:append items}
          (:list-closed b) (assoc :closed true)))

      ;; ref binding (optionally with a type-override / required marker)
      (:ref-fn-id b)
      (let [ref-name (keyword (:name (get-in ctx [:fns (:ref-fn-id b)])))]
        (if (or type-ref (some? (:required b)))
          (cond-> {:ref ref-name}
            type-ref (assoc :type type-ref)
            (some? (:required b)) (assoc :required (:required b)))
          ref-name))

      ;; literal value (value-present). Always {:value …} — safe against
      ;; keyword/fn-name collisions, and re-parses to value-present.
      (:value-present b)
      (cond-> {:value (:value b)}
        type-ref (assoc :type type-ref)
        (some? (:required b)) (assoc :required (:required b)))

      ;; metadata-only binding (type-override and/or required narrowing,
      ;; no value/ref/append). A bare `{:type T}` would be read by the
      ;; parser as a PB' OWN-SLOT declaration — it must carry a binding
      ;; marker to stay a binding on the inherited slot. The no-op
      ;; `:as <arg-name>` is exactly what authors write for this; it
      ;; pins the override without minting a rename slot (same name →
      ;; `collect-exposed-names` ignores it).
      (or type-ref (some? (:required b)))
      (cond-> {:as arg-name}
        type-ref (assoc :type type-ref)
        (some? (:required b)) (assoc :required (:required b)))

      :else {})))


(defn- export-composed
  "Reconstruct a composed fn-def's `:parent`/`:parents` + `:args` map.
   `:args` is assembled from three record sources:
     1. rename slots (`:as`) — own slots with a `:source-slot-id` and a
        companion empty binding on that source slot;
     2. PB' own-slot declarations — own slots with no companion binding;
     3. bindings on inherited slots — keyed by the target slot's name."
  [fnr ctx]
  (let [own-id (:id fnr)
        own-slots (slots-of own-id ctx)
        bindings (get-in ctx [:bindings own-id])
        binding-by-slot (into {} (map (juxt :slot-id identity)) bindings)
        ;; rename slots: own slot whose source-slot-id has a companion
        ;; binding on this fn — that binding pairs with the rename's
        ;; source. PB' enrichment also sets source-slot-id but leaves NO
        ;; binding on the source, so the binding check discriminates the
        ;; two. `rename-by-source` maps source-slot-id → exposed name.
        rename-slot? (fn [s]
                       (and (:source-slot-id s)
                            (contains? binding-by-slot (:source-slot-id s))))
        rename-slots (filter rename-slot? own-slots)
        rename-by-source (into {}
                               (map (fn [s] [(:source-slot-id s) (keyword (:name s))]))
                               rename-slots)
        ;; Positional-rename slots: own slots exposed by an `{:as X}`
        ;; INSIDE a list binding's items (source-slot-id nil — the source
        ;; is a list position, not a single slot). Their name appears as
        ;; `:as` in some list-item value map. They are reconstructed from
        ;; the bare `{:as X}` list item (via `item->edn` +
        ;; `collect-exposed-names`), NOT as a PB' own-slot declaration.
        positional-names
        (into #{}
              (comp (mapcat (fn [b] (get-in ctx [:items (:id b)])))
                    (keep (fn [it]
                            (let [v (:value it)]
                              (when (and (map? v) (:as v))
                                (some-> (:as v) keyword))))))
              bindings)
        positional-rename? (fn [s]
                             (and (nil? (:source-slot-id s))
                                  (contains? positional-names (keyword (:name s)))))
        ;; PB' own-slots = own slots that are neither scalar- nor
        ;; positional-rename slots.
        pb-args
        (into {}
              (map (fn [s] [(keyword (:name s)) (pb-own-slot-spec s ctx)]))
              (remove (some-fn rename-slot? positional-rename?) own-slots))
        ;; One arg-value per binding, keyed by the (source) slot's name.
        ;; When the slot is a rename source, force the map form and graft
        ;; `:as <exposed>` on top of the binding's fields — so a rename
        ;; that ALSO carries a ref / value / type round-trips as a single
        ;; `{:as … :ref …}` entry instead of two clobbering ones.
        binding-args
        (into {}
              (keep (fn [b]
                      (when-let [slot (get-in ctx [:slots (:slot-id b)])]
                        (let [arg-name (keyword (:name slot))]
                          (if-let [exposed (get rename-by-source (:slot-id b))]
                            [arg-name (assoc (binding-field-map b ctx) :as exposed)]
                            ;; A non-meaningful binding is the empty row a
                            ;; no-op `{:as <same-name>}` leaves on an
                            ;; inherited slot (an explicit free-arg
                            ;; re-exposure). Reproduce it with the same
                            ;; no-op `:as` so the empty binding round-trips.
                            [arg-name (if (meaningful-binding? b)
                                        (binding->arg-value b arg-name ctx)
                                        {:as arg-name})]))))
                    bindings))
        parent-names (mapv (fn [pid] (keyword (:name (get-in ctx [:fns pid]))))
                           (:parent-ids fnr))
        args (merge pb-args binding-args)]
    (cond-> (if (= 1 (count parent-names))
              {:parent (first parent-names)}
              {:parents parent-names})
      (seq args) (assoc :args args)
      (:return-type-fn-id fnr)
      (assoc :return-type (id->type-ref (:return-type-fn-id fnr) ctx)))))


;; =============================================================================
;; Top-level
;; =============================================================================

(defn- fn-row->fn-def
  "One stored fn-row → its fns.edn fn-def map. Anonymous rows (no name)
   are NOT emitted as top-level defs — they are inlined into type refs
   by `id->type-ref`."
  [fnr ctx]
  (let [body (if (seq (:parent-ids fnr))
               (export-composed fnr ctx)
               (export-type-row fnr ctx))]
    (cond-> (assoc body :name (keyword (:name fnr)))
      (:namespace-id fnr) (assoc :namespace (:namespace-id fnr))
      (:description fnr)   (assoc :description (:description fnr))
      ;; `[]` is truthy to the parser's `attach-fn-meta`, so an empty
      ;; effect list is a present key that must round-trip — gate on
      ;; key presence, not `seq`.
      (contains? fnr :expects-effects)
      (assoc :expects-effects (mapv keyword (:expects-effects fnr)))
      (contains? fnr :branch-local?)
      (assoc :branch-local? (:branch-local? fnr)))))


(defn records->fn-defs
  "Convert a flat record seq back into a vector of fns.edn fn-def maps.
   Inverse of `records.parse/parse-module` at the records level:

     (= (parse-module (records->fn-defs (parse-module fns)))
        (parse-module fns))

   Skips primitive rows and anonymous (name-less) rows — the former are
   boot data, the latter are reconstructed inline at their use sites."
  [records]
  (let [ctx (index-records records)]
    (->> (vals (:fns ctx))
         (remove #(contains? prim-id->kw (:id %)))
         (filter :name)
         (mapv #(fn-row->fn-def % ctx)))))
