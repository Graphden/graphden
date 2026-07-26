(ns graphden.crud.entities
  "Heavy CRUD logic for the web/crud base functions — the bodies
   behind the generic `create/update/delete-entity`, the form
   parsers, the `process-*` request dispatchers, the sequence-arg
   operations, the fn-type / effects tightening flow, and the
   graph dump / single-entity query.

   Top of the crud.* DAG: may require every other `graphden.crud.*`
   namespace. It does NOT — and must not — depend on any
   `graphden.packages.*` package: the rendering code that does stays
   in `web/crud/impls.clj`."
  (:require
    [clojure.set]
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.crud.request :as request]
    [graphden.crud.secret-shape :as secret-shape]
    [graphden.crud.type-check :as tc]
    [graphden.crud.types-api :as types-api]
    [graphden.crud.validation :as validation]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.records :as records]
    [graphden.services.reconciler :as recon]
    [graphden.storage.postgres.graph-epoch :as epoch]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.util.abort-shield :as shield]
    [graphden.versioning.branch-local :as branch-local]
    [graphden.versioning.storage.core :as vcore]
    [graphden.web.errors :as web-errors]))


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

(defn affected-fn-ids
  "Returns the seed set of fn-ids whose compiled closure may be invalidated by a
   write to `entity-type` carrying `entity-data`.

   Three answers, and the difference between the last two is the whole point:

   - a non-empty set ⇒ delta-recompile exactly these and their dependents;
   - `#{}` ⇒ the write cannot have changed ANY compiled closure. Leave the
     registry alone;
   - `nil` ⇒ *unknown* shape. The caller must full-clear.

   `:ns` and `:slot` used to answer `nil` — under a comment that said, of the
   namespace, that it \"doesn't touch closures\". Both therefore dropped the
   compiled registry, and the NEXT request rebuilt every fn in the graph.
   Measured on the e2e graph (4137 fns): create one namespace, and the next
   request took 49.6 s; create one slot, 49.8 s. Reads either side of it: 14 ms.
   Every type-editing test in the suite creates slots, which is why three of them
   cost 165 s, 115 s and 114 s while the median file costs 8 s."
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
      (some-> (sp/read-entity storage :binding bid) :fn-id hash-set))

    ;; A namespace is a label. No compiled closure reaches one, and the graph
    ;; cache does not hold one either.
    :ns #{}

    ;; A slot is an immutable global identity, and an fn reaches its slots
    ;; through `fn-slot` junction rows — which are written separately and carry
    ;; their own `:fn-id`, so they take the delta path above. So the fns whose
    ;; closure a slot write can touch are exactly the fns that already EXPOSE it:
    ;; none, on a create (nothing points at it yet), and none on a delete (the
    ;; guard refuses while an fn-slot still does). The query is here rather than
    ;; an assumed `#{}` so that any future in-place slot edit is seeded correctly
    ;; instead of silently skipped.
    :slot
    (when-let [id (:id entity-data)]
      (into #{} (keep :fn-id) (sp/query-entities storage :fn-slot {:slot-id id})))

    ;; A `:service` row is desired-state metadata — "keep THIS fn running". It
    ;; changes no fn's DEFINITION, so no compiled closure moves. It used to answer
    ;; nil (the fallthrough), which the invalidator reads as "unknown shape" and
    ;; handles by dropping the whole compiled registry — so every service
    ;; create / enable / disable / delete full-cleared the registry, and the next
    ;; request (often the reconcile's own execute) recompiled the entire graph.
    ;; Measured: the service-lifecycle flow full-cleared 12 times and each blocked
    ;; the executor on a ~48s recompile. The reconciler reacts to service writes
    ;; through its own NOTIFY listener (`service:<op>` events), never through this
    ;; fn-graph invalidation.
    :service #{}

    ;; Everything else — :execution rows, package pins, tenancy addon
    ;; entities (:org / :token / :domain / :grant / :user), any future
    ;; addon schema — cannot move a compiled closure: the compiler reads
    ;; ONLY the fn-graph entity types enumerated above. These used to hit
    ;; a `nil` fallthrough ("unknown shape" → full registry clear), which
    ;; made every such write pay a whole-graph recompile on the next
    ;; request — the same cliff the :ns / :slot / :service arms above
    ;; were pulled out of, one entity type at a time. If a new entity
    ;; type ever DOES participate in compilation, it must be added to
    ;; `fn-graph-entity-types` + given an arm here — the compiler would
    ;; have to be taught about it anyway.
    #{}))


(defn invalidate!
  "Convenience wrapper: derive the affected fn-id seeds and call
   `invalidate-graph-cache!` with the right arity. Pass `entity-data`
   that already includes `:id` (so :fn deletes pre-read the row,
   binding-list-item deletes pre-read the item).

   `:fn` writes also drop the per-storage branch-local cache (in
   `graphden.versioning.branch-local`) — `:parent-ids` and
   `:branch-local?` changes can both shift the effective set, and
   the cache key is the storage handle so it lives below the
   graph-cache layer.

   Then sweeps sibling branch ctxs via
   `branch-router/invalidate-affected-ctxs!`: a branch that inherits
   from the written branch resolves the new rows on read but would keep
   serving the compiled closures it cached earlier.

   Also kicks `recon/restart-services-depending-on!` against the
   affected fn-id seeds so cron-loop services whose closure was
   captured before the edit get restarted. HTTP services re-read
   their compiled-registry lazily on the next request and don't
   need the hint; cron loops sit in closed-over fn-graphs and
   would otherwise fire the pre-edit code forever. Best-effort —
   the restart is observability-grade; a failure during it
   doesn't fail the user's CRUD call. No-op when the reconciler
   singleton isn't wired (tests, REPL eval)."
  [ctx storage entity-type entity-data]
  ;; Abort-shielded (form path calls this as its own graph node) —
  ;; see create-entity's note.
  (shield/run!
    (fn []
      (when (= entity-type :fn)
        ;; Cache lives below the VersionedStorage wrapper and is keyed by
        ;; the BASE storage handle; unwrap before invalidating.
        (let [base (or (:base-storage storage) storage)]
          (branch-local/invalidate! base)))
      ;; The cache holds slot rows, and a `:slot` write now invalidates nothing
      ;; (see `affected-fn-ids`) — so splice the single row rather than let a reader
      ;; of the whole graph miss it.
      (when (= entity-type :slot)
        (when-let [id (:id entity-data)]
          (exec-ctx/refresh-slot-in-graph-cache! ctx id)))
      (let [seeds (affected-fn-ids storage entity-type entity-data)]
        (if seeds
          (exec-ctx/invalidate-graph-cache! ctx seeds)
          (exec-ctx/invalidate-graph-cache! ctx))
        ;; The write is visible from every branch that inherits from the one
        ;; we wrote on, and each of those may have its own cached compiled
        ;; registry on this pod. Sweep them too — `invalidate-graph-cache!`
        ;; above only touched the ctx the request came in on.
        (when-let [router (br/current-router)]
          (br/invalidate-affected-ctxs! router (vcore/current-branch-id storage) seeds))
        (when (seq seeds)
          (try
            ;; `recon/running` is a process-wide defonce atom — the same
            ;; one the integrant init wired up.
            (recon/restart-services-depending-on!
              ctx recon/running seeds (vcore/current-branch-id storage))
            (catch Exception e
              (log/warn e
                        "post-edit service restart hook failed"
                        {:entity-type entity-type :seeds seeds}))))
        ;; Eager work done — mark the router's epoch watermark with THIS
        ;; write's bump so the lazy fetch-time heal doesn't re-clear what
        ;; the delta invalidation above already handled. If this line is
        ;; never reached (client abort anywhere above), the watermark
        ;; stays behind and the next context fetch heals — that is the
        ;; audit-6 self-heal contract.
        (br/note-graph-epoch-validated! storage)))))


(def ^:private fn-graph-entity-types
  "Entity types whose mutations invalidate compiled fn-graphs on
   every pod. `:ns` is OUT — namespace renames don't touch fn
   closures (the editor only displays them differently)."
  #{:fn :slot :fn-slot :binding :binding-list-item})


(defn notify-after-write!
  "Fire NOTIFY events on `graphden_events` so sibling pods react to
   the mutation:

   - `:service` writes → `service:<op>:<id>` — handled by the
     reconciler's listener callback in `system/core.clj`.
   - fn-graph writes (`:fn` / `:slot` / `:fn-slot` / `:binding` /
     `:binding-list-item`) → one `fn:invalidate:<seed-fn-id>|<branch-id>`
     event per affected fn-id (delta invalidation), or
     `fn:invalidate:|<branch-id>` with empty id when the change is
     cross-cutting (full clear) — handled by `:exec/compiled-registry`'s
     listener callback.

   The branch-id rides along because the sibling pod has to answer the
   same question this pod answered locally: which of MY cached branch
   registries can see this write? Without it a sibling would either
   over-invalidate (recompiling `main` for a `dev` edit) or, as it did
   before, invalidate only its base ctx and leave every cached branch
   serving stale closures.

   Cheap on the write path: one `pg_notify` SQL against the main
   pool, per emitted event. Becomes a no-op when the ctx has no
   `:notify-emitter` (tests without PG)."
  [ctx storage entity-type op data]
  (when-let [emit (:notify-emitter ctx)]
    (cond
      (and (= entity-type :service) (:id data))
      (emit {:kind :service :op op :id (str (:id data))})

      (contains? fn-graph-entity-types entity-type)
      (let [seeds (affected-fn-ids storage entity-type data)
            branch-id (some-> (vcore/current-branch-id storage) str)
            ;; The writing org, straight off the written row — OrgScopedStorage
            ;; stamps `:org-id` on scoped entities, so `data` (the returned
            ;; row) carries it without this core-layer code depending on
            ;; tenancy. nil in single-tenant / un-scoped writes → omitted. Used
            ;; only by the SSE relay to fan an event out to the right org's
            ;; remote executors (the local invalidate path ignores it).
            org-id (:org-id data)
            ;; `cond->`, not a bare assoc: an un-versioned storage has no
            ;; branch, and a nil-valued key is a different map from an
            ;; absent one — `parse-payload` omits it on the way back for
            ;; the same reason.
            ;; The request's exact epoch bumps ride the event so the
            ;; receiving pod can mark them covered (4th payload slot).
            epochs (some-> epoch/*request-bump-log* deref seq vec)
            event (fn [id]
                    (cond-> {:kind :fn :op :invalidate :id id}
                      branch-id (assoc :branch-id branch-id)
                      org-id (assoc :org-id org-id)
                      epochs (assoc :epochs epochs)))]
        ;; The same three-way answer the local path takes, because a pod receives
        ;; its OWN notify: an empty-id event means "full clear" to the listener,
        ;; so emitting one for a write that changed no closure would undo the
        ;; local delta and rebuild the whole graph on the next request — which is
        ;; exactly what a `:slot` write did, from here, even after the local path
        ;; was fixed.
        ;;
        ;; A bare slot that nothing exposes is invisible to every compiled
        ;; closure, on this pod and on its siblings. The moment an fn DOES expose
        ;; it, that `:fn-slot` write emits a seeded event, and the sibling's
        ;; splice reads the slot row in with it.
        (cond
          (seq seeds) (doseq [seed seeds]
                        (emit (event (str seed))))
          ;; nil ≡ "unknown shape" — mirror the local `(invalidate-graph-cache!
          ;; ctx)` full clear.
          (nil? seeds) (emit (event ""))
          :else nil)))))


(defn html-error-response
  "Wrap `reason` in a Ring response with the canonical
   `<p class=\"error\">…</p>` body the editor's CSS expects. Centralises
   what was eight near-identical literal builders scattered across the
   create/update/delete + sequence apply branches. The
   `Content-Type` header is set explicitly so the response is correct
   regardless of whatever an upstream wrapper decides.

   Public so the `crud.entities.seq` / `crud.entities.tighten`
   sub-namespaces can build the same error envelope without
   duplicating the literal."
  [status reason]
  {:status status
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str "<p class=\"error\">" reason "</p>")})


;; === Context-aware Query Functions ===

(defn list-entities
  [entity-type where ctx]
  (vec (sp/query-entities (request/require-storage ctx)
                          (keyword entity-type) (or where {}))))


(defn get-entity
  [entity-type id ctx]
  (sp/read-entity (request/require-storage ctx) (keyword entity-type) id))


(defn- secret-leaf-capability-rej
  "Refuse :fn creates whose parent-ids touches ANY admin-only vault
   base-fn (declared via `:tags #{:admin-only-vault}` in
   `web/vault/fns.edn`; see `secret-shape/find-admin-only-vault-base-fn-ids`),
   UNLESS the data carries `:_admin-secret-create true`. The admin
   path (`crud.secrets/create-secret`) sets the marker and strips
   it before calling the storage layer; any other path (the
   generic `/api/entities/fn` endpoint, ad-hoc API clients, etc.)
   reaches this gate WITHOUT the marker and gets bounced through
   `/api/secrets`.

   Covers `:secret-leaf`, `:vault-put`, `:vault-delete`,
   `:vault-metadata-put`. The three write-side bases are admin-side
   operations that mutate OpenBao state; user fn-defs that compose
   them would bypass the audited `/api/secrets` flow.
   `:vault-metadata-get` (read-only) is NOT gated — metadata isn't
   a secret value.

   Returns a rejection map or nil. Mirrors the shape `write-rej`
   returns so the existing error-throw branch handles both."
  [storage data]
  (let [gated-ids (secret-shape/find-admin-only-vault-base-fn-ids storage)
        parents-set (set (:parent-ids data))]
    (when (seq (clojure.set/intersection gated-ids parents-set))
      (when-not (:_admin-secret-create data)
        {:type :capability/secret-leaf-restricted
         :reason "fn-defs with parent on an admin-only vault base-fn (:secret-leaf / :vault-put / :vault-delete / :vault-metadata-put) can only be created via POST /api/secrets — the admin path that also writes the value to OpenBao. Use the Secrets sidebar panel in the editor, or call /api/secrets directly."}))))


(defn create-entity
  [entity-type data ctx]
  ;; Abort-shielded: the whole bump->write->invalidate->note pipeline
  ;; completes even if the client disconnects mid-request (see
  ;; util.abort-shield) - un-noted epochs made every abort cost a
  ;; background recompile via the graph-epoch heal.
  (shield/run!
    (fn []
      (let [storage (request/require-storage ctx)
            et (keyword entity-type)
            ;; For :fn create the row may not have an `:id` yet; the
            ;; cycle check still wants it (parent / FK targets need to
            ;; know who's "owner"). Synthesize one so the check sees a
            ;; stable owner — `sp/create-entity` honours a pre-supplied
            ;; `:id` so the synthesized value is what lands in storage.
            ;; `:binding` :value-present normalisation lives in
            ;; `storage/protocol/core/standard-crud-normalize-data`
            ;; (called from every postgres CRUD entry) so direct
            ;; `sp/create-entity` users (tests, sync) pick it up too.
            data' (cond-> data
                    (and (= et :fn) (nil? (:id data))) (assoc :id (random-uuid)))]
        ;; Capability gate: secret-shaped fn-defs are admin-only — see
        ;; `secret-leaf-capability-rej` for the rationale. The marker is
        ;; an in-memory contract between `crud.secrets` and this fn; it
        ;; never reaches storage.
        (when (= et :fn)
          (when-let [rej (secret-leaf-capability-rej storage data')]
            (throw (ex-info (:reason rej)
                            {:type (:type rej)
                             :entity-type et
                             :data (dissoc data' :_admin-secret-create)}))))
        (when-let [rej (validation/write-rej storage et data')]
          (throw (ex-info (:reason rej)
                          {:type (:type rej)
                           :entity-type et :data data'})))
        (let [result (sp/create-entity storage et (dissoc data' :_admin-secret-create))]
          (invalidate! ctx storage et result)
          (notify-after-write! ctx storage et :write result)
          result)))))


(defn update-entity
  [entity-type id data ctx]
  ;; Abort-shielded: the whole bump->write->invalidate->note pipeline
  ;; completes even if the client disconnects mid-request (see
  ;; util.abort-shield) - un-noted epochs made every abort cost a
  ;; background recompile via the graph-epoch heal.
  (shield/run!
    (fn []
      (let [storage (request/require-storage ctx)
            et (keyword entity-type)
            check-data (assoc data :id id)]
        (when-let [rej (validation/write-rej storage et check-data)]
          (throw (ex-info (:reason rej)
                          {:type (:type rej)
                           :entity-type et :id id :data data})))
        (let [result (sp/update-entity storage et id data)]
          (invalidate! ctx storage et result)
          (notify-after-write! ctx storage et :write (assoc result :id id))
          result)))))


(defn delete-entity
  [entity-type id ctx]
  ;; Abort-shielded: the whole bump->write->invalidate->note pipeline
  ;; completes even if the client disconnects mid-request (see
  ;; util.abort-shield) - un-noted epochs made every abort cost a
  ;; background recompile via the graph-epoch heal.
  (shield/run!
    (fn []
      (let [storage (request/require-storage ctx)
            et (keyword entity-type)
            ;; Pre-read so we know the parent fn-id for binding /
            ;; fn-slot / binding-list-item before the row is gone.
            ;; For :fn we need the row anyway to drop its
            ;; rich-types-registry entry by NAME (the registry is keyed
            ;; on fn-name, not fn-id), so the read pays for itself.
            snapshot (if (= et :fn)
                       (or (sp/read-entity storage et id) {:id id})
                       (sp/read-entity storage et id))]
        ;; User-facing delete → tombstone (so deleting an inherited entity on a
        ;; branch actually hides it, not a silent no-op). Sync / rollback deletes
        ;; keep the default hard-delete.
        (binding [vcore/*tombstone-delete?* true]
          (sp/delete-entity storage et id))
        ;; rich-types-registry entry survives the storage delete unless
        ;; we explicitly drop it. Without this the registry grows
        ;; monotonically as fn-defs are created and deleted across an
        ;; executor's lifetime — small per-entry but on a long-running
        ;; prod instance it adds up to a real GC-pressure source.
        (when (and (= et :fn) (:name snapshot))
          ;; Row id threaded so the drop is keyed by THIS identity — a
          ;; same-named duplicate (stale-identity class) keeps its entry.
          (registry/unregister-rich-type! (keyword (:name snapshot)) id))
        (invalidate! ctx storage et snapshot)
        ;; NOTIFY the full pre-read `snapshot` (not a bare `{:id id}`): sibling
        ;; pods' `affected-fn-ids` needs the row's FKs (`:binding-id` / `:fn-id`)
        ;; to derive the delta seed. A bare id fell through to the empty-seed
        ;; (full-clear) NOTIFY — and since a pod receives its OWN NOTIFY, every
        ;; fn-graph delete (incl. a single sequence-item remove) then forced a
        ;; full compiled-registry rebuild (tens of seconds) on the emitting pod.
        (notify-after-write! ctx storage et :delete (assoc (or snapshot {}) :id id))
        true))))


(defn- subtree-fn-id-closure
  "BFS the set of fn-ids transitively reachable from `root-id` via:
   - `parent-ids` (inheritance chain)
   - `binding.ref-fn-id` for bindings owned by an in-set fn
   - `binding.type-override-fn-id` for those same bindings
   - `binding-list-item.ref-fn-id` for items under those bindings
   - `slot.type-fn-id` for slots in any in-set fn's `fn-slots` row

   These are exactly the edges that the editor + layout + runtime
   need to render or execute the root fn. Nothing else in the graph
   contributes to that view.

   `graph` is the full graph map from `cached-or-load-graph`."
  [graph root-id]
  (let [fns-by-id        (into {} (map (juxt :id identity)) (:fns graph))
        fn-slots-by-fn   (group-by :fn-id (:fn-slots graph))
        slots-by-id      (into {} (map (juxt :id identity)) (:slots graph))
        bindings-by-fn   (group-by :fn-id (:bindings graph))
        items-by-binding (group-by :binding-id (:list-items graph))
        seen (java.util.HashSet.)
        stack (java.util.ArrayDeque.)
        push! (fn [^java.util.UUID id]
                (when (and id (not (java.util.HashSet/.contains seen id)))
                  (java.util.ArrayDeque/.push stack id)))]
    (push! root-id)
    (while (not (java.util.ArrayDeque/.isEmpty stack))
      (let [fid (java.util.ArrayDeque/.pop stack)]
        (when-not (java.util.HashSet/.contains seen fid)
          (java.util.HashSet/.add seen fid)
          (when-let [fn-row (get fns-by-id fid)]
            (doseq [pid (:parent-ids fn-row)] (push! pid))
            ;; The fn's own type-fn / impl references — so a fn's subtree is
            ;; self-contained for by-id type resolution once the editor no
            ;; longer holds a full-fns mirror. `base-fn-id` (composed → its
            ;; base), `return-type-fn-id` (base-fn's declared return type),
            ;; `element-fn-id` (a list type-row's element type). Each resolves
            ;; to a small base-fn / type-row.
            (push! (:base-fn-id fn-row))
            (push! (:return-type-fn-id fn-row))
            (push! (:element-fn-id fn-row))
            (doseq [b (get bindings-by-fn fid)]
              (push! (:ref-fn-id b))
              (push! (:type-override-fn-id b))
              (doseq [it (get items-by-binding (:id b))]
                (push! (:ref-fn-id it))))
            (doseq [fs (get fn-slots-by-fn fid)]
              (when-let [slot (get slots-by-id (:slot-id fs))]
                (push! (:type-fn-id slot))))))))
    (set seen)))


(defn- filter-graph-to-fn-ids
  "Filter every row in `graph` down to those that participate in the
   given `fn-id-set`. Mirrors the `subtree-fn-id-closure` edge rules:
   own bindings + own list-items + own fn-slots + their referenced
   slots."
  [graph fn-id-set]
  (let [kept-fns        (filterv #(contains? fn-id-set (:id %))     (:fns graph))
        kept-fn-slots   (filterv #(contains? fn-id-set (:fn-id %))  (:fn-slots graph))
        kept-slot-ids   (into #{} (map :slot-id) kept-fn-slots)
        kept-slots      (filterv #(contains? kept-slot-ids (:id %)) (:slots graph))
        kept-bindings   (filterv #(contains? fn-id-set (:fn-id %))  (:bindings graph))
        kept-binding-ids (into #{} (map :id) kept-bindings)
        kept-items      (filterv #(contains? kept-binding-ids (:binding-id %))
                                 (:list-items graph))]
    {:fns        kept-fns
     :slots      kept-slots
     :fn-slots   kept-fn-slots
     :bindings   kept-bindings
     :list-items kept-items}))


(defn strip-impl-of
  "Hide the internal COMPOSITION of the fns whose ids are in `hidden-fn-ids`
   from a graph dump: blank each hidden fn's `:parent-ids` and drop its
   bindings + binding-list-items, leaving its SIGNATURE (name / namespace /
   return-type / fn-slots / slots) intact. The fn stays discoverable and
   executable — only how it is built is concealed; the executor runs the full
   graph server-side, so hiding this from a viewer never affects execution.

   Pure: the caller decides which ids are hidden (own-org ownership /
   `:view-impl` grant — see the tenancy filter). Gracefully no-ops on dump
   shapes without `:fns` (`:tree` / `:namespace` / `:search`)."
  [graph hidden-fn-ids]
  (if (empty? hidden-fn-ids)
    graph
    (let [dropped-binding-ids (into #{}
                                    (comp (filter #(contains? hidden-fn-ids (:fn-id %)))
                                          (map :id))
                                    (:bindings graph))]
      (cond-> graph
        (:fns graph)        (update :fns
                                    (fn [fns]
                                      (mapv #(if (contains? hidden-fn-ids (:id %))
                                               (assoc % :parent-ids [])
                                               %)
                                            fns)))
        (:bindings graph)   (update :bindings
                                    (fn [bs]
                                      (filterv #(not (contains? hidden-fn-ids (:fn-id %))) bs)))
        (:list-items graph) (update :list-items
                                    (fn [items]
                                      (filterv #(not (contains? dropped-binding-ids (:binding-id %)))
                                               items)))))))


;; Seam: a `(fn [graph-dump] -> graph-dump)` the tenancy addon installs to
;; strip the composition of fns the CURRENT viewer lacks `:view-impl` on —
;; `strip-impl-of` with the hidden set computed from the request's grants +
;; org. nil (no addon / single-tenant) = identity, everything visible. Held
;; in an atom so the addon installs it at init with no compile-time dep from
;; this layer up into tenancy. (`defonce` takes no docstring — hence the
;; comment; `defonce` so a namespace reload doesn't wipe the installed filter.)
(defonce view-impl-filter (atom nil))


(defn apply-view-impl-filter
  "Run the installed `view-impl-filter` over a graph dump; identity when the
   seam is unset (single-tenant / no tenancy addon)."
  [graph]
  (if-let [f @view-impl-filter]
    (f graph)
    graph))


(def ^:private light-fn-fields
  "The per-fn columns the editor's sidebar / picker / search views
   actually read. Every other column (slots, bindings, and the bulk of
   the scalar fn columns) is fetched on demand via `:subtree` when a fn
   is opened. Keep this in sync with the fields consumed in
   `editor-sidebar.js` / `editor-fn-picker.js` / `editor-data.js`.

   `:used-as-parent-count` / `:used-as-ref-count` are server-computed
   reverse-reference counts over the WHOLE graph (see `reverse-ref-index`),
   so the editor's delete/edit gate stays correct once it no longer holds
   a full-fns mirror to count against. Both are omitted (→ 0 client-side)
   when zero.

   `:org-id` rides along so the view-impl filter (tenancy) can tell a
   viewer's OWN-org fns (internals visible) from public / shared ones
   (internals hidden) in the light scopes too; it is dropped from the wire
   when nil (single-tenant) by the `remove nil? val` projection."
  [:id :name :namespace-id :org-id :role :description :constraint
   :parent-ids :return-type-fn-id
   :used-as-parent-count :used-as-ref-count])


(defn- reverse-ref-index
  "Reverse-reference tallies over the ENTIRE graph, so a caller holding
   only a slice can still answer \"how many fns depend on X\":

   - `:as-parent` — fn-id → #fns listing it in their `parent-ids`.
   - `:as-ref`    — fn-id → #bindings + #list-items whose `ref-fn-id`
     points at it.

   These are exactly the two dependency kinds the delete guard blocks on
   (`web/crud` `:_delete-fn-*`), so the editor's up-front gate matches the
   server's 409 instead of drifting from it. `type-override-fn-id` /
   `slot.type-fn-id` are intentionally NOT counted — the delete guard
   doesn't block on them either."
  [graph]
  {:as-parent (reduce (fn [m f] (reduce (fn [m pid] (update m pid (fnil inc 0))) m (:parent-ids f)))
                      {} (:fns graph))
   :as-ref (as-> {} m
                 (reduce (fn [m b] (if-let [r (:ref-fn-id b)] (update m r (fnil inc 0)) m)) m (:bindings graph))
                 (reduce (fn [m it] (if-let [r (:ref-fn-id it)] (update m r (fnil inc 0)) m)) m (:list-items graph)))})


(defn- with-ref-counts
  "Annotate a fn row with its reverse-reference counts from `rev`, omitting
   either count when zero (an absent key reads as 0 client-side)."
  [rev f]
  (let [ap (get (:as-parent rev) (:id f) 0)
        ar (get (:as-ref rev) (:id f) 0)]
    (cond-> f
      (pos? ap) (assoc :used-as-parent-count ap)
      (pos? ar) (assoc :used-as-ref-count ar))))


(defn- light-fn-row
  "Project a (roled) fn row — annotated with reverse-ref counts from `rev`
   — down to `light-fn-fields`, dropping nils so the wire payload carries
   no `\"x\":null` churn (an absent key reads as `undefined` client-side,
   identical to the editor's truthy checks)."
  [rev f]
  (into {} (remove (comp nil? val)) (select-keys (with-ref-counts rev f) light-fn-fields)))


(def ^:private default-search-limit
  "Cap on `:search` results. The sidebar filter / fn-picker only render a
   bounded list; an unbounded match on a huge graph would defeat the
   whole point of moving the filter server-side. `:truncated?` in the
   response tells the client more matched than were returned."
  200)


(defn list-all-graph-entities
  "Dump every storage row the editor needs to render the graph. Routes
   through the shared graph-cache (populated by layout / compile-
   runtime) so editor refreshes after mutations don't re-query the
   same five tables every time.

   Each fn-row is augmented with a `:role` field so the sidebar can
   group entries into Types vs Functions sections without an extra
   round-trip through `/api/types`.

   `scope` controls payload size:

   - `nil` / `:full` (default, backward compatible) — every
     `{:fns :slots :fn-slots :bindings :list-items :namespaces}`.
     Response is ~4.5 MB on a 3000-fn graph; appropriate for the
     editor's initial \"give me everything\" load.

   - `:tree` — `{:namespaces :counts}` ONLY, no fn rows. `:counts` is a
     vector of `{:namespace-id :count}` (named fns per namespace). This
     is the O(namespaces) sidebar-init payload: the editor renders a
     collapsed tree from it and pulls each namespace's fns lazily via
     `:namespace` on expand. Replaces `:index` on the editor hot path.

   - `:namespace` with `namespace-id` — light rows (`light-fn-fields`)
     for the named fns of that one namespace. The lazy-expand payload.

   - `:search` with `q` — light rows for named fns whose raw name
     contains `q` (case-insensitive), capped at `default-search-limit`
     with a `:truncated?` flag. The server-side replacement for the
     editor's client-side filter box + the fn / namespace / MI-reparent
     pickers + name→id resolution.

   - `:index` — only `{:fns :namespaces}` plus enough metadata for
     the sidebar tree (every fn's role + namespace-id + name). Drops
     `:slots`, `:fn-slots`, `:bindings`, `:list-items` entirely.
     Still O(all-fns); retained for CLI / batch / backward-compat
     callers. The editor no longer uses it (see `:tree`).

   - `:subtree` with `root-id` — only the fns transitively reachable
     from `root-id` via inheritance + binding refs + type overrides +
     list-item refs + own-slot type-fn-ids, plus the slots / fn-slots
     / bindings / list-items they own. Typically 30-60 fns / ~50 KB
     for a single editor fn-view. Falls back to `:full` shape if
     `root-id` is nil or doesn't resolve to a fn-row."
  ([ctx] (list-all-graph-entities ctx nil nil nil nil))
  ([ctx scope] (list-all-graph-entities ctx scope nil nil nil))
  ([ctx scope root-id] (list-all-graph-entities ctx scope root-id nil nil))
  ([ctx scope root-id namespace-id q]
   (let [storage (request/require-storage ctx)
         base (types-api/cached-or-load-graph ctx)
         fn-slots-by-fn (group-by :fn-id (:fn-slots base))
         ;; `roled-fns` / `namespaces` are only realised by the branches
         ;; that need them — the O(namespaces) `:tree` scope skips role
         ;; computation over every fn entirely, `:namespace` / `:search`
         ;; role only the projected subset (via `role-of`).
         rich-snapshot (delay (registry/rich-types-snapshot))
         role-of (fn [f]
                   (assoc f :role
                          (types-api/compute-fn-role
                            f
                            (boolean (seq (get fn-slots-by-fn (:id f))))
                            @rich-snapshot)))
         roled-fns (delay (mapv role-of (:fns base)))
         ;; Whole-graph reverse-ref tallies — realised only for the scopes
         ;; that project fn rows (`:namespace` / `:search` / `:subtree`).
         rev-index (delay (reverse-ref-index base))
         namespaces (delay (vec (sp/query-entities storage :ns {})))]
     (cond
       (= scope :tree)
       ;; Sidebar init: the namespace list + a per-namespace count of
       ;; NAMED fns (anonymous fns are never shown as leaves). No fn rows
       ;; at all — leaves load lazily via `:namespace`. This is the
       ;; O(namespaces) replacement for the O(all-fns) `:index` pull that
       ;; the editor fetched on every init AND every post-mutation refresh.
       {:namespaces @namespaces
        :counts (->> (:fns base)
                     (filter :name)
                     (group-by :namespace-id)
                     (mapv (fn [[nid fns]] {:namespace-id nid :count (count fns)})))}

       (= scope :namespace)
       ;; Lazy per-namespace expand: light rows for one namespace's named
       ;; fns. A `nil` `namespace-id` intentionally selects the "(root)"
       ;; bucket — the namespace-less fns the sidebar renders under its
       ;; `(root)` node — since `nil = (:namespace-id f)` matches them.
       {:fns (into []
                   (comp (filter #(and (:name %) (= namespace-id (:namespace-id %))))
                         (map (comp (partial light-fn-row @rev-index) role-of)))
                   (:fns base))}

       (= scope :search)
       ;; Server-side filter: case-insensitive substring on the raw fn
       ;; name, capped at `default-search-limit`. Replaces the client-side
       ;; scan over the (former) full-fns mirror in the sidebar filter box,
       ;; the fn / namespace / MI-reparent pickers, and name→id resolution.
       (let [needle (some-> q str/lower-case str/trim not-empty)
             matches (when needle
                       (into []
                             (filter #(and (:name %)
                                           (str/includes? (str/lower-case (:name %)) needle)))
                             (:fns base)))
             limited (into [] (take default-search-limit) matches)]
         {:fns (mapv (comp (partial light-fn-row @rev-index) role-of) limited)
          :truncated? (boolean (and needle (> (count matches) default-search-limit)))})

       (= scope :index)
       ;; Drop nil-valued fields from each fn row. This is a sidebar /
       ;; picker payload fetched fresh on every editor refresh (~3900 fns),
       ;; and most fns leave the majority of columns null (org-id,
       ;; deleted-at, anonymous-hash, constraint, base-fn-id,
       ;; element-fn-id, return-type-fn-id…). Serialising `"x":null` ~3900×
       ;; per column was ~25% of the ~1.9 MB response — pure churn on every
       ;; keep-alive-closed fetch. An absent key reads as `undefined` in
       ;; the editor's truthy checks exactly like `null`, so no data is
       ;; lost; the per-fn detail (with all fields) still comes from the
       ;; `:subtree` fetch on select.
       {:fns (mapv (fn [f] (into {} (remove (comp nil? val)) f)) @roled-fns)
        :namespaces @namespaces}

       (and (= scope :subtree) root-id)
       (let [closure (subtree-fn-id-closure base root-id)
             roled-by-id (into {} (map (juxt :id identity)) @roled-fns)
             sub (filter-graph-to-fn-ids base closure)
             ;; Annotate with whole-graph reverse-ref counts so the
             ;; graph-view delete/edit gate reads them off the fn row
             ;; instead of counting over the (now sliced) client mirror.
             sub-roled-fns (mapv #(with-ref-counts @rev-index (or (get roled-by-id (:id %)) %))
                                 (:fns sub))
             ;; Include each fn's namespace AND its parent chain so
             ;; the sidebar can render the full path (e.g. `web.crud
             ;; .branches` needs `web` + `web.crud` + `web.crud
             ;; .branches`). Without the parent walk a leaf-only ns
             ;; slice has no recoverable label tree.
             ns-by-id (into {} (map (juxt :id identity)) @namespaces)
             ns-ids (loop [acc #{} pending (into #{} (keep :namespace-id) sub-roled-fns)]
                      (if-let [nid (first pending)]
                        (if (contains? acc nid)
                          (recur acc (disj pending nid))
                          (let [n (get ns-by-id nid)
                                p (:parent-id n)]
                            (recur (conj acc nid)
                                   (cond-> (disj pending nid)
                                     (and p (not (contains? acc p)))
                                     (conj p)))))
                        acc))
             sub-namespaces (filterv #(contains? ns-ids (:id %)) @namespaces)]
         (assoc sub :fns sub-roled-fns :namespaces sub-namespaces))

       :else
       (-> base
           (assoc :fns @roled-fns)
           (assoc :namespaces @namespaces))))))


;; === Compound type-row create / update ======================================

(defn parse-create-record-type
  "Stage 1 of create-record-type — JSON body → `{:name :ns-id
   :description :fields}`."
  [request]
  (let [body (request/read-json-body request)
        ns-raw (:namespace-id body)]
    {:name (some-> (:name body) str)
     :ns-id (when-not (str/blank? (str ns-raw))
              (request/parse-uuid-or-clear (str ns-raw)))
     :description (:description body)
     :fields (vec (:fields body))}))


;; C19: stage 2 of create-record-type was here as
;; `validate-create-record-type`. Removed — replaced by the
;; `:_create-record-type-validation` `:cond` graph fn-def in
;; `web/crud/fns.edn` (predicates + error consts). Test-side
;; analogue lives in `entities_test/_validate-create-record-type-inline`.


(defn- create-record-type-fn-row!
  "Phase 1 of create-record-type — root `:fn` row + journal entry.
   Returns the new fn-id."
  [storage journal nm ns-id desc]
  (let [own-id (java.util.UUID/randomUUID)]
    (sp/create-entity storage :fn
                      (cond-> {:id own-id
                               :name nm
                               :namespace-id ns-id
                               :parent-ids []
                               :base-fn-id nil
                               :element-fn-id nil
                               :return-type-fn-id nil
                               :anonymous-hash nil
                               :constraint nil}
                        (and desc (seq desc)) (assoc :description desc)))
    (swap! journal conj [:fn own-id])
    own-id))


(defn- create-record-type-fields!
  "Phase 2 of create-record-type — mint a `:slot` + `:fn-slot`
   junction per field, journalling each create for the rollback
   callable. Resolves each field's `:type` to a type-fn-id, throws
   `:type-row/field-missing-name` if any field lacks a name."
  [storage journal own-id fields]
  (doseq [[idx field] (map-indexed vector fields)]
    (let [field-name (some-> (:name field) str)
          type-id (tc/resolve-type-fn-id-or-throw storage (:type field))
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
      (swap! journal conj [:slot slot-id])
      (sp/create-entity storage :fn-slot
                        {:id fn-slot-id
                         :fn-id own-id
                         :slot-id slot-id
                         :position idx})
      (swap! journal conj [:fn-slot fn-slot-id]))))


(defn apply-create-record-type-body
  "Phases 1-3 of create-record-type's atomic write — fn-row + N
   slot-rows + N fn-slot junctions + cache-invalidate. Mutates
   `journal` (shared atom-of-vector) on each successful storage
   write so the rollback callable can replay in reverse. Throws on
   any storage / type-resolve failure — caught by the surrounding
   `:try` graph node. Reached only after `:_create-record-type-
   validation` passed.

   Body is orchestration; each phase lives in a small private
   helper so the read flows top-to-bottom by phase name."
  [parsed journal ctx]
  (let [storage (request/require-storage ctx)
        {nm :name ns-id :ns-id desc :description fields :fields} parsed
        own-id (create-record-type-fn-row! storage journal nm ns-id desc)]
    (create-record-type-fields! storage journal own-id fields)
    (invalidate! ctx storage :fn {:id own-id})
    {:ok true :id (str own-id) :name nm}))


(defn apply-create-rollback
  "`:try`'s on-throw branch for create-record-type AND create-list-type.
   Derefs `journal` and replays entries in reverse, deleting each
   row best-effort (delete failures are logged, not re-thrown — the
   important contract is that user state stays consistent with what
   the response says). Returns the `{:ok false :error :data}` shape."
  [journal exception ctx]
  (let [storage (request/require-storage ctx)]
    (doseq [[et id] (reverse @journal)]
      (try (sp/delete-entity storage et id)
           (catch Exception e
             (log/warn e "Rollback delete-entity failed for"
                       et id "— manual cleanup may be required")))))
  ;; `Throwable/.getMessage` can return null (Java API contract); wrap
  ;; both branches in `str` so the `:error` field is always a string.
  (let [msg (str (Throwable/.getMessage ^Throwable exception))]
    (cond-> {:ok false :error msg}
      (instance? clojure.lang.ExceptionInfo exception)
      (assoc :data (ex-data exception)))))


;; create-list-type's parse + validation stages are graph fn-defs
;; (`:_create-list-type-parsed` / `:_create-list-type-validation`); the
;; rollback-bearing apply stage is `apply-create-list-type-body` +
;; `apply-create-rollback`, composed by the graph `:try`.


(defn apply-create-list-type-body
  "Phases 1-3 of create-list-type's atomic write — fn-row with
   `:element-fn-id` + synthesised `items` slot + fn-slot junction +
   cache-invalidate. Mutates `journal` (shared atom) for rollback.
   Throws on storage / type-resolve failure — caught by the
   surrounding `:try` graph node."
  [parsed journal ctx]
  (let [storage (request/require-storage ctx)
        {nm :name ns-id :ns-id desc :description element-ref :element-ref} parsed
        own-id (java.util.UUID/randomUUID)
        elem-id (tc/resolve-type-fn-id-or-throw storage element-ref)
        seq-id (tc/resolve-type-fn-id-or-throw storage "sequence")
        slot-id (java.util.UUID/randomUUID)
        fn-slot-id (java.util.UUID/randomUUID)]
    (sp/create-entity storage :fn
                      (cond-> {:id own-id
                               :name nm
                               :namespace-id ns-id
                               :parent-ids []
                               :base-fn-id nil
                               :element-fn-id elem-id
                               :return-type-fn-id nil
                               :anonymous-hash nil
                               :constraint nil}
                        (and desc (seq desc)) (assoc :description desc)))
    (swap! journal conj [:fn own-id])
    (sp/create-entity storage :slot
                      {:id slot-id
                       :name "items"
                       :type-fn-id seq-id
                       :required true})
    (swap! journal conj [:slot slot-id])
    (sp/create-entity storage :fn-slot
                      {:id fn-slot-id
                       :fn-id own-id
                       :slot-id slot-id
                       :position 0})
    (swap! journal conj [:fn-slot fn-slot-id])
    (invalidate! ctx storage :fn {:id own-id})
    {:ok true :id (str own-id) :name nm}))


;; update-record-type's parse stage is a graph fn-def composing
;; `:parse-json-body` + per-field getters + `:contains?` on
;; `:description` for the `:has-description?` distinction; validation is
;; the `:_update-record-type-validation` `:cond`. The rollback-bearing
;; apply stage is `apply-update-record-type-body` + `-rollback` below.


;; === Stage-3 update-record-type apply: journalled txn split for graph ===
;;
;; The 141-line monolith was decomposed so the journalled-write pattern
;; is visible at the graph level: `:_update-record-type-apply` is now a
;; `:try` (core.system) whose body runs phases 2-5 + invalidate + success
;; and whose `on-throw` reads the shared `:atom` journal and replays it
;; in reverse. The atom is a single `:_apply-update-record-type-journal`
;; fn-def referenced from both branches at the `:try`-call's cache
;; level, so body and rollback see the SAME instance.
;;
;; The phases themselves stay as ONE Clojure helper each — per-iteration
;; storage writes are still iteration (not composition), and the inner
;; per-field reuse/mint decision is tightly coupled to the journal.
;; Splitting further would scatter one conceptual operation into N
;; atom-threading hops with no semantic gain (skill graphden-fn-refactor
;; §3 §1). The win here is the OUTER shape — try / journal / rollback —
;; not lower-level per-iteration atomisation.

(defn- load-update-record-type-state
  "Pre-update snapshot the diff-and-apply needs: the current fn-row,
   its fn-slots, the underlying slot-rows, and the `[name type-fn-id]
   → fn-slot` index that drives the reuse-vs-mint decision."
  [storage fn-id]
  (let [existing-fn (first (sp/query-entities storage :fn {:id fn-id}))
        current-fss (sp/query-entities storage :fn-slot {:fn-id fn-id})
        current-slot-ids (mapv :slot-id current-fss)
        current-slots (when (seq current-slot-ids)
                        (sp/query-entities storage :slot
                                           {:id current-slot-ids}))
        slots-by-id (into {} (map (juxt :id identity)) (or current-slots []))
        ;; Match by (name, type-fn-id): retypes must yield a new
        ;; slot since slot rows are immutable.
        slots-by-name+type (into {}
                                 (map (fn [fs]
                                        (let [s (get slots-by-id (:slot-id fs))]
                                          [[(:name s) (:type-fn-id s)] fs])))
                                 current-fss)]
    {:existing-fn existing-fn
     :current-fss current-fss
     :slots-by-name+type slots-by-name+type}))


(defn- resolve-update-fields
  "Resolve every incoming field's type up front so a typo doesn't
   leave the row half-rewritten — all storage writes wait until we
   have the full resolved vector. Throws `:type-row/field-missing-
   name` on a blank name and propagates whatever
   `resolve-type-fn-id-or-throw` raises on a bad type ref."
  [storage fields]
  (mapv (fn [field]
          (let [field-name (some-> (:name field) str)
                _ (when (str/blank? field-name)
                    (throw (ex-info "field name required"
                                    {:type :type-row/field-missing-name})))
                type-id (tc/resolve-type-fn-id-or-throw storage (:type field))
                required? (if (contains? field :required)
                            (boolean (:required field)) true)]
            {:name field-name
             :type-fn-id type-id
             :description (:description field)
             :required required?}))
        fields))


(defn- compute-slot-assignments
  "Decide per-field whether to reuse a current slot (same name + type)
   or mint a fresh one. `:reuse?` flag drives every downstream phase;
   reused entries carry the existing slot-id / fn-slot-id, new ones
   carry pre-allocated UUIDs and the resolved `:spec`."
  [resolved slots-by-name+type]
  (mapv (fn [r]
          (if-let [fs (get slots-by-name+type
                           [(:name r) (:type-fn-id r)])]
            {:slot-id (:slot-id fs)
             :fn-slot-id (:id fs)
             :reuse? true}
            {:slot-id (java.util.UUID/randomUUID)
             :fn-slot-id (java.util.UUID/randomUUID)
             :reuse? false
             :spec r}))
        resolved))


(defn- create-new-slots!
  "Phase 2: insert slot rows for fields the diff classified as new.
   Records each insert in `journal` for the rollback path."
  [storage journal assignments]
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
      (swap! journal conj {:op :create :entity-type :slot :id slot-id}))))


(defn- delete-unused-fn-slots!
  "Phase 3: drop every current fn-slot whose id isn't in `kept-fs-ids`.
   Journals the full row so the rollback path can resurrect it."
  [storage journal current-fss kept-fs-ids]
  (doseq [fs current-fss
          :when (not (kept-fs-ids (:id fs)))]
    (sp/delete-entity storage :fn-slot (:id fs))
    (swap! journal conj {:op :delete :entity-type :fn-slot :row fs})))


(defn- rewire-fn-slot-positions!
  "Phase 4: walk `assignments` in target-position order — reused
   entries get a delete+re-create with the new position when they
   moved (UNIQUE constraint on `(fn-id, position)` means we can't
   bump in-place); new entries get a fresh fn-slot insert. Each leg
   is journalled so a downstream failure can fully rewind."
  [storage journal fn-id current-fss assignments]
  (doseq [[idx a] (map-indexed vector assignments)]
    (cond
      (:reuse? a)
      (let [old-fs (first (filter #(= (:id %) (:fn-slot-id a)) current-fss))]
        (when (and old-fs (not= (:position old-fs) idx))
          (sp/delete-entity storage :fn-slot (:id old-fs))
          (swap! journal conj {:op :delete :entity-type :fn-slot :row old-fs})
          (let [new-row (assoc old-fs :position idx)]
            (sp/create-entity storage :fn-slot new-row)
            (swap! journal conj {:op :create :entity-type :fn-slot
                                 :id (:id new-row)}))))

      :else
      (do
        (sp/create-entity storage :fn-slot
                          {:id (:fn-slot-id a)
                           :fn-id fn-id
                           :slot-id (:slot-id a)
                           :position idx})
        (swap! journal conj {:op :create :entity-type :fn-slot
                             :id (:fn-slot-id a)})))))


(defn- apply-fn-row-patch!
  "Phase 5: optional rename / re-description of the fn-row itself.
   No-op when neither field changes. The patch isn't journalled — a
   throw downstream would already have left earlier phases for the
   rollback path to reverse."
  [storage existing-fn fn-id nm has-description? desc]
  (when (or (and nm (not= nm (:name existing-fn)))
            has-description?)
    (let [patch (cond-> {}
                  (and nm (not= nm (:name existing-fn)))
                  (assoc :name nm)
                  has-description?
                  (assoc :description desc))]
      (sp/update-entity storage :fn fn-id patch))))


(defn apply-update-record-type-body
  "Body of `:_update-record-type-apply`'s `:try`. Performs phases 2-5
   of the diff-and-apply (create new slots / delete unused fn-slots /
   rewire positions / optional rename), appending rollback hints to
   `journal` along the way, then invalidates caches and returns the
   success response. Throws on any storage failure or bad-type-resolve
   — caught by `:try`, which hands control to `-rollback`.

   Body itself is the orchestration; each phase lives in a small
   `apply-update-record-type-*` private helper so the read flows
   top-to-bottom by phase name instead of by line range."
  [parsed journal ctx]
  (let [storage (request/require-storage ctx)
        {fn-id :fn-id nm :name desc :description fields :fields
         has-description? :has-description?} parsed
        {:keys [existing-fn current-fss slots-by-name+type]}
        (load-update-record-type-state storage fn-id)
        resolved (resolve-update-fields storage fields)
        assignments (compute-slot-assignments resolved slots-by-name+type)
        kept-fs-ids (into #{} (comp (filter :reuse?) (map :fn-slot-id))
                          assignments)]
    (create-new-slots! storage journal assignments)
    (delete-unused-fn-slots! storage journal current-fss kept-fs-ids)
    (rewire-fn-slot-positions! storage journal fn-id current-fss assignments)
    (apply-fn-row-patch! storage existing-fn fn-id nm has-description? desc)
    ;; The compound write happened through `sp/*-entity` —
    ;; bypassing the defbase wrappers that normally call
    ;; `invalidate!`. Without this nudge the next read of
    ;; `/api/graph/entities` would return the cached pre-
    ;; update graph and the editor would see no change.
    (invalidate! ctx storage :fn-slot {:fn-id fn-id})
    {:ok true :id (str fn-id) :name (or nm (:name existing-fn))}))


(defn apply-update-record-type-rollback
  "Called by `:try`'s `:on-throw` when the body throws. Reads the
   journal atom + replays its entries in reverse: a recorded `:create`
   becomes a delete, a recorded `:delete` becomes a create. Each
   replay step is wrapped in its own try/swallow so one stuck reversal
   doesn't block the rest. Returns the partial Ring response carrying
   the original exception's message + ex-data."
  [journal exception ctx]
  (let [storage (request/require-storage ctx)]
    (doseq [entry (reverse (deref journal))]
      (try
        (case (:op entry)
          :create (sp/delete-entity storage (:entity-type entry) (:id entry))
          :delete (sp/create-entity storage (:entity-type entry) (:row entry))
          nil)
        (catch Exception e
          (log/warn e "Journalled-rollback step failed:" (:op entry)
                    (:entity-type entry) (or (:id entry) (:row entry))
                    "— manual cleanup may be required"))))
    (cond-> {:ok false :error (str (Throwable/.getMessage exception))}
      (instance? clojure.lang.ExceptionInfo exception)
      (assoc :data (ex-data exception)))))


;; === Form Parsing ===
;;
;; All parse-*-from-form impls are permissive — fields are only
;; assoc'd when the key is actually present in the form. That way
;; both create (full form) and update (partial form, e.g.
;; description-only) flow through the same code without partial
;; updates blanking the unsent fields. Empty strings are kept (so
;; a submitted-empty `description=` clears the field rather than
;; leaving the old value).

(defn ensure-rename-slot!
  "Phase 6b — keep UI rename atomically consistent with EDN parser
   output. When a binding write carries a non-blank `:rename-to=X`
   AND the binding's owner fn is composed (parent-ids non-empty),
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


;; === Action Handlers ===

(defn chain-has-process-effect?
  "Walks the parent-ids closure of `fn-id` looking for any ancestor that
   declares `:process` in its rich-types entry. Used by guard 6 —
   composed fn-defs whose own rich-type entry is missing (e.g. failed
   sync-time type-check) can still be service-eligible if an ancestor
   declares the effect.

   Also surfaced as the `:chain-has-process-effect?` base-fn in
   `web/crud/impls.clj` so the guard composes at the graph layer.

   BFS by frontier level: one batched `:fn {:id frontier}` query per
   level instead of per-node `read-entity`. Same shape as the
   inheritance walker in `crud.validation/flag-key-on-chain?`."
  [storage fn-id]
  (loop [frontier [fn-id]
         seen #{}]
    (if (empty? frontier)
      false
      (let [rows (sp/query-entities storage :fn {:id frontier})
            has-process? (some (fn [row]
                                 (let [eff (some-> (registry/rich-type-of-id (:id row))
                                                   :effects)]
                                   (contains? (or eff #{}) :process)))
                               rows)]
        (if has-process?
          true
          (let [seen' (into seen frontier)
                next-frontier (->> rows
                                   (mapcat :parent-ids)
                                   (remove nil?)
                                   (remove seen')
                                   distinct
                                   vec)]
            (recur next-frontier seen')))))))


(defn- humanise-create-exception
  "Render the user-facing form of a create-entity failure — Postgres
   unique-violation messages read like internal log lines; rewrite the
   common shape and fall back to any `:reason` carried in `ex-data` or
   the original message."
  [^Exception e entity-type entity-data type-str]
  (let [msg (or (Throwable/.getMessage e) "")
        nm (some-> entity-data :name)]
    (cond
      (and (re-find #"(?i)duplicate key" msg) nm)
      (str (name entity-type) " " (pr-str nm)
           " already exists here — pick a different name")
      (re-find #"(?i)duplicate key" msg)
      (str (name entity-type) " already exists with these fields")
      ;; Prefer a carried :reason (already user-facing). A generic SQL
      ;; message is NOT user-facing — raw JDBC text leaked FK names,
      ;; casts and internal ids into 400 bodies (audit-7). Classify it
      ;; via the storage error registry and return the category's safe
      ;; sentence; the raw message goes to the log ref.
      :else (or (some-> (ex-data e) :reason)
                (let [category (some-> (ex-data e) :type namespace)
                      ref (str (random-uuid))]
                  (log/warn e "create-entity storage error withheld from client"
                            {:ref ref :entity-type entity-type})
                  (case category
                    "validation-error" msg
                    "constraint-violation" msg
                    (str "Storage rejected the write (ref " ref
                         ") — see server log")))
                (str "Failed to create " type-str)))))


(defn- try-create-or-error
  "Run `sp/create-entity` with capability gating + humanised exception
   formatting. Returns `{:created <id>}` or `{:error <human-msg>}`.

   Capability gate: secret-shaped fn-defs (parent=[:vault-get]) can
   only be created via /api/secrets — the form-driven path never
   carries the in-memory `:_admin-secret-create` marker, so any
   attempt to sneak one through /api/entities/fn bounces with a 409.
   Closes the orthogonal hole to the delete-side guard at
   `process-delete-entity`.

   Projects to `:id` — leaving the whole record in `:created` makes
   the NOTIFY emitter stringify the map and the listener's
   `UUID/fromString` throws \"UUID string too large\"."
  [storage entity-type entity-data type-str]
  (let [cap-rej (when (= entity-type :fn)
                  (secret-leaf-capability-rej storage entity-data))]
    (cond
      cap-rej {:error (:reason cap-rej) :http-status 403}
      :else (try
              {:created (:id (sp/create-entity storage entity-type entity-data))}
              (catch Exception e
                (log/error e "create-entity failed for"
                           entity-type entity-data)
                ;; The error's HTTP status comes from the central map —
                ;; a name/position collision is a 409 CONFLICT, not a
                ;; malformed 400 (audit-7 error honesty).
                {:error (humanise-create-exception e entity-type entity-data type-str)
                 :http-status (web-errors/status-for-ex-data (ex-data e))})))))


(defn- forward-rename-slot!
  "Phase 6c — forward a form `:rename-to` to the dedicated renamed-view
   slot. A failure here is logged, not fatal — the binding is still
   useful without the rename slot."
  [storage form-data entity-data]
  (try (ensure-rename-slot! storage
                            (:fn-id entity-data)
                            (:slot-id entity-data)
                            (when-not (str/blank? (:rename-to form-data))
                              (str (:rename-to form-data))))
       (catch Exception e
         (log/error e "ensure-rename-slot! failed"))))


(defn- post-create-type-check-fn-id
  "Resolve the OWNING fn-id for a binding-shaped mutation so the
   post-create type-check sees the aggregate of every sibling binding."
  [storage type-str entity-data]
  (cond
    (= type-str "binding")
    (:fn-id entity-data)
    (= type-str "binding-list-item")
    (some-> (:binding-id entity-data)
            (#(sp/read-entity storage :binding %))
            :fn-id)))


(defn- verify-post-create-or-rollback!
  "Post-create whole-fn type-check for binding mutations. A binding can
   be individually valid yet break the OWNING fn-def's aggregate
   check; on failure delete the just-created row so DB state stays
   consistent. Returns the rejection map (with `:reason`) when the
   check rejects, nil when the row stays."
  [storage create-result type-str entity-data entity-type]
  (when (and (:created create-result)
             (#{"binding" "binding-list-item"} type-str))
    (when-let [fn-id (post-create-type-check-fn-id storage type-str entity-data)]
      (when-let [rej (tc/type-check-fn-after-mutation! storage fn-id)]
        ;; Roll back the just-created row when the post-write check
        ;; rejects. Silently nil'ing the rollback failure would mask
        ;; orphan rows surviving the rejection — log so dashboards see it.
        (try (sp/delete-entity storage entity-type (:created create-result))
             (catch Exception e
               (log/warn e "Rollback delete-entity failed after type-check rejection"
                         {:entity-type entity-type
                          :id (:created create-result)})))
        rej))))


(defn apply-create-core
  "§3.3 atomic core of the create-apply flow: capability gate +
   `sp/create-entity` (with unique-violation humanisation) + Phase-6c
   rename-slot side-effect + post-create whole-fn type-check +
   on-failure rollback. Returns a uniform shape:
     `{:created <id>}` on success
     `{:error <human-msg>}` on any failure path
   so the outer graph can dispatch on the shape and run invalidate /
   notify / response without re-deriving the rollback semantics.

   The §3.3 invariant — type-check + rollback see the SAME just-
   created row id — lives entirely inside this fn. Phase 4.4 is the
   place where a `:atom` + `:try` graph composition expresses the
   same invariant; here we keep the carve-out because the rollback
   is conditional on the post-check result, not on an exception."
  [{:keys [entity-type type-str form-data entity-data]} ctx]
  (let [storage (request/require-storage ctx)
        create-result (try-create-or-error storage entity-type entity-data type-str)]
    (if-let [post-rej (verify-post-create-or-rollback!
                        storage create-result type-str entity-data entity-type)]
      {:error (:reason post-rej)}
      (if (:created create-result)
        (do
          ;; Rename-slot side-effect runs ONLY after the post-create check
          ;; passes. Running it earlier meant a binding the aggregate check
          ;; then REJECTED (and rolled back) still left the renamed-view
          ;; slot + fn-slot behind — an orphan the fn permanently exposed
          ;; with no backing binding. The rename is a type-preserving name
          ;; alias, so the check's outcome is unaffected by ordering.
          (when (and (= type-str "binding")
                     (contains? form-data :rename-to))
            (forward-rename-slot! storage form-data entity-data))
          create-result)
        ;; Preserve the error's :http-status (409 collisions, 403
        ;; capability — the central web.errors mapping) alongside the
        ;; human message.
        (cond-> {:error (or (:error create-result)
                            (str "Failed to create " type-str))}
          (:http-status create-result)
          (assoc :http-status (:http-status create-result)))))))


(defn apply-update-core
  "§3.1 atomic core of the update-apply flow: `sp/update-entity` +
   Phase-6c rename-slot side-effect (binding writes only). Returns
   a uniform shape:
     `{:updated <id>}` on success
     `{:error <msg>}` on write failure
   The rename-slot failure is logged but never escalated — the
   binding row is still useful without the rename slot, matching the
   legacy behaviour."
  [{:keys [entity-type type-str id-uuid form-data entity-data]} ctx]
  (let [storage (request/require-storage ctx)
        error-msg (volatile! nil)
        updated (try (sp/update-entity storage entity-type id-uuid entity-data)
                     (catch Exception e
                       (log/error e "update-entity failed for"
                                  entity-type id-uuid entity-data)
                       ;; Surface a write-rejection reason when the storage
                       ;; layer provides one (e.g. the fn-name collision
                       ;; check) — a bare "Failed to update entity" hides
                       ;; exactly the message the user can act on.
                       (vreset! error-msg (some-> (ex-data e) :reason))
                       nil))]
    (when (and updated (= type-str "binding") id-uuid
               (contains? form-data :rename-to))
      (try
        (when-let [existing (sp/read-entity storage :binding id-uuid)]
          (ensure-rename-slot! storage
                               (:fn-id existing)
                               (:slot-id existing)
                               (when-not (str/blank? (:rename-to form-data))
                                 (str (:rename-to form-data)))))
        (catch Exception e
          (log/error e "ensure-rename-slot! failed"))))
    (if updated
      {:updated id-uuid}
      {:error (or @error-msg "Failed to update entity")})))


;; === Re-exports from sub-namespaces ==========================================
;;
;; The sequence-ops and tighten domains live in
;; `crud.entities.seq` / `crud.entities.tighten` to keep this file
;; focused on the generic CRUD + record/list-type + delete chains.
;; External callers (notably `web/crud/impls.clj` and
;; `crud/entities_test.clj`) reach them via the historical
;; `entities/<sym>` surface, so each public symbol is mirrored here
;; as a thin defn that delegates to the sub-namespace's impl.
;;
;; `requiring-resolve` not a top-of-file require: the sub-nses
;; themselves `(:require [graphden.crud.entities :as entities])` to
;; call `entities/invalidate!` + `entities/html-error-response`, so a
;; top-of-file require here would cycle. Resolve lazily — first
;; invocation pays the require cost, subsequent calls hit the Var
;; deref directly.

(defn find-sequence-binding
  [ctx fn-id]
  ((requiring-resolve 'graphden.crud.entities.seq/find-sequence-binding)
   ctx fn-id))


(defn resolve-sequence-payload
  [storage body]
  ((requiring-resolve 'graphden.crud.entities.seq/resolve-sequence-payload)
   storage body))


(defn find-seq-append-binding
  [parsed ctx]
  ((requiring-resolve 'graphden.crud.entities.seq/find-seq-append-binding)
   parsed ctx))


(defn apply-seq-append-core
  [parsed seq-binding ctx]
  ((requiring-resolve 'graphden.crud.entities.seq/apply-seq-append-core)
   parsed seq-binding ctx))


(defn load-seq-remove-item
  [parsed ctx]
  ((requiring-resolve 'graphden.crud.entities.seq/load-seq-remove-item)
   parsed ctx))


(defn load-seq-update-item
  [parsed ctx]
  ((requiring-resolve 'graphden.crud.entities.seq/load-seq-update-item)
   parsed ctx))


(defn apply-seq-update-core
  [parsed item ctx]
  ((requiring-resolve 'graphden.crud.entities.seq/apply-seq-update-core)
   parsed item ctx))


(defn commit-tighten!
  [storage binding-id b new-c effects-vec]
  ((requiring-resolve 'graphden.crud.entities.tighten/commit-tighten!)
   storage binding-id b new-c effects-vec))


(defn tighten-fn-type-impl!
  [storage binding-id delta]
  ((requiring-resolve 'graphden.crud.entities.tighten/tighten-fn-type-impl!)
   storage binding-id delta))


(defn tighten-effects-impl!
  [storage binding-id effects-vec]
  ((requiring-resolve 'graphden.crud.entities.tighten/tighten-effects-impl!)
   storage binding-id effects-vec))


(defn apply-tighten-core
  [parsed ctx]
  ((requiring-resolve 'graphden.crud.entities.tighten/apply-tighten-core)
   parsed ctx))
