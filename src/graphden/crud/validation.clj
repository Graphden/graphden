(ns graphden.crud.validation
  "Server-side write-time guards for the web/crud base functions.

   Cycle checks, multi-inheritance collision checks, `:terminal` /
   `:list-closed` enforcement and constraint-shape validation — every
   guard the generic `create-entity` / `update-entity` and the
   form-driven `process-*` paths run before touching storage.

   Depends only on `graphden.crud.request` from the crud.* tree."
  (:require
    [graphden.executor.compile.lookups :as l]
    [graphden.storage.protocol.core :as sp]
    [graphden.types.check :as types-check]))


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

(defn cycle-check-pair
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

(defn visible-slot-names
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


(defn mi-collision-check
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


(defn mi-collision-rej
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

(defn ancestor-binding-flag?
  "Walk the PARENT chain of `fn-id` (skipping fn-id's own bindings)
   and return true iff any ancestor's binding on `slot-id` has
   `(flag-key ancestor-binding) = true`. Used to gate `:terminal`
   and `:list-closed` enforcement.

   BFS by frontier level: two batched storage queries per level
   (`:fn {:id frontier}` for the next layer's parent-ids; `:binding
   {:fn-id frontier :slot-id slot-id}` for any flagged ancestor on
   the way up). Avoids the O(depth) read-entity + per-ancestor
   binding-query N+1 — same shape as the inheritance-chain batcher
   in `crud.fn-execution.lookup`."
  [storage fn-id slot-id flag-key]
  (let [seed (when fn-id (sp/read-entity storage :fn fn-id))]
    (loop [frontier (->> (:parent-ids seed)
                         (remove nil?)
                         distinct
                         vec)
           seen #{}]
      (if (empty? frontier)
        false
        (let [bindings (sp/query-entities storage :binding
                                          {:fn-id frontier :slot-id slot-id})
              flagged? (some #(true? (get % flag-key)) bindings)]
          (if flagged?
            true
            (let [fn-rows (sp/query-entities storage :fn {:id frontier})
                  seen' (into seen frontier)
                  next-frontier (->> fn-rows
                                     (mapcat :parent-ids)
                                     (remove nil?)
                                     (remove seen')
                                     distinct
                                     vec)]
              (recur next-frontier seen'))))))))


(defn terminal-rej
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


(defn list-closed-rej
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
(def constraint-op-keywords
  #{:union :variant :fn :refine :map :tuple :and :or :not
    :> :>= :< :<= := :not= :matches :in :exists :every})


(defn constraint-type-ref-names
  "Walk a constraint vector and collect every keyword nested anywhere
   inside it as a bare-name string set, minus the operator heads in
   `constraint-op-keywords`. Used to find type-row references hidden
   in `[:union T1 T2 …]` / `[:variant :tag1 T1 …]` / `[:fn {…} T]`
   shapes — those are stored as keywords, NOT FK columns, so the
   FK-only cycle walker misses them."
  [c]
  (let [walk (fn walk
               [x acc]
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


(defn resolve-constraint-refs-to-ids
  "Batched name → fn-id resolution for the set of bare-name strings
   produced by `constraint-type-ref-names`. Names that don't resolve
   (e.g. variant tags) are silently dropped. Empty input → empty set."
  [storage names]
  (if (empty? names)
    #{}
    (->> (sp/query-entities storage :fn {:name (vec names)})
         (keep :id)
         set)))


(defn cycle-check-rej
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

(defn resolve-base-name
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


(defn constraint-shape-rej
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


(defn write-rej
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
