(ns graphden.packages.web.crud.impls
  "Implementations for web/crud base functions.

   Context-aware functions receive ctx as second argument.
   Pure functions receive only args map."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.executor.compile.lookups :as l]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.records :as records]
    [graphden.packages.web.html.impls :as html]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.check :as types-check]
    [graphden.types.core :as types]
    [graphden.versioning.storage.core :as vs])
  (:import
    (graphden.versioning.storage.core
      VersionedStorage)))


(declare type-check-binding-direct!)
(declare ensure-rename-slot!)


;; === Helpers ===

(defn- parse-query-string
  [s]
  (when (and s (not (str/blank? s)))
    (into {} (for [pair (str/split s #"&")
                   :let [[k v] (str/split pair #"=" 2)]
                   :when k]
               [k (java.net.URLDecoder/decode (or v "") "UTF-8")]))))


(defn- require-storage
  [ctx]
  (or (:storage ctx)
      (throw (ex-info "Storage not available in context"
                      {:type :execution-error/missing-storage}))))


(defn- entity-type-from-string
  [s]
  (case s
    "fn" :fn
    "ns" :ns
    "slot" :slot
    "fn-slot" :fn-slot
    "binding" :binding
    "binding-list-item" :binding-list-item
    nil))


(defn- parse-uri-segments
  "Pulls the `(type [id])` tail out of `:uri` for the entity routes.

   We can't rely on reitit's `:path-params` here because the route
   handler is invoked through a hof-wrap whose `:request` deep-free is
   captured from the outer fn-graph scope rather than from reitit's
   per-call `enrich-request` augmentation. The captured request is
   the raw http-kit one and never sees `:path-params`. Parsing the URI
   ourselves is dependency-free and exact for this small path family."
  [uri]
  (when uri
    ;; Recognised shapes:
    ;;   /api/entities/:type
    ;;   /api/entities/:type/:id
    ;;   /api/sequence/append/:fn-id
    ;;   /api/sequence/item/:item-id
    (let [segs (->> (str/split uri #"/") (remove str/blank?) vec)]
      (cond
        (and (= "api" (get segs 0)) (= "entities" (get segs 1)))
        {:type-str (get segs 2) :id-str (get segs 3)}

        (and (= "api" (get segs 0)) (= "sequence" (get segs 1)) (= "append" (get segs 2)))
        {:fn-id-str (get segs 3)}

        (and (= "api" (get segs 0)) (= "sequence" (get segs 1)) (= "item" (get segs 2)))
        {:item-id-str (get segs 3)}

        (and (= "api" (get segs 0)) (= "bindings" (get segs 1))
             (= "tighten-fn-effects" (get segs 3)))
        {:binding-id-str (get segs 2)}

        :else {}))))


(defn- extract-entity-params
  "Extracts type-str, id-str, entity-type from request. Prefers
   reitit's `:path-params` when present; falls back to URI parsing
   (the handler is sometimes reached with the raw http-kit request
   that hasn't been through reitit's `enrich-request`)."
  [request]
  (let [pp (:path-params request)
        rp (when (nil? pp) (parse-uri-segments (:uri request)))
        type-str (or (:type pp) (:type-str rp))
        id-str (or (:id pp) (:id-str rp))]
    {:type-str type-str
     :id-str id-str
     :entity-type (entity-type-from-string type-str)}))


;; === Affected-fn-id derivation for delta invalidation =======================
;;
;; Every CRUD mutation either lands ON a fn-row (e.g. `:fn` create), TOUCHES a
;; specific fn-row's closure (e.g. binding / fn-slot / binding-list-item under
;; some owner fn), OR cuts across many fns (e.g. `:slot` rename — slots are
;; shared; `:ns` rename — namespaces don't affect closures at all). For the
;; first two cases we can name the affected seed and let
;; `compile-runtime/delta-recompile!` walk the reverse-deps index to recompile
;; just the blast radius. For the cross-cutting cases we hand back nil and the
;; 1-arity `invalidate-graph-cache!` falls through to a full rebuild.
;;
;; The seed is just the directly-mutated fn-id (or, for binding-list-item, the
;; fn that OWNS the parent binding). Descendants are picked up by the
;; reverse-deps walk in `delta-recompile!` — no need to expand them here.

(defn- affected-fn-ids
  "Returns the seed set of fn-ids whose closure may be invalidated by a
   write to `entity-type` carrying `entity-data`. nil ⇒ caller must
   invoke 1-arity `invalidate-graph-cache!` (full clear)."
  [storage entity-type entity-data]
  (case entity-type
    :fn
    (when-let [id (:id entity-data)] #{id})

    (:fn-slot :binding)
    (when-let [fid (:fn-id entity-data)] #{fid})

    :binding-list-item
    ;; Items live under a binding; the binding's fn-id is the owner.
    ;; On delete we pre-read the item before the row is gone so
    ;; `entity-data` carries `:binding-id`; on create the caller
    ;; supplies it directly.
    (when-let [bid (:binding-id entity-data)]
      (some-> (sp/read-entity storage :binding bid) :fn-id (#(hash-set %))))

    ;; :slot is shared across many fns; :ns doesn't touch closures —
    ;; both fall through to a full clear.
    nil))


(defn- invalidate!
  "Convenience wrapper: derive the affected fn-id seeds and call
   `invalidate-graph-cache!` with the right arity. Pass `entity-data`
   that already includes `:id` (so :fn deletes pre-read the row,
   binding-list-item deletes pre-read the item)."
  [ctx storage entity-type entity-data]
  (if-let [seeds (affected-fn-ids storage entity-type entity-data)]
    (exec-ctx/invalidate-graph-cache! ctx seeds)
    (exec-ctx/invalidate-graph-cache! ctx)))


;; === Cycle-check on writes ==================================================
;;
;; The `validate-no-dependency-cycle!` protocol is implemented and
;; contract-tested but, until this commit, was never invoked on the
;; CRUD write path — a user could `POST /api/entities/binding` with a
;; `ref-fn-id` that closed a cycle and the server happily stored it,
;; trusting the editor's client-side `wouldCycle` to filter such
;; requests. That contract is now enforced server-side too: every
;; edge-introducing write runs through `cycle-check-rej!` before
;; touching storage.
;;
;; Self-reference (`owner = ref`) is allowed by design — recursion
;; is bounded at runtime by `*max-depth*`. See `constraints.clj`'s
;; `validate-no-dependency-cycle-impl` for the invariant.

(defn- cycle-check-pair
  "Run `validate-no-dependency-cycle!` for a single (owner, ref) pair.
   Returns nil on success or `{:reason …}` on rejection. Errors
   other than the cycle violation rethrow."
  [storage owner-id ref-id]
  (when (and owner-id ref-id)
    (try
      (sp/validate-no-dependency-cycle! storage owner-id ref-id)
      nil
      (catch clojure.lang.ExceptionInfo e
        (if (= :constraint-violation/dependency-cycle
               (:type (ex-data e)))
          {:reason (str "Dependency cycle: adding "
                        owner-id " → " ref-id
                        " would close a loop in the inheritance / ref graph")}
          (throw e))))))


;; === MI-collision check =====================================================
;;
;; Multi-inheritance composes the parents' interfaces. Two parents may
;; share a slot identity (one ancestor up the chain — fine) OR share an
;; arg NAME at distinct slot-ids — that's the collision: the merged
;; descendant would expose two differently-typed slots under the same
;; user-visible name, and the executor wouldn't know which to forward.
;;
;; The editor's `editor-edit-validation.js/miCollisionCheck` runs the
;; same logic client-side; this is the server-side mirror so non-editor
;; API consumers (scripts, tests, future UIs) get the same protection.

(defn- visible-slot-names
  "For `fn-id`, walk its inheritance closure and return a map of
   `{slot-id → effective-name}` for every slot the fn exposes
   (own + inherited). Effective name accounts for `slot.source-slot-id`
   renames via the existing `lookups/rename-for-slot` helper."
  [fn-id lookups]
  (let [chain (l/inheritance-chain* fn-id lookups)
        fn-slots-by-fn (:fn-slots-by-fn lookups)
        slot-map (:slot-map lookups)]
    (reduce
      (fn [acc fid]
        (let [own (get fn-slots-by-fn fid [])]
          (reduce (fn [a fs]
                    (let [sid (:slot-id fs)]
                      (if (or (nil? sid) (contains? a sid))
                        a
                        (let [eff (l/rename-for-slot fn-id sid lookups)
                              fallback (some-> (get slot-map sid) :name keyword)]
                          (assoc a sid (or eff fallback))))))
                  acc
                  own)))
      {}
      chain)))


(defn- mi-collision-check
  "Check whether the candidate `parent-ids` set introduces an arg-name
   collision (two different slots from different parents under the
   same user-visible name). Returns nil on success or `{:reason …}`.

   `parent-ids` of length < 2 cannot collide, early-out."
  [storage parent-ids]
  (let [pids (filterv some? (or parent-ids []))]
    (when (>= (count pids) 2)
      (let [graph {:fns        (sp/query-entities storage :fn {})
                   :slots      (sp/query-entities storage :slot {})
                   :fn-slots   (sp/query-entities storage :fn-slot {})
                   :bindings   []
                   :list-items []}
            lookups (l/build-lookups graph)
            per-parent (mapv #(visible-slot-names % lookups) pids)
            ;; Group slot-ids by name across all parents.
            by-name (reduce
                      (fn [acc m]
                        (reduce-kv (fn [a sid nm]
                                     (update a nm (fnil conj #{}) sid))
                                   acc
                                   m))
                      {}
                      per-parent)]
        (some (fn [[nm sids]]
                (when (>= (count sids) 2)
                  {:reason (str "Arg name collision: " (pr-str nm)
                                " is defined by " (count sids)
                                " distinct ancestor slots across the parent set")}))
              by-name)))))


(defn- mi-collision-rej
  "MI-collision check on a `:fn` write. Triggered when the row carries
   `:parent-ids` with two or more entries. Other entity types pass
   through (they don't change the parent set)."
  [storage entity-type entity-data]
  (when (and (= entity-type :fn)
             (seq (:parent-ids entity-data)))
    (mi-collision-check storage (:parent-ids entity-data))))


;; === :terminal / :list-closed enforcement ===================================
;;
;; Two declared-but-unenforced binding flags. Each gates a specific
;; downstream operation when it appears anywhere in the inheritance
;; chain above the writer:
;;
;;   :terminal true   — ancestors that mark a binding terminal say
;;                      "this is the final word; descendants don't
;;                      get to re-bind this slot." A descendant
;;                      `POST /api/entities/binding` for the same
;;                      `(slot-id)` is rejected.
;;
;;   :list-closed true — sequence slot. Ancestors can extend (with
;;                      `:list-append true` items); a `:list-closed`
;;                      flag downstream of any ancestor seals the
;;                      list — further `:list-append true` bindings
;;                      from descendants get rejected.
;;
;; `:override-kind :fixed` is the schema default but the codebase
;; uses inheritance with overrides everywhere; enforcing it strictly
;; would break the world. Treated as advisory only until the default
;; is revisited (separate concern — would require data migration to
;; flip existing rows from `:fixed` to `:default`).

(defn- ancestor-binding-flag?
  "Walk the PARENT chain of `fn-id` (skipping fn-id's own bindings)
   and return true iff any ancestor's binding on `slot-id` has
   `(flag-key ancestor-binding) = true`. Used to gate `:terminal`
   and `:list-closed` enforcement."
  [storage fn-id slot-id flag-key]
  (let [fn-row (when fn-id (sp/read-entity storage :fn fn-id))]
    (loop [queue (filterv some? (or (:parent-ids fn-row) []))
           seen #{}]
      (cond
        (empty? queue) false
        (seen (peek queue)) (recur (pop queue) seen)
        :else
        (let [fid (peek queue)
              rest-queue (pop queue)
              parent-fn (sp/read-entity storage :fn fid)
              ;; query for the ONE binding on (parent-fn, slot)
              own-bindings (sp/query-entities storage :binding
                                              {:fn-id fid :slot-id slot-id})
              flagged? (some #(true? (get % flag-key)) own-bindings)]
          (if flagged?
            true
            (recur (into rest-queue
                         (filterv (complement seen)
                                  (or (:parent-ids parent-fn) [])))
                   (conj seen fid))))))))


(defn- terminal-rej
  "Reject a `:binding` write whose `(fn-id, slot-id)` is sealed by
   an ancestor's `:terminal true` flag. Returns nil on success or
   `{:reason …}`."
  [storage entity-type entity-data]
  (when (and (= entity-type :binding)
             (:fn-id entity-data)
             (:slot-id entity-data))
    (when (ancestor-binding-flag? storage (:fn-id entity-data)
                                  (:slot-id entity-data) :terminal)
      {:reason
       (str "Binding rejected: an ancestor in the inheritance chain "
            "marked this slot's binding `:terminal true`, sealing it "
            "against descendant overrides.")})))


(defn- list-closed-rej
  "Reject a sequence-slot write whose `(fn-id, slot-id)` chain has a
   `:list-closed true` ancestor binding. Triggered on either:

   - A new `:binding` with `:list-append true` (the descendant tries
     to extend a sealed list).
   - A new `:binding-list-item` whose binding's chain is closed."
  [storage entity-type entity-data]
  (let [check (fn [fn-id slot-id]
                (when (ancestor-binding-flag? storage fn-id slot-id :list-closed)
                  {:reason
                   (str "List rejected: an ancestor in the inheritance "
                        "chain marked this list `:list-closed true`, "
                        "sealing it against further `:list-append`.")}))]
    (case entity-type
      :binding
      (when (and (true? (:list-append entity-data))
                 (:fn-id entity-data)
                 (:slot-id entity-data))
        (check (:fn-id entity-data) (:slot-id entity-data)))

      :binding-list-item
      (when-let [bid (:binding-id entity-data)]
        (when-let [b (sp/read-entity storage :binding bid)]
          (check (:fn-id b) (:slot-id b))))

      nil)))


(defn- cycle-check-rej
  "Inspect `entity-data` for the writes that introduce fn-id edges
   and run a cycle check on each. Returns nil on success or
   `{:reason …}` on rejection. Slot / ns / unrelated entities pass
   through.

   Edge sources mirror `forward-deps-of` in the executor compile
   module — the two paths (write-time validation, runtime
   recompile blast) share the same view of which fields create
   dependency edges."
  [storage entity-type entity-data]
  (case entity-type
    :fn
    (let [own-id (:id entity-data)
          parent-ids (filter some? (:parent-ids entity-data))
          fk-refs (keep entity-data
                        [:base-fn-id :element-fn-id :return-type-fn-id])]
      (or (some #(cycle-check-pair storage own-id %) parent-ids)
          (some #(cycle-check-pair storage own-id %) fk-refs)))

    :binding
    (let [own-id (:fn-id entity-data)]
      (or (cycle-check-pair storage own-id (:ref-fn-id entity-data))
          (cycle-check-pair storage own-id (:type-override-fn-id entity-data))))

    :binding-list-item
    ;; Items live under a binding; the OWNING fn-id is the cycle's
    ;; owner side. Look it up before validating.
    (when-let [bid (:binding-id entity-data)]
      (when-let [b (sp/read-entity storage :binding bid)]
        (cycle-check-pair storage (:fn-id b) (:ref-fn-id entity-data))))

    nil))


;; === Context-aware Query Functions ===

(defbase list-entities
  [entity-type where]
  (vec (sp/query-entities (require-storage ctx) (keyword entity-type) (or where {}))))


(defbase get-entity
  [entity-type id]
  (sp/read-entity (require-storage ctx) (keyword entity-type) id))


(defn- write-rej
  "Run every server-side write-time guard against the proposed row.
   Returns the first `{:reason :type}` rejection or nil if all pass.
   Centralised so generic `create-entity` / `update-entity` and the
   form-driven `process-*` paths share the same checks in the same
   order."
  [storage entity-type entity-data]
  (or (some-> (cycle-check-rej storage entity-type entity-data)
              (assoc :type :constraint-violation/dependency-cycle))
      (some-> (mi-collision-rej storage entity-type entity-data)
              (assoc :type :constraint-violation/mi-collision))
      (some-> (terminal-rej storage entity-type entity-data)
              (assoc :type :constraint-violation/terminal-binding))
      (some-> (list-closed-rej storage entity-type entity-data)
              (assoc :type :constraint-violation/list-closed))))


(defbase create-entity
  [entity-type data]
  (let [storage (require-storage ctx)
        et (keyword entity-type)
        ;; For :fn create the row may not have an `:id` yet; the
        ;; cycle check still wants it (parent / FK targets need to
        ;; know who's "owner"). Synthesize one so the check sees a
        ;; stable owner — `sp/create-entity` honours a pre-supplied
        ;; `:id` so the synthesized value is what lands in storage.
        ;;
        ;; New name (`data'`) instead of shadowing `data` — the
        ;; defbase walker treats let bindings as a SET (letrec-ish),
        ;; so `(let [data (assoc data ...)] ...)` would remove `data`
        ;; from the substitution map BEFORE walking the init RHS,
        ;; leaving the inner `data` unresolved.
        data' (if (and (= et :fn) (nil? (:id data)))
                (assoc data :id (random-uuid))
                data)]
    (when-let [rej (write-rej storage et data')]
      (throw (ex-info (:reason rej)
                      {:type (:type rej)
                       :entity-type et :data data'})))
    (let [result (sp/create-entity storage et data')]
      (invalidate! ctx storage et result)
      result)))


(defbase update-entity
  [entity-type id data]
  (let [storage (require-storage ctx)
        et (keyword entity-type)
        check-data (assoc data :id id)]
    (when-let [rej (write-rej storage et check-data)]
      (throw (ex-info (:reason rej)
                      {:type (:type rej)
                       :entity-type et :id id :data data})))
    (let [result (sp/update-entity storage et id data)]
      (invalidate! ctx storage et result)
      result)))


(defbase delete-entity
  [entity-type id]
  (let [storage (require-storage ctx)
        et (keyword entity-type)
        ;; Pre-read so we know the parent fn-id for binding /
        ;; fn-slot / binding-list-item before the row is gone.
        ;; For :fn the row's :id IS the seed; we synthesize one
        ;; rather than pay the read.
        snapshot (if (= et :fn)
                   {:id id}
                   (sp/read-entity storage et id))]
    (sp/delete-entity storage et id)
    (invalidate! ctx storage et snapshot)
    true))


(defn- load-graph-entities-uncached
  "Read every slot/fn-slot/binding-model row from storage in one
   branch-cached batch when versioned, or as five vanilla queries
   otherwise. Same shape as the `:graph-cache` atom."
  [storage]
  (if (instance? VersionedStorage storage)
    (vs/query-all-graph-entities storage)
    {:fns        (vec (sp/query-entities storage :fn {}))
     :slots      (vec (sp/query-entities storage :slot {}))
     :fn-slots   (vec (sp/query-entities storage :fn-slot {}))
     :bindings   (vec (sp/query-entities storage :binding {}))
     :list-items (vec (sp/query-entities storage :binding-list-item {}))}))


(defn- cached-or-load-graph
  [ctx]
  (or (exec-ctx/cached-graph ctx)
      (let [data (load-graph-entities-uncached (require-storage ctx))]
        (exec-ctx/fill-graph-cache! ctx data)
        data)))


(defbase list-all-graph-entities
  []
  ;; Slot/fn-slot/binding model: dump every storage row the editor
  ;; needs to render the graph. Routes through the shared graph-cache
  ;; (populated by layout / compile-runtime) so editor refreshes after
  ;; mutations don't re-query the same five tables every time.
  (let [storage (require-storage ctx)
        base (cached-or-load-graph ctx)]
    (assoc base :namespaces (vec (sp/query-entities storage :ns {})))))


(declare type-fn->rich-type)


(declare rich-type-from-row)


(defn- rich-types-with-type-rows
  "Augment the in-memory rich-type registry with structural definitions
   for storage-only type-rows (refinements, list types, records). The
   registry built by the type-checker only carries fn / fn-def entries;
   refinement types like `:port` show up as bare keywords inside other
   fns' rich-type bodies but have no top-level entry. The editor's
   refinement-aware mismatch indicator and value-validation hint look
   up `:port` → expect `[:refine :int [:and [:>= 1] [:<= 65535]]]`,
   so we expose the structural form alongside the existing entries."
  [ctx]
  (let [snapshot (registry/rich-types-snapshot)
        ;; Reuse the shared graph-cache instead of re-querying :fn —
        ;; chain walks over `:base-fn-id` / `:element-fn-id` resolve
        ;; in memory via `fns-by-id`.
        graph (cached-or-load-graph ctx)
        fns (:fns graph)
        slots (:slots graph)
        fn-slots (:fn-slots graph)
        fns-by-id (into {} (map (juxt :id identity)) fns)
        slot-by-id (into {} (map (juxt :id identity)) slots)
        slots-by-fn (group-by :fn-id fn-slots)
        ;; Type-rows we'll surface:
        ;; - refinements (have :base-fn-id)
        ;; - list-types (have :element-fn-id)
        ;; - record-types (empty parent-ids + no impl-hash + has fn-slots)
        ;; - union / variant / fn-type rows (have :constraint shaped as
        ;;   `[:union …]` / `[:variant …]` / `[:fn args ret]` —
        ;;   payload goes through `rich-type-from-row` which knows
        ;;   all three shapes)
        constraint-tagged?
        (fn [f tag]
          (and (vector? (:constraint f))
               (= tag (first (:constraint f)))))
        type-row?
        (fn [f]
          (and (:name f)
               (or (some? (:base-fn-id f))
                   (some? (:element-fn-id f))
                   (constraint-tagged? f :union)
                   (constraint-tagged? f :variant)
                   (constraint-tagged? f :fn)
                   (and (empty? (:parent-ids f))
                        (nil? (:impl-hash f))
                        (seq (get slots-by-fn (:id f)))))))
        type-rows (filter type-row? fns)
        record-shape
        (fn [f]
          (when-let [own (seq (get slots-by-fn (:id f)))]
            (into {}
                  (keep (fn [fs]
                          (when-let [s (get slot-by-id (:slot-id fs))]
                            (when-let [tn (some-> (:type-fn-id s)
                                                  fns-by-id
                                                  :name
                                                  keyword)]
                              [(keyword (:name s)) tn]))))
                  (sort-by :position own))))]
    (reduce (fn [acc f]
              (let [;; Marker-bearing rows (refinement / list / union /
                    ;; variant) carry their structural form via
                    ;; `rich-type-from-row` — `record-shape` would
                    ;; misclassify e.g. `:positive-int` (which has a
                    ;; synthesised `:value` slot for the inner-type
                    ;; binding) as the record `{:value :int}`,
                    ;; losing the refinement constraint. Only fall
                    ;; back to `record-shape` when no marker FK / tag
                    ;; is present — the genuine record case.
                    marker? (or (:base-fn-id f)
                                (:element-fn-id f)
                                (constraint-tagged? f :union)
                                (constraint-tagged? f :variant)
                                (constraint-tagged? f :fn))
                    structural (if marker?
                                 (rich-type-from-row f fns-by-id)
                                 (or (record-shape f)
                                     (rich-type-from-row f fns-by-id)))
                    n (some-> (:name f) keyword)
                    existing (get acc n)
                    ;; A real type-row's registry entry (when one
                    ;; exists from a prior pass) has empty :args —
                    ;; type-rows aren't called, they're just shapes.
                    ;; Base-fns whose declared `:return-type :any`
                    ;; would otherwise match the override criterion
                    ;; (`:invoke`, `:call`, …) — they'd get clobbered
                    ;; by the structural-shape override and lose
                    ;; their real args. The empty-args guard keeps
                    ;; them out.
                    real-type-row? (or (nil? existing)
                                       (and (= :any (:return existing))
                                            (empty? (:args existing))))]
                ;; Prefer the structural form ([:refine …] / [:list …]
                ;; / record map) over a registry entry that just
                ;; records `:return :any` — without that, the type-
                ;; checker pass on a refinement / record type-row
                ;; overwrites the structural entry with a stub and the
                ;; editor loses constraint info.
                ;;
                ;; `:type-row? true` marks augmented entries so callers
                ;; (e.g. `types-candidates`) can skip them — a type-row
                ;; isn't itself callable, just a shape. Real fns whose
                ;; declared return happens to be a structural type
                ;; (`(:return-type :positive-int)` etc.) come through
                ;; the original `record-rich-types-raw!` path and have
                ;; no `:type-row?` flag, so they stay candidate-eligible.
                (if (and n structural real-type-row?)
                  (assoc acc n {:return structural :args {} :effects #{}
                                :type-row? true})
                  acc)))
            snapshot
            type-rows)))


(defbase all-rich-types
  []
  ;; Snapshot of the in-memory rich-type registry, augmented with
  ;; structural definitions for storage-only type-rows (refinements,
  ;; lists). Built-in primitives (`:int`, `:text`, …) stay out — they
  ;; are leaf nodes the editor recognises by name. Structural forms
  ;; (`[:fn …]`, `[:list …]`, `[:refine …]`, records) survive the
  ;; JSON round-trip as arrays / objects.
  (rich-types-with-type-rows ctx))


;; === Type-API helpers (Phase 1: type-aware UI integration) ===

(defn- json->type
  "Inverse of cheshire's default Clojure→JSON encoding for type
   expressions. The wire format is whatever `(rich-types-with-type-rows)`
   yields after JSON round-trip:
     :int                    →  \"int\"               →  :int
     [:fn {:x :int} :int]    →  [\"fn\", {\"x\":\"int\"}, \"int\"]
                                                      →  [:fn {:x :int} :int]
     [:refine :int [:>= 0]]  →  [\"refine\", \"int\", [\">=\", 0]]
                                                      →  [:refine :int [:>= 0]]
     {:a :int :b :text}      →  {\"a\":\"int\", \"b\":\"text\"}
                                                      →  {:a :int :b :text}

   Strings become keywords, map keys become keywords, vectors recurse,
   numbers / booleans / nil pass through. Strings inside refinement
   constraints (rare) WILL be keywordised — refinements are mostly
   numeric so the trade-off is acceptable; refactor here if string
   literals enter the type language."
  [x]
  (cond
    (string? x)     (keyword x)
    (map? x)        (into {}
                          (map (fn [[k v]]
                                 [(if (string? k) (keyword k) k)
                                  (json->type v)]))
                          x)
    (sequential? x) (mapv json->type x)
    :else           x))


(defn- describe-mismatch
  "One-line human-readable explanation for why `candidate` is NOT a
   subtype of `expected`. Best-effort — the type-checker proper
   (in graphden.types.check) emits richer contextual messages but
   they require a binding context we don't have at the API layer.

   For UI use: enough to render \"why is this dimmed in the picker?\"
   tooltip text. The structured `expected` and `candidate` types
   accompany this string in the response so the UI can render its
   own details if it wants to."
  [expected candidate]
  (cond
    (= expected :any)
    "every type is a subtype of :any"

    (= candidate :any)
    (str ":any is not a subtype of " (pr-str expected)
         " — :any can't narrow to a concrete type without an explicit cast")

    (and (types/refine-type? expected) (not (types/refine-type? candidate)))
    (str (pr-str candidate) " lacks the refinement constraint required by "
         (pr-str expected))

    (and (types/refine-type? candidate) (types/refine-type? expected)
         (not= (types/refine-constraint candidate)
               (types/refine-constraint expected)))
    (str "refinement constraints differ — "
         (pr-str (types/refine-constraint candidate))
         " ≠ " (pr-str (types/refine-constraint expected)))

    (and (types/primitive? candidate) (types/primitive? expected))
    (str (pr-str candidate) " is not a primitive subtype of " (pr-str expected))

    (and (types/fn-type? candidate) (types/fn-type? expected))
    (str "function signature mismatch — "
         (pr-str candidate) " is not a subtype of " (pr-str expected))

    :else
    (str (pr-str candidate) " is not a subtype of " (pr-str expected))))


(defn- read-json-body
  "Pull the JSON body off a Ring request whether it arrives as a
   string, an InputStream, or already-parsed Clojure data. Returns
   a Clojure map with keyword keys, or nil for an empty body. The
   layout endpoint has the same logic — we duplicate here so the
   types API doesn't depend on app.layout (cross-package)."
  [request]
  (let [raw (:body request)]
    (cond
      (nil? raw)                            nil
      (map? raw)                            raw
      (instance? java.io.InputStream raw)
      (json/parse-stream
        (java.io.InputStreamReader. raw "UTF-8") true)
      (and (string? raw) (not (str/blank? raw)))
      (json/parse-string raw true)
      :else                                 nil)))


(defbase types-compatible
  "Single-pair subtype check. POST body: `{expected, candidate}` where
   each side is a type in the JSON shape produced by `/api/types`.
   Returns `{ok, expected, candidate, reason?}`. UI uses this to
   render type-mismatch explainers without re-implementing
   `subtype?` in JS."
  [request]
  (let [body (read-json-body request)
        expected-raw (:expected body)
        candidate-raw (:candidate body)]
    (cond
      (nil? expected-raw)
      {:ok false
       :error "Request body must include 'expected'"}

      (nil? candidate-raw)
      {:ok false
       :error "Request body must include 'candidate'"}

      :else
      (let [expected (json->type expected-raw)
            candidate (json->type candidate-raw)
            ok? (types/subtype? candidate expected)]
        (cond-> {:ok ok?
                 :expected expected
                 :candidate candidate}
          (not ok?)
          (assoc :reason (describe-mismatch expected candidate)))))))


(defbase types-candidates
  "Enumerate every fn whose return type is a subtype of `expected`,
   optionally further filtered. POST body:
     {expected: <type>,
      effects?: [\"db\" \"env\" …]   ; allowed-effect set; candidates
                                     ; with effects ⊆ this pass
      name-prefix?: \"app.server\"   ; namespace / name prefix filter}
   Returns `{count, candidates: [{name, return, args, effects}, ...]}`
   sorted alphabetically.

   Powers fn-pickers and free-arg suggestion lists. Rationale: the
   editor today re-implements a primitive-only check in
   editor-literal-types.js — this endpoint replaces that with the
   real `subtype?` predicate, so structural / refinement / union
   types start filtering correctly without per-platform logic."
  [request]
  (let [body (read-json-body request)
        expected-raw (:expected body)]
    (if (nil? expected-raw)
      {:ok false :error "Request body must include 'expected'"}
      (let [expected (json->type expected-raw)
            allowed-effects (when-let [effs (:effects body)]
                              (set (map (fn [e] (if (string? e) (keyword e) e))
                                        effs)))
            name-prefix (some-> (:name-prefix body) str)
            registry-snapshot (rich-types-with-type-rows ctx)
            candidates
            (->> registry-snapshot
                 (keep (fn [[fn-name {:keys [return args effects type-row?]}]]
                         (let [eff-set (or effects #{})
                               name-str (some-> fn-name name)]
                           (when (and (not type-row?) ; type-rows aren't callable producers
                                      (types/subtype? return expected)
                                      (or (nil? allowed-effects)
                                          (every? allowed-effects eff-set))
                                      (or (nil? name-prefix)
                                          (and name-str
                                               (str/starts-with? name-str name-prefix))))
                             {:name fn-name
                              :return return
                              :args (or args {})
                              :effects (vec (sort eff-set))}))))
                 (sort-by (fn [c] (some-> c :name name))))]
        {:ok true
         :expected expected
         :count (count candidates)
         :candidates (vec candidates)}))))


;; === Rendering Helpers (private) ===
;; NOTE: fn-field-specs duplicates the `:fn-field-specs` fn-def in
;; fns.edn — they must stay in sync. The duplication exists because
;; base-fn impls cannot resolve fn-defs at runtime.

(def ^:private fn-field-specs
  [["ID" :id] ["Name" :name :keyword-to-str] ["Parent ID" :parent-id]
   ["Return Type" :return-type :keyword-to-str] ["Impl Hash" :impl-hash]])


(defn- form-input-h
  [{:keys [field-name label-text field-value extra-attrs]}]
  [:div {:class "form-group"}
   [:label {:for field-name} label-text]
   [:input (merge {:type "text" :name field-name :id field-name}
                  (when field-value {:value field-value})
                  extra-attrs)]])


(defn- form-select-h
  [{:keys [field-name label-text options selected-value extra-attrs]}]
  [:div {:class "form-group"}
   [:label {:for field-name} label-text]
   (into [:select (merge {:name field-name :id field-name} extra-attrs)]
         (for [[v l] options]
           [:option (cond-> {:value v} (= v selected-value) (assoc :selected true)) l]))])


(defn- render-fn-form
  [entity all-fns]
  (let [editing? (some? entity)
        parent-options (into [["" "None"]]
                             (->> all-fns
                                  (filter :name)
                                  (mapv (fn [f] [(str (:id f)) (name (:name f))]))))]
    [:form {:hx-post (if editing? (str "/api/entities/fn/" (:id entity)) "/api/entities/fn")
            :hx-target "#modal-content" :hx-swap "innerHTML"
            :_ "on htmx:afterRequest if event.detail.successful trigger entityCreated on body then call hideModal()"}
     (form-input-h {:field-name "name" :label-text "Name"
                    :field-value (when entity (name (:name entity)))
                    :extra-attrs {:required true}})
     (form-select-h {:field-name "parent-id" :label-text "Parent (optional)"
                     :options parent-options
                     :selected-value (when entity (str (:parent-id entity)))})
     (html/button-row {:buttons [[:button {:type "button" :class "btn btn-secondary" :onclick "hideModal()"} "Cancel"]
                                 [:button {:type "submit" :class "btn btn-primary"} (if editing? "Save" "Create")]]
                       :style {:display "flex" :gap "8px" :justify-content "flex-end" :margin-top "16px"}})]))


;; === Render View Functions (context-aware) ===

(defbase render-entity-actions
  [entity-type entity-id]
  [:div {:style "margin-top: 16px; display: flex; gap: 8px;"}
   [:button {:class "btn btn-primary"
             :hx-get (str "/partials/entity-form/" entity-type "/" entity-id)
             :hx-target "#details-content" :hx-swap "innerHTML"} "Edit"]
   [:button {:class "btn btn-danger"
             :hx-delete (str "/api/entities/" entity-type "/" entity-id)
             :hx-confirm "Are you sure you want to delete this entity?"
             :hx-target "#details-panel" :hx-swap "outerHTML"
             :_ "on htmx:afterRequest trigger entityDeleted on body"} "Delete"]])


(defbase render-entity-details-view
  [request]
  (let [storage (require-storage ctx)
        {:keys [type-str id-str entity-type]} (extract-entity-params request)]
    (if (and entity-type id-str)
      (if-let [entity (sp/read-entity storage entity-type (java.util.UUID/fromString id-str))]
        [:div
         [:div {:style "margin-bottom: 12px;"}
          (html/badge {:badge-text type-str :badge-type type-str})]
         (when (= type-str "fn")
           (html/entity-field-rows {:entity entity :field-specs fn-field-specs}))
         (render-entity-actions {:entity-type type-str :entity-id id-str})]
        [:p {:class "error"} "Entity not found"])
      [:p {:class "error"} "Invalid request"])))


(defbase render-entity-form-view
  [request]
  (let [storage (require-storage ctx)
        {:keys [type-str id-str entity-type]} (extract-entity-params request)]
    (if entity-type
      (let [entity (when id-str (sp/read-entity storage entity-type (java.util.UUID/fromString id-str)))
            all-fns (vec (sp/query-entities storage :fn {}))]
        [:div
         [:h4 (str (if entity "Edit " "Create ") type-str)]
         (case type-str
           "fn" (render-fn-form entity all-fns)
           [:p "Not implemented"])])
      [:p {:class "error"} "Invalid entity type"])))


;; === Form Parsing (pure) ===

;; All three parse-*-from-form impls are permissive — fields are
;; only assoc'd when the key is actually present in the form. That
;; way both create (full form) and update (partial form, e.g.
;; description-only) flow through the same code without partial
;; updates blanking the unsent fields. Empty strings are kept (so
;; a submitted-empty `description=` clears the field rather than
;; leaving the old value).


(defn- resolve-type-fn-id
  "Look up a type-row fn by name in storage and return its id (a UUID
   the schema's `return-type-fn-id` FK accepts). The argument is the
   form value — either a string like \"ring-response-shape\" or a
   raw UUID string. Throws `ex-info` with `:type :crud/unknown-type-ref`
   when the name doesn't resolve to a fn-row — process-create-entity
   catches and surfaces a clean message."
  [storage v]
  (when-not (str/blank? v)
    (or (try (java.util.UUID/fromString v) (catch Exception _ nil))
        (let [match (or (first (sp/query-entities storage :fn {:name v}))
                        (first (sp/query-entities storage :fn
                                                  {:name (keyword v)})))]
          (or (:id match)
              (throw (ex-info (str "Unknown type reference: " (pr-str v)
                                   " — no fn with that name exists yet")
                              {:type :crud/unknown-type-ref
                               :ref v})))))))


(defbase parse-fn-from-form
  [form-data]
  (let [storage (require-storage ctx)]
    (cond-> {}
      (contains? form-data :name)
      (assoc :name (str (:name form-data)))
      (not (str/blank? (:parent-id form-data)))
      (assoc :parent-id (java.util.UUID/fromString (:parent-id form-data)))
      ;; namespace-id follows the empty-as-clear convention so a user
      ;; can move a fn back to the unnamespaced root via the editor.
      (contains? form-data :namespace-id)
      (assoc :namespace-id (when-not (str/blank? (:namespace-id form-data))
                             (java.util.UUID/fromString (:namespace-id form-data))))
      (contains? form-data :description)
      (assoc :description (:description form-data))
      ;; `return-type` form field accepts either a known type-row's
      ;; name (`"ring-response-shape"`) or its UUID. Resolves via
      ;; storage; `nil` reaches the create path which rejects since
      ;; the FK won't validate against a non-existent fn-id.
      (contains? form-data :return-type)
      (assoc :return-type-fn-id
             (resolve-type-fn-id storage (:return-type form-data)))
      ;; `parent-ids` is the multi-valued ref-many field. Form encoding
      ;; reserves form-keys to single values, so the list comes in as a
      ;; comma-separated UUID string. Empty clears (base-fn).
      (contains? form-data :parent-ids)
      (assoc :parent-ids
             (let [v (:parent-ids form-data)]
               (if (str/blank? v)
                 []
                 (mapv #(java.util.UUID/fromString (str/trim %))
                       (str/split v #","))))))))


(defbase parse-ns-from-form
  [form-data]
  (cond-> {}
    (contains? form-data :name)
    (assoc :name (str (:name form-data)))
    (not (str/blank? (:parent-id form-data)))
    (assoc :parent-id (java.util.UUID/fromString (:parent-id form-data)))
    (contains? form-data :description)
    (assoc :description (:description form-data))))


(defn- ^:private parse-uuid-or-clear
  [v]
  (when-not (str/blank? v) (java.util.UUID/fromString v)))


(defn- rich-type-from-row
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


(defn- chain-fns-by-id
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


(defn- type-fn->rich-type
  "Single-row entry: load only the chain we need on demand. Used from
   binding type-checks where we already know just one row. The batch
   path in `rich-types-with-type-rows` skips this and threads the
   pre-loaded `fns-by-id` straight into `rich-type-from-row`."
  [storage tfn]
  (rich-type-from-row tfn (chain-fns-by-id storage tfn)))


(defn- list-items-for-binding
  "Load `:binding-list-item` rows for a binding-id, ordered by position.
   Returns `[{:value … :ref-fn-id … :literal …} …]` — the raw rows."
  [storage binding-id]
  (->> (sp/query-entities storage :binding-list-item {:binding-id binding-id})
       (sort-by :position)
       vec))


(defn- binding-shape-for-edn
  "Convert one DB binding row + its list-items into the EDN-shape value
   `check-fn-def!` expects:

     literal value      → `{:value V}` (or bare V via classify-literal
                          downstream — `:value` map is always safe)
     ref-binding        → keyword (the bound fn's name)
     rename-only        → `{:as :renamed}` (no value/ref)
     list with items    → `[item …]` vector

   Falls back to nil for incomplete bindings (no value, no ref, no
   rename) — those don't contribute to the type-check input."
  [storage fn-by-id slot-by-id renamed-view-by-source b]
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


(defn- reconstruct-fn-def
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
        (let [parents (when (seq parent-ids)
                        (sp/read-entities storage :fn parent-ids))
              fn-by-id (-> parents
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


(defn- type-check-fn-after-mutation!
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
      {:reason (.getMessage e)})
    (catch Exception e
      ;; Defensive: any unexpected error during reconstruction is
      ;; surfaced (better than silent broken state, worse than
      ;; nothing).
      {:reason (str "type-check error: " (.getMessage e))})))


(defbase parse-slot-from-form
  "Form-data → slot-row fields. `:type-fn-id` is the slot's declared
   type (a fn-id pointing at a primitive / refinement / record). All
   slot fields except `:id` (auto-generated) and `:name` are optional
   on update; on create, `:name` and `:type-fn-id` are typically both
   present."
  [form-data]
  (cond-> {}
    (contains? form-data :name)
    (assoc :name (str (:name form-data)))
    (contains? form-data :type-fn-id)
    (assoc :type-fn-id (parse-uuid-or-clear (:type-fn-id form-data)))
    (contains? form-data :description)
    (assoc :description (:description form-data))
    (contains? form-data :required)
    (assoc :required (= "true" (:required form-data)))))


(defbase parse-fn-slot-from-form
  "Form-data → fn-slot junction row fields. Both refs are required on
   create; `:position` is optional (defaults to 0)."
  [form-data]
  (cond-> {}
    (contains? form-data :fn-id)
    (assoc :fn-id (parse-uuid-or-clear (:fn-id form-data)))
    (contains? form-data :slot-id)
    (assoc :slot-id (parse-uuid-or-clear (:slot-id form-data)))
    (contains? form-data :position)
    (assoc :position (Integer/parseInt (:position form-data)))))


(defbase parse-binding-from-form
  "Form-data → binding-row fields. Empty-as-clear convention applies
   to every nullable slot (`:value`, `:ref-fn-id`,
   `:type-override-fn-id`, `:description`) so an editor can drop an
   override by sending an empty form value. `:fn-id` and `:slot-id`
   are required for create; treated as updates of the existing row
   for PUT.

   `:rename-to` is intentionally NOT a binding field anymore — Phase
   6c moved rename info onto a dedicated renamed-view slot row
   (`slot.source-slot-id` FK link). UI rename code path keeps the
   `rename-to` form field for back-compat: `process-update-entity`
   drops it from the binding write and forwards it to
   `ensure-rename-slot!`, which creates / updates the renamed-view
   slot directly."
  [form-data]
  (cond-> {}
    (contains? form-data :fn-id)
    (assoc :fn-id (parse-uuid-or-clear (:fn-id form-data)))
    (contains? form-data :slot-id)
    (assoc :slot-id (parse-uuid-or-clear (:slot-id form-data)))
    (contains? form-data :value)
    (assoc :value (when-not (str/blank? (:value form-data))
                    (json/parse-string (:value form-data) true)))
    (contains? form-data :ref-fn-id)
    (assoc :ref-fn-id (parse-uuid-or-clear (:ref-fn-id form-data)))
    (contains? form-data :override-kind)
    (assoc :override-kind (when-not (str/blank? (:override-kind form-data))
                            (keyword (:override-kind form-data))))
    (contains? form-data :type-override-fn-id)
    (assoc :type-override-fn-id (parse-uuid-or-clear (:type-override-fn-id form-data)))
    (contains? form-data :description)
    (assoc :description (:description form-data))
    (contains? form-data :terminal)
    (assoc :terminal (= "true" (:terminal form-data)))
    (contains? form-data :list-append)
    (assoc :list-append (= "true" (:list-append form-data)))
    (contains? form-data :list-closed)
    (assoc :list-closed (= "true" (:list-closed form-data)))
    (contains? form-data :required)
    (assoc :required (when-not (str/blank? (:required form-data))
                       (= "true" (:required form-data))))))


(defbase parse-binding-list-item-from-form
  "Form-data → binding-list-item row fields. `:binding-id` and
   `:position` are required for create; the value is either a literal
   `:value` (JSON-decoded) or a `:ref-fn-id`, but not both."
  [form-data]
  (cond-> {}
    (contains? form-data :binding-id)
    (assoc :binding-id (parse-uuid-or-clear (:binding-id form-data)))
    (contains? form-data :position)
    (assoc :position (Integer/parseInt (:position form-data)))
    (contains? form-data :value)
    (assoc :value (when-not (str/blank? (:value form-data))
                    (json/parse-string (:value form-data) true)))
    (contains? form-data :ref-fn-id)
    (assoc :ref-fn-id (parse-uuid-or-clear (:ref-fn-id form-data)))
    (contains? form-data :literal)
    (assoc :literal (= "true" (:literal form-data)))))


;; === Action Handlers (context-aware) ===

(defbase process-create-entity
  [request]
  (let [storage (require-storage ctx)
        {:keys [type-str entity-type]} (extract-entity-params request)
        ;; `:body` may be a slurped string (when the internal-request
        ;; path uses `:ring-body`) OR a raw httpkit InputStream (when
        ;; reitit hands the original request through). `parse-query-string`
        ;; only accepts strings, so coerce explicitly.
        raw-body (:body request)
        body-str (cond
                   (string? raw-body) raw-body
                   (instance? java.io.InputStream raw-body) (clojure.core/slurp raw-body)
                   :else nil)
        form-data (when body-str
                    (into {} (map (fn [[k v]] [(keyword k) v])
                                  (parse-query-string body-str))))]
    (cond
      (and entity-type form-data)
      (let [parse-result
            (try {:entity-data
                  (case type-str
                    "fn" (parse-fn-from-form {:form-data form-data} ctx)
                    "ns" (parse-ns-from-form {:form-data form-data} ctx)
                    "slot" (parse-slot-from-form {:form-data form-data} ctx)
                    "fn-slot" (parse-fn-slot-from-form {:form-data form-data} ctx)
                    "binding" (parse-binding-from-form {:form-data form-data} ctx)
                    "binding-list-item"
                    (parse-binding-list-item-from-form {:form-data form-data} ctx)
                    nil)}
                 (catch clojure.lang.ExceptionInfo e
                   {:parse-rej {:reason (.getMessage e)}}))
            entity-data (:entity-data parse-result)
            parse-rej (:parse-rej parse-result)
            ;; Phase 6e — defend against direct
            ;; `POST /api/entities/fn-slot` calls that would attach
            ;; an own-slot to a composed fn without proper rename
            ;; context. Internal flows (parser, ensure-rename-slot!)
            ;; bypass this since they write via sp/create-entity
            ;; directly; only HTTP CRUD requests land here.
            ;;
            ;; Rule: a composed fn (parent-fn-ids non-empty) may only
            ;; own slots whose `:source-slot-id` is set. Positional
            ;; list-item renames are still NULL but they're created
            ;; only by the parser, never by this HTTP path.
            fn-slot-rej (when (and entity-data (= type-str "fn-slot"))
                          (let [fn-row (sp/read-entity storage :fn (:fn-id entity-data))
                                slot-row (sp/read-entity storage :slot (:slot-id entity-data))]
                            (when (and fn-row slot-row
                                       (seq (:parent-ids fn-row))
                                       (nil? (:source-slot-id slot-row)))
                              {:reason
                               (str "Composed fn " (pr-str (:name fn-row))
                                    " can only own slots that rename "
                                    "an inherited slot (set :source-slot-id). "
                                    "To add a new arg create a new fn-def "
                                    "with this one as parent.")})))
            ;; Cycle pre-check: the editor's `wouldCycle` filter
            ;; runs client-side before the request is sent, but
            ;; non-editor API consumers (scripts, tests, future
            ;; clients) need server enforcement too. The check is
            ;; cheap when `entity-data` carries no fn-id refs
            ;; (early-out via `cycle-check-rej`).
            ;; Cycle / MI / :terminal / :list-closed pre-checks. The
            ;; editor runs `wouldCycle` + `miCollisionCheck` client-
            ;; side; the others are server-only. Each surfaces as a
            ;; 400 with the rejection's `:reason` for the user.
            write-pre-rej (when entity-data
                            (write-rej storage entity-type entity-data))
            type-rej (or parse-rej
                         fn-slot-rej
                         write-pre-rej
                         (when (and entity-data (= type-str "binding"))
                           (type-check-binding-direct! storage entity-data nil)))
            humanise-create-error
            (fn [e]
              ;; Postgres unique-constraint violations are the common
              ;; case for entity-create and the raw message
              ;;   "ERROR: duplicate key value violates unique constraint
              ;;    \"idx_…_unique\" Detail: Key (...)=(...) already exists."
              ;; reads like an internal log line. Detect it and
              ;; render the user-facing version.
              (let [msg (or (.getMessage e) "")
                    nm (some-> entity-data :name)]
                (cond
                  (and (re-find #"(?i)duplicate key" msg) nm)
                  (str (name entity-type) " " (pr-str nm)
                       " already exists here — pick a different name")
                  (re-find #"(?i)duplicate key" msg)
                  (str (name entity-type) " already exists with these fields")
                  :else (or (some-> (ex-data e) :reason) msg
                            (str "Failed to create " type-str)))))
            create-result (when (and entity-data (nil? type-rej))
                            (try {:created (sp/create-entity storage entity-type entity-data)}
                                 (catch Exception e
                                   (log/error e "create-entity failed for"
                                              entity-type entity-data)
                                   {:error (humanise-create-error e)})))
            ;; Phase 6c — rename info no longer lives on the binding
            ;; row. The form-data may still carry `:rename-to`
            ;; (compat with UI's writeBindingFields helper); we
            ;; forward it to `ensure-rename-slot!` which creates /
            ;; updates the dedicated renamed-view slot. Errors are
            ;; logged but don't fail the whole request — the binding
            ;; itself is still useful even if the rename slot can't
            ;; be created.
            _rename-pair (when (and (:created create-result)
                                    (= type-str "binding")
                                    (contains? form-data :rename-to))
                           (try (ensure-rename-slot! storage
                                                     (:fn-id entity-data)
                                                     (:slot-id entity-data)
                                                     (when-not (str/blank? (:rename-to form-data))
                                                       (str (:rename-to form-data))))
                                (catch Exception e
                                  (log/error e "ensure-rename-slot! failed"))))
            ;; Post-create whole-fn type-check for binding mutations.
            ;; A binding can be individually-valid (covered by type-
            ;; check-binding-direct! above) yet break the OWNING fn-
            ;; def's aggregate check (e.g. when type-vars bound here
            ;; conflict with siblings, or when a ref's return doesn't
            ;; satisfy the slot's structural fn-shape). Run check-fn-
            ;; def! against the reconstructed fn-def shape; on
            ;; failure, delete the just-created binding so DB state
            ;; stays consistent.
            post-rej (when (and (:created create-result)
                                (#{"binding" "binding-list-item"} type-str))
                       (let [fn-id (cond
                                     (= type-str "binding")
                                     (:fn-id entity-data)
                                     (= type-str "binding-list-item")
                                     (some-> (:binding-id entity-data)
                                             (#(sp/read-entity storage :binding %))
                                             :fn-id))]
                         (when fn-id
                           (when-let [rej (type-check-fn-after-mutation! storage fn-id)]
                             (try (sp/delete-entity storage entity-type
                                                    (:created create-result))
                                  (catch Exception _))
                             rej))))]
        (cond
          type-rej {:status 400
                    :body (str "<p class=\"error\">" (:reason type-rej) "</p>")}
          post-rej {:status 400
                    :body (str "<p class=\"error\">" (:reason post-rej) "</p>")}
          (:created create-result)
          (do (invalidate! ctx storage entity-type
                           (assoc entity-data :id (:created create-result)))
              {:status 200 :headers {"HX-Trigger" "entityCreated"}
               :body "<p>Entity created successfully</p>"})
          :else {:status 400
                 :body (str "<p class=\"error\">"
                            (or (:error create-result)
                                (str "Failed to create " type-str))
                            "</p>")}))
      :else
      {:status 400 :body (str "<p class=\"error\">Invalid request — type="
                              (pr-str type-str) " entity-type=" (pr-str entity-type)
                              " body=" (pr-str (:body request))
                              " form-data=" (pr-str form-data) "</p>")})))


(defn- type-check-binding-direct!
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
        (cond
          (or (nil? new-value) (= actual :any))                    nil
          (types/subtype? actual expected)                         nil
          (and (types/refine-type? expected)
               (types/subtype? actual (types/refine-base expected))
               (let [r (types-check/literal-satisfies-refinement?
                         new-value (types/refine-constraint expected))]
                 (or (true? r) (= :unknown r))))                   nil
          :else
          {:reason (str "Type mismatch on value: expected " (pr-str expected)
                        ", got " (pr-str actual)
                        " (value " (pr-str new-value) ")")}))

      ;; Ref-binding case: bound fn's return type vs expected.
      (some? new-ref-id)
      (let [target-fn (some-> (sp/read-entity storage :fn new-ref-id))
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


(defn- ensure-rename-slot!
  "Phase 6b — keep UI rename atomically consistent with EDN parser
   output. When a binding write carries a non-blank `:rename-to=X`
   AND the binding's owner fn is composed (parent-fn-ids non-empty),
   the EDN parser would have ALSO emitted an own-slot row + fn-slot
   junction so descendants binding `X` find a slot identity to
   target. UI today writes only the binding row; this helper fills
   in the missing pair.

   Args: `fn-id` (binding's owner fn), `source-slot-id` (the slot
   the binding targets — becomes the new slot's :source-slot-id
   FK), `rename-to` (new name).

   Idempotent: walks the deterministic UUIDv5 scheme for slot-id
   and fn-slot-id, no-ops when the rows already exist (e.g. on
   repeat PUT). Returns nil; throws on unexpected storage failures
   so the caller can surface to the user."
  [storage fn-id source-slot-id rename-to]
  (when (and fn-id source-slot-id rename-to (not (str/blank? rename-to)))
    (let [fn-row (sp/read-entity storage :fn fn-id)
          parent-ids (:parent-ids fn-row)
          source-slot (sp/read-entity storage :slot source-slot-id)]
      (when (and (seq parent-ids) source-slot)
        (let [new-slot-id (records/slot-id fn-id rename-to)
              new-fn-slot-id (records/fn-slot-id fn-id new-slot-id)
              ;; Reuse source slot's type-fn-id so the renamed view
              ;; has the same type — UI doesn't expose type-override
              ;; in the rename popover. Type narrowing remains a
              ;; separate edit (the type chip).
              slot-row {:id new-slot-id
                        :name rename-to
                        :type-fn-id (:type-fn-id source-slot)
                        :required (or (:required source-slot) false)
                        :description nil
                        :source-slot-id source-slot-id}]
          (when-not (sp/read-entity storage :slot new-slot-id)
            (sp/create-entity storage :slot slot-row))
          (when-not (sp/read-entity storage :fn-slot new-fn-slot-id)
            (sp/create-entity storage :fn-slot
                              {:id new-fn-slot-id
                               :fn-id fn-id
                               :slot-id new-slot-id
                               :position 0})))))))


(defbase process-update-entity
  "PUT /api/entities/:type/:id — updates an entity from a form-encoded
   body. Mirror of `process-create-entity` but goes through
   `update-entity` and requires both `:type` and `:id` URI segments."
  [request]
  (let [storage (require-storage ctx)
        {:keys [type-str id-str entity-type]} (extract-entity-params request)
        raw-body (:body request)
        body-str (cond
                   (string? raw-body) raw-body
                   (instance? java.io.InputStream raw-body) (clojure.core/slurp raw-body)
                   :else nil)
        form-data (when body-str
                    (into {} (map (fn [[k v]] [(keyword k) v])
                                  (parse-query-string body-str))))]
    (cond
      (and entity-type id-str form-data)
      (let [entity-data (case type-str
                          "fn" (parse-fn-from-form {:form-data form-data} ctx)
                          "ns" (parse-ns-from-form {:form-data form-data} ctx)
                          "slot" (parse-slot-from-form {:form-data form-data} ctx)
                          "fn-slot" (parse-fn-slot-from-form {:form-data form-data} ctx)
                          "binding" (parse-binding-from-form {:form-data form-data} ctx)
                          "binding-list-item"
                          (parse-binding-list-item-from-form {:form-data form-data} ctx)
                          nil)
            id-uuid (try (java.util.UUID/fromString id-str) (catch Exception _ nil))
            ;; Pre-read the existing row so the post-update invalidation
            ;; can name the affected fn-id even when the form-data
            ;; doesn't carry it (binding / fn-slot updates only ship
            ;; the changed fields; their `:fn-id` is set at create
            ;; time and never re-sent).
            pre-existing (when (and entity-type id-uuid)
                           (try (sp/read-entity storage entity-type id-uuid)
                                (catch Exception _ nil)))
            ;; Same write-time guards as on create. The merged view
            ;; (existing FK fields + the changed fields from form-
            ;; data + the explicit id) gives us a complete picture
            ;; of what the row will look like after write.
            merged-data (merge pre-existing entity-data {:id id-uuid})
            write-pre-rej (when entity-data
                            (write-rej storage entity-type merged-data))
            type-rej (or write-pre-rej
                         (when (and entity-data (= type-str "binding") id-uuid)
                           (type-check-binding-direct! storage entity-data id-uuid)))
            updated (when (and entity-data (nil? type-rej))
                      (try (sp/update-entity storage entity-type
                                             (java.util.UUID/fromString id-str)
                                             entity-data)
                           (catch Exception e
                             (log/error e "update-entity failed for"
                                        entity-type id-str entity-data)
                             nil)))
            ;; Phase 6c — same as in process-create-entity. UPDATE
            ;; doesn't carry fn-id / slot-id in entity-data (they're
            ;; immutable after binding creation), so we read the
            ;; existing binding row for those. Form-data carries
            ;; the rename-to string; binding-row no longer does.
            _rename-pair (when (and updated (= type-str "binding") id-uuid
                                    (contains? form-data :rename-to))
                           (try
                             (let [existing (sp/read-entity storage :binding id-uuid)]
                               (when existing
                                 (ensure-rename-slot! storage
                                                      (:fn-id existing)
                                                      (:slot-id existing)
                                                      (when-not (str/blank? (:rename-to form-data))
                                                        (str (:rename-to form-data))))))
                             (catch Exception e
                               (log/error e "ensure-rename-slot! failed"))))]
        (cond
          type-rej {:status 400
                    :body (str "<p class=\"error\">" (:reason type-rej) "</p>")}
          updated  (do (invalidate! ctx storage entity-type
                                    ;; Existing row carries the immutable
                                    ;; FK fields (`:fn-id`, `:binding-id`)
                                    ;; the updater doesn't echo back; the
                                    ;; just-applied form-data overlays
                                    ;; field changes for completeness.
                                    (merge pre-existing entity-data {:id id-uuid}))
                       {:status 200 :headers {"HX-Trigger" "entityUpdated"}
                        :body "<p>Entity updated successfully</p>"})
          :else {:status 400 :body "<p class=\"error\">Failed to update entity</p>"}))
      :else
      {:status 400 :body "<p class=\"error\">Invalid update request</p>"})))


(defn- ns-non-empty-reason
  "Returns a human-readable reason if `ns-id` still has nested
   namespaces or fns living under it; nil if empty (and therefore
   safe to delete)."
  [storage ns-id]
  (let [child-ns (count (sp/query-entities storage :ns {:parent-id ns-id}))
        child-fns (count (sp/query-entities storage :fn {:namespace-id ns-id}))]
    (when (or (pos? child-ns) (pos? child-fns))
      (str "Namespace contains "
           (when (pos? child-ns) (str child-ns " sub-namespace" (when (> child-ns 1) "s")))
           (when (and (pos? child-ns) (pos? child-fns)) " and ")
           (when (pos? child-fns) (str child-fns " graph" (when (> child-fns 1) "s")))
           " — remove the contents first."))))


(defn- fn-in-use-reason
  "Returns a human-readable reason if `fn-id` is referenced by another
   fn (as a parent, via a binding's `ref-fn-id`, or via a list-item's
   `ref-fn-id`); nil if unreferenced. Slot/binding model: bindings
   replace arg-rows for ref tracking, with list-items handling
   sequence-element refs."
  [storage fn-id]
  (let [used-as-parent (count (filter (fn [f]
                                        (and (not= (:id f) fn-id)
                                             (some #(= % fn-id) (:parent-ids f))))
                                      (sp/query-entities storage :fn {})))
        ref-bindings (count (sp/query-entities storage :binding {:ref-fn-id fn-id}))
        ref-items (count (sp/query-entities storage :binding-list-item {:ref-fn-id fn-id}))
        refs (+ ref-bindings ref-items)]
    (when (or (pos? used-as-parent) (pos? refs))
      (str "Graph is "
           (when (pos? used-as-parent) (str "a parent of " used-as-parent " other graph"
                                            (when (> used-as-parent 1) "s")))
           (when (and (pos? used-as-parent) (pos? refs)) " and ")
           (when (pos? refs) (str "referenced by " refs " binding" (when (> refs 1) "s")))
           " — remove the dependents first."))))


(defbase process-delete-entity
  [request]
  (let [storage (require-storage ctx)
        {:keys [type-str entity-type id-str]} (extract-entity-params request)
        id (when id-str (try (java.util.UUID/fromString id-str)
                             (catch Exception _ nil)))]
    (cond
      (or (nil? entity-type) (nil? id))
      {:status 400 :body "<p class=\"error\">Invalid request</p>"}

      ;; Namespace delete — must be empty.
      (= entity-type :ns)
      (if-let [reason (ns-non-empty-reason storage id)]
        {:status 409 :body (str "<p class=\"error\">" reason "</p>")}
        (do (sp/delete-entity storage entity-type id)
            ;; ns rename / delete doesn't reach into closures; full
            ;; clear is overkill but `affected-fn-ids` returns nil
            ;; for `:ns` so `invalidate!` falls through to that path
            ;; today. Acceptable until we audit per-ns descendants.
            (invalidate! ctx storage entity-type {:id id})
            {:status 200 :headers {"HX-Trigger" "entityDeleted"} :body ""}))

      ;; Fn delete — must be unreferenced.
      (= entity-type :fn)
      (if-let [reason (fn-in-use-reason storage id)]
        {:status 409 :body (str "<p class=\"error\">" reason "</p>")}
        (do (sp/delete-entity storage entity-type id)
            (invalidate! ctx storage entity-type {:id id})
            {:status 200 :headers {"HX-Trigger" "entityDeleted"} :body ""}))

      ;; Other entity types (slot/fn-slot/binding/binding-list-item) —
      ;; no extra constraint. Pre-read so we still know the parent
      ;; fn-id after the row is gone (binding-list-item especially —
      ;; we'd otherwise lose the binding-id needed to derive fn-id).
      :else
      (let [snapshot (try (sp/read-entity storage entity-type id)
                          (catch Exception _ nil))]
        (sp/delete-entity storage entity-type id)
        (invalidate! ctx storage entity-type (or snapshot {:id id}))
        {:status 200 :headers {"HX-Trigger" "entityDeleted"} :body ""}))))


;; === Sequence operations =====================================================
;;
;; Slot/binding model: a sequence slot's items live in
;; `binding_list_item` rows ordered by `:position`. The binding row
;; for `(fn, sequence-slot)` carries `:list-append true` when items
;; extend a parent's items rather than replace them. Append/remove
;; operate on item rows directly — no linked-list pointers, just
;; positional indices.

(defn- find-sequence-binding
  "Find the binding row that owns the sequence items for `fn-id`. A fn
   that has at least one sequence-typed slot may have an own binding
   on it (with or without `:list-append`); when it doesn't yet, the
   first append creates one. Returns either the existing binding row
   or a synthetic `{:fn-id … :slot-id …}` placeholder pinning where
   the binding will be created.

   Resolves entirely against the in-memory graph cache — five-table
   reads collapse to one cache hit per editor sequence-edit click."
  [ctx fn-id]
  (let [graph (cached-or-load-graph ctx)
        fns-by-id (into {} (map (juxt :id identity)) (:fns graph))
        slots-by-id (into {} (map (juxt :id identity)) (:slots graph))
        fn-slots-by-fn (group-by :fn-id (:fn-slots graph))
        bindings-by-fn-slot (into {}
                                  (map (fn [b] [[(:fn-id b) (:slot-id b)] b]))
                                  (:bindings graph))
        sequence?
        (fn [slot]
          (= "sequence" (:name (get fns-by-id (:type-fn-id slot)))))
        ;; Walk parent chain in memory.
        chain (loop [acc [], seen #{}, queue [fn-id]]
                (if (empty? queue)
                  acc
                  (let [fid (first queue)
                        rest-q (vec (rest queue))]
                    (if (or (nil? fid) (contains? seen fid))
                      (recur acc seen rest-q)
                      (let [f (get fns-by-id fid)
                            pids (->> (:parent-ids f) (remove nil?) (remove seen))]
                        (recur (conj acc fid) (conj seen fid)
                               (into rest-q pids)))))))
        sequence-slot
        (some (fn [fid]
                (some (fn [fs]
                        (let [s (get slots-by-id (:slot-id fs))]
                          (when (sequence? s) s)))
                      (get fn-slots-by-fn fid [])))
              chain)]
    (when sequence-slot
      (or (get bindings-by-fn-slot [fn-id (:id sequence-slot)])
          {:fn-id fn-id :slot-id (:id sequence-slot) :synthetic true}))))


(defn- ensure-sequence-binding
  "Return the binding row for the fn's sequence slot, creating an
   empty `:list-append` binding if one doesn't exist yet. Used by
   `process-sequence-append` so the first append doesn't have to
   special-case the absent-binding path."
  [ctx fn-id]
  (let [b (find-sequence-binding ctx fn-id)]
    (cond
      (nil? b) nil
      (:synthetic b)
      (sp/create-entity (require-storage ctx) :binding
                        {:fn-id (:fn-id b) :slot-id (:slot-id b)
                         :list-append true})
      :else b)))


(defn- resolve-sequence-payload
  "Parses a sequence-op JSON body into the `binding-list-item` shape.
   Body shapes:
     {\"ref\":  \"fn-uuid-string\"}
     {\"ref-name\": \"my-fn\"}
     {\"value\": <any JSON>}"
  [storage body]
  (cond
    (contains? body :ref)
    {:ref-fn-id (java.util.UUID/fromString (:ref body))}

    (contains? body :ref-name)
    (if-let [target (first (sp/query-entities storage :fn {:name (:ref-name body)}))]
      {:ref-fn-id (:id target)}
      (throw (ex-info (str "Fn not found by name: " (:ref-name body))
                      {:type :sequence-op/fn-not-found :ref-name (:ref-name body)})))

    (contains? body :value)
    {:value (:value body)}

    :else
    (throw (ex-info "Sequence op body requires :ref, :ref-name, or :value"
                    {:type :sequence-op/invalid-body :body body}))))


(defbase process-sequence-append
  "POST /api/sequence/append/:fn-id
   Body: {\"ref\"|\"ref-name\"|\"value\": …}
   Appends one item to the sequence binding of fn :fn-id. Creates an
   empty `:list-append true` binding if the fn doesn't yet have one."
  [request]
  (let [storage (require-storage ctx)
        fn-id-str (or (get-in request [:path-params :fn-id])
                      (:fn-id-str (parse-uri-segments (:uri request))))
        fn-id (try (java.util.UUID/fromString fn-id-str) (catch Exception _ nil))
        raw-body (:body request)
        body-str (cond
                   (string? raw-body) raw-body
                   (instance? java.io.InputStream raw-body) (clojure.core/slurp raw-body)
                   :else nil)
        body (when body-str
               (try (json/parse-string body-str true) (catch Exception _ nil)))]
    (cond
      (nil? fn-id) {:status 400 :body "<p class=\"error\">Invalid fn-id</p>"}
      (nil? body)  {:status 400 :body "<p class=\"error\">JSON body required</p>"}
      :else
      (if-let [binding (ensure-sequence-binding ctx fn-id)]
        (let [items (sp/query-entities storage :binding-list-item
                                       {:binding-id (:id binding)})
              max-pos (apply max -1 (map :position items))
              new-pos (inc max-pos)
              payload (resolve-sequence-payload storage body)
              new-item (merge {:id (random-uuid)
                               :binding-id (:id binding)
                               :position new-pos}
                              payload)
              ;; Same write-time guards as the regular binding-list-
              ;; item create path: cycle through `:ref-fn-id`, plus
              ;; `:list-closed` enforcement so a sealed list can't
              ;; be extended via `/api/sequence/append`.
              pre-rej (write-rej storage :binding-list-item new-item)]
          (if pre-rej
            {:status 400 :body (str "<p class=\"error\">" (:reason pre-rej) "</p>")}
            (do (sp/create-entity storage :binding-list-item new-item)
                ;; The fn that owns the binding (and thus gets a fresh
                ;; sequence-element bound into its closure) is the seed.
                ;; Skip the binding-id round-trip — fn-id is right here.
                (exec-ctx/invalidate-graph-cache! ctx #{fn-id})
                {:status 200
                 :headers {"Content-Type" "application/json"}
                 :body (json/generate-string {:item-id (:id new-item)
                                              :position new-pos})})))
        {:status 404 :body "<p class=\"error\">Fn has no sequence slot</p>"}))))


(defbase process-sequence-remove
  "DELETE /api/sequence/item/:item-id
   Removes one binding-list-item. Positions of remaining items are
   left as-is (no compaction); editor reads items sorted by position
   so a hole is harmless."
  [request]
  (let [storage (require-storage ctx)
        item-id-str (or (get-in request [:path-params :item-id])
                        (:item-id-str (parse-uri-segments (:uri request))))
        item-id (try (java.util.UUID/fromString item-id-str) (catch Exception _ nil))]
    (cond
      (nil? item-id) {:status 400 :body "<p class=\"error\">Invalid item-id</p>"}
      :else
      (let [item (sp/read-entity storage :binding-list-item item-id)]
        (if (nil? item)
          {:status 404 :body "<p class=\"error\">Item not found</p>"}
          (do (sp/delete-entity storage :binding-list-item item-id)
              ;; Item carries `:binding-id`; `affected-fn-ids` follows
              ;; that to the binding's `:fn-id` for the seed.
              (invalidate! ctx storage :binding-list-item item)
              {:status 200 :body ""}))))))


;; === Tighten fn-typed binding effects =====================================
;;
;; Phase 8 carved out a 4-arity `[:fn args ret #{eff-set}]` form so a
;; slot whose callable should stay pure (or only do certain effects)
;; can REJECT impure callbacks at sync time. There was no UI to set
;; the constraint, so it lived only in EDN-side declarations. This
;; endpoint exposes it: the editor sends `{effects: ["io" "db"]}`,
;; the server constructs the 4-arity constraint, dedupes via
;; deterministic `anonymous-fn-id` (same shape collapses to one row),
;; and writes the binding's `:type-override-fn-id`.
;;
;; Subtype safety: the new constraint must be a SUBTYPE of the
;; current effective fn-type. Tightening from a 3-arity (no eff
;; constraint = any effects allowed) to a 4-arity is always a
;; narrowing; tightening across two 4-arities requires the new
;; eff-set ⊆ old. `subtype?` enforces this and we surface the
;; rejection as a 400.

(declare commit-tighten!)


(defn- json->type-form
  "Local copy of the inverse-of-cheshire encoding used by
   `/api/types/compatible` — keeps `tighten-fn-type-impl!` self-
   contained without reaching into the public defbase. Strings →
   keywords, recurses into vectors / maps, leaves everything else
   alone."
  [x]
  (cond
    (string? x)     (keyword x)
    (map? x)        (into {}
                          (map (fn [[k v]]
                                 [(if (string? k) (keyword k) k)
                                  (json->type-form v)]))
                          x)
    (sequential? x) (mapv json->type-form x)
    :else           x))


(defn- tighten-fn-type-impl!
  "Compute a narrower fn-type constraint by selectively replacing
   `args`, `ret`, or `effects` from the current effective type.
   `delta` is `{:args {…} :ret T :effects [\"io\" …]}` — any subset.
   Defaults preserve the current value: 3-arity gets a 4th element
   only when `:effects` is supplied, and `:args` / `:ret` keep
   whatever the current shape carries when omitted.

   Subtype-checks the new constraint against the current; rejects
   widenings. Then runs the bound-callable safety check (effects
   only — narrower args / ret don't introduce new escape paths the
   way effects do, and the post-write `check-fn-def!` catches deeper
   structural mismatches)."
  [storage binding-id delta]
  (let [b (sp/read-entity storage :binding binding-id)]
    (cond
      (nil? b)
      {:status 404 :reason "Binding not found"}

      :else
      (let [slot (sp/read-entity storage :slot (:slot-id b))
            cur-tfn-id (or (:type-override-fn-id b) (:type-fn-id slot))
            cur-tfn (when cur-tfn-id (sp/read-entity storage :fn cur-tfn-id))
            cur-c (:constraint cur-tfn)]
        (cond
          (or (not (vector? cur-c)) (not= :fn (first cur-c)))
          {:status 400
           :reason (str "Slot's effective type is not an fn-type ("
                        (pr-str cur-c) "); can't tighten.")}

          :else
          (let [cur-args (or (nth cur-c 1) {})
                cur-ret (nth cur-c 2)
                cur-eff (when (= 4 (count cur-c)) (nth cur-c 3))
                {:keys [args ret effects]} delta
                ;; Args delta is a per-name override map. Merge so
                ;; unmentioned arg names keep their current type.
                new-args (if (map? args)
                           (merge cur-args (json->type-form args))
                           cur-args)
                new-ret (if (some? ret)
                          (json->type-form ret)
                          cur-ret)
                new-eff (cond
                          (some? effects) (into #{} (map keyword) effects)
                          cur-eff         cur-eff
                          :else           nil)
                new-c (cond-> [:fn new-args new-ret] new-eff (conj new-eff))
                ok? (types/subtype? new-c cur-c)]
            (if-not ok?
              {:status 400
               :reason (str "Proposed type " (pr-str new-c)
                            " is not a narrowing of " (pr-str cur-c)
                            " — every component (args / ret / effects)"
                            " must be a subtype of the current value.")}
              ;; Bound-callable effect check — same as the
              ;; effect-only path. Args / ret narrowings don't
              ;; introduce new escape paths beyond what
              ;; `check-fn-def!` covers.
              (let [eff-set (or new-eff #{})
                    ref-fn-id (:ref-fn-id b)
                    ref-row (when ref-fn-id (sp/read-entity storage :fn ref-fn-id))
                    ref-info (when-let [n (:name ref-row)]
                               (registry/rich-type-of (keyword n)))
                    ref-effects (or (:effects ref-info) #{})
                    escapes (when (and (some? new-eff) (seq ref-effects))
                              (clojure.set/difference (set ref-effects) eff-set))]
                (if (seq escapes)
                  {:status 400
                   :reason (str "Bound fn `" (:name ref-row) "`"
                                " produces effects " (vec (sort escapes))
                                " that the requested constraint "
                                (vec (sort eff-set))
                                " forbids. Either widen the effect set"
                                " or rebind to a fn with effects ⊆ "
                                (vec (sort eff-set)) ".")}
                  (commit-tighten! storage binding-id b new-c nil))))))))))


(defn- tighten-effects-impl!
  "Backwards-compatible thin wrapper — `tighten-fn-type-impl!` with
   only the `:effects` delta filled in. Tests load this symbol
   directly; production callers go through the form-driven defbase."
  [storage binding-id effects-vec]
  (tighten-fn-type-impl! storage binding-id {:effects effects-vec}))


(defn- commit-tighten!
  "Helper for `tighten-effects-impl!` — performs the actual write
   (anon fn-row create + binding update) once the safety checks have
   passed. Pulled out so the impl's let-and-cond chain stays
   readable."
  [storage binding-id b new-c _effects-vec]
  (let [hash-hex (records/digest-hex "SHA-1" (pr-str new-c))
        new-id (records/anonymous-fn-id hash-hex)
        pre-override (:type-override-fn-id b)]
    ;; Find or create. Storage upsert is the natural fit — same
    ;; id ⇒ same row, no orphan duplicates.
    (when-not (sp/read-entity storage :fn new-id)
      (sp/create-entity storage :fn
                        {:id new-id
                         :name nil
                         :namespace-id nil
                         :parent-ids []
                         :impl-hash nil
                         :base-fn-id nil
                         :element-fn-id nil
                         :return-type-fn-id nil
                         :anonymous-hash hash-hex
                         :constraint new-c}))
    (sp/update-entity storage :binding binding-id
                      {:type-override-fn-id new-id})
    ;; Aggregate type-check on the owning fn. The bound-callable
    ;; effect check above is the primary guard; this catches
    ;; whatever else `check-fn-def!` evaluates (return-type
    ;; subtype, deeper structural unification, etc.). Roll back
    ;; on rejection so the binding doesn't end up in a broken
    ;; state the user has to debug.
    (let [post-rej (type-check-fn-after-mutation! storage (:fn-id b))]
      (if post-rej
        (do (sp/update-entity storage :binding binding-id
                              {:type-override-fn-id pre-override})
            {:status 400
             :reason (str "Tightening rejected by post-write "
                          "type-check: " (:reason post-rej))})
        {:status 200
         :result {:type-override-fn-id new-id
                  :constraint new-c
                  :fn-id (:fn-id b)}}))))


(defbase process-tighten-binding-effects
  "POST /api/bindings/:binding-id/tighten-fn-effects
   Body: `{\"args\"?: {…}, \"ret\"?: T, \"effects\"?: [\"db\" …]}`

   For an fn-typed binding, narrow the slot's effective type by
   selectively replacing `args`, `ret`, or `effects`. Any subset
   may be supplied; omitted components keep their current value.
   Effects-only is the special case the path name still reflects;
   args / ret narrowings work the same way (subtype check, dedup
   into anon fn-row, write `:type-override-fn-id`). Sync-time
   `:expects-effects` checks start enforcing the narrowed contract
   immediately."
  [request]
  (let [storage (require-storage ctx)
        binding-id-str (or (get-in request [:path-params :binding-id])
                           (:binding-id-str (parse-uri-segments (:uri request))))
        binding-id (try (java.util.UUID/fromString binding-id-str)
                        (catch Exception _ nil))
        body (read-json-body request)
        ;; Pull each delta component out of the JSON body. `effects`
        ;; must be a JSON array (or absent); `args` must be a map
        ;; (or absent); `ret` is any type-form (or absent). The
        ;; impl handles defaulting from the current constraint.
        effects-val (:effects body)
        args-val (:args body)
        ret-val (:ret body)
        delta (cond-> {}
                (some? effects-val) (assoc :effects effects-val)
                (some? args-val)    (assoc :args args-val)
                (some? ret-val)     (assoc :ret ret-val))]
    (cond
      (nil? binding-id)
      {:status 400 :body "<p class=\"error\">Invalid binding-id</p>"}

      (and (some? effects-val) (not (sequential? effects-val)))
      {:status 400 :body "<p class=\"error\">'effects' must be a JSON array</p>"}

      (and (some? args-val) (not (map? args-val)))
      {:status 400 :body "<p class=\"error\">'args' must be a JSON object</p>"}

      (empty? delta)
      {:status 400 :body "<p class=\"error\">Body must include at least one of args / ret / effects</p>"}

      :else
      (let [{:keys [status reason result]}
            (tighten-fn-type-impl! storage binding-id delta)]
        (if (= 200 status)
          (do (invalidate! ctx storage :binding {:fn-id (:fn-id result)})
              {:status 200
               :headers {"Content-Type" "application/json"}
               :body (json/generate-string result)})
          {:status status
           :body (str "<p class=\"error\">" reason "</p>")})))))


;; === Pure Functions ===

(defbase parse-form-body
  [request]
  (let [body (:body request)
        content-type (get-in request [:headers "content-type"] "")]
    (if (and body (str/includes? content-type "application/x-www-form-urlencoded"))
      (or (parse-query-string body) {})
      {})))


(defbase parse-json-body
  [request]
  (let [body (:body request)
        content-type (get-in request [:headers "content-type"] "")]
    (when (and body (str/includes? content-type "application/json"))
      (json/parse-string body true))))


(defbase str-to-uuid
  [string]
  (try
    (java.util.UUID/fromString string)
    (catch Exception _ nil)))


;; === Registry ===

(def impls
  {:list-entities list-entities
   :get-entity get-entity
   :create-entity create-entity
   :update-entity update-entity
   :delete-entity delete-entity
   :list-all-graph-entities list-all-graph-entities
   :all-rich-types all-rich-types
   :types-compatible types-compatible
   :types-candidates types-candidates
   :render-entity-details-view render-entity-details-view
   :render-entity-form-view render-entity-form-view
   :process-create-entity process-create-entity
   :process-update-entity process-update-entity
   :process-delete-entity process-delete-entity
   :process-sequence-append process-sequence-append
   :process-sequence-remove process-sequence-remove
   :process-tighten-binding-effects process-tighten-binding-effects
   :render-entity-actions render-entity-actions
   :parse-fn-from-form parse-fn-from-form
   :parse-ns-from-form parse-ns-from-form
   :parse-slot-from-form parse-slot-from-form
   :parse-fn-slot-from-form parse-fn-slot-from-form
   :parse-binding-from-form parse-binding-from-form
   :parse-binding-list-item-from-form parse-binding-list-item-from-form
   :parse-form-body parse-form-body
   :parse-json-body parse-json-body
   :str-to-uuid str-to-uuid})
