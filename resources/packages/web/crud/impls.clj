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


;; Operators / kind heads inside a constraint vector that are NOT
;; type-row references — used by `constraint-type-ref-names` to
;; filter them out. Anything else that looks like a keyword is
;; treated as a candidate type-row name. False positives (e.g. a
;; variant tag that happens to share a name with an existing type)
;; only over-reject; under-rejection would let cycles slip through.
(def ^:private constraint-op-keywords
  #{:union :variant :fn :refine :and :or :not
    :> :>= :< :<= := :not= :matches :in :exists :every})


(defn- constraint-type-ref-names
  "Walk a constraint vector and collect every keyword nested anywhere
   inside it as a bare-name string set, minus the operator heads in
   `constraint-op-keywords`. Used to find type-row references hidden
   in `[:union T1 T2 …]` / `[:variant :tag1 T1 …]` / `[:fn {…} T]`
   shapes — those are stored as keywords, NOT FK columns, so the
   FK-only cycle walker misses them."
  [c]
  (let [walk (fn walk [x acc]
               (cond
                 (keyword? x)
                 (if (constraint-op-keywords x)
                   acc
                   (conj acc (name x)))
                 (map? x)
                 (reduce-kv (fn [a _k v] (walk v a)) acc x)
                 (sequential? x)
                 (reduce (fn [a el] (walk el a)) acc x)
                 :else acc))]
    (walk c #{})))


(defn- resolve-constraint-refs-to-ids
  "Batched name → fn-id resolution for the set of bare-name strings
   produced by `constraint-type-ref-names`. Names that don't resolve
   (e.g. variant tags) are silently dropped. Empty input → empty set."
  [storage names]
  (if (empty? names)
    #{}
    (->> (sp/query-entities storage :fn {:name (vec names)})
         (keep :id)
         set)))


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
                        [:base-fn-id :element-fn-id :return-type-fn-id])
          ;; Constraint-vector type-refs — keyword type names buried
          ;; inside `[:union …]` / `[:variant …]` / `[:fn …]` shapes.
          ;; The FK-only walker misses these because they're stored
          ;; as JSONB keywords, not UUID columns.
          constraint-refs (when-let [c (:constraint entity-data)]
                            (resolve-constraint-refs-to-ids
                              storage (constraint-type-ref-names c)))]
      (or (some #(cycle-check-pair storage own-id %) parent-ids)
          (some #(cycle-check-pair storage own-id %) fk-refs)
          (some #(cycle-check-pair storage own-id %) constraint-refs)))

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


;; === Constraint-shape validation ============================================
;;
;; The CRUD write path historically accepted any `:constraint` payload
;; that round-tripped through `parse-fn-from-form`. That left every
;; ill-formed shape — empty `[:and]`, single-branch `[:union :int]`,
;; non-even `[:variant t1 T1 orphan]` — quietly persisted to land as a
;; runtime puzzle later. The fixes belong here, on the boundary, not
;; somewhere downstream where the bad shape has already propagated.

(defn- resolve-base-name
  "Walk `:base-fn-id` through refinements down to a primitive keyword
   so `constraint-compatible-with-base?` can dispatch. nil if the
   chain dead-ends or the base isn't a known primitive."
  [storage base-fn-id]
  (loop [cur base-fn-id, depth 0]
    (when (and cur (< depth 32))
      (when-let [f (sp/read-entity storage :fn cur)]
        (let [nm (some-> (:name f) keyword)]
          (cond
            ;; Reached a primitive (no parents, no impl, no constraint).
            (and nm
                 (empty? (:parent-ids f))
                 (nil? (:impl-hash f))
                 (nil? (:base-fn-id f))
                 (nil? (:element-fn-id f))
                 (nil? (:constraint f))
                 (#{:null :uuid :text :int :bool :numeric :timestamptz
                    :jsonb :bytes :any :fn :sequence :keyword :float} nm))
            nm
            ;; Refinement-of-refinement: descend.
            (:base-fn-id f)
            (recur (:base-fn-id f) (inc depth))
            ;; Otherwise unknown shape — bail.
            :else nil))))))


(defn- constraint-shape-rej
  "Validate the high-level shape of `:constraint` and (for refinements)
   its compatibility with the base. Returns `{:reason …}` on rejection
   or nil. Only runs against `:fn` writes — other entity types pass
   through."
  [storage entity-type entity-data]
  (when (= entity-type :fn)
    (let [c (:constraint entity-data)
          base-id (:base-fn-id entity-data)]
      (cond
        ;; No constraint → nothing to check here.
        (nil? c) nil

        (not (vector? c))
        {:reason (str "Constraint must be a vector; got " (pr-str c))}

        :else
        (let [head (first c)
              args (rest c)]
          (cond
            (= head :union)
            (cond
              (< (count args) 2)
              {:reason "Union needs ≥ 2 branches"}
              (not= (count args) (count (set args)))
              {:reason "Union has duplicate branches"})

            (= head :variant)
            (cond
              (zero? (count args))
              {:reason "Variant needs ≥ 1 (tag, type) pair"}
              (odd? (count args))
              {:reason "Variant tag/type pairs must come in pairs (odd element count)"}
              (let [tags (take-nth 2 args)]
                (not= (count tags) (count (set tags))))
              {:reason "Variant has duplicate tags"})

            (#{:and :or} head)
            (when (zero? (count args))
              {:reason (str (name head) " constraint needs ≥ 1 operand")})

            (= head :fn)
            (cond
              (not (map? (first args)))
              {:reason "fn-type constraint must be `[:fn {args-map} ret-type]`"}
              (< (count args) 2)
              {:reason "fn-type constraint must include both args-map and return-type"})

            ;; Atomic op (`:>`, `:matches`, …) on a refinement — defer
            ;; to types/check.
            (and base-id (some? head))
            (when-let [base-name (resolve-base-name storage base-id)]
              (when-not (types-check/constraint-compatible-with-base? base-name c)
                {:reason (str "Constraint op " (pr-str head)
                              " is not legal on base type :" (name base-name))}))))))))


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
      (some-> (constraint-shape-rej storage entity-type entity-data)
              (assoc :type :constraint-violation/constraint-shape))
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


(defn- compute-fn-role
  "Mirrors `executor.compile-runtime/type-row-role`, with one tweak:
   `impl-hash` is currently always `nil` in storage (sync uses a
   placeholder hash impl — see `executor.composition.core/
   compute-impl-hash`), so a base-fn looks indistinguishable from a
   synthesised record in storage alone. We cross-reference the
   `rich-types-registry` snapshot: if a fn-name has a non-empty
   `:args` map and is NOT marked `:type-row?`, it's a base-fn even
   when `impl-hash` is nil. Roles: `:composed`, `:base-fn`,
   `:refinement`, `:list`, `:union`, `:variant`, `:fn-type`,
   `:record`, `:primitive`."
  [fn-row has-slots? rich-snapshot]
  (let [c (:constraint fn-row)
        rich-entry (some-> (:name fn-row) keyword rich-snapshot)
        base-fn-via-registry? (and rich-entry
                                   (not (:type-row? rich-entry))
                                   (seq (:args rich-entry)))]
    (cond
      (seq (:parent-ids fn-row))                 :composed
      (some? (:impl-hash fn-row))                :base-fn
      base-fn-via-registry?                      :base-fn
      (some? (:base-fn-id fn-row))               :refinement
      (some? (:element-fn-id fn-row))            :list
      (and (vector? c) (= :union (first c)))     :union
      (and (vector? c) (= :variant (first c)))   :variant
      (and (vector? c) (= :fn (first c)))        :fn-type
      has-slots?                                 :record
      :else                                      :primitive)))


(defbase list-all-graph-entities
  []
  ;; Slot/fn-slot/binding model: dump every storage row the editor
  ;; needs to render the graph. Routes through the shared graph-cache
  ;; (populated by layout / compile-runtime) so editor refreshes after
  ;; mutations don't re-query the same five tables every time.
  ;;
  ;; Each fn-row is augmented with a `:role` field so the sidebar can
  ;; group entries into Types vs Functions sections without an extra
  ;; round-trip through `/api/types`.
  (let [storage (require-storage ctx)
        base (cached-or-load-graph ctx)
        fn-slots-by-fn (group-by :fn-id (:fn-slots base))
        rich-snapshot (registry/rich-types-snapshot)
        roled-fns (mapv (fn [f]
                          (assoc f :role
                                 (compute-fn-role
                                   f
                                   (boolean (seq (get fn-slots-by-fn (:id f))))
                                   rich-snapshot)))
                        (:fns base))]
    (-> base
        (assoc :fns roled-fns)
        (assoc :namespaces (vec (sp/query-entities storage :ns {}))))))


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
                  (assoc acc n (cond-> {:return structural :args {} :effects #{}
                                        :type-row? true}
                                 (and (:description f)
                                      (seq (:description f)))
                                 (assoc :description (:description f))))
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


(defn- constraint-contains-type-ref?
  "Walk a `constraint` payload (`[:union T1 T2 …]`, `[:variant tag1 T1
   tag2 T2 …]`, `[:fn {arg T} ret eff-set]`) and return true if any
   nested keyword/string entry resolves to `type-name`."
  [constraint type-name]
  (let [target (some-> type-name name)]
    (boolean
      (when target
        (letfn [(matches? [x]
                  (cond
                    (keyword? x) (= (name x) target)
                    (string? x)  (= x target)
                    :else        false))
                (walk [x]
                  (cond
                    (matches? x) true
                    (vector? x)  (some walk x)
                    (seq? x)     (some walk x)
                    (map? x)     (or (some walk (keys x))
                                     (some walk (vals x)))
                    :else        false))]
          (walk constraint))))))


(declare resolve-type-fn-id)
(declare parse-uuid-or-clear)


(defn- resolve-type-fn-id-or-throw
  "Like `resolve-type-fn-id` but throws `:type-row/unknown-type` when
   the name doesn't match anything. Used by the compound-create
   endpoints so the caller gets a clean error instead of a NULL
   foreign key violation downstream."
  [storage type-ref]
  (or (resolve-type-fn-id storage type-ref)
      (throw (ex-info (str "Unknown type: " (pr-str type-ref))
                      {:type :type-row/unknown-type :type-ref type-ref}))))


(defbase process-create-record-type
  "Atomically create a record type-row: one fn-row + N slot-rows + N
   fn-slot-junctions. JSON body shape:
     {namespace-id?, name, description?,
      fields: [{name, type, description?, required?}, …]}
   `type` per field accepts a name (`\"int\"` / `\"ring-request-shape\"`)
   or a UUID. On any sub-write failure the partial fn-row is
   deleted (best-effort rollback) and the error surfaces to the
   caller via `{:ok false :error \"…\"}`."
  [request]
  (let [storage (require-storage ctx)
        body (read-json-body request)
        nm (some-> (:name body) str)
        ns-raw (:namespace-id body)
        ns-id (when-not (str/blank? (str ns-raw)) (parse-uuid-or-clear (str ns-raw)))
        desc (:description body)
        fields (vec (:fields body))]
    (cond
      (str/blank? nm)
      {:ok false :error "name required"}

      (empty? fields)
      {:ok false :error "fields required (a record needs ≥1 field)"}

      :else
      (let [own-id (java.util.UUID/randomUUID)
            created (atom [])
            cleanup (fn []
                      (doseq [[et id] (reverse @created)]
                        (try (sp/delete-entity storage et id) (catch Exception _ nil))))]
        (try
          (sp/create-entity storage :fn
                            (cond-> {:id own-id
                                     :name nm
                                     :namespace-id ns-id
                                     :parent-ids []
                                     :impl-hash nil
                                     :base-fn-id nil
                                     :element-fn-id nil
                                     :return-type-fn-id nil
                                     :anonymous-hash nil
                                     :constraint nil}
                              (and desc (seq desc)) (assoc :description desc)))
          (swap! created conj [:fn own-id])
          (doseq [[idx field] (map-indexed vector fields)]
            (let [field-name (some-> (:name field) str)
                  type-id (resolve-type-fn-id-or-throw storage (:type field))
                  field-desc (:description field)
                  required? (if (contains? field :required) (boolean (:required field)) true)
                  slot-id (java.util.UUID/randomUUID)
                  fn-slot-id (java.util.UUID/randomUUID)]
              (when (str/blank? field-name)
                (throw (ex-info "field name required"
                                {:type :type-row/field-missing-name})))
              (sp/create-entity storage :slot
                                (cond-> {:id slot-id
                                         :name field-name
                                         :type-fn-id type-id
                                         :required required?}
                                  (and field-desc (seq field-desc))
                                  (assoc :description field-desc)))
              (swap! created conj [:slot slot-id])
              (sp/create-entity storage :fn-slot
                                {:id fn-slot-id
                                 :fn-id own-id
                                 :slot-id slot-id
                                 :position idx})
              (swap! created conj [:fn-slot fn-slot-id])))
          (invalidate! ctx storage :fn {:id own-id})
          {:ok true :id (str own-id) :name nm}
          (catch clojure.lang.ExceptionInfo e
            (cleanup)
            {:ok false :error (.getMessage e) :data (ex-data e)})
          (catch Exception e
            (cleanup)
            {:ok false :error (str (.getMessage e))}))))))


(defbase process-create-list-type
  "Atomically create a list type-row: one fn-row with `element-fn-id`
   plus the synthesised `items` slot. JSON body:
     {namespace-id?, name, description?, element-type}
   `element-type` accepts a type-name or a UUID."
  [request]
  (let [storage (require-storage ctx)
        body (read-json-body request)
        nm (some-> (:name body) str)
        ns-raw (:namespace-id body)
        ns-id (when-not (str/blank? (str ns-raw)) (parse-uuid-or-clear (str ns-raw)))
        desc (:description body)
        element-ref (:element-type body)]
    (cond
      (str/blank? nm)
      {:ok false :error "name required"}

      (nil? element-ref)
      {:ok false :error "element-type required"}

      :else
      (let [own-id (java.util.UUID/randomUUID)
            created (atom [])
            cleanup (fn []
                      (doseq [[et id] (reverse @created)]
                        (try (sp/delete-entity storage et id) (catch Exception _ nil))))]
        (try
          (let [elem-id (resolve-type-fn-id-or-throw storage element-ref)
                seq-id (resolve-type-fn-id-or-throw storage "sequence")
                slot-id (java.util.UUID/randomUUID)
                fn-slot-id (java.util.UUID/randomUUID)]
            (sp/create-entity storage :fn
                              (cond-> {:id own-id
                                       :name nm
                                       :namespace-id ns-id
                                       :parent-ids []
                                       :impl-hash nil
                                       :base-fn-id nil
                                       :element-fn-id elem-id
                                       :return-type-fn-id nil
                                       :anonymous-hash nil
                                       :constraint nil}
                                (and desc (seq desc)) (assoc :description desc)))
            (swap! created conj [:fn own-id])
            (sp/create-entity storage :slot
                              {:id slot-id
                               :name "items"
                               :type-fn-id seq-id
                               :required true})
            (swap! created conj [:slot slot-id])
            (sp/create-entity storage :fn-slot
                              {:id fn-slot-id
                               :fn-id own-id
                               :slot-id slot-id
                               :position 0})
            (swap! created conj [:fn-slot fn-slot-id])
            (invalidate! ctx storage :fn {:id own-id})
            {:ok true :id (str own-id) :name nm})
          (catch clojure.lang.ExceptionInfo e
            (cleanup)
            {:ok false :error (.getMessage e) :data (ex-data e)})
          (catch Exception e
            (cleanup)
            {:ok false :error (str (.getMessage e))}))))))


(defbase process-update-record-type
  "Update an existing record type-row by computing the diff of the
   submitted field list against the row's current fn-slots, then
   atomically applying it. JSON body:
     {id, name?, description?,
      fields: [{name, type, description?, required?}, …]}
   Matching is by `(name, resolved type-fn-id)` against the slots
   the record currently exposes:
     - same `(name, type)` → keep existing slot, refresh position
     - new entry / changed type → create a fresh slot (slots are
       immutable so a retype is always a new slot)
     - missing from input → drop the fn-slot junction (the slot
       row itself stays — it may be shared by other fns)
   Field renames are modelled as a remove + add, losing any
   bindings the user had carved against the old (name, type) pair.

   Atomicity: there's no with-transaction at the protocol layer,
   so we journal every write into a `created` atom together with
   enough state to undo it. On any failure mid-flight we walk the
   journal in reverse — re-INSERTing deleted fn-slots and
   DELETing freshly-minted slots / fn-slots — to leave the row
   in its prior shape.

   Caveat: this does NOT garbage-collect orphaned slots. A slot
   that the record dropped but no other fn references is left
   floating; storage-level shared-slot semantics mean we can't
   tell from here whether deletion is safe."
  [request]
  (let [storage (require-storage ctx)
        body (read-json-body request)
        fn-id-raw (:id body)
        fn-id (when fn-id-raw
                (try (java.util.UUID/fromString (str fn-id-raw))
                     (catch Exception _ nil)))
        nm (some-> (:name body) str)
        desc (when (contains? body :description) (:description body))
        fields (vec (:fields body))]
    (cond
      (nil? fn-id)
      {:ok false :error "id required (UUID)"}

      (empty? fields)
      {:ok false :error "fields required (a record needs ≥1 field)"}

      :else
      (let [existing-fn (first (sp/query-entities storage :fn {:id fn-id}))
            current-fss (sp/query-entities storage :fn-slot {:fn-id fn-id})
            current-slot-ids (mapv :slot-id current-fss)
            current-slots (when (seq current-slot-ids)
                            (sp/query-entities storage :slot
                                               {:id current-slot-ids}))
            slots-by-id (into {} (map (juxt :id identity)) (or current-slots []))
            ;; Match by (name, type-fn-id): retypes must yield a new
            ;; slot since slot rows are immutable.
            slots-by-name+type (into {} (map (fn [fs]
                                               (let [s (get slots-by-id (:slot-id fs))]
                                                 [[(:name s) (:type-fn-id s)] fs])))
                                     current-fss)
            journal (atom [])
            cleanup (fn []
                      (doseq [entry (reverse @journal)]
                        (try
                          (case (:op entry)
                            :create (sp/delete-entity storage (:entity-type entry) (:id entry))
                            :delete (sp/create-entity storage (:entity-type entry) (:row entry))
                            nil)
                          (catch Exception _ nil))))]
        (cond
          (nil? existing-fn)
          {:ok false :error (str "fn " fn-id " not found")}

          :else
          (try
            ;; Phase 1: resolve every incoming field's type up front
            ;; so a typo doesn't leave us with a half-rewritten row.
            (let [resolved (mapv (fn [field]
                                   (let [field-name (some-> (:name field) str)
                                         _ (when (str/blank? field-name)
                                             (throw (ex-info "field name required"
                                                             {:type :type-row/field-missing-name})))
                                         type-id (resolve-type-fn-id-or-throw storage (:type field))
                                         required? (if (contains? field :required)
                                                     (boolean (:required field)) true)]
                                     {:name field-name
                                      :type-fn-id type-id
                                      :description (:description field)
                                      :required required?}))
                                 fields)
                  ;; Decide per-field whether to reuse a current slot
                  ;; (same name+type) or mint a new one.
                  kept-fs-ids (atom #{})
                  assignments (mapv (fn [r]
                                      (if-let [fs (get slots-by-name+type
                                                       [(:name r) (:type-fn-id r)])]
                                        (do (swap! kept-fs-ids conj (:id fs))
                                            {:slot-id (:slot-id fs)
                                             :fn-slot-id (:id fs)
                                             :reuse? true})
                                        {:slot-id (java.util.UUID/randomUUID)
                                         :fn-slot-id (java.util.UUID/randomUUID)
                                         :reuse? false
                                         :spec r}))
                                    resolved)]
              ;; Phase 2: create slots for new entries.
              (doseq [a assignments
                      :when (not (:reuse? a))]
                (let [{:keys [slot-id spec]} a]
                  (sp/create-entity storage :slot
                                    (cond-> {:id slot-id
                                             :name (:name spec)
                                             :type-fn-id (:type-fn-id spec)
                                             :required (:required spec)}
                                      (and (:description spec) (seq (:description spec)))
                                      (assoc :description (:description spec))))
                  (swap! journal conj {:op :create :entity-type :slot :id slot-id})))
              ;; Phase 3: delete every existing fn-slot that we're
              ;; not keeping. Journal the full row so cleanup can
              ;; resurrect it on failure downstream.
              (doseq [fs current-fss
                      :when (not (@kept-fs-ids (:id fs)))]
                (sp/delete-entity storage :fn-slot (:id fs))
                (swap! journal conj {:op :delete :entity-type :fn-slot :row fs}))
              ;; Phase 4: bump positions on kept fn-slots that
              ;; moved, and create fresh fn-slots for new entries.
              ;; Position is unique per (fn-id, position) so we
              ;; never re-use a position already on a row we kept.
              (doseq [[idx a] (map-indexed vector assignments)]
                (cond
                  (:reuse? a)
                  (let [old-fs (first (filter #(= (:id %) (:fn-slot-id a)) current-fss))]
                    (when (and old-fs (not= (:position old-fs) idx))
                      ;; Two-step shuffle: delete + re-create with new
                      ;; position. The journal records both legs so a
                      ;; later failure can rewind to the pre-update row.
                      (sp/delete-entity storage :fn-slot (:id old-fs))
                      (swap! journal conj {:op :delete :entity-type :fn-slot :row old-fs})
                      (let [new-row (assoc old-fs :position idx)]
                        (sp/create-entity storage :fn-slot new-row)
                        (swap! journal conj {:op :create :entity-type :fn-slot :id (:id new-row)}))))

                  :else
                  (do
                    (sp/create-entity storage :fn-slot
                                      {:id (:fn-slot-id a)
                                       :fn-id fn-id
                                       :slot-id (:slot-id a)
                                       :position idx})
                    (swap! journal conj {:op :create :entity-type :fn-slot :id (:fn-slot-id a)}))))
              ;; Phase 5: optional rename / re-description of the fn-row.
              (when (or (and nm (not= nm (:name existing-fn)))
                        (contains? body :description))
                (let [patch (cond-> {}
                              (and nm (not= nm (:name existing-fn)))
                              (assoc :name nm)
                              (contains? body :description)
                              (assoc :description desc))]
                  (sp/update-entity storage :fn fn-id patch)))
              ;; The compound write happened through `sp/*-entity` —
              ;; bypassing the defbase wrappers that normally call
              ;; `invalidate!`. Without this nudge the next read of
              ;; `/api/graph/entities` would return the cached pre-
              ;; update graph and the editor would see no change.
              (invalidate! ctx storage :fn-slot {:fn-id fn-id})
              {:ok true :id (str fn-id) :name (or nm (:name existing-fn))})
            (catch clojure.lang.ExceptionInfo e
              (cleanup)
              {:ok false :error (.getMessage e) :data (ex-data e)})
            (catch Exception e
              (cleanup)
              {:ok false :error (str (.getMessage e))})))))))


(defbase types-usages
  "Find every place a type-row is referenced. POST body
   `{type-fn-id: <uuid-string>}`. Returns `{ok, type-fn-id,
   type-name, usages: [{fn-id, fn-name, role, kind, slot-name?},
   …]}` — one entry per usage. `kind` is one of `:base-of`
   (refinement target), `:element-of` (list element type),
   `:return-of` (declared return type), `:slot-of` (a slot's
   `type-fn-id`), `:binding-of` (a binding's
   `type-override-fn-id`), `:union-branch`, `:variant-branch`.

   Powers the inline-expand panel's \"Used by N\" footer so the
   user can navigate from a type-row to every fn that mentions it."
  [request]
  (let [body (read-json-body request)
        target-id-raw (:type-fn-id body)
        target-id (when target-id-raw
                    (try (java.util.UUID/fromString (str target-id-raw))
                         (catch Exception _ nil)))]
    (if (nil? target-id)
      {:ok false :error "Request body must include valid 'type-fn-id'"}
      (let [{:keys [fns slots fn-slots bindings]} (cached-or-load-graph ctx)
            fn-by-id (into {} (map (juxt :id identity)) fns)
            slot-by-id (into {} (map (juxt :id identity)) slots)
            slot-owner-by-id (into {} (map (juxt :slot-id :fn-id)) fn-slots)
            target-fn (get fn-by-id target-id)
            target-name (some-> target-fn :name)
            fn-summary (fn [fid kind & [extra]]
                         (let [f (get fn-by-id fid)]
                           (merge {:fn-id (str fid)
                                   :fn-name (or (:name f) "(anonymous)")
                                   :role (:role f)
                                   :kind kind}
                                  (or extra {}))))
            base-uses (->> fns
                           (filter #(= (:base-fn-id %) target-id))
                           (map #(fn-summary (:id %) :base-of)))
            elem-uses (->> fns
                           (filter #(= (:element-fn-id %) target-id))
                           (map #(fn-summary (:id %) :element-of)))
            ret-uses (->> fns
                          (filter #(= (:return-type-fn-id %) target-id))
                          (map #(fn-summary (:id %) :return-of)))
            constraint-uses
            (->> fns
                 (filter (fn [f]
                           (and (vector? (:constraint f))
                                target-name
                                (constraint-contains-type-ref? (:constraint f) target-name))))
                 (mapcat (fn [f]
                           (let [kind (case (first (:constraint f))
                                        :union :union-branch
                                        :variant :variant-branch
                                        :fn :fn-type-arg-or-return
                                        :other)]
                             [(fn-summary (:id f) kind)]))))
            slot-uses (->> slots
                           (filter #(= (:type-fn-id %) target-id))
                           (mapcat (fn [s]
                                     (let [owner-id (get slot-owner-by-id (:id s))]
                                       (when owner-id
                                         [(fn-summary owner-id :slot-of
                                                      {:slot-name (:name s)})])))))
            binding-uses (->> bindings
                              (filter #(= (:type-override-fn-id %) target-id))
                              (map (fn [b]
                                     (let [s (get slot-by-id (:slot-id b))]
                                       (fn-summary (:fn-id b) :binding-of
                                                   {:slot-name (:name s)})))))
            usages (vec (concat base-uses elem-uses ret-uses
                                constraint-uses slot-uses binding-uses))]
        {:ok true
         :type-fn-id (str target-id)
         :type-name (some-> target-name str)
         :count (count usages)
         :usages usages}))))


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
                       (str/split v #",")))))
      ;; Type-row creation fields. Accept either UUID or named-type
      ;; ref for `base-fn-id` / `element-fn-id`; `constraint` arrives
      ;; as a JSON-encoded vector (e.g. `["union","null","text"]`).
      ;; Parsed back into a Clojure vector with keywordised heads /
      ;; primitives — same shape the loader / type-checker expect.
      (contains? form-data :base-fn-id)
      (assoc :base-fn-id
             (when-not (str/blank? (:base-fn-id form-data))
               (resolve-type-fn-id storage (:base-fn-id form-data))))
      (contains? form-data :element-fn-id)
      (assoc :element-fn-id
             (when-not (str/blank? (:element-fn-id form-data))
               (resolve-type-fn-id storage (:element-fn-id form-data))))
      ;; `expects-effects` — authored effect-set contract. Storage
       ;; holds nil (= no contract) or a JSONB array of bare effect
       ;; names (e.g. `["db" "io"]`). Form field arrives as
       ;; comma-separated bare names; a literal "[]" or "null" lets
       ;; the user pin "no contract" / "explicit no-effects" distinct
       ;; from "unset" (cleared field = unset / nil).
       (contains? form-data :expects-effects)
       (assoc :expects-effects
              (let [raw (str (:expects-effects form-data))]
                (cond
                  (str/blank? raw)          nil
                  (= raw "null")            nil
                  (= raw "[]")              []
                  :else
                  (vec (->> (str/split raw #",")
                            (map str/trim)
                            (remove str/blank?)
                            (map #(str/replace-first % #"^:" "")))))))
      (contains? form-data :constraint)
      (assoc :constraint
             (let [raw (:constraint form-data)]
               (when-not (str/blank? raw)
                 ;; JSON arrays / strings re-keywordised: `:union`,
                 ;; `:variant`, `:and`, `:or`, `:>=` etc. live on the
                 ;; Clojure side as keywords, with type-name members
                 ;; (`"null"` `"int"`) also coerced to keywords.
                 (let [parsed (try (json/parse-string raw)
                                   (catch Exception _ raw))]
                   (letfn [(re-kw [x]
                             (cond
                               (and (string? x)
                                    (or (str/starts-with? x ":")
                                        ;; Alpha-leading identifiers (`union`,
                                        ;; `int`, `matches`, etc.) and bare
                                        ;; comparison operators (`>`, `>=`,
                                        ;; `<`, `<=`, `=`, `!=`) — without
                                        ;; the second branch any constraint
                                        ;; whose head is a non-alphanumeric
                                        ;; op would stay as a string and
                                        ;; downstream contains? checks (which
                                        ;; key on `:>` keywords) would fail.
                                        (re-matches #"[a-zA-Z][a-zA-Z0-9_-]*" x)
                                        (re-matches #"[!<>=]+" x)))
                               (keyword (str/replace-first x #"^:" ""))
                               (vector? x) (mapv re-kw x)
                               (sequential? x) (mapv re-kw x)
                               :else x))]
                     (re-kw parsed)))))))))


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
     {\"value\": <any JSON>}

   A `\":foo\"`-shaped value string is the wire form of a keyword
   literal (JSON has no keyword type) — restore the keyword and set
   `:literal true`, matching how `records.clj` stores a fn-def's
   `{:value :kw :literal? true}` item. Without the flag a read would
   re-emit the keyword colon-stripped and the editor would mis-type
   it as plain text."
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
    (let [v (:value body)]
      (if (and (string? v) (> (count v) 1) (str/starts-with? v ":"))
        {:value (keyword (subs v 1)) :literal true}
        {:value v}))

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
        (let [binding-id (:id binding)
              ;; A new item's `:position` must clear the BASE
              ;; `binding_list_item` table, not just the resolved
              ;; view. A soft-deleted item leaves its base row (the
              ;; cross-branch identity) still holding `(binding_id,
              ;; position)`, and that row's UNIQUE index rejects a
              ;; colliding insert. The resolved view hides those
              ;; orphans — query the base storage so they count.
              base-storage (or (:base-storage storage) storage)
              used-pos (map :position
                            (concat
                              (sp/query-entities base-storage :binding-list-item
                                                 {:binding-id binding-id})
                              (sp/query-entities storage :binding-list-item
                                                 {:binding-id binding-id})))
              new-pos (inc (apply max -1 used-pos))
              payload (resolve-sequence-payload storage body)
              new-item (merge {:id (random-uuid)
                               :binding-id binding-id
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


(defbase process-sequence-update
  "PUT /api/sequence/item/:item-id
   Body: {\"ref\"|\"ref-name\"|\"value\": …}
   Replaces the value/ref of one existing binding-list-item — the
   in-place edit counterpart of append/remove. The complementary
   column is cleared so the item stays unambiguously a value OR a
   ref; the `:literal` keyword flag is set when (and only when) the
   new value is a keyword (see `resolve-sequence-payload`)."
  [request]
  (let [storage (require-storage ctx)
        item-id-str (or (get-in request [:path-params :item-id])
                        (:item-id-str (parse-uri-segments (:uri request))))
        item-id (try (java.util.UUID/fromString item-id-str) (catch Exception _ nil))
        raw-body (:body request)
        body-str (cond
                   (string? raw-body) raw-body
                   (instance? java.io.InputStream raw-body) (clojure.core/slurp raw-body)
                   :else nil)
        body (when body-str
               (try (json/parse-string body-str true) (catch Exception _ nil)))]
    (cond
      (nil? item-id) {:status 400 :body "<p class=\"error\">Invalid item-id</p>"}
      (nil? body)    {:status 400 :body "<p class=\"error\">JSON body required</p>"}
      :else
      (let [item (sp/read-entity storage :binding-list-item item-id)]
        (if (nil? item)
          {:status 404 :body "<p class=\"error\">Item not found</p>"}
          (let [payload (resolve-sequence-payload storage body)
                changes (merge {:value nil :ref-fn-id nil :literal nil} payload)
                pre-rej (write-rej storage :binding-list-item
                                   (merge item changes {:id item-id}))]
            (if pre-rej
              {:status 400 :body (str "<p class=\"error\">" (:reason pre-rej) "</p>")}
              (do (sp/update-entity storage :binding-list-item item-id changes)
                  (invalidate! ctx storage :binding-list-item item)
                  {:status 200 :body ""}))))))))


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
   :types-usages types-usages
   :process-create-record-type process-create-record-type
   :process-create-list-type process-create-list-type
   :process-update-record-type process-update-record-type
   :render-entity-details-view render-entity-details-view
   :render-entity-form-view render-entity-form-view
   :process-create-entity process-create-entity
   :process-update-entity process-update-entity
   :process-delete-entity process-delete-entity
   :process-sequence-append process-sequence-append
   :process-sequence-remove process-sequence-remove
   :process-sequence-update process-sequence-update
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
