(ns graphden.crud.type-check
  "Save-time type guards for the web/crud base functions.

   Resolves a slot's expected type, reconstructs a composed fn's
   EDN-shape fn-def from its DB rows, and runs the type-checker
   against either a single binding or the whole owning fn after a
   CRUD mutation.

   Also holds the type-row → rich-type chain walkers
   (`rich-type-from-row` / `chain-fns-by-id` / `type-fn->rich-type`):
   they're shared by this guard layer AND the higher-level types-API
   namespace, so they live here, at the lower level of the crud.*
   DAG, and `graphden.crud.types-api` requires them.

   Sits above `graphden.crud.request` / `graphden.crud.validation`
   in the crud.* layering; it has no crud.* require of its own."
  (:require
    [clojure.string :as str]
    [graphden.executor.registry.core :as registry]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.check :as types-check]
    [graphden.types.core :as types]))


(defn- query-fn-by-name
  "Look up a fn by `:name`, tolerating the enum-typed `fn.name` column.
   A name that was never created is not a valid enum value, so the
   storage codec raises `:validation-error/type-mismatch` on the
   query itself. Swallow exactly that — it just means \"no such fn\"
   — but rethrow anything else. Mirrors
   `compile-runtime/query-fn-by-name`."
  [storage value]
  (try
    (first (sp/query-entities storage :fn {:name value}))
    (catch clojure.lang.ExceptionInfo e
      (when-not (= :validation-error/type-mismatch (:type (ex-data e)))
        (throw e))
      nil)))


(defn resolve-type-fn-id
  "Look up a type-row fn by name in storage and return its id (a UUID
   the schema's `return-type-fn-id` FK accepts). The argument is the
   form value — either a string like \"ring-response-shape\" or a
   raw UUID string. Throws `ex-info` with `:type :crud/unknown-type-ref`
   when the name doesn't resolve to a fn-row — process-create-entity
   catches and surfaces a clean message."
  [storage v]
  (when-not (str/blank? v)
    (or (try (java.util.UUID/fromString v) (catch Exception _ nil))
        (let [match (or (query-fn-by-name storage v)
                        (query-fn-by-name storage (keyword v)))]
          (or (:id match)
              (throw (ex-info (str "Unknown type reference: " (pr-str v)
                                   " — no fn with that name exists yet")
                              {:type :crud/unknown-type-ref
                               :ref v})))))))


(defn resolve-type-fn-id-or-throw
  "Like `resolve-type-fn-id` but throws `:type-row/unknown-type` when
   the name doesn't match anything. Used by the compound-create
   endpoints so the caller gets a clean error instead of a NULL
   foreign key violation downstream."
  [storage type-ref]
  (or (resolve-type-fn-id storage type-ref)
      (throw (ex-info (str "Unknown type: " (pr-str type-ref))
                      {:type :type-row/unknown-type :type-ref type-ref}))))


