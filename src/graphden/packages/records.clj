(ns graphden.packages.records
  "Pure conversion of fns.edn entries into records of the new model
   (`fn`, `slot`, `fn-slot`, `binding`, `binding-list-item`).

   Each top-level fn-def in a fns.edn is one of these forms:

     ;; base-fn (impl in impls.clj):
     {:name :http-server
      :args {:handler {:type :fn} :port {:type :port}}
      :return-type :any}

     ;; record-type (no impl, slots define the shape):
     {:name :ring-response-shape
      :type {:status :http-status :headers :jsonb :body :text}}

     ;; refinement-type:
     {:name :positive-int
      :refine {:base :int :constraint [:> 0]}}

     ;; list-type:
     {:name :int-list
      :list :int}

     ;; composed fn-def (parent → bindings):
     {:name :default-auth-fail-response
      :parent :ring-response-shape
      :args {:status 401 :headers {} :body \"Unauthorized\"}}

   This module ONLY parses syntactic shapes and produces records. It
   does NOT touch storage, type-checker, or executor — those live in
   composition layer (rewrite forthcoming). Records are tagged maps:

     {:kind :fn …}
     {:kind :slot …}
     {:kind :fn-slot …}
     {:kind :binding …}
     {:kind :binding-list-item …}

   Each record has `:id` deterministic from its identity-tuple, so
   re-running the parser on the same EDN gives the same UUIDs (allowing
   idempotent upserts).

   ## Roles (matches schema.clj table)

   | parent-ids | impl-hash | base-fn-id | element-fn-id | constraint | fn-slot rows | Role |
   | empty      | NOT NULL  | NULL       | NULL          | NULL       | *            | base-fn |
   | empty      | NULL      | NULL       | NULL          | NULL       | NOT empty    | record-type |
   | empty      | NULL      | NOT NULL   | NULL          | NOT NULL   | empty        | refinement-type |
   | empty      | NULL      | NULL       | NOT NULL      | NULL       | empty        | list-type |
   | NOT empty  | *         | *          | *             | *          | *            | composed fn-def |
  "
  (:require
    [clojure.string :as str])
  (:import
    (java.nio.charset
      StandardCharsets)
    (java.security
      MessageDigest)
    (java.util
      UUID)))


;; =============================================================================
;; UUID v5 (deterministic, name-based)
;; =============================================================================

(def ^:private records-namespace-uuid
  "Stable namespace UUID for records produced by this parser. Changing
   it invalidates EVERY row's id — only do that during full rebuild."
  #uuid "f0a3b8c2-7e9d-4a1c-9f8b-3d4e5f6a7b8c")


(defn- uuid-v5
  "UUID v5 (SHA-1 of namespace || name). Deterministic — same inputs →
   same output."
  ^UUID [namespace-uuid name-str]
  (let [ns-bytes (let [arr (byte-array 16)
                       buf (java.nio.ByteBuffer/wrap arr)]
                   (java.nio.ByteBuffer/.putLong buf (UUID/.getMostSignificantBits namespace-uuid))
                   (java.nio.ByteBuffer/.putLong buf (UUID/.getLeastSignificantBits namespace-uuid))
                   arr)
        name-bytes (String/.getBytes name-str StandardCharsets/UTF_8)
        digest (doto (MessageDigest/getInstance "SHA-1")
                 (MessageDigest/.update ns-bytes)
                 (MessageDigest/.update name-bytes))
        hash-bytes (MessageDigest/.digest digest)]
    (aset hash-bytes 6 (unchecked-byte (bit-or (bit-and (aget hash-bytes 6) 0x0f) 0x50)))
    (aset hash-bytes 8 (unchecked-byte (bit-or (bit-and (aget hash-bytes 8) 0x3f) 0x80)))
    (let [buf (java.nio.ByteBuffer/wrap hash-bytes 0 16)
          msb (java.nio.ByteBuffer/.getLong buf)
          lsb (java.nio.ByteBuffer/.getLong buf)]
      (UUID. msb lsb))))


(defn fn-id
  "Deterministic UUID for a globally-named fn. `ns-path` is the
   module's `:namespace` string (e.g. \"core.system\") or nil for
   namespace-less. `fn-name` is a keyword."
  ^UUID [ns-path fn-name]
  (uuid-v5 records-namespace-uuid (str "fn:" (or ns-path "") "/" (name fn-name))))


(defn anonymous-fn-id
  "Deterministic UUID for an anonymous (composite) fn keyed by the
   shape-hash. Anonymous types with the same shape collapse to one
   row via `fn.anonymous_hash` UNIQUE."
  ^UUID [shape-hash]
  (uuid-v5 records-namespace-uuid (str "anon-fn:" shape-hash)))


(defn slot-id
  "Deterministic UUID for a slot owned by a specific fn. Slots are
   immutable once created; sharing across fns happens via composition
   rows referencing the same slot-id (future optimization — for now
   each fn owns its own slot rows)."
  ^UUID [owner-fn-id slot-name]
  (uuid-v5 records-namespace-uuid
           (str "slot:" owner-fn-id ":" (name slot-name))))


(defn fn-slot-id
  "Deterministic UUID for a fn-slot junction row."
  ^UUID [own-fn-id own-slot-id]
  (uuid-v5 records-namespace-uuid
           (str "fn-slot:" own-fn-id ":" own-slot-id)))


(defn binding-id
  "Deterministic UUID for a binding (fn × slot)."
  ^UUID [own-fn-id own-slot-id]
  (uuid-v5 records-namespace-uuid
           (str "binding:" own-fn-id ":" own-slot-id)))


(defn binding-list-item-id
  "Deterministic UUID for a binding's ordered list item."
  ^UUID [owner-binding-id position]
  (uuid-v5 records-namespace-uuid
           (str "list-item:" owner-binding-id ":" position)))


;; =============================================================================
;; Anonymous-shape hashing
;; =============================================================================

(defn digest-hex
  "Lower-case hex digest of `s` under `algo` (e.g. \"SHA-1\", \"SHA-256\").
   Shared by both shape-dedup hashing here and impl-hash computation in
   the executor registry."
  [algo s]
  (let [digest (MessageDigest/getInstance algo)
        utf-bytes (String/.getBytes ^String s StandardCharsets/UTF_8)
        hash-bytes (MessageDigest/.digest digest utf-bytes)]
    (str/join (map #(format "%02x" (bit-and ^byte % 0xff)) hash-bytes))))


(defn- shape-hash
  "Stable hash of a shape — sorted (slot-name, type-keyword) pairs. Used
   to dedupe anonymous composites that have identical structure across
   different declarations."
  [shape-map]
  (digest-hex "SHA-1"
              (pr-str (->> shape-map
                           (into (sorted-map))
                           (mapv (fn [[k v]] [(name k) (pr-str v)]))))))


;; =============================================================================
;; Primitive boot-data
;; =============================================================================

(def primitive-names
  "14 primitive types pre-seeded as fn-rows on startup. Each becomes a
   leaf in the type tree — slots reference these via `slot.type-fn-id`."
  [:null :uuid :text :int :bool :numeric :timestamptz :jsonb :bytes
   :any :fn :sequence :keyword :float])


(defn primitive-fn-id
  "Deterministic fn-id for one of the 14 primitive types. Public so
   callers (loader, system/core for `:fn-type` aliases) can resolve
   a primitive keyword to the fn-id without going through the full
   `name->id` map."
  ^UUID [primitive-name]
  (uuid-v5 records-namespace-uuid
           (str "primitive:" (name primitive-name))))


(defn primitive-fn-ids
  "Map `{primitive-keyword → fn-id}` for the 14 base primitives."
  []
  (into {}
        (map (fn [p] [p (primitive-fn-id p)]))
        primitive-names))


(defn boot-primitive-records
  "Records to upsert at startup for the 14 primitive types. Each is a
   bare fn-row — name only, no slots/parents/impl/constraint."
  []
  (mapv (fn [p]
          {:kind :fn
           :id (primitive-fn-id p)
           :name (name p)
           :namespace-id nil
           :parent-ids []
           :impl-hash nil
           :base-fn-id nil
           :element-fn-id nil
           :return-type-fn-id nil
           :anonymous-hash nil
           :constraint nil
           :description (str "Primitive type :" (name p) ".")})
        primitive-names))


;; =============================================================================
;; Type-reference resolution
;; =============================================================================

(defn- type-spec-map?
  "True iff `m` is the loader's expanded `{:type T :required B …}`
   shape (vs an inline-composite map `{:k :int}`). The presence of
   `:type` is the discriminator — record-types are pure {field-name
   field-type} maps with no `:type` key."
  [m]
  (and (map? m) (contains? m :type)))


(defn- resolve-type-ref
  "Resolve a `:type T` reference in EDN to a fn-id. T is one of:
   - primitive keyword (e.g. `:int`) → primitive's fn-id
   - named-fn keyword (e.g. `:ring-response-shape`) → fn-id by name
   - type-spec map `{:type T :required …}` → recurse on T (loader's
     expanded shape — strips required/description metadata)
   - inline composite map `{:foo :int :bar :text}` → anonymous fn-id
     by shape-hash (caller is expected to also emit the anonymous
     fn / slot / fn-slot records elsewhere)
   - structural fn type `[:fn args ret]` → primitive `:fn`
   - structural list type `[:list T]` → primitive `:sequence`
   - structural refinement `[:refine B C]` → recurse on base
   - structural union `[:union …]` → primitive `:any`
   - type-var symbol `'a` → primitive `:any`

   The structural degradations match the storage `value-kind` enum —
   the rich shape is preserved separately in the in-memory rich-types
   registry maintained by the registry layer.

   `name->id` map: known fn-name keywords already-assigned (to fn-id).
   Returns the fn-id (UUID) if resolvable, or throws on unknown."
  [t name->id]
  (cond
    (and (keyword? t) (some #{t} primitive-names))
    (primitive-fn-id t)

    (keyword? t)
    (or (get name->id t)
        (throw (ex-info (str "Unknown type reference: " (pr-str t))
                        {:type :records/unknown-type-ref
                         :ref t})))

    ;; Type-var symbol → :any (storage degradation).
    (symbol? t)
    (primitive-fn-id :any)

    (type-spec-map? t)
    (recur (:type t) name->id)

    ;; Inline composite — id by shape-hash; caller is responsible for
    ;; emitting the corresponding fn / slot / fn-slot records too.
    (map? t)
    (anonymous-fn-id (shape-hash t))

    (vector? t)
    (case (first t)
      ;; Inline `[:fn args ret]` — one anonymous fn-row per shape.
      ;; `shape-hash` expects a map, so we hash the pr-str of the
      ;; full vector (same digest scheme); the corresponding row is
      ;; emitted by `inline-fn-type-rows-from-fn-def` during the
      ;; module pass. Slot's `:type-fn-id` lands on this id instead
      ;; of the primitive `:fn` UUID, so the editor recovers the
      ;; structural form via the row's `:constraint`.
      :fn     (anonymous-fn-id (digest-hex "SHA-1" (pr-str t)))
      :list   (primitive-fn-id :sequence)
      :refine (recur (second t) name->id)
      :union  (primitive-fn-id :any)
      (throw (ex-info (str "Unsupported structural type: " (pr-str t))
                      {:type :records/unsupported-type-ref
                       :ref t})))

    :else
    (throw (ex-info (str "Unsupported type reference shape: " (pr-str t))
                    {:type :records/unsupported-type-ref
                     :ref t}))))


;; =============================================================================
;; Inline fn-type synthesis
;; =============================================================================
;;
;; The slot/binding model has no place to stash a structural
;; `[:fn args ret]` shape on a slot — `:type-fn-id` is a single FK, and
;; up to commit fix(types): variant desugar this FK landed on the
;; primitive `:fn` row whenever EDN declared an inline fn-type. The
;; structural inner names were thrown away at parse time, so the
;; editor's chip read "fn" with no way to recover args / ret.
;;
;; Mirroring how unions / variants stash their payload (anonymous
;; fn-row whose `:constraint` carries the structural shape, slot
;; points at THIS row): every inline `[:fn args ret]` reference
;; produces an anonymous fn-row with `:constraint [:fn args ret]`.
;; `resolve-type-ref` returns the same deterministic id for the
;; same shape, so two slots typed `[:fn {:request :ring-request}
;; :ring-response]` collapse to one row.

(defn- inline-fn-type-rows-from-form
  "Walk an EDN type-form, collecting fn-rows for every nested
   `[:fn args ret]` reference. Recurses through compound forms
   (`[:list T]`, `[:refine B C]`, record maps) so a fn-type buried
   inside a compound shape still gets a row.

   Returns a vector of {:kind :fn …} maps; caller appends to its
   own records output."
  [form]
  (cond
    (and (vector? form) (= :fn (first form)) (>= (count form) 3))
    (let [shape form
          ;; `shape-hash` expects a MAP; `[:fn args ret]` is a vector,
          ;; so hash the printed form directly. Same shape → same id
          ;; via SHA-1 of canonical pr-str (keyword args / nested
          ;; types serialise deterministically because clojure
          ;; orders map keys in pr-str when keys are simple).
          h (digest-hex "SHA-1" (pr-str shape))
          id (anonymous-fn-id h)
          self-row {:kind :fn
                    :id id
                    :name nil
                    :namespace-id nil
                    :parent-ids []
                    :impl-hash nil
                    :base-fn-id nil
                    :element-fn-id nil
                    :return-type-fn-id nil
                    :anonymous-hash h
                    :constraint shape
                    :description nil}
          ;; Args / ret may themselves contain inline fn-types
          ;; (a HOF's `:f :next-handler` returning another fn).
          inner (concat (mapcat inline-fn-type-rows-from-form
                                (vals (or (nth shape 1 nil) {})))
                        (inline-fn-type-rows-from-form (nth shape 2 nil)))]
      (into [self-row] inner))

    ;; Compound forms — recurse into the relevant branch(es).
    (and (vector? form) (#{:list :refine} (first form)))
    (inline-fn-type-rows-from-form (nth form 1 nil))

    (and (vector? form) (= :union (first form)))
    (mapcat inline-fn-type-rows-from-form (rest form))

    ;; Type-spec map `{:type T :required B}` — recurse on `:type`.
    (type-spec-map? form)
    (inline-fn-type-rows-from-form (:type form))

    ;; Record / inline-composite map — recurse over each value.
    (map? form)
    (mapcat inline-fn-type-rows-from-form (vals form))

    :else nil))


(defn- inline-fn-type-rows-from-fn-def
  "Collect fn-rows for every inline `[:fn args ret]` reachable from a
   single fn-def's type-bearing fields (`:args` map values,
   `:return-type`, `:type` for record-types, `:refine`, `:list`).
   Result is deduplicated by id so the same shape mentioned in two
   places produces one row."
  [fn-def]
  (let [args-vals (when (map? (:args fn-def)) (vals (:args fn-def)))
        type-form (:type fn-def)
        type-vals (cond
                    (map? type-form) (vals type-form)
                    (some? type-form) [type-form])
        refine-base (when (map? (:refine fn-def)) (:base (:refine fn-def)))
        forms (concat args-vals
                      [(:return-type fn-def)]
                      type-vals
                      (when refine-base [refine-base])
                      [(:list fn-def)]
                      (when-let [ft (:fn-type fn-def)]
                        ;; `:fn-type [args ret]` shape — wrap so the
                        ;; recursion sees the inner args / ret.
                        (when (and (sequential? ft) (= 2 (count ft)))
                          [(into [:fn] ft)])))]
    (->> forms
         (mapcat inline-fn-type-rows-from-form)
         (distinct))))


;; =============================================================================
;; Per-form parsers — produce records from one fn-def EDN entry
;; =============================================================================

(declare parse-fn-def)


(defn- emit-composite-records
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
                                       (not (type-spec-map? field-type)))
                [type-fn-id sub-records]
                (if inline-composite?
                  (let [inline-id (anonymous-fn-id (shape-hash field-type))
                        inline-fn {:kind :fn
                                   :id inline-id
                                   :name nil
                                   :namespace-id nil
                                   :parent-ids []
                                   :impl-hash nil
                                   :base-fn-id nil
                                   :element-fn-id nil
                                   :return-type-fn-id nil
                                   :anonymous-hash (shape-hash field-type)
                                   :constraint nil
                                   :description nil}]
                    ;; (no :required for inline composite anon-fn rows)
                    [inline-id
                     (into [inline-fn]
                           (emit-composite-records inline-id field-type name->id))])
                  [(resolve-type-ref field-type name->id) []])

                slot-description (when (type-spec-map? field-type)
                                   (:description field-type))
                slot-required (cond
                                (type-spec-map? field-type)
                                (if (contains? field-type :required)
                                  (:required field-type)
                                  true)
                                :else true)
                slot (slot-id owner-fn-id field-name)
                fn-slot (fn-slot-id owner-fn-id slot)]
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


(defn- parse-base-fn
  "A fn-def with `:args` declaration and an impl is a base-fn. The
   args become slot/fn-slot rows."
  [{:keys [args return-type description]
    fn-name :name ns-id :namespace} name->id]
  (let [own-id (fn-id ns-id fn-name)
        ret-id (when return-type (resolve-type-ref return-type name->id))
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


(defn- parse-record-type
  "`{:name :foo :type {:k T …}}` — record-type with the given fields."
  [{:keys [description]
    fn-name :name ns-id :namespace shape :type} name->id]
  (let [own-id (fn-id ns-id fn-name)
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


(defn- parse-refinement
  "`{:name :foo :refine {:base T :constraint C}}` — refinement-type.

   Emits a fn-row with `base-fn-id` + `constraint` set, plus a single
   `:value` slot whose type points at the base. The synthesised impl
   in the registry layer reads `(:value args)`, so the slot must exist
   for composed children to bind it via `:args {:value …}`."
  [{:keys [refine description]
    fn-name :name ns-id :namespace} name->id]
  (let [own-id (fn-id ns-id fn-name)
        base-id (resolve-type-ref (:base refine) name->id)
        value-slot (slot-id own-id "value")
        value-fn-slot (fn-slot-id own-id value-slot)]
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


(defn- parse-list-type
  "`{:name :foo :list T}` — list-type with the given element type.

   Emits a fn-row plus a single `:items` slot (list-typed) so children
   can bind concrete sequence content via `:args {:items {:append [...]}}`."
  [{:keys [description]
    fn-name :name ns-id :namespace element-type :list} name->id]
  (let [own-id (fn-id ns-id fn-name)
        element-id (resolve-type-ref element-type name->id)
        items-slot (slot-id own-id "items")
        items-fn-slot (fn-slot-id own-id items-slot)]
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
      :type-fn-id (primitive-fn-id :sequence)
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
  (let [own-id (fn-id ns-id fn-name)]
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
    (let [own-id (fn-id ns-id fn-name)]
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
      (mapv (fn [pn]
              (or (get name->id pn)
                  (throw (ex-info (str "Unknown parent: " (pr-str pn))
                                  {:type :records/unknown-parent
                                   :name (:name fn-def)
                                   :parent pn}))))
            parent-names))))


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

(defn- type-row-arg-names
  "Set of arg-names a fn-def directly declares slots for (base-fn,
   record-type, refinement, list-type)."
  [fn-def]
  (cond
    (:type fn-def)   (set (keys (:type fn-def)))
    (:refine fn-def) #{:value}
    (:list fn-def)   #{:items}
    (and (:args fn-def) (not (:parent fn-def)) (not (:parents fn-def)))
    (set (keys (:args fn-def)))
    :else            #{}))


(defn- arg-spec-type
  "Pull the type keyword out of a base-fn's `:args` value — recognises
   bare keywords, `{:type T :required B}` spec maps, and falls back
   to `:any` when the shape is unfamiliar."
  [arg-spec]
  (cond
    (keyword? arg-spec) arg-spec
    (and (map? arg-spec) (:type arg-spec)) (:type arg-spec)
    :else :any))


(defn- slot-type-of
  "Find the declared type of `[owner-name owner-arg]`. Inspects the
   owner's type-row / base-fn / record / refinement / list shape.
   Returns a type keyword like `:sequence` / `:int` / `:any` / nil."
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
      :else nil)))


(defn- rename-target
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


(defn- chain-of
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


(defn- ref-targets-of
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


(defn- rename-passthrough-ref
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


(defn- resolve-slot-owner-strict
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


(defn- resolve-slot-owner
  "Find `[owner-name slot-name]` for the slot that `composed-fn-name`
   targets when binding `arg-name`.

   Two-pass walk:
   - Pass 1: inheritance chain (parent-ids). Direct slot-name match
     on a type-row / base-fn ancestor wins, OR a `{X {:as arg-name}}`
     rename surfaces a rename slot owned by the ancestor.
   - Pass 2: data-flow tree (ref-fn-id). Only consulted if pass 1
     finds nothing; refs propagate the ref-target's renamed free
     args outward, so the slot may live deep in the ref tree.

   Falls back to `[primary-parent arg-name]` when both passes are
   exhausted — matches the legacy slot-id formula for slots whose
   owner lives in a base-fn outside `defs-by-name`."
  [composed-fn-name arg-name defs-by-name]
  (let [fd (get defs-by-name composed-fn-name)]
    (or (resolve-slot-owner-strict composed-fn-name arg-name defs-by-name #{})
        [(or (when fd (or (:parent fd) (first (:parents fd))))
             composed-fn-name)
         arg-name])))


(defn- build-defs-by-name
  "Map of {fn-name → fn-def} from the input vector for parse-time
   slot resolution."
  [module-fn-defs]
  (into {}
        (keep (fn [fd]
                (when-let [n (:name fd)]
                  [n fd])))
        module-fn-defs))


(defn- map-arg-value->binding-fields
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
                         (try (resolve-type-ref type-ref name->id)
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


(defn- item->record
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
              :id (binding-list-item-id owner-binding-id idx)
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


(defn- composed-own-fn
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


(defn- ancestor-type-pin
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


(defn- collect-exposed-names
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
                            (fn-id (:namespace owner-def) owner-name)))]
      (when owner-fn-id
        (slot-id owner-fn-id owner-arg)))))


(defn- build-rename-slot-records
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
  [composed-fn-name exposed-names own-id name->id defs-by-name]
  (vec
    (for [[exposed type-spec source-arg] exposed-names
          :let [slot-name (clojure.core/name exposed)
                sid (slot-id own-id slot-name)
                fsid (fn-slot-id own-id sid)
                type-fn-id (or (when type-spec
                                 (try (resolve-type-ref type-spec name->id)
                                      (catch Exception _ nil)))
                               (primitive-fn-id :any))
                source-sid (resolve-source-slot-id composed-fn-name source-arg
                                                   defs-by-name name->id)]]
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
        :position 0}])))


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


(defn- build-binding-and-items
  "Translate one `[arg-name arg-value]` into the binding row plus its
   list-item rows. Walks inheritance to find the slot owner so the
   binding targets the canonical slot-id."
  [own-id fn-name [arg-name arg-value] name->id defs-by-name]
  (let [[owner-name owner-arg] (resolve-slot-owner fn-name arg-name defs-by-name)
        owner-fn-def (get defs-by-name owner-name)
        owner-fn-id (or (get name->id owner-name)
                        (fn-id (:namespace owner-fn-def) owner-name))
        slot (slot-id owner-fn-id owner-arg)
        bid (binding-id own-id slot)
        slot-type (slot-type-of owner-name owner-arg defs-by-name)
        sequence-slot? (= :sequence slot-type)
        {:keys [fields items]} (arg-value->binding-fields arg-value name->id sequence-slot?)
        binding-row (merge blank-binding-row
                           {:id bid :fn-id own-id :slot-id slot}
                           fields)
        item-rows (vec (map-indexed
                         (fn [idx item] (item->record item idx bid name->id))
                         items))]
    (into [binding-row] item-rows)))


(defn- parse-composed
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
        own-id (fn-id ns-id fn-name)
        parent-ids (resolve-parent-list fn-def name->id)
        ret-id (when return-type
                 (try (resolve-type-ref return-type name->id) (catch Exception _ nil)))
        own-fn (composed-own-fn own-id fn-name ns-id parent-ids description ret-id)
        exposed-names (collect-exposed-names args fn-name defs-by-name)
        rename-slot-records (build-rename-slot-records fn-name exposed-names
                                                       own-id name->id
                                                       defs-by-name)
        binding+items (mapv #(build-binding-and-items own-id fn-name %
                                                      name->id defs-by-name)
                            args)]
    (into [own-fn]
          (concat (apply concat rename-slot-records)
                  (apply concat binding+items)))))


(defn- attach-fn-meta
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
             :id (fn-id ns-id fn-name)
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
                                      [(:name fd) (fn-id (:namespace fd) (:name fd))])))
                            module-fn-defs)
         name->id (merge extra-name->id own-name->id)
         defs-by-name (merge extra-defs-by-name (build-defs-by-name module-fn-defs))
         ;; Inline `[:fn args ret]` references buried in fn-defs'
         ;; type-bearing fields produce anonymous fn-rows that the
         ;; slot's `:type-fn-id` lands on. Synthesised once per
         ;; module (deduped by id) and prepended so they exist
         ;; before any slot row references them.
         inline-fn-rows (->> module-fn-defs
                             (mapcat inline-fn-type-rows-from-fn-def)
                             (distinct)
                             vec)]
     (vec (concat inline-fn-rows
                  (mapcat #(parse-fn-def % name->id defs-by-name) module-fn-defs))))))
