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
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.registry.core :as registry]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [graphden.types.check :as types-check]
    [graphden.types.check.literals :as types-lit]
    [graphden.types.core :as types]
    [graphden.types.diagnostics :as diag]
    [graphden.versioning.storage.core :as vs]))


(defn with-org-alias-view*
  "Run `thunk` with the type-alias registry filtered to {public + current-org}
   when a TENANT is in scope (§4 Risk-2 fix) — so a tenant never resolves another
   org's same-named type. Platform / public (or single-tenant, org = public) →
   the full global registry, unchanged. Binds the EXISTING
   `types/*type-aliases-override*` (READ-only callers — they resolve, never
   register, so registration still writes the org-agnostic global). Shared by the
   type-CHECK guards here and the alias-resolving READ paths (value-form / types-
   api) so a tenant's editor display is org-filtered too."
  [thunk]
  (if (= (tc/current-org) tc/public-org)
    (thunk)
    (binding [types/*type-aliases-override*
              (atom (cr/org-alias-snapshot tc/public-org (tc/current-org)))]
      (thunk))))


(defn resolve-type-fn-id
  "Look up a type-row fn by name in storage and return its id (a UUID
   the schema's `return-type-fn-id` FK accepts). The argument is the
   form value — either a string like \"ring-response-shape\" or a
   raw UUID string. Throws `ex-info` with `:type :crud/unknown-type-ref`
   when the name doesn't resolve to a fn-row — process-create-entity
   catches and surfaces a clean message."
  [storage v]
  (when-not (str/blank? v)
    (or (request/parse-uuid-or-clear v)
        (let [match (lookup/query-fn-by-name storage v)]
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
      ;; `c` arrives already decoded by the storage codec, which
      ;; round-trips keyword operators (`:=` / `:not=` / …) and leaves
      ;; string VALUES (`[:= "x"]`, `[:not= ""]`, `[:matches "re"]`)
      ;; intact — so pass it through verbatim. Keywordizing here would
      ;; corrupt value-carrying constraints (e.g. `:non-empty-text`'s
      ;; `[:not= ""]` → `[:not= :]`, which then accepts `""`).
      (when base [:refine base c]))
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
         (#{:fn :map :tuple} (first (:constraint tfn))))
    (:constraint tfn)
    (and (empty? (:parent-ids tfn))
         (nil? (:return-type-fn-id tfn))
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
        row->kw (fn [row]
                  (when-let [n (:name row)]
                    ;; reconstruct pre-annotates rows with ::ns-path so
                    ;; per-ns duplicate names emit QUALIFIED and resolve
                    ;; precisely through the registry's dual index.
                    (if-let [nsp (::ns-path row)]
                      (keyword nsp n)
                      (keyword n))))
        ref-name (when ref-id (some-> (get fn-by-id ref-id) row->kw))]
    (cond
      (some? items)
      (mapv (fn [it]
              (cond
                (some? (:value it)) {:value (:value it)
                                     :literal? (true? (:literal it))}
                (:ref-fn-id it) (some-> (get fn-by-id (:ref-fn-id it))
                                        row->kw)
                :else nil))
            items)

      ref-name ref-name
      ;; `:value-present` flag (intent), not `(some? :value)` —
      ;; `{:value nil}` is a legitimate pinned-nil binding that must
      ;; round-trip through reconstruct → type-check, otherwise it
      ;; vanishes and the type-checker sees the slot as free.
      (true? (:value-present b)) {:value (:value b)}
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
              ;; ns paths for QUALIFIED name emission — per-ns duplicate
              ;; names resolve precisely only through the qualified
              ;; registry key, and the editor world (random ids, any
              ;; namespace) is exactly where duplicates live. One
              ;; batched :ns read over the rows in hand.
              ns-ids (into #{} (keep :namespace-id) (vals fn-by-id))
              ns-rows (when (seq ns-ids)
                        (sp/read-entities storage :ns (vec ns-ids)))
              ns-path (fn ns-path
                        [nsid]
                        (when-let [r (get ns-rows nsid)]
                          (if-let [p (:parent-id r)]
                            (str (or (ns-path p)
                                     (some-> (sp/read-entity storage :ns p)
                                             :name))
                                 "." (:name r))
                            (:name r))))
              annotate (fn [row]
                         (if-let [nsp (some-> (:namespace-id row) ns-path)]
                           (assoc row ::ns-path nsp)
                           row))
              fn-by-id (into {} (map (fn [[k v]] [k (annotate v)])) fn-by-id)
              parent-name (fn [pid]
                            (let [row (get fn-by-id pid)]
                              (when-let [n (:name row)]
                                (if-let [nsp (::ns-path row)]
                                  (keyword nsp n)
                                  (keyword n)))))
              own-bindings (sp/query-entities storage :binding {:fn-id fn-id})
              ;; Phase 6c — own fn-slot rows of `fn-id` carry the
              ;; renamed-view slots (the FK link replacing the legacy
              ;; `binding.rename_to` text). Pull them so
              ;; `binding-shape-for-edn` can reconstruct
              ;; `{:as :renamed :type T}` shapes from the slot side.
              own-fn-slots (sp/query-entities storage :fn-slot {:fn-id fn-id})
              ;; Batch the parent fn-slot fetch: the vector-valued
              ;; `:fn-id` filter folds into a single IN-clause instead
              ;; of one round-trip per parent (3 for a typical MI chain).
              fn-slots (if (seq parent-ids)
                         (sp/query-entities storage :fn-slot {:fn-id (vec parent-ids)})
                         [])
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
                              (merge (into {}
                                           (map (fn [[k v]] [k (annotate v)]))
                                           (sp/read-entities storage :fn ref-ids))))
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
                   ;; The ROW id — editor-created fns have RANDOM ids
                   ;; (not the sync path's name-derived uuid-v5), so the
                   ;; registry write that follows the check must key the
                   ;; entry by THIS id, not a derived one.
                   :fn-id fn-id
                   :args args}
            (= 1 (count parent-ids)) (assoc :parent (parent-name (first parent-ids)))
            (> (count parent-ids) 1) (assoc :parents (mapv parent-name parent-ids))
            ret-name (assoc :return-type ret-name)
            ;; Persisted authored contract — flows into the registry
            ;; entry on re-check (JSONB stores strings; `[]` is a
            ;; meaningful "everything captured" declaration).
            (some? (:lambda-params own))
            (assoc :lambda-params (mapv keyword (:lambda-params own)))))))))


(defn type-check-fn-after-mutation!
  "Run `check-fn-def!` on the affected fn-id after a CRUD mutation
   touched its bindings/slots. Returns nil on success or
   `{:reason … :diagnostic …}` on type-check failure. Since
   error-tolerance Phase 2 the CRUD callers KEEP the write on failure
   and surface `:diagnostic` additively as `:type-warnings` on the
   success envelope — this guard's job is recording, not rejection.
   `:reason` stays the human-readable message string; `:diagnostic`
   is the cleaned structured ex-data (`diag/from-ex` — `:expected` /
   `:actual` / `:arg-name` / …). Composed fns only; type-rows /
   base-fns short-circuit (no parents → nothing to check).

   Also keeps the per-branch diagnostics store fresh: failure records,
   success clears, the fn's entry under the storage's current branch
   (nil branch-id for an unversioned/base storage = default branch)."
  [storage fn-id]
  (with-org-alias-view*
    (fn []
      (let [result
            (try
              (when-let [fn-def (reconstruct-fn-def storage fn-id)]
                (types-check/check-fn-def! fn-def))
              nil
              (catch clojure.lang.ExceptionInfo e
                ;; `.getMessage` is nullable; `str` keeps the response field a
                ;; string instead of a JSON-`null` the client would render as
                ;; "rejected, no reason".
                {:reason (str (Throwable/.getMessage e))
                 :diagnostic (diag/from-ex e)})
              (catch Exception e
                ;; Defensive: any unexpected error during reconstruction is
                ;; surfaced (better than silent broken state, worse than
                ;; nothing).
                (let [msg (str "type-check error: " (Throwable/.getMessage e))]
                  {:reason msg
                   :diagnostic {:message msg}})))
            branch-id (vs/current-branch-id storage)]
        (if result
          (diag/record! branch-id fn-id [(:diagnostic result)])
          (diag/clear-fn! branch-id fn-id))
        result))))


(defn type-check-binding-direct!
  "On-demand single-binding type validator. Resolves the slot's
   expected type once, then validates EITHER the value (literal
   compared by `subtype?`) OR the ref (the bound fn's `:return-type`
   from the rich-types registry compared via subtype? or unify).
   Returns nil on success or `{:reason … :diagnostic …}` on mismatch
   (`:reason` = message string, `:diagnostic` = structured
   `:expected`/`:actual` map). Since error-tolerance Phase 2 this is
   NO LONGER wired as a blocking pre-write gate on
   `/api/entities/binding` POST/PUT — those writes proceed and the
   post-mutation `type-check-fn-after-mutation!` records the aggregate
   result in the diagnostics store. This fn stays available (through
   the `:type-check-binding-rej` base-fn) for pre-flight validation
   surfaces that want a verdict WITHOUT writing; it records nothing.

   Skip silently when the slot's expected type is `:any` (the
   uninformative escape hatch — type-check can't catch anything
   useful)."
  [storage entity-data binding-id]
  (with-org-alias-view*
    (fn []
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
          (let [actual (or (types-lit/classify-literal new-value) :any)]
            (when-not (or (nil? new-value) (= actual :any)
                          (types/subtype? actual expected)
                          (and (types/refine-type? expected)
                               (types/subtype? actual (types/refine-base expected))
                               (let [r (types-lit/literal-satisfies-refinement?
                                         new-value (types/refine-constraint expected))]
                                 (or (true? r) (= :unknown r)))))
              (let [msg (str "Type mismatch on value: expected " (pr-str expected)
                             ", got " (pr-str actual)
                             " (value " (pr-str new-value) ")")]
                {:reason msg
                 :diagnostic {:type :types/check-failed
                              :reason :value-mismatch
                              :expected expected
                              :actual actual
                              :binding {:value new-value}
                              :message msg}})))

          ;; Ref-binding case: bound fn's return type vs expected.
          (some? new-ref-id)
          (let [target-fn (sp/read-entity storage :fn new-ref-id)
                target-name (some-> target-fn :name keyword)
                target-info (some-> target-fn :id registry/rich-type-of-id)
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
                                             (types/refine-base expected)))
                        ;; HOF-forwarding semantics: when the slot expects a
                        ;; fn-VALUE ([:fn ...]), the ref-binding forwards the
                        ;; target as the callable — `compile-eager`'s hof-wrap
                        ;; turns it into a 0-arg (or single-arg) thunk that
                        ;; closes over the caller's env. The CALLABLE's
                        ;; signature is `[:fn (target's args) (target's
                        ;; return) (target's effects)]` (`make-fn-type`),
                        ;; which is what the slot must accept. Without this
                        ;; clause a scalar-returning fn-ref like
                        ;; `:current-time-ms` (return `:int`) into a `[:fn {}
                        ;; :any]` slot gets rejected even though the runtime
                        ;; would correctly hof-wrap it. The sync-time check
                        ;; in `types/check.clj` is already HOF-aware via
                        ;; variadic-ignore + closure-capture strip; this
                        ;; clause brings the API spot-check in line.
                        (and (types/fn-type? expected)
                             (types/subtype?
                               (types/make-fn-type
                                 (or (:args target-info) {})
                                 target-ret
                                 (or (:effects target-info) :any))
                               expected)))]
            (when-not ok?
              (let [msg (str "Type mismatch on ref binding: slot expects "
                             (pr-str expected) ", but " (pr-str target-name)
                             " returns " (pr-str target-ret))]
                {:reason msg
                 :diagnostic {:type :types/check-failed
                              :reason :ref-return-mismatch
                              :expected expected
                              :actual target-ret
                              :binding target-name
                              :message msg}}))))))))
