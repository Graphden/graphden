(ns graphden.layout.builder-helpers
  "Pure helpers used by `build-graph-elements` — the bulk of the
   graph-building logic that doesn't belong to the cycle-guarded
   `process-*` recursion family (those stay in `graphden.layout.graph`).

   Roles within this namespace, top to bottom:

   - expansion-spec utilities (`get-effective-spec` / `spec->expand-set`
     / `spec-trivial?`);
   - state-recording side helpers (`record-optional-unset!` /
     `record-hof-captured!`);
   - cytoscape node/edge field shaping (`arg-row->node-id-fields` /
     `arg-source-fn-fields` / `edge-source-fields`);
   - value / unset arg-node emission (`add-arg-value-node` /
     `add-unset-arg-node`);
   - arg classification + collection
     (`arg-render-spec` / `collect-fn-args` / `collect-expanded-args`);
   - small predicates / lookups (`arg-determined?` /
     `make-parent-bound-terminals` / `child-covered-sources-for-fn` /
     `arg-marks-hof?` / `child-hof` / `terminal-source-of`);
   - ref-edge emission + type-row internals (`add-ref-edge!` /
     `type-row-role` / `emit-type-row-internals!` / `add-fn-node`);
   - edge-label / type-chain computation
     (`target-interface-names` / `compute-edge-label` /
     `compute-edge-type-chain` / `edge-narrowing-fields` /
     `build-inverse-source-map` / `caller-bound-arg`)."
  (:require
    [clojure.string :as str]
    [graphden.executor.compile.bindings :as cb]
    [graphden.layout.bindings :as bnd]
    [graphden.layout.data :as data]))


;; =============================================================================
;; PURE HELPERS used by build-graph-elements
;; =============================================================================

(defn get-effective-spec
  "Look up expansion spec by cytoscape node-id string. The `expansions`
   map is keyed by the same node-id that `add-fn-node` emits, so the
   match is exact."
  [expansions node-id]
  (or (get expansions node-id) 0))


(defn spec->expand-set
  "Convert any expansion spec into a set of fn-ids that should be
   `merged into` the focus fn's display (always includes fn-id itself)."
  [fn-map fn-id spec]
  (let [levels (data/get-inheritance-levels fn-id fn-map)
        full-depth (cond
                     (integer? spec) spec
                     (map? spec) (or (:full-depth spec) 0)
                     :else 0)
        partial-fns (when (map? spec)
                      (set (map (fn [id]
                                  (if (uuid? id)
                                    id
                                    (parse-uuid (str id))))
                                (:partial-fns spec))))
        cascade-fns (set (mapcat identity
                                 (take (inc full-depth) levels)))]
    (cond-> cascade-fns
      (seq partial-fns) (into partial-fns))))


(defn spec-trivial?
  "True for specs that don't expand anything beyond the focus fn."
  [spec]
  (cond
    (integer? spec) (zero? spec)
    (map? spec) (and (zero? (or (:full-depth spec) 0))
                     (empty? (:partial-fns spec)))
    :else true))


(defn arg-is-optional?
  "Walk the source-id chain to the root arg and return its `:required`
   value. Propagated shadows have `:required=nil`, so we need to look at
   the base-fn's primary arg to know whether an unbound slot is truly
   optional (`:required false` → caller may leave it blank) or required
   (no explicit `:required false` → caller must supply it)."
  [_arg-map arg]
  ;; Anchors carry the slot row's `:required` directly (see
  ;; derive-fn-slot-views) — every anchor on the chain represents the
  ;; same slot and therefore the same value.
  (false? (:required arg)))


;; Forward declarations — these defn-s reference each other. The
;; pure-helper block (target-interface-names / compute-edge-label /
;; build-inverse-source-map / caller-bound-arg) lives below the
;; mutating helpers but is used by them.
(declare target-interface-names
         compute-edge-label
         compute-edge-type-chain
         edge-narrowing-fields
         build-inverse-source-map
         caller-bound-arg
         terminal-source-of
         child-covered-sources-for-fn)


