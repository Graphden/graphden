(ns graphden.crud.entities.invalidation
  "Cache invalidation for graph writes — the DELTA SEED derivation and
   the two things a write has to tell: the local compiled registry
   (`invalidate!`) and the other pods (`notify-after-write!`).

   Split out of `crud.entities` because it is the one concern every
   write path in that tree consults and nothing here needs any of them
   back; the public names stay re-exported from `crud.entities` for the
   historical callers (`web/crud/impls.clj`, the tenancy addon)."
  (:require
    [clojure.tools.logging :as log]
    [graphden.crud.test-autorun :as test-autorun]
    [graphden.executor.context :as exec-ctx]
    [graphden.services.reconciler :as recon]
    [graphden.storage.postgres.graph-epoch :as epoch]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.types.diagnostics :as diag]
    [graphden.util.abort-shield :as shield]
    [graphden.versioning.branch-local :as branch-local]
    [graphden.versioning.storage.core :as vcore]))


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

    ;; A `:resource-override` row shadows a frontend asset BODY — data the
    ;; asset handlers re-read from storage on every serve, so no compiled
    ;; closure moves and no recompile is owed. The immutable-RESPONSE
    ;; cache stays correct on its own: it keys on the query string, and a
    ;; save rolls the effective `?v=` hash, so the next request is a fresh
    ;; cache key on every node (no cross-node flush to coordinate).
    :resource-override #{}

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
          (branch-local/invalidate! base))
        ;; A `:fn` write (rename / reparent / return-type / delete) can
        ;; stale a recorded type-check diagnostic for that fn on this
        ;; branch — drop the entry; the next post-mutation check
        ;; re-records it if the fn is still broken.
        (when-let [id (:id entity-data)]
          (diag/clear-fn! (vcore/current-branch-id storage) id)))
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
        ;; Third best-effort sibling: queue the affected PURE tests for
        ;; a debounced background re-run (Block 3.1 phase 2). Same
        ;; contract as the service-restart hook — a failure here never
        ;; fails the user's CRUD call.
        (when (seq seeds)
          (try
            (test-autorun/schedule-affected!
              ctx seeds (vcore/current-branch-id storage))
            (catch Exception e
              (log/warn e
                        "post-edit test auto-run hook failed"
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
