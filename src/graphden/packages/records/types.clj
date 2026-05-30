(ns graphden.packages.records.types
  "Type-reference resolution + inline fn-type synthesis. Resolves EDN
   `:type T` references to fn-ids and collects anonymous fn-rows for
   nested structural `[:fn args ret]` shapes."
  (:require
    [graphden.packages.records.ids :as ids]))


;; =============================================================================
;; Type-reference resolution
;; =============================================================================

(defn type-spec-map?
  "True iff `m` is the loader's expanded `{:type T :required B …}`
   shape (vs an inline-composite map `{:k :int}`). The presence of
   `:type` is the discriminator — record-types are pure {field-name
   field-type} maps with no `:type` key."
  [m]
  (and (map? m) (contains? m :type)))


(defn resolve-type-ref
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
    (and (keyword? t) (some #{t} ids/primitive-names))
    (ids/primitive-fn-id t)

    ;; `:never` (bottom type) has no storage value-kind — degrade to
    ;; `:any`, the same way a type-var does. The rich `:never` lives
    ;; only in the in-memory rich-types registry.
    (= t :never)
    (ids/primitive-fn-id :any)

    (keyword? t)
    (or (get name->id t)
        (throw (ex-info (str "Unknown type reference: " (pr-str t))
                        {:type :records/unknown-type-ref
                         :ref t})))

    ;; Type-var symbol → :any (storage degradation).
    (symbol? t)
    (ids/primitive-fn-id :any)

    (type-spec-map? t)
    (recur (:type t) name->id)

    ;; Inline composite — id by shape-hash; caller is responsible for
    ;; emitting the corresponding fn / slot / fn-slot records too.
    (map? t)
    (ids/anonymous-fn-id (ids/shape-hash t))

    (vector? t)
    (case (first t)
      ;; Inline `[:fn args ret]` — one anonymous fn-row per shape.
      ;; `shape-hash` expects a map, so we hash the pr-str of the
      ;; full vector (same digest scheme); the corresponding row is
      ;; emitted by `inline-fn-type-rows-from-fn-def` during the
      ;; module pass. Slot's `:type-fn-id` lands on this id instead
      ;; of the primitive `:fn` UUID, so the editor recovers the
      ;; structural form via the row's `:constraint`.
      :fn     (ids/anonymous-fn-id (ids/digest-hex "SHA-1" (pr-str t)))
      :list   (ids/primitive-fn-id :sequence)
      ;; A homogeneous `[:map K V]` is jsonb-shaped on the wire — same
      ;; storage kind as a record. A `[:tuple …]` is a fixed-length
      ;; sequence. The rich shape is kept in the rich-types registry.
      :map    (ids/primitive-fn-id :jsonb)
      :tuple  (ids/primitive-fn-id :sequence)
      :refine (recur (second t) name->id)
      ;; `[:secret <inner>]` is an information-flow marker — at storage
      ;; layer it's structurally identical to its inner type (the wire
      ;; format stores the path/text, not the taint flag; the marker
      ;; lives in the rich-types registry alongside the structural form).
      :secret (recur (second t) name->id)
      :union  (ids/primitive-fn-id :any)
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

(defn inline-fn-type-rows-from-form
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
          h (ids/digest-hex "SHA-1" (pr-str shape))
          id (ids/anonymous-fn-id h)
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

    ;; `[:union …]` / `[:map K V]` / `[:tuple …]` — every element past
    ;; the head is itself a type that may bury an inline fn-type.
    (and (vector? form) (#{:union :map :tuple} (first form)))
    (mapcat inline-fn-type-rows-from-form (rest form))

    ;; Type-spec map `{:type T :required B}` — recurse on `:type`.
    (type-spec-map? form)
    (inline-fn-type-rows-from-form (:type form))

    ;; Record / inline-composite map — recurse over each value.
    (map? form)
    (mapcat inline-fn-type-rows-from-form (vals form))

    :else nil))


(defn inline-fn-type-rows-from-fn-def
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
                      (when-let [m (:map fn-def)]
                        [(:key m) (:value m)])
                      (:tuple fn-def)
                      (when-let [ft (:fn-type fn-def)]
                        ;; `:fn-type [args ret]` shape — wrap so the
                        ;; recursion sees the inner args / ret.
                        (when (and (sequential? ft) (= 2 (count ft)))
                          [(into [:fn] ft)])))]
    (->> forms
         (mapcat inline-fn-type-rows-from-form)
         (distinct))))