(defn rich-type-from-row
  "Pure version of `type-fn->rich-type`: walks `:base-fn-id` /
   `:element-fn-id` chains via the pre-loaded `fns-by-id` lookup,
   no DB. Used both by the batch path (`rich-types-with-type-rows`)
   and by the single-row fallback below.

   Union and variant type-rows carry their structural payload in
   `:constraint`. Union surfaces verbatim (`[:union T1 T2 …]`);
   variant goes through `types/desugar-variant` so the editor sees
   the same union-of-pinned-records shape the runtime sees."
  [tfn fns-by-id]
  (cond
    (nil? tfn) nil
    (some? (:base-fn-id tfn))
    (let [base (rich-type-from-row (get fns-by-id (:base-fn-id tfn)) fns-by-id)
          c (:constraint tfn)]
      (when base [:refine base (when c (mapv #(if (string? %) (keyword %) %) c))]))
    (some? (:element-fn-id tfn))
    (when-let [elem (rich-type-from-row (get fns-by-id (:element-fn-id tfn)) fns-by-id)]
      [:list elem])
    (and (vector? (:constraint tfn))
         (= :union (first (:constraint tfn))))
    (:constraint tfn)
    (and (vector? (:constraint tfn))
         (= :variant (first (:constraint tfn))))
    (types/desugar-variant (:constraint tfn))
    (and (vector? (:constraint tfn))
         (= :fn (first (:constraint tfn))))
    (:constraint tfn)
    (and (empty? (:parent-ids tfn))
         (nil? (:impl-hash tfn))
         (some? (:name tfn)))
    (keyword (:name tfn))
    :else :jsonb))


(defn chain-fns-by-id
  "Read only the fn rows the type chain needs by walking `:base-fn-id`
   / `:element-fn-id` outward from `tfn`. Typical 1-2-deep chains
   resolve in 1-2 batched reads; primitives need 0."
  [storage tfn]
  (loop [acc (cond-> {} tfn (assoc (:id tfn) tfn))
         queue (filterv some? [(some-> tfn :base-fn-id)
                               (some-> tfn :element-fn-id)])]
    (if (empty? queue)
      acc
      (let [batch (filterv #(not (contains? acc %)) queue)
            fetched (when (seq batch) (sp/read-entities storage :fn batch))
            next-queue (into []
                             (mapcat (fn [[_ row]]
                                       (filterv some? [(:base-fn-id row)
                                                       (:element-fn-id row)])))
                             fetched)]
        (recur (merge acc fetched) next-queue)))))


(defn type-fn->rich-type
  "Single-row entry: load only the chain we need on demand. Used from
   binding type-checks where we already know just one row. The batch
   path in `rich-types-with-type-rows` skips this and threads the
   pre-loaded `fns-by-id` straight into `rich-type-from-row`."
  [storage tfn]
  (rich-type-from-row tfn (chain-fns-by-id storage tfn)))


(defn list-items-for-binding
  "Load `:binding-list-item` rows for a binding-id, ordered by position.
   Returns `[{:value … :ref-fn-id … :literal …} …]` — the raw rows."
  [storage binding-id]
  (->> (sp/query-entities storage :binding-list-item {:binding-id binding-id})
       (sort-by :position)
       vec))


(defn binding-shape-for-edn
  "Convert one DB binding row + its list-items into the EDN-shape value
   `check-fn-def!` expects:

     literal value      → `{:value V}` (or bare V via classify-literal
                          downstream — `:value` map is always safe)
     ref-binding        → keyword (the bound fn's name)
     rename-only        → `{:as :renamed}` (no value/ref)
     list with items    → `[item …]` vector

   Falls back to nil for incomplete bindings (no value, no ref, no
   rename) — those don't contribute to the type-check input."
  [storage fn-by-id _slot-by-id renamed-view-by-source b]
  (let [items (when (or (true? (:list-append b)) (some? (:id b)))
                (not-empty (list-items-for-binding storage (:id b))))
        ;; Phase 6c — rename info now lives on the renamed-view slot
        ;; (own-slot of binding's fn-id with source-slot-id pointing
        ;; at binding's slot-id). The rename's TYPE comes from that
        ;; slot's `:type-fn-id` (parser sets it from the `:type`
        ;; sibling in the EDN `{:as :name :type T}` shape).
        renamed-view (get renamed-view-by-source (:slot-id b))
        ref-id (:ref-fn-id b)
        ref-name (when ref-id (some-> (get fn-by-id ref-id) :name keyword))]
    (cond
      (some? items)
      (mapv (fn [it]
              (cond
                (some? (:value it)) {:value (:value it)
                                     :literal? (true? (:literal it))}
                (:ref-fn-id it) (some-> (get fn-by-id (:ref-fn-id it))
                                        :name keyword)
                :else nil))
            items)

      ref-name ref-name
      (some? (:value b)) {:value (:value b)}
      renamed-view {:as (keyword (:name renamed-view))
                    :type (some-> (:type-fn-id renamed-view)
                                  (->> (get fn-by-id))
                                  :name keyword)}
      :else nil)))


(defn reconstruct-fn-def
  "Build the EDN-shape fn-def map (the form `check-fn-def!` accepts)
   from a fn-id by walking the DB rows. Returns nil for fn-rows that
   aren't composed fn-defs (have no parents) — those don't need
   `check-fn-def!`. The resulting map carries `:name`, `:parent` /
   `:parents`, `:args` (slot-name → binding-shape), and
   `:return-type` (when declared)."
  [storage fn-id]
  (when-let [own (sp/read-entity storage :fn fn-id)]
    (let [parent-ids (or (:parent-ids own) [])]
      (when (seq parent-ids)
        (let [parent-rows (when (seq parent-ids)
                            (sp/read-entities storage :fn parent-ids))
              fn-by-id (-> parent-rows
                           (assoc fn-id own)
                           (cond->
                             (:return-type-fn-id own)
                             (assoc (:return-type-fn-id own)
                                    (sp/read-entity storage :fn
                                                    (:return-type-fn-id own)))))
              parent-name (fn [pid]
                            (some-> (get fn-by-id pid) :name keyword))
              own-bindings (sp/query-entities storage :binding {:fn-id fn-id})
              ;; Phase 6c — own fn-slot rows of `fn-id` carry the
              ;; renamed-view slots (the FK link replacing the legacy
              ;; `binding.rename_to` text). Pull them so
              ;; `binding-shape-for-edn` can reconstruct
              ;; `{:as :renamed :type T}` shapes from the slot side.
              own-fn-slots (sp/query-entities storage :fn-slot {:fn-id fn-id})
              fn-slots (mapcat (fn [pid]
                                 (sp/query-entities storage :fn-slot {:fn-id pid}))
                               parent-ids)
              slot-ids (into [] (comp (mapcat (fn [b] [(:slot-id b)]))
                                      (filter some?))
                             (concat own-bindings own-fn-slots fn-slots))
              slot-rows (when (seq slot-ids)
                          (sp/read-entities storage :slot slot-ids))
              slot-by-id (or slot-rows {})
              ;; Map source-slot-id → renamed-view slot row, restricted
              ;; to renames owned by `fn-id` (since binding-shape-for-
              ;; edn always asks about the owner's renames). One pass
              ;; over own-fn-slots; no per-binding query.
              renamed-view-by-source
              (into {}
                    (keep (fn [fs]
                            (when-let [s (get slot-by-id (:slot-id fs))]
                              (when-let [src (:source-slot-id s)]
                                [src s]))))
                    own-fn-slots)
              ;; Resolve any ref-targets in bindings into fn-by-id so
              ;; binding-shape-for-edn can name them.
              ref-ids (->> own-bindings
                           (keep :ref-fn-id)
                           distinct
                           (remove #(contains? fn-by-id %)))
              fn-by-id+refs (cond-> fn-by-id
                              (seq ref-ids)
                              (merge (sp/read-entities storage :fn ref-ids)))
              args (into {}
                         (keep (fn [b]
                                 (when-let [slot (get slot-by-id (:slot-id b))]
                                   (when-let [shape (binding-shape-for-edn
                                                      storage fn-by-id+refs
                                                      slot-by-id
                                                      renamed-view-by-source b)]
                                     [(keyword (:name slot)) shape]))))
                         own-bindings)
              ret-name (some-> (:return-type-fn-id own)
                               (->> (get fn-by-id+refs))
                               :name keyword)]
          (cond-> {:name (some-> (:name own) keyword)
                   :args args}
            (= 1 (count parent-ids)) (assoc :parent (parent-name (first parent-ids)))
            (> (count parent-ids) 1) (assoc :parents (mapv parent-name parent-ids))
            ret-name (assoc :return-type ret-name)))))))


(defn type-check-fn-after-mutation!
  "Run `check-fn-def!` on the affected fn-id after a CRUD mutation
   touched its bindings/slots. Returns nil on success or
   `{:reason …}` on type-check failure — caller can use that to
   reject + rollback. Composed fns only; type-rows / base-fns
   short-circuit (no parents → nothing to check)."
  [storage fn-id]
  (try
    (when-let [fn-def (reconstruct-fn-def storage fn-id)]
      (types-check/check-fn-def! fn-def))
    nil
    (catch clojure.lang.ExceptionInfo e
      {:reason (Throwable/.getMessage e)})
    (catch Exception e
      ;; Defensive: any unexpected error during reconstruction is
      ;; surfaced (better than silent broken state, worse than
      ;; nothing).
      {:reason (str "type-check error: " (Throwable/.getMessage e))})))


(defn type-check-binding-direct!
  "Save-time type guard for `/api/entities/binding` POST/PUT. Resolves
   the slot's expected type once, then validates EITHER the value
   (literal compared by `subtype?`) OR the ref (the bound fn's
   `:return-type` from the rich-types registry compared via subtype?
   or unify). Returns nil on success or `{:reason …}` on rejection.

   Skip silently when the slot's expected type is `:any` (the
   uninformative escape hatch — type-check can't catch anything
   useful)."
  [storage entity-data binding-id]
  (let [slot-id (or (:slot-id entity-data)
                    (when binding-id
                      (some-> (sp/read-entity storage :binding binding-id)
                              :slot-id)))
        slot (when slot-id (sp/read-entity storage :slot slot-id))
        tfn (when (:type-fn-id slot)
              (sp/read-entity storage :fn (:type-fn-id slot)))
        expected (type-fn->rich-type storage tfn)
        new-value (when (contains? entity-data :value) (:value entity-data))
        new-ref-id (:ref-fn-id entity-data)]
    (cond
      ;; No expected type or :any escape hatch — skip.
      (or (nil? expected) (= expected :any))
      nil

      ;; Value-binding case: literal vs expected.
      (contains? entity-data :value)
      (let [actual (or (types-check/classify-literal new-value) :any)]
        (when-not (or (nil? new-value) (= actual :any)
                      (types/subtype? actual expected)
                      (and (types/refine-type? expected)
                           (types/subtype? actual (types/refine-base expected))
                           (let [r (types-check/literal-satisfies-refinement?
                                     new-value (types/refine-constraint expected))]
                             (or (true? r) (= :unknown r)))))
          {:reason (str "Type mismatch on value: expected " (pr-str expected)
                        ", got " (pr-str actual)
                        " (value " (pr-str new-value) ")")}))

      ;; Ref-binding case: bound fn's return type vs expected.
      (some? new-ref-id)
      (let [target-fn (sp/read-entity storage :fn new-ref-id)
            target-name (some-> target-fn :name keyword)
            target-info (when target-name (registry/rich-type-of target-name))
            target-ret (or (some-> target-info :return) :any)
            ;; Same `:any` escape on the target side — without rich-
            ;; type info we can't reason about a freshly-created fn
            ;; whose registry entry isn't populated yet.
            ok? (or (= target-ret :any)
                    (types/subtype? target-ret expected)
                    ;; Refinement: target is base-typed, expected is
                    ;; the refinement → need explicit validate, but a
                    ;; lenient check passes when base subtype holds.
                    (and (types/refine-type? expected)
                         (types/subtype? target-ret
                                         (types/refine-base expected))))]
        (when-not ok?
          {:reason (str "Type mismatch on ref binding: slot expects "
                        (pr-str expected) ", but " (pr-str target-name)
                        " returns " (pr-str target-ret))})))))