(defn record-optional-unset!
  "Append `{:name arg-name :slot-id slot-id}` to `state.optional-unsets-
   by-node[node-id]`. Used to populate a node's `+default, +else` badge
   with the names of unbound optional args the caller chose to leave
   blank. `slot-id` is carried so the editor can resolve the ancestor
   that declared the slot (and surface it in the strip's per-name
   tooltip) without re-walking the inheritance chain in two places."
  [state node-id arg-name slot-id]
  (when (and node-id arg-name)
    (swap! state update-in [:optional-unsets-by-node node-id]
           (fn [xs]
             (let [entry (cond-> {:name arg-name}
                           slot-id (assoc :slot-id slot-id))]
               (if xs (conj xs entry) [entry]))))))


(defn record-hof-captured!
  "Append `arg-name` to `state.hof-captured-by-node[node-id]`. Used to
   populate the λname HOF capture badge on nodes whose lambda-param
   free arg is supplied per-call by the surrounding HOF invocation."
  [state node-id arg-name]
  (when (and node-id arg-name)
    (swap! state update-in [:hof-captured-by-node node-id]
           (fn [xs] (if xs (conj xs arg-name) [arg-name])))))


(defn arg-row->node-id-fields
  "Extract slot/binding/item/fn ids from an arg row for embedding in
   node data. Editor JS reads these directly so it can address the
   real binding row through /api/entities/binding/:id without going
   through a `synth-arg-id` reverse-lookup."
  [arg]
  ;; `:arg-type` carries the slot's resolved type-kw on every shape
  ;; (anchor rows AND binding-classifier kind-marker maps). `:type` on
  ;; anchor rows still IS that same type-kw; it's read here as a
  ;; back-compat fallback for callers that build anchor-shaped maps
  ;; inline without the explicit `:arg-type` field. Classifier maps
  ;; (`:kind :value/:ref/:unset`) always carry `:arg-type` directly,
  ;; so the fallback never sees a kind discriminator.
  (cond-> {}
    (:slot-id arg)    (assoc :slotId    (str (:slot-id arg)))
    (:binding-id arg) (assoc :bindingId (str (:binding-id arg)))
    (:item-id arg)    (assoc :itemId    (str (:item-id arg)))
    (:fn-id arg)      (assoc :fnId      (str (:fn-id arg)))
    (:arg-type arg)   (assoc :argType   (-> arg :arg-type name))
    (and (:type arg) (not (:arg-type arg)))
    (assoc :argType (-> arg :type name))))


(defn arg-source-fn-fields
  "Provenance for an inherited slot. An arg row's `:source-id` jumps
   straight to the slot's defining fn (the owner), so to surface the
   FULL inheritance path this walks the fn's parent chain instead —
   from the immediate parent down to (and including) the owner — and
   emits `:sourceChain`: a vector of `{:fnId :fnName}` ordered
   leaf→root. Returns `{}` for an own (non-inherited) slot, so callers
   can `merge` unconditionally."
  [lookups arg]
  (let [{:keys [fn-map slot-owner]} lookups
        owner (when (:source-id arg) (get slot-owner (:slot-id arg)))]
    (if (and owner (not= owner (:fn-id arg)))
      (let [ancestor-fns (loop [acc [], cs (rest (data/get-inheritance-chain* (:fn-id arg) lookups))]
                           (cond
                             (empty? cs)          acc
                             (= (first cs) owner) (conj acc owner)
                             :else (recur (conj acc (first cs)) (rest cs))))]
        (if (seq ancestor-fns)
          {:sourceChain (mapv (fn [fid]
                                {:fnId   (str fid)
                                 :fnName (some-> fid fn-map :name name)})
                              ancestor-fns)}
          {}))
      {})))


(defn edge-source-fields
  "Edge-data shape mirroring `arg-row->node-id-fields` for the SOURCE
   side of an inheritance/ref edge — same slot/binding/item/fn ids
   plus `:sourcePrevArgId` / `:sourceNextArgId` so editor JS can render
   sequence-item × / + buttons without consulting `lookups.argMap`.
   Pulled from the synth arg row via arg-id."
  [lookups source-arg-id]
  (when source-arg-id
    (when-let [arg (get-in lookups [:arg-map source-arg-id])]
      (cond-> (arg-row->node-id-fields arg)
        (:prev-arg-id arg) (assoc :sourcePrevArgId (str (:prev-arg-id arg)))
        (:next-arg-id arg) (assoc :sourceNextArgId (str (:next-arg-id arg)))))))


(defn add-arg-value-node
  "Emit a value-style arg node + edge linking it to `source-node-id`.
   No-op when the node is already present (dedup by `:added-node-ids`).
   Returns the emitted node-id."
  [state lookups arg source-node-id expanded-fns]
  (let [arg-id (:arg-id arg)
        arg-name (:arg-name arg)
        value (:value arg)
        node-id (str "arg-" source-node-id "-" arg-id)
        edge-id (str "e-val-" source-node-id "-" arg-id)]
    (when-not (contains? (:added-node-ids @state) node-id)
      (swap! state update :added-node-ids conj node-id)
      ;; pr-str renders keywords with the leading `:`, distinguishing
      ;; them from same-name strings. json/generate-string would lose
      ;; that distinction (`:foo` and `"foo"` both serialised as `"foo"`),
      ;; so the wire `:value` for a keyword goes out as `":foo"` (with
      ;; the colon) — same convention the codec's `preserve-keywords`
      ;; already uses for jsonb columns. The editor's `classifyLiteralJS`
      ;; relies on the leading `:` to recognise keywords; without this
      ;; fixup a literal `:headers` bound to a `[:list :keyword]` slot
      ;; would mismatch as `text ⊄ keyword` and render the red ring.
      (let [display-value (bnd/truncate-label (pr-str value) 20)
            wire-value (if (keyword? value) (str value) value)
            id-fields (arg-row->node-id-fields arg)]
        (swap! state update :nodes conj
               {:data (merge {:id node-id
                              :label display-value
                              :type "arg"
                              :argId (str arg-id)
                              :value wire-value}
                             id-fields
                             ;; `arg` here is the display-projected row
                             ;; (`:arg-id` / `:arg-name`), which carries
                             ;; no `:source-id`; resolve the canonical
                             ;; anchor row from `arg-map` for provenance.
                             (arg-source-fn-fields
                               lookups (get (:arg-map lookups) arg-id)))}))
      (swap! state update :edges conj
             {:data (merge {:id edge-id
                            :source source-node-id
                            :target node-id
                            :sourceArgId arg-id
                            :argName (or (compute-edge-label lookups arg-id source-node-id expanded-fns)
                                         (when arg-name (name arg-name)))}
                           (edge-source-fields lookups arg-id)
                           (edge-narrowing-fields lookups arg-id expanded-fns))}))
    node-id))


(defn make-parent-bound-terminals
  "Memoised `(fn [fn-id]) → #{slot-id …}`. Returns the set of slot-ids
   that `fn-id`'s parent-id closure (including itself) binds. A slot
   counts as bound when some fn in the closure has a binding row with
   `:value`, `:ref-fn-id`, or non-empty list-items.

   Bindings inside ref-id targets are NOT included — those are scoped
   to the ref's own call context, not to the inheritance chain."
  [lookups]
  (let [{:keys [fn-map bindings-by-fn items-by-binding]} lookups
        cache (atom {})]
    (fn [fn-id]
      (or (get @cache fn-id)
          (let [visited (atom #{})
                slots (atom #{})
                walk (fn walk
                       [fid]
                       (when (and fid (not (contains? @visited fid)))
                         (swap! visited conj fid)
                         (doseq [b (get bindings-by-fn fid [])]
                           (when (or (cb/value-binding? b)
                                     (cb/ref-binding? b)
                                     (seq (get items-by-binding (:id b) [])))
                             (swap! slots conj (:slot-id b))))
                         (when-let [f (get fn-map fid)]
                           (doseq [pid (:parent-ids f)]
                             (walk pid)))))]
            (walk fn-id)
            (let [result @slots]
              (swap! cache assoc fn-id result)
              result))))))


(defn arg-determined?
  "True iff arg's slot is bound somewhere in its owning fn's
   parent-id closure. With slots as terminal identity the legacy
   source-chain walk collapses to a single closure-membership check
   on the arg's `:fn-id`. MI is handled inside parent-bound-terminals
   (it walks every parent path); ref-target bindings are excluded
   there too (the walk only follows :parent-ids, not :ref-id)."
  [arg-map parent-bound-terminals arg-id]
  (when-let [arg (get arg-map arg-id)]
    (when-let [fid (:fn-id arg)]
      (contains? (parent-bound-terminals fid) (:slot-id arg)))))


(defn arg-render-spec
  "Pure: given a raw arg row + the active binding state, return the
   rendered item map (`ref-item-from-arg` / `ref-item-from-bnd` /
   `value-item-from-arg` / `value-item-from-bnd` / `unset-item-from-arg`)
   or `nil` to skip (the slot is covered by another emission).

   Extracted from `collect-fn-args`'s inner `mapv` so the 7-arm cond
   that classifies arg-vs-binding state lives in one named place
   instead of being buried inside a let.

   `binding-applies?` / `bound-by-chain?` / `binding-goes-to-child?`
   are the closure predicates from `collect-fn-args`; they encapsulate
   the inheritance / source-chain checks that depend on the broader
   lookups + `fn-ancestry` set."
  [arg arg-map bindings is-structural
   binding-applies? bound-by-chain? binding-goes-to-child?]
  (let [arg-name (bnd/resolve-arg-name arg arg-map)
        ;; `:value-present` (flag), NOT `(some? :value)` — a slot
        ;; pinned to literal `nil` is a value-binding the editor must
        ;; render, and is what distinguishes `{:default nil}` from an
        ;; unbound free arg post-parser.
        has-value (true? (:value-present arg))
        has-ref (some? (:ref-id arg))
        source-has-ref (when-let [sid (:source-id arg)]
                         (let [source-arg (get arg-map sid)]
                           (some? (:ref-id source-arg))))
        defines-own-ref (and has-ref (not source-has-ref))
        binding-key (or (:id arg) (:source-id arg))
        ;; bindings is slot-id-keyed, so this is one direct lookup; the
        ;; per-step `binding-applies?` check is just the closure-
        ;; membership test (slot equality is implicit).
        raw-binding (let [b (get bindings (:slot-id arg))]
                      (when (binding-applies? b) b))
        bnd (when (and raw-binding
                       (not (binding-goes-to-child? binding-key)))
              raw-binding)]
    (cond
      ;; Own-ref or matching bnd-ref → emit from the raw arg.
      (or (and has-ref defines-own-ref)
          (and bnd (:ref-id bnd) (= (:ref-id bnd) (:ref-id arg))))
      (bnd/ref-item-from-arg arg arg-name)

      ;; Ancestor binding's ref shadows / fills the slot.
      (and bnd (:ref-id bnd)
           (or (and has-ref (not= (:ref-id bnd) (:ref-id arg)))
               (and (not has-ref) (not has-value))))
      (bnd/ref-item-from-bnd arg bnd)

      ;; Ancestor binding's literal value covers the slot.
      (and bnd (true? (:value-present bnd)))
      (bnd/value-item-from-bnd arg bnd)

      ;; Has-ref on a non-structural call — emit as own ref.
      (and has-ref (not is-structural))
      (bnd/ref-item-from-arg arg arg-name)

      ;; Structural ref without its own binding — render as a dashed
      ;; unset (the ref's bindings handle it).
      (and has-ref is-structural (not defines-own-ref))
      (bnd/unset-item-from-arg arg arg-name)

      ;; Raw arg carries a literal value.
      has-value
      (bnd/value-item-from-arg arg arg-name)

      ;; Slot covered by an ancestor's binding-chain — skip this row
      ;; (the binding's emission handles it).
      (or raw-binding (bound-by-chain? arg))
      nil

      :else
      (bnd/unset-item-from-arg arg arg-name))))


(defn collect-fn-args
  "Collect renderable arg entries for `fn-id` given the active
   `bindings` map. Each entry is `{:kind :ref|:value|:unset :arg-name
   :arg-id :arg-type …}` — `:kind` is the binding-kind discriminator,
   `:arg-type` carries the slot's resolved type-kw separately so the
   wire `:argType` field never collides with the kind marker. Pure —
   no state mutation.

   Options:
     :is-structural          — true for structural nodes inside an
                                expansion. Unbound refs (no binding)
                                become `:unset` instead of `:ref` so
                                we don't false-share with sibling fns.
     :displayed-ref-arg-ids  — set of arg-ids belonging to fns shown
                                as nodes in the current expansion.
                                Bindings whose source-chain leads
                                here are deferred to the leaf node.
     :expansion-root-chain   — set of fn-ids in the expansion root's
                                inheritance chain. Forwarded into
                                `child-covered-sources-for-fn`."
  [lookups fn-id bindings & {:keys [is-structural displayed-ref-arg-ids expansion-root-chain]
                             :or {is-structural false displayed-ref-arg-ids #{} expansion-root-chain #{}}}]
  (let [{:keys [arg-map args-by-fn]} lookups
        fn-ancestry (set (data/get-inheritance-chain* fn-id lookups))
        ;; A binding "applies" when its owning fn is in `fn-id`'s
        ;; inheritance closure AND it targets the same slot the arg
        ;; under inspection lives on. With slots as terminal identity
        ;; that reaches-target test is just a slot-id equality check.
        ;; Bindings is keyed by slot-id; the entry already carries the
        ;; binding's owner :fn-id. "Applies" reduces to: owner-fn is in
        ;; fn-id's inheritance closure (slot-id match is implicit since
        ;; we look up by slot-id).
        binding-applies? (fn [b]
                           (or (nil? b)
                               (contains? fn-ancestry (:fn-id b))))
        bound-by-chain? (fn [arg]
                          (contains? bindings (:slot-id arg)))
        raw-args (get args-by-fn fn-id [])
        sequence-anchors (filterv bnd/sequence-anchor? raw-args)
        chain-item-ids (into #{}
                             (mapcat (fn [anchor]
                                       (map :id (bnd/walk-anchor-chain anchor arg-map))))
                             sequence-anchors)
        anchor-ids (set (map :id sequence-anchors))
        sequence-slot-entries
        (vec (mapcat
               (fn [anchor]
                 (bnd/expand-sequence-anchor
                   anchor
                   (or (bnd/resolve-arg-name anchor arg-map) "items")
                   arg-map))
               sequence-anchors))
        args (filterv (fn [a]
                        (not (or (contains? anchor-ids (:id a))
                                 (contains? chain-item-ids (:id a)))))
                      raw-args)
        child-sources (child-covered-sources-for-fn lookups fn-id :expansion-root-chain expansion-root-chain)
        all-covered-sources (into child-sources displayed-ref-arg-ids)
        ;; child-covered-sources-for-fn now returns slot-ids; the
        ;; binding's lookup is one direct slot-id check on the arg
        ;; the binding is anchored at.
        binding-goes-to-child?
        (fn [binding-key]
          (when-let [arg (get arg-map binding-key)]
            (contains? all-covered-sources (:slot-id arg))))
        all-args (mapv #(arg-render-spec % arg-map bindings is-structural
                                         binding-applies? bound-by-chain?
                                         binding-goes-to-child?)
                       args)
        own-slot-terminals (into #{}
                                 (keep (fn [a]
                                         (terminal-source-of arg-map (:id a))))
                                 raw-args)
        inherited-ref-args
        (if-not is-structural
          []
          (let [ancestor-fns (rest (data/get-inheritance-chain* fn-id lookups))
                seen-terminals (atom own-slot-terminals)]
            (vec
              (keep
                (fn [a]
                  (when (:ref-id a)
                    (let [terminal-id (terminal-source-of arg-map (:id a))]
                      (when-not (contains? @seen-terminals terminal-id)
                        (swap! seen-terminals conj terminal-id)
                        (merge {:kind :ref
                                :arg-name (bnd/resolve-arg-name a arg-map)
                                :ref-id (:ref-id a)
                                :arg-id (:id a)
                                :is-binding false}
                               (bnd/arg-ids-from a))))))
                (mapcat #(get args-by-fn % []) ancestor-fns)))))
        deduped-args
        (let [seen (atom #{})]
          (into []
                (keep (fn [arg]
                        (let [terminal-id (terminal-source-of arg-map (:arg-id arg))]
                          (when-not (and terminal-id (contains? @seen terminal-id))
                            (when terminal-id (swap! seen conj terminal-id))
                            arg))))
                (into (filterv some? all-args) inherited-ref-args)))
        kind-order {:ref 0 :value 1 :unset 2}
        sorted-args (sort-by #(get kind-order (:kind %) 3) deduped-args)]
    (into (vec sorted-args) sequence-slot-entries)))


(defn collect-expanded-args
  "Collect rendered arg entries for an EXPANDED group of fns. `levels` is
   a vector of BFS levels (each a coll of fn-ids), `expand-set` selects
   which fns within `levels` participate in this expansion. Walks
   descendant-first, dedups slots covered by closer fns, and finally
   collapses MI shadows by (terminal-primary, ref-or-value).

   Pure — no state mutation. Closures are over `lookups` plus top-level
   helpers (`terminal-source-of`, `walk-anchor-chain`, `resolve-arg-name`,
   `expand-sequence-anchor`)."
  [lookups levels expand-set _bindings]
  (let [{:keys [arg-map args-by-fn slot-map]} lookups
        active-fns (filterv expand-set (mapcat identity levels))
        ;; A renamed-view slot (`{:as …}`) is a distinct slot row whose
        ;; `:source-slot-id` points at the slot it renames. It and its
        ;; source are ONE logical slot — collapse them to a single
        ;; identity so the dedup below treats the inherited raw slot and
        ;; the descendant's renamed view as the same thing (otherwise an
        ;; expanded fn shows both, e.g. `error-response` + the raw
        ;; `not-acceptable-response`).
        canon-slot (fn [sid]
                     (loop [s sid, seen #{}]
                       (let [src (:source-slot-id (get slot-map s))]
                         (if (and src (not (contains? seen s)))
                           (recur src (conj seen s))
                           s))))
        ;; Slot-id-keyed dedup: once a slot has been emitted at the
        ;; closest active fn, deeper ancestors' views of that same
        ;; slot are skipped.
        covered-slots (atom #{})
        result (atom [])
        chain-level (atom 0)
        bound-slot-terminals
        (reduce
          (fn [acc fn-id]
            (reduce
              (fn [acc2 arg]
                (if (or (true? (:value-present arg)) (some? (:ref-id arg))
                        ;; Sequence binding — the anchor arg row carries
                        ;; no :value (items live in chained arg rows),
                        ;; so without this branch a slot bound to e.g.
                        ;; `[:headers]` reads as still-unset and an
                        ;; expanded ancestor emits a phantom unset
                        ;; placeholder for the same slot.
                        (and (bnd/sequence-anchor? arg)
                             (some? (:next-arg-id arg))))
                  (conj acc2 (canon-slot (:slot-id arg)))
                  acc2))
              acc
              (get args-by-fn fn-id [])))
          #{}
          active-fns)]
    (doseq [fn-id active-fns]
      (let [raw-args (get args-by-fn fn-id [])
            anchor-ids (into #{}
                             (comp (filter bnd/sequence-anchor?)
                                   (map :id))
                             raw-args)
            chain-ids (into #{}
                            (mapcat (fn [a]
                                      (when (bnd/sequence-anchor? a)
                                        (map :id (bnd/walk-anchor-chain a arg-map)))))
                            raw-args)
            args (filterv (fn [a]
                            (not (or (contains? anchor-ids (:id a))
                                     (contains? chain-ids (:id a)))))
                          raw-args)
            current-level @chain-level
            fn-refs (atom [])
            fn-values (atom [])
            fn-unsets (atom [])]
        (doseq [arg args]
          (let [cslot (canon-slot (:slot-id arg))
                already-covered (contains? @covered-slots cslot)
                has-value (true? (:value-present arg))
                has-ref (some? (:ref-id arg))
                shadow-of-bound
                (and (not has-value) (not has-ref)
                     (contains? bound-slot-terminals cslot))]
            (when (and (not already-covered) (not shadow-of-bound))
              (swap! covered-slots conj cslot)
              (let [arg-name (bnd/resolve-arg-name arg arg-map)
                    from-ancestor (pos? current-level)]
                (cond
                  has-ref
                  (swap! fn-refs conj
                         (assoc (bnd/ref-item-from-arg arg arg-name)
                                :from-ancestor from-ancestor))

                  has-value
                  (swap! fn-values conj
                         (assoc (bnd/value-item-from-arg arg arg-name)
                                :from-ancestor from-ancestor))

                  :else
                  (swap! fn-unsets conj
                         (assoc (bnd/unset-item-from-arg arg arg-name)
                                :from-ancestor from-ancestor)))))))
        (let [raw-args-of-fn (get args-by-fn fn-id [])
              anchors (filter bnd/sequence-anchor? raw-args-of-fn)
              from-ancestor (pos? current-level)]
          ;; Dedup anchors by slot-id too. Without this an inherited
          ;; slot that's already bound by a closer fn (sequence items
          ;; chained off the descendant's anchor) re-renders here from
          ;; the parent's EMPTY anchor — which `expand-sequence-anchor`
          ;; surfaces as a phantom `:unset` placeholder. The user sees
          ;; "two `path` rows" after expanding the parent. Mirrors the
          ;; covered-slots gate the scalar arg loop above uses.
          (doseq [anchor anchors
                  :when (not (contains? @covered-slots (canon-slot (:slot-id anchor))))
                  :let [slot-name (or (bnd/resolve-arg-name anchor arg-map) "items")]
                  entry (bnd/expand-sequence-anchor anchor slot-name arg-map)]
            (swap! covered-slots conj (canon-slot (:slot-id anchor)))
            (swap! result conj (assoc entry :from-ancestor from-ancestor))))
        (doseq [a @fn-refs] (swap! result conj a))
        (doseq [a @fn-values] (swap! result conj a))
        (doseq [a @fn-unsets] (swap! result conj a))
        (swap! chain-level inc)))
    (let [seen (atom #{})
          ;; Slot-id is the terminal identity: dedup keys can use it
          ;; directly instead of walking arg's source chain to find
          ;; the defining anchor. Canonicalised across `:source-slot-id`
          ;; so a renamed view and its source collapse to one key.
          slot-id-of (fn [aid]
                       (canon-slot (or (:slot-id (get arg-map aid)) aid)))]
      (into []
            (keep (fn [arg]
                    (let [t (slot-id-of (:arg-id arg))
                          k (case (:kind arg)
                              :ref [t :ref (:ref-id arg)]
                              :value [t :value (:value arg)]
                              :unset [t :unset]
                              [t (:kind arg)])]
                      (when-not (contains? @seen k)
                        (swap! seen conj k)
                        arg))))
            @result))))


(defn child-covered-sources-for-fn
  "Slot-ids that `fn-id`'s child refs already render — used for
   binding deduplication so the same upstream binding isn't drawn
   twice (once on `fn-id` and once on the child node it feeds).

   For each child-ref of `fn-id`, walks the ref-target's inheritance
   closure and collects every slot in its fn-slots junctions (own +
   inherited). `expansion-root-chain` slots are excluded — those are
   shared-ancestor slots, rendered at the parent regardless."
  [lookups fn-id & {:keys [expansion-root-chain] :or {expansion-root-chain #{}}}]
  (let [{:keys [args-by-fn fn-slots-by-fn]} lookups
        fn-args (get args-by-fn fn-id [])
        child-ref-ids (keep :ref-id fn-args)
        expansion-chain-slot-ids (when (seq expansion-root-chain)
                                   (set (mapcat (fn [eid]
                                                  (map :slot-id
                                                       (get fn-slots-by-fn eid [])))
                                                expansion-root-chain)))
        slot-ids-of-fn-closure
        (fn [root-fn-id]
          (->> (data/get-inheritance-chain* root-fn-id lookups)
               (mapcat (fn [fid] (map :slot-id (get fn-slots-by-fn fid []))))
               (remove (fn [sid]
                         (and expansion-chain-slot-ids
                              (contains? expansion-chain-slot-ids sid))))))]
    (set (mapcat slot-ids-of-fn-closure child-ref-ids))))


(defn arg-marks-hof?
  "Does `arg-entity` propagate a fn-typed marker anywhere in its
   source-id chain? Used to decide whether a ref-binding to another
   fn crosses a HOF boundary. The HOF marker is `:type :fn`."
  [_arg-map arg-entity]
  ;; Anchor rows carry the slot's effective type directly (slot's
  ;; `:type-fn-id` overlaid by the binding's `:type-override-fn-id`),
  ;; so the legacy walk-to-root step is redundant — every row in the
  ;; same slot's chain reports the same `:type`.
  (= :fn (:type arg-entity)))


(defn child-hof
  "HOF context to thread into a child render: ORs the parent's `is-hof`
   with whether `arg-id` crosses the HOF boundary (any source-id chain
   step has `:is-fn=true`). Once HOF, descendants stay HOF."
  [arg-map arg-id is-hof]
  (or is-hof (arg-marks-hof? arg-map (get arg-map arg-id))))


(defn terminal-source-of
  "Stable identity for `arg-id`'s slot-inheritance lineage. In the
   slot/binding model the slot itself is the terminal identity, so
   we just return `:slot-id` of the row. Falls back to `arg-id`
   when the row isn't in `arg-map` (callers occasionally pass a node
   id that never came from a real arg row — e.g. expansion roots)."
  [arg-map arg-id]
  (or (:slot-id (get arg-map arg-id)) arg-id))


(defn add-ref-edge!
  "Emit a ref-style edge from `source-node-id` to `node-id` if not
   already present. Dedup by edge-id (no double draw of the same
   source→target line) AND by `(source-node-id + source-arg-id →
   node-id)` (the same caller-arg can produce multiple synthetic edges
   through different MI paths; `processed-arg-targets` collapses them
   to one). Carries `:sourceArgId` so the post-process edge-migration
   pass can rewrite `:source` for cross-HOF captures."
  [state lookups source-node-id node-id source-arg-id edge-arg-name source-expanded-fns]
  (when (and source-node-id edge-arg-name)
    (let [edge-id (str "e-ref-" source-node-id "-" node-id)
          arg-target-key (when source-arg-id
                           (str source-node-id "-" source-arg-id "->" node-id))
          is-duplicate (and arg-target-key
                            (contains? (:processed-arg-targets @state) arg-target-key))]
      (when (and (not (contains? (:added-node-ids @state) edge-id))
                 (not is-duplicate))
        (swap! state update :added-node-ids conj edge-id)
        (when arg-target-key
          (swap! state update :processed-arg-targets conj arg-target-key))
        (swap! state update :edges conj
               {:data (merge {:id edge-id
                              :source source-node-id
                              :target node-id
                              :sourceArgId source-arg-id
                              :argName (or (compute-edge-label lookups source-arg-id source-node-id source-expanded-fns)
                                           (when edge-arg-name (name edge-arg-name)))}
                             (edge-source-fields lookups source-arg-id)
                             (edge-narrowing-fields lookups source-arg-id source-expanded-fns))})))))


(def ^:private max-visible-ancestors
  "Cap on the number of ancestor BFS levels rendered in a fn-node's
   header label before we collapse the rest into `…`."
  4)


(declare add-fn-node)


(defn type-row-role
  "Classify a fn-row into one of the type roles. Mirrors
   `executor.compile-runtime/type-row-role` so the layout can decide
   whether to surface internal-structure edges for a type. Returns
   `:base-fn` / `:composed` / `:refinement` / `:list` / `:union` /
   `:variant` / `:fn-type` / `:record` / `:primitive`."
  [fn-row has-slots?]
  (let [c (:constraint fn-row)]
    (cond
      (seq (:parent-ids fn-row))      :composed
      (some? (:return-type-fn-id fn-row)) :base-fn
      (some? (:base-fn-id fn-row))    :refinement
      (some? (:element-fn-id fn-row)) :list
      (and (vector? c) (= :union (first c)))   :union
      (and (vector? c) (= :variant (first c))) :variant
      (and (vector? c) (= :fn (first c)))      :fn-type
      has-slots?                      :record
      :else                           :primitive)))


(defn resolve-type-ref
  "Resolve a type-form reference (a `:constraint`-vector element) to a
   fn-id. Named primitives / aliases resolve via `fn-by-name`; nested
   vectors are anonymous and skipped — they'll surface inline at a
   later iteration."
  [fn-by-name form]
  (when (keyword? form)
    (:id (get fn-by-name form))))


(defn emit-type-row-internal-edge!
  "Append a single synthetic edge from a type-row root to one of its
   constituent type targets. `target-fn-id` is added as a fn-node so
   it renders as its own card."
  [state lookups source-node-id target-fn-id edge-id arg-name]
  (when (and target-fn-id
             (not (contains? (:added-node-ids @state) edge-id)))
    (let [target-id (add-fn-node state lookups target-fn-id false nil nil)]
      (swap! state update :added-node-ids conj edge-id)
      (swap! state update :edges conj
             {:data {:id edge-id
                     :source source-node-id
                     :target target-id
                     :argName arg-name
                     :isTypeInternal true}}))))


(defn emit-type-row-internals!
  "Surface a root-level type-row's internal composition as outgoing
   edges. The target nodes are the real fn-rows of the referenced
   types (rendered like any other fn-card). Roles:
     :refinement → edge to base-fn (`base`)
     :list       → edge to element-fn (`element`)
     :union      → one unlabelled edge per branch
     :variant    → one edge per branch, labelled with the tag
   :record, :base-fn, :composed, :primitive, :fn-type — no synthetic
   edges (records render via their own fn-slots; primitives are
   leaves; composed/base-fns flow through the normal pipeline)."
  [state lookups root-fn-id node-id]
  (let [{:keys [fn-map fn-by-name fn-slots-by-fn]} lookups
        f (get fn-map root-fn-id)
        has-slots? (boolean (seq (get fn-slots-by-fn root-fn-id)))
        role (when f (type-row-role f has-slots?))
        edge-id (fn [suffix] (str "e-type-" node-id "-" suffix))]
    (case role
      :refinement
      (emit-type-row-internal-edge!
        state lookups node-id (:base-fn-id f) (edge-id "base") "base")

      :list
      (emit-type-row-internal-edge!
        state lookups node-id (:element-fn-id f) (edge-id "element") "element")

      :union
      (doseq [[idx form] (map-indexed vector (rest (:constraint f)))]
        (emit-type-row-internal-edge!
          state lookups node-id (resolve-type-ref fn-by-name form)
          (edge-id (str "union-" idx)) ""))

      :variant
      (doseq [[idx [tag form]] (map-indexed vector
                                            (partition 2 (rest (:constraint f))))]
        (emit-type-row-internal-edge!
          state lookups node-id (resolve-type-ref fn-by-name form)
          (edge-id (str "variant-" idx))
          (str tag)))

      nil)))


(defn add-fn-node
  "Emit (or reuse) a cytoscape fn-node for `original-fn-id`. The node-id
   uniquely identifies the call-site: root fns key by `\"fn-<id>\"`; nested
   fns key by `(caller-node-id, source-arg-id)` so two usages of the
   same fn from different bindings are distinct nodes (matches Clojure
   call-site semantics). Returns the resolved node-id."
  [state lookups original-fn-id is-root source-node-id source-arg-id]
  (let [{:keys [fn-map]} lookups
        node-id (cond
                  (or is-root (nil? source-arg-id))
                  (str "fn-" original-fn-id)

                  ;; Strip "fn-" from caller-node-id so we don't keep
                  ;; doubling the prefix at each nesting.
                  :else
                  (let [caller-tag (if (and source-node-id
                                            (str/starts-with? source-node-id "fn-"))
                                     (subs source-node-id 3)
                                     (str source-node-id))]
                    (str "fn-" caller-tag "-" source-arg-id)))]
    (when-not (contains? (:added-node-ids @state) node-id)
      (swap! state update :added-node-ids conj node-id)
      (let [levels (data/get-inheritance-levels original-fn-id fn-map)
            ;; Name of a fn for label rendering. For level 0 (`top-level?`)
            ;; we do NOT substitute the nearest named ancestor: an anonymous
            ;; fn's "name slot" stays empty so the black header bar visually
            ;; signals "no own name". For levels ≥ 1 we DO substitute so each
            ;; ancestor row still shows something meaningful.
            fn-name-of (fn [fid top-level?]
                         (let [f (get fn-map fid)]
                           (or (when (:name f) (name (:name f)))
                               (when-not top-level?
                                 (some (fn [pid]
                                         (when-let [p (get fn-map pid)]
                                           (when (:name p) (name (:name p)))))
                                       (rest (data/get-inheritance-chain* fid lookups))))
                               (if top-level? "" "(anonymous)"))))
            visible-levels (take (inc max-visible-ancestors) levels)
            raw-lines (vec
                        (map-indexed
                          (fn [lvl-idx level-fn-ids]
                            (str/join ", "
                                      (map #(fn-name-of % (zero? lvl-idx))
                                           level-fn-ids)))
                          visible-levels))
            label-lines (if (> (count levels) (inc max-visible-ancestors))
                          (conj raw-lines "...")
                          raw-lines)
            label (str/join "\n" label-lines)]
        (swap! state update :nodes conj
               {:data {:id node-id
                       :label label
                       :type "fn"
                       :isRoot is-root
                       :originalFnId (str original-fn-id)}})))
    node-id))


(defn add-unset-arg-node
  "Emit a placeholder for an unset arg, choosing one of four routings:

     1. Optional (root `:required=false`) — compact `?name` badge via
        `:optionalArgs`; the caller's fn has a sensible fallback baked in.
     2. Lambda-param of an enclosing HOF (`is-hof=true` AND no caller-side
        structural binding via cross-HOF source-id chain) — compact
        `λname` badge via `:hofCapturedArgs`. The HOF impl supplies it
        per call.
     3. HOF capture — `is-hof=true` but a caller's chain DOES bind this
        arg structurally (cross-HOF source-id). The binding is rendered
        on the capturing caller's edge; we record a migration target so
        post-processing rewrites the edge to originate from THIS inside-
        consumer node (the leaf that actually reads the value). Nothing
        new is emitted in the lambda body to avoid double-counting.
     4. Otherwise — a visible dashed placeholder node. This IS the
        caller's interface; the caller must fill it."
  [state lookups inverse-source-map arg-name arg-type arg-id source-node-id expanded-fns is-hof]
  (let [arg-map (:arg-map lookups)
        arg-rec (get arg-map arg-id)
        optional? (arg-is-optional? arg-map arg-rec)
        displayed-name (or (compute-edge-label lookups arg-id source-node-id expanded-fns)
                           (when arg-name (name arg-name)))]
    (cond
      optional?
      (record-optional-unset! state source-node-id displayed-name (:slot-id arg-rec))

      is-hof
      (if-let [bound (caller-bound-arg arg-map inverse-source-map arg-id)]
        (swap! state update :captured-edge-migrations assoc (:id bound) source-node-id)
        (record-hof-captured! state source-node-id displayed-name))

      :else
      (let [node-id (str "unset-" source-node-id "-" arg-id)
            edge-id (str "e-unset-" source-node-id "-" arg-id)
            ;; Empty sequence anchor: the arg itself is :sequence-typed
            ;; and the chain head is nil. Mark the node so the frontend
            ;; routes the click into `appendSequenceItem` (Phase 5)
            ;; instead of the regular free-arg binder, which would try
            ;; to PUT `value=` on the anchor.
            empty-seq? (and arg-rec
                            (= :sequence (:type arg-rec))
                            (nil? (:next-arg-id arg-rec)))]
        (when-not (contains? (:added-node-ids @state) node-id)
          (swap! state update :added-node-ids conj node-id)
          (swap! state update :nodes conj
                 {:data (cond-> (merge {:id node-id
                                        :label (if arg-type (name arg-type) "any")
                                        :type "fn"
                                        :isPlaceholder true
                                        ;; argId / argType let the frontend
                                        ;; offer in-place binding of free-arg
                                        ;; slots (Phase 4) without re-deriving
                                        ;; them from the node-id string.
                                        :argId (str arg-id)}
                                       (when arg-rec
                                         (arg-row->node-id-fields arg-rec)))
                          arg-type  (assoc :argType (name arg-type))
                          empty-seq? (assoc :isSequenceAnchor true
                                            :sequenceFnId (str (:fn-id arg-rec))))})
          (swap! state update :edges conj
                 {:data (merge {:id edge-id
                                :source source-node-id
                                :target node-id
                                :sourceArgId arg-id
                                :argName displayed-name
                                :isUnset true}
                               ;; Same id-bundle as a bound-arg edge so the
                               ;; edge-label overlay can resolve the slot /
                               ;; fn / type via `argRowFromNode` and render
                               ;; the type-chip on this edge (the placeholder
                               ;; carries no type label of its own).
                               (edge-source-fields lookups arg-id)
                               (edge-narrowing-fields lookups arg-id expanded-fns))}))))))


(defn target-interface-names
  "Distinct external names of `target-fn-id`'s named free slots. E.g.
   `merge-in` declares `:value {:as :defaults}`, so its interface is
   `[\"defaults\"]`. Used to enrich edge labels when the source-side
   label is uninformative — most notably sequence chain items whose
   own arg has `:source-id=nil` and no name, so the source-chain walk
   yields nil and the edge falls back to the synthetic `maps[0]`-style
   index."
  [lookups target-fn-id]
  (when target-fn-id
    (->> (get (:args-by-fn lookups) target-fn-id [])
         (keep (fn [a]
                 (when (and (:name a)
                            (nil? (:value a))
                            (nil? (:ref-id a))
                            (:source-id a))
                   (:name a))))
         (distinct)
         (vec))))


(defn build-inverse-source-map
  "Reverse source-id index: arg-id → vector of args whose `:source-id`
   points directly here. Used to walk DOWNWARD from a HOF-target's free
   arg to find caller-side bindings via structural source-id chains
   (cross-HOF)."
  [arg-map]
  (reduce (fn [m a]
            (if-let [sid (:source-id a)]
              (update m sid (fnil conj []) a)
              m))
          {}
          (vals arg-map)))


(defn caller-bound-arg
  "Walks the cross-HOF inheritance lineage of `target-arg-id` and
   returns the first arg in that lineage with `:value` or `:ref-id`
   set — i.e., the caller-side binding that fills the inner unset
   slot. Returns nil when no caller has supplied a value.

   Algorithm:
     1. Walk UP `target-arg-id`'s `:source-id` chain to the terminal
        anchor (`source-id=nil`). Every member of the chain shares a
        slot identity.
     2. BFS DOWN `inverse-source-map` starting at the terminal — that
        traverses every arg in any descendant fn that views the same
        slot.
     3. Return the first hit with a binding.

   Used both to classify HOF captures (λ-badge vs migration) and to
   record the migration target so post-processing rewrites the edge
   to originate at the inner consumer node."
  [arg-map inverse-source-map target-arg-id]
  (let [terminal (loop [aid target-arg-id]
                   (if-let [parent (some-> aid arg-map :source-id)]
                     (recur parent)
                     aid))]
    (loop [queue [terminal]
           visited #{}]
      (when (seq queue)
        (let [cur (peek queue)
              rest-q (pop queue)]
          (if (contains? visited cur)
            (recur rest-q visited)
            (let [children (get inverse-source-map cur [])
                  bound (some (fn [a]
                                (when (or (true? (:value-present a))
                                          (some? (:ref-id a)))
                                  a))
                              children)]
              (or bound
                  (recur (into rest-q (map :id) children)
                         (conj visited cur))))))))))


(defn edge-narrowing-fields
  "Optional `:typeChain` edge-data field. Returns `{}` when the chain
   is uninteresting (no narrowing visible at the current expansion),
   so callers can `(merge … (edge-narrowing-fields …))` unconditionally."
  [lookups arg-id expanded-fns]
  (if-let [chain (compute-edge-type-chain lookups arg-id expanded-fns)]
    {:typeChain chain}
    {}))


(defn compute-edge-type-chain
  "Walk the same source-chain `compute-edge-label` uses, group adjacent
   anchor rows by their effective `:type`, and surface the groups iff
   the user's expansion crosses a type-narrowing boundary (≥ 2 distinct
   groups visible AND those groups span more than one fn). Each group:
   `{:type kw :fns [fn-name …]}`, leaf first. nil when no narrowing is
   visible — the edge then keeps its default single-chip rendering.

   The extra cross-fn gate is what keeps a sequence-item edge clean:
   inside one fn, the source-chain traverses item → anchor (e.g. `any`
   → `sequence`), but that's a container/element relation, not an
   inheritance narrowing. Without the gate the edge would carry a
   bogus '↑ sequence (router-ring-response)' row that says nothing
   about ancestors."
  [lookups arg-id expanded-fns]
  (let [{:keys [fn-map arg-map binding-by-fn-slot]} lookups]
    (when arg-id
      (let [source-chain (loop [acc [], cur (get arg-map arg-id)]
                           (if cur
                             (recur (conj acc cur)
                                    (some-> (:source-id cur) arg-map))
                             acc))
            visible (filter #(contains? expanded-fns (:fn-id %)) source-chain)
            labeled (mapv (fn [arg]
                            (let [b (get binding-by-fn-slot
                                         [(:fn-id arg) (:slot-id arg)])]
                              {:fn   (some-> (:fn-id arg) fn-map :name name)
                               :type (some-> (:type arg) name)
                               ;; `build-anchor-row`'s `:type` is the slot's
                               ;; declared type unless a binding overrides it
                               ;; — so a narrowing visible in this chain is
                               ;; attributable to one of those two sources.
                               :source (if (:type-override-fn-id b)
                                         "binding-override" "slot-declared")}))
                          visible)
            groups (->> labeled
                        (partition-by :type)
                        (mapv (fn [grp]
                                {:type   (:type (first grp))
                                 :fns    (vec (keep :fn grp))
                                 :source (:source (first grp))})))
            distinct-fns (->> groups (mapcat :fns) distinct count)]
        (when (and (> (count groups) 1)
                   (> distinct-fns 1))
          groups)))))


(defn compute-edge-label
  "Pick the most informative label for an edge sourced at `arg-id`,
   given the current set of `expanded-fns`. Walks the source-id chain
   and prefers names from fns the user actually expanded; falls back
   to the target fn's interface names so the user sees WHICH slot of
   the target this edge is feeding."
  [lookups arg-id _source-node-id expanded-fns]
  (let [{:keys [fn-map arg-map]} lookups]
    (when arg-id
      (let [source-chain (loop [acc [], cur (get arg-map arg-id)]
                           (if cur
                             (recur (conj acc cur)
                                    (some-> (:source-id cur) arg-map))
                             acc))
            source-arg (get arg-map arg-id)
            ;; Keep only args whose fn-id is in the expanded set
            visible (filter #(contains? expanded-fns (:fn-id %)) source-chain)
            labeled (mapv (fn [arg]
                            {:fn (some-> (:fn-id arg) fn-map :name name)
                             :arg-name (bnd/resolve-arg-name arg arg-map)})
                          visible)
            groups (->> labeled
                        (partition-by :arg-name)
                        (mapv (fn [grp]
                                {:name (:arg-name (first grp))
                                 :fns (vec (keep :fn grp))})))
            source-label
            (cond
              (empty? groups) nil
              ;; Single arg name across every visible ancestor — no rename
              ;; along the chain, so no fn-name disambiguation is needed.
              (= 1 (count groups)) (:name (first groups))
              :else (->> groups
                         (map (fn [{nm :name fns :fns}]
                                (if (seq fns)
                                  (str nm " (" (str/join ", " fns) ")")
                                  nm)))
                         (str/join "\n")))]
        (cond
          ;; Source-chain gave a non-blank label — keep it.
          (and source-label (not (str/blank? source-label))) source-label

          ;; No useful source-side name (typically a chain item with
          ;; source-id=nil). Fall back to the target's renamed free
          ;; args.
          :else
          (let [interface-names (target-interface-names lookups (:ref-id source-arg))]
            (when (seq interface-names)
              (str/join ", " interface-names))))))))
