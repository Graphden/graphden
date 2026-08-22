(ns graphden.executor.context
  "Execution context for the function executor."
  (:require
    [graphden.crud.fn-execution.free-arg-cache :as free-arg-cache]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.registry :as registry]
    [graphden.storage.protocol.core :as sp]
    [graphden.util.counters :as counters]))


;; === ExecutionContext Record ===

(defrecord ExecutionContext
  [storage          ; Storage instance implementing ExecutionGraph.
   base-fns         ; {fn-name-keyword → impl-fn} — read by compile.
   clock            ; Zero-arg fn returning current time in ms (testability).
   compiled-registry ; Atom: {fn-id → compiled-closure} or nil. Populated
   ;; by the compile system at startup; `execute` reads from it on the hot
   ;; path.
   graph-cache      ; Atom holding `{:fns [...] :slots […] …}` loaded from
   ;; storage. Populated lazily by read-heavy consumers (e.g. the layout
   ;; API). Invalidated by CRUD mutations via `invalidate-graph-cache!`.
   ;; Nil before first load.
   compile-deps])   ; Atom: `{fn-id → #{depender-fn-ids}}` reverse-dependency
;; index. Built alongside `:compiled-registry` by `compile-runtime/rebuild!`,
;; consumed by the delta-invalidation path so a single-fn mutation doesn't
;; force re-compilation of the whole registry. Nil when registry is cold.
;;
;; Extra (off-record) atom assoc'd by `create-context`:
;;
;; - `:invalidation-lock` — plain `Object` instance. Held by
;;   `invalidate-graph-cache!` and `compile-runtime/rebuild!` to
;;   serialize the read-graph → compute → prime-multi-atom sequence,
;;   so two concurrent writers can't interleave a stale storage
;;   snapshot's prime over a newer one. The `swap!` on
;;   `:compiled-registry` is still defensive for direct delta-
;;   recompile callers that bypass invalidate-graph-cache!.


(defn- splice-graph-cache!
  "Update the cached graph IN PLACE for the fns that just changed, instead of
   throwing the whole thing away.

   The compiled registry has had delta invalidation for a while; `:graph-cache`
   never did — every CRUD write nil'd it, and the next reader reloaded the whole
   graph (fn + slot + fn-slot + binding + list-item, thousands of rows) out of
   Postgres. That is fine when writes are rare. Under a write STREAM — which is
   exactly what the e2e suite is — the cache is never warm, so every read pays a
   full reload: /api/types/candidates went from 0.147 s to 1.29 s under a
   60-write loop, and to 45 s under the real suite. Both of the suite's standing
   flakes are 30-second waits on exactly such a read.

   Correctness rests on two properties of the model:

   - `changed-fn-ids` names every fn whose OWN rows moved; a binding write
     carries its owning fn-id. So re-reading those fns' fn-slots / bindings /
     list-items is sufficient — nothing else can have changed.
   - a `slot` is an immutable global identity (CLAUDE.md: atomic
     `(name, type-fn-id)`, immutable post-create) and is SHARED across fns. So
     slots may only be ADDED here, never removed: another fn may still point at
     one this fn just dropped.

   A fn that was deleted simply does not come back from storage, and its rows
   are filtered out. On a cold cache this is a no-op — the next read loads
   everything, as before."
  [ctx changed-fn-ids]
  (let [cache (:graph-cache ctx)
        cached (some-> cache deref)
        ;; PRIVILEGED handle, like the cold load in
        ;; `types-api/cached-or-load-graph` — the cache holds the FULL
        ;; org-agnostic graph and is sliced per read. `(:storage ctx)` is
        ;; ORG-SCOPED under tenancy, and this splice DELETES the changed ids
        ;; before re-adding what the read returned: a ctx whose org cannot see
        ;; a fn therefore erased it from the shared cache. That happens on
        ;; every write, because `invalidate-affected-ctxs!` sweeps the sibling
        ;; branch contexts too — each splicing through its own scope. Measured
        ;; on a tenancy stack: write a binding, and `?scope=search` for the
        ;; OWNING fn answered 0 hits for ~1-2s (until some full reload restored
        ;; it), which is what makes the editor toast “Function not found: <the
        ;; fn you just edited>”.
        storage (or (:compile-storage ctx) (:storage ctx))]
    (when (and cached storage (seq changed-fn-ids))
      (let [ids (set changed-fn-ids)
            of-changed? (fn [row] (contains? ids (:fn-id row)))
            ;; Read fns the SAME way the canonical full load does — through
            ;; `query-entities` (as the fn-slot / binding / list-item reads below
            ;; already do) — NOT `read-entities`.
            ;;
            ;; The two resolve differently on a versioned store, and the gap is a
            ;; correctness one. `read-entities` runs `resolve-entities-batch`,
            ;; which returns an entity whenever its IDENTITY row exists — and a
            ;; `:branch-local?` fn's identity is visible on every branch, while its
            ;; version data is not (docs/VERSIONING.md § branch-local). So after a
            ;; merge, splicing a branch-local fn by id put it into the TARGET
            ;; branch's cache, where it must not resolve. `query-entities` runs
            ;; `resolve-all-entities`, which returns only entities with a version
            ;; actually visible on this branch — so the branch-local fn is dropped,
            ;; and a deleted fn still "doesn't come back", which is the removal
            ;; this splice already relies on. The `{:id #{…}}` where filters after
            ;; resolution, so it is one batch resolve for all changed ids, not one
            ;; per id.
            ;;
            ;; It was invisible until delta-recompile stopped re-priming the cache
            ;; from a fresh `read-graph` (which uses `query-entities` and silently
            ;; corrected the leak within the same call). `smoke-pass-test [7]`
            ;; pins it end to end.
            fresh-fns (vec (sp/query-entities storage :fn {:id (vec ids)}))
            ;; One batched query per table, not one per changed fn: on a
            ;; VersionedStorage each query-entities call pays a full
            ;; version-table resolve, so the per-id mapcat cost K full scans
            ;; per delta write. The where filter treats a vector as IN, and
            ;; these caches are keyed by maps downstream — result order is
            ;; not load-bearing.
            fresh-fn-slots (vec (sp/query-entities storage :fn-slot {:fn-id (vec ids)}))
            fresh-bindings (vec (sp/query-entities storage :binding {:fn-id (vec ids)}))
            fresh-binding-ids (into #{} (map :id) fresh-bindings)
            fresh-items (if (seq fresh-binding-ids)
                          (vec (sp/query-entities storage :binding-list-item
                                                  {:binding-id (vec fresh-binding-ids)}))
                          [])
            ;; Items keyed off bindings the changed fns USED to own must go too,
            ;; even when the binding itself is gone now.
            stale-binding-ids (into #{}
                                    (comp (filter of-changed?) (map :id))
                                    (:bindings cached))
            ;; Slots: add-only. Immutable identities, shared across fns.
            known-slots (into #{} (map :id) (:slots cached))
            missing-slot-ids (into [] (comp (map :slot-id) (remove known-slots) (distinct))
                                   fresh-fn-slots)
            extra-slots (if (seq missing-slot-ids)
                          (vec (vals (sp/read-entities storage :slot missing-slot-ids)))
                          [])]
        (reset! cache
                {:fns (into (filterv #(not (contains? ids (:id %))) (:fns cached))
                            fresh-fns)
                 :slots (into (:slots cached) extra-slots)
                 :fn-slots (into (filterv (complement of-changed?) (:fn-slots cached))
                                 fresh-fn-slots)
                 :bindings (into (filterv (complement of-changed?) (:bindings cached))
                                 fresh-bindings)
                 :list-items (into (filterv #(not (contains? stale-binding-ids
                                                             (:binding-id %)))
                                            (:list-items cached))
                                   fresh-items)})
        true))))


(defn invalidate-graph-cache!
  "Drop derived caches on `ctx` and refresh type-aliases from storage.

   Two arities:

   - `[ctx]` — full invalidation. Used when the caller doesn't know
     which fns changed (mass updates, schema migrations). Clears
     `:graph-cache` AND `:compiled-registry`; the next `execute`
     triggers a full `rebuild!` via the lazy fallback in
     `compile-runtime/registry`.

   - `[ctx changed-fn-ids]` — delta invalidation. Hands a set of
     fn-ids that just mutated; the `:compile-deps` reverse-index
     determines the blast radius (the changed fns + everything that
     transitively depends on them) and ONLY those entries get
     recompiled. The rest of the registry stays warm. Falls back to a
     full rebuild when the reverse-index isn't yet populated (cold
     start).

     `changed-fn-ids` distinguishes three answers, and the last two are
     NOT the same thing:

     - a non-empty set — delta-recompile these and their dependents;
     - `#{}` — the caller knows the write reached no compiled closure
       (a `:slot` nothing exposes yet, a `:ns` no closure can reach).
       Do nothing at all;
     - `nil` — unknown shape. Full clear.

     `#{}` used to fall into the full clear, and the full clear drops
     the compiled registry — so the next request rebuilt the whole
     graph. At 4137 fns that cost 49.8 s, against 14 ms either side.

   Both paths re-register type-aliases from storage so newly-created
   types are resolvable to the type-checker without a server
   restart."
  ([ctx] (invalidate-graph-cache! ctx nil))
  ([ctx changed-fn-ids]
   ;; Serialize the whole invalidation body on the per-context lock —
   ;; both branches (full clear / delta recompile) write to a cluster
   ;; of related atoms in sequence, and two concurrent callers that
   ;; interleaved their writes could leave the caches reflecting an
   ;; older storage snapshot than what's already committed. Test ctx
   ;; without the lock falls through with no synchronization (no
   ;; concurrency to worry about anyway).
   (let [body (fn []
                ;; Monotonic token for stale-while-revalidate: every real
                ;; invalidation (delta or full) bumps it, so a background full
                ;; rebuild can tell whether a newer write landed mid-compile and
                ;; skip its swap rather than clobber a delta patch. (The `#{}`
                ;; no-op skip below never runs `body`, so it doesn't bump.)
                (when-let [ic (:invalidation-count ctx)] (swap! ic inc))
                ;; Splice when we know what changed; drop wholesale only when we
                ;; don't. The full drop is what made every write cost the next
                ;; reader a complete graph reload from Postgres.
                (when-not (splice-graph-cache! ctx changed-fn-ids)
                  (when-let [c (:graph-cache ctx)]
                    (reset! c nil)))
                ;; The per-execute free-arg-slot-map memo is a pure
                ;; function of graph state; any mutation may change a
                ;; fn's free-arg surface, so drop it here. Full drop
                ;; (not delta) — the recompute lands only on the first
                ;; post-edit execute of each fn.
                (free-arg-cache/clear!)
                (cond
                  ;; Storage isn't wired (stripped test ctx) — nothing
                  ;; to refresh.
                  (or (nil? (:storage ctx))
                      (nil? (:compiled-registry ctx)))
                  (when-let [c (:compiled-registry ctx)]
                    (reset! c nil))

                  ;; Delta path — caller named the changed fns AND we
                  ;; have a reverse-deps index from a prior compile.
                  (and (seq changed-fn-ids)
                       (some-> (:compile-deps ctx) deref some?)
                       (some-> (:compiled-registry ctx) deref some?))
                  (cr/delta-recompile! ctx changed-fn-ids)

                  :else
                  (do
                    ;; The write said nothing about what it changed, so the whole
                    ;; registry is now stale. The rebuild is NOT counted here — it
                    ;; lands later, in the background, driven by the next request
                    ;; through `compile-runtime/registry`. Two full-clears before
                    ;; one read cost one rebuild, so these two counters answer
                    ;; different questions and must not be compared to each other.
                    (counters/count! :registry/invalidate-full)
                    (when-let [fc (:full-clear-count ctx)] (swap! fc inc))
                    (let [holder (:compiled-registry ctx)
                          stale? (:registry-stale? ctx)]
                      (if (and holder stale? (some? @holder))
                        ;; WARM: keep serving the stale registry, flag it for
                        ;; revalidation. The gate rebuilds it in the background
                        ;; (`rebuild-optimistic!`) — no request ever blocks behind
                        ;; a ~50s cold compile on this ctx. See the ctx-atom
                        ;; comment in `make-execution-context`.
                        (reset! stale? true)
                        ;; COLD (never compiled, e.g. boot / cold branch) or a
                        ;; stripped test ctx without the machinery — nothing to
                        ;; serve stale, so clear and let the gate compile once.
                        (when holder (reset! holder nil))))
                    (cr/refresh-type-registries-from-storage! ctx))))]
     ;; An EMPTY (but non-nil) seed set is an ANSWER, not a shrug: the caller
     ;; knows this write cannot have changed any compiled closure — a `:slot`
     ;; nothing exposes yet, a `:ns` no closure can reach (see
     ;; `crud.entities/affected-fn-ids`). `nil` still means "I don't know", and
     ;; still full-clears.
     ;;
     ;; The distinction is worth its own branch because the full clear drops the
     ;; compiled registry, and then the NEXT request rebuilds the entire graph.
     ;; Measured at 4137 fns: create one slot, and the next request took 49.8 s;
     ;; one namespace, 49.6 s. Reads either side of it, 14 ms. Both of those
     ;; writes used to land here, and every type-editing test creates slots.
     (if (and (some? changed-fn-ids) (empty? changed-fn-ids))
       ;; This counter IS the regression test for 0b74f1dc. The branch it guards
       ;; is invisible from outside — the write succeeds either way, and the only
       ;; evidence it went wrong is 49.8 s appearing on some LATER request, on a
       ;; box that will be blamed for being busy. A count of skips that drops to
       ;; zero says the `#{}` answer stopped being honoured, immediately and on
       ;; any hardware.
       (counters/count! :registry/invalidate-skipped)
       (cr/call-with-invalidation-lock ctx body)))))


(defn refresh-slot-in-graph-cache!
  "Keep the graph cache's `:slots` honest across a `:slot` write, without touching
   the compiled registry.

   A slot write cannot change any compiled closure — an fn reaches its slots
   through `fn-slot` rows, which are written separately and seed the delta path
   themselves. But the cache DOES hold slot rows, and a reader of the whole graph
   would otherwise not see a slot until something attached it to an fn. So splice
   the one row: re-read it, and either replace it or (if it is gone) drop it.

   O(slots) on a vector, against a full clear that cost the next request a rebuild
   of every fn in the graph."
  [ctx slot-id]
  (when-let [c (:graph-cache ctx)]
    (when-let [cached @c]
      (when-let [storage (:storage ctx)]
        (let [fresh (sp/read-entity storage :slot slot-id)
              others (filterv #(not= slot-id (:id %)) (:slots cached))]
          (reset! c (assoc cached :slots (cond-> others fresh (conj fresh)))))))))


(defn cached-graph
  "Return the cached `{:fns :slots :fn-slots :bindings :list-items}`
   snapshot, or nil on miss. Read-path consumers (layout, /api/graph/
   entities, /api/types) call this first, then fall back to their
   preferred loader on miss and call `fill-graph-cache!` afterwards."
  [ctx]
  (some-> (:graph-cache ctx) deref))


(defn fill-graph-cache!
  "Populate the graph-cache atom with `data`. No-op when `:graph-cache`
   isn't present (test contexts that skip the cache atom)."
  [ctx data]
  (when-let [c (:graph-cache ctx)]
    (reset! c data)))


;; === Context Validation ===

(defn- validate-context-options!
  "Validates context creation options. Throws on invalid options."
  [storage]
  (cond
    (not storage)
    (throw (ex-info "Storage is required"
                    {:type :execution-error/invalid-context}))

    (not (satisfies? sp/ExecutionGraph storage))
    (throw (ex-info "storage must implement ExecutionGraph protocol"
                    {:type :execution-error/invalid-context
                     :received-type (type storage)}))))


;; === Context Creation ===

(defn create-context
  "Creates an execution context.

   Options:
   - :storage   Storage instance (required).
   - :base-fns  Map of fn-name → impl-fn (optional; defaults to the
                global registry snapshot).
   - :clock     Zero-arg fn returning current time in ms (default
                `System/currentTimeMillis`). Inject in tests for
                deterministic time.
   - :allowed-effects  Optional set of effect categories this context
                permits (e.g. `#{:db :time}`). When set, `record-effect!`
                throws `:execution/forbidden-effect` for any effect
                outside it — the cloud sandbox boundary
                (docs/TENANCY_SEAM.md § Effect gate). `nil`/absent (the
                default) = unrestricted (self-hosted / mixed).
   - :auth-provider  Optional `graphden.auth.provider/AuthProvider` — the
                authentication seam (docs/TENANCY_SEAM.md § Auth seam).
                Read by the
                `:authenticate-request` base-fn. Absent → that base-fn
                fails closed (`{:authenticated? false}`).
   - :request-scope  Optional request-scope seam (docs/TENANCY_SEAM.md
                § Context) — a fn
                `(fn [ctx request thunk] …)` the branch-router's `dispatch`
                wraps each handler call with. The tenancy addon uses it to
                authenticate + bind `*current-org*`. Absent → `dispatch`
                calls the handler directly (single-tenant).
   - :execute-guard  Optional per-namespace execute guard (§4.2) — a fn
                `(fn [ctx fn-id] …)` `compile-runtime/execute` consults once
                per top-level execute; throws `:authz/forbidden` on a denied
                tenant execute. Absent → no execute authorization.
   - :executor-orgs  Optional membership predicate over org-ids — usually a
                set, or a fn for a hash-sharded fleet that doesn't enumerate
                its tenants. The compiled registry then holds only those
                orgs' fns plus the un-owned platform rows, instead of every
                tenant's. nil (the default, and the only value single-tenant
                ever uses) compiles the whole graph. Admit the public org
                explicitly — core doesn't know its name.
   - :byo-executor?  When true, this pod is a customer's OWN executor — it may
                serve the `:byo` orgs in its shard. A HOSTED pod (default,
                false) refuses any `:byo` org with a 421. See
                `tenancy.context/byo-org?`."
  [{:keys [storage base-fns clock allowed-effects auth-provider request-scope
           execute-guard app-router verify-domain user-ops
           my-tokens executor-orgs byo-executor? fleet-forward fleet-command]}]
  (validate-context-options! storage)
  (-> (->ExecutionContext storage
                          (or base-fns (registry/get-default-registry))
                          (or clock #(System/currentTimeMillis))
                          (atom nil)
                          (atom nil)
                          (atom nil))
      ;; Per-context lock for serializing the read-graph → compute →
      ;; prime-multi-atom sequence in `invalidate-graph-cache!` /
      ;; `compile-runtime/rebuild!`. Without it, concurrent CRUD
      ;; requests can prime `:graph-cache` / `:compile-deps` from
      ;; out-of-order storage snapshots and leave the caches
      ;; reflecting an older view than what storage already holds.
      ;; A ReentrantLock (not `locking`/`synchronized`): the body under
      ;; it recompiles for seconds, and a virtual thread blocked on a
      ;; monitor pins its carrier (JDK 21) — see
      ;; `compile-runtime/call-with-invalidation-lock`.
      (assoc :invalidation-lock (java.util.concurrent.locks.ReentrantLock.))
      ;; Fleet placement bookkeeping (docs/FLEET_RFC.md §6.2): the set of cell
      ;; ROOTS this executor has loaded via `compile-runtime/load-cell!`. Lets
      ;; `evict-cell!` reference-count shared fns. Empty + unused on a
      ;; non-fleet ctx (which loads its whole shard via `rebuild!`).
      (assoc :loaded-roots (atom #{}))
      ;; Availability: stale-while-revalidate for the request-path full clear.
      ;; A full clear (nil-seed write, migration) used to nil `:compiled-registry`,
      ;; so the next request ran the ~50s cold `rebuild!` UNDER the invalidation
      ;; lock and every concurrent request on this ctx blocked behind it (the
      ;; pod-wide hang). Instead we KEEP the (now stale) registry, flip
      ;; `:registry-stale?`, and let the gate serve stale while a background
      ;; `rebuild-optimistic!` revalidates — the same pattern the epoch heal uses.
      ;;   `:invalidation-count` — monotonic, bumped on EVERY invalidation
      ;;     (delta + full). It is the `unchanged?` token: a background full
      ;;     rebuild swaps only if no newer invalidation landed mid-compile, so
      ;;     it can't clobber a delta that patched the live registry meanwhile.
      ;;   `:registry-rebuild-inflight` — CAS guard so one background revalidate
      ;;     runs per ctx, however many requests observe the stale flag.
      (assoc :invalidation-count (atom 0)
             :full-clear-count (atom 0)
             :registry-stale? (atom false)
             :registry-rebuild-inflight (atom false))
      ;; Effect sandbox — nil = unrestricted. Read on the hot path by
      ;; `compile-runtime/execute`, which binds `*allowed-effects*` for
      ;; the execution so `record-effect!` can gate.
      (cond-> allowed-effects (assoc :allowed-effects (set allowed-effects)))
      ;; Auth seam (§3.0) — read by the `:authenticate-request` base-fn.
      (cond-> auth-provider (assoc :auth-provider auth-provider))
      ;; Request-scope seam (§3.0 B4) — wrapped around each handler call by
      ;; the branch-router's `dispatch`.
      (cond-> request-scope (assoc :request-scope request-scope))
      ;; Per-namespace execute guard (§4.2) — consulted by `execute`.
      (cond-> execute-guard (assoc :execute-guard execute-guard))
      ;; App-router seam (§3.4 FaaS) — consulted by the branch-router's
      ;; `dispatch` BEFORE the editor/API flow: a request to a tenant's
      ;; subdomain is served by that org's handler fn (org-scoped + effect-
      ;; gated), never the editor.
      (cond-> app-router (assoc :app-router app-router))
      ;; Self-serve DNS-verify seam (§3.4 #2) — `(fn [ctx hostname] …)` the
      ;; `:invoke-verify-domain` base-fn calls so a tenant can prove ownership
      ;; of its own custom domain (DNS-TXT) and flip it verified. Addon-only.
      (cond-> verify-domain (assoc :verify-domain verify-domain))
      ;; User-model seam — `{:create-user … :login …}` the
      ;; `:invoke-create-user` / `:invoke-login` base-fns call. Login mints a
      ;; session `:token`; the storage-token-provider resolves it. Addon-only.
      (cond-> user-ops (assoc :user-ops user-ops))
      ;; Self-serve API-token seam — `{:mint … :list … :revoke …}` the
      ;; tenancy-admin `:invoke-{mint,list,revoke}-my-token` base-fns call so
      ;; a tenant can manage long-lived bearers for its OWN org (MCP / API
      ;; clients) without the operator. Addon-only.
      (cond-> my-tokens (assoc :my-tokens my-tokens))
      ;; Executor shard — which orgs' fns this pod compiles. Read by
      ;; `compile-runtime/read-graph`. nil ⇒ the whole graph. A collection
      ;; becomes a set (which is itself the membership predicate); a fn is
      ;; taken as the predicate directly.
      (cond-> executor-orgs
        (assoc :executor-orgs (if (fn? executor-orgs)
                                executor-orgs
                                (set executor-orgs))))
      ;; Pod role — a BYO executor may serve the `:byo` orgs in its shard;
      ;; a hosted pod (default) 421s them.
      (cond-> byo-executor? (assoc :byo-executor? true))
      ;; Fleet forward-hop seam (docs/FLEET_RFC.md §6.1, T2.6): a
      ;; `(fn [request org entry-fn-id] → response-or-nil)` the app-router
      ;; consults BEFORE 421'ing — forwards to the executor that holds the cell
      ;; (per `:placement`). Absent (single-tenant / no fleet identity) → 421 as
      ;; before.
      (cond-> fleet-forward (assoc :fleet-forward fleet-forward))
      ;; Fleet control-plane command seam (docs/FLEET_RFC.md §6.3) — the
      ;; `branch-router/dispatch` consults it for `POST /internal/fleet/cell/…`.
      ;; Absent (single-tenant / no fleet identity) → no internal endpoint.
      (cond-> fleet-command (assoc :fleet-command fleet-command))))


(defn current-time-ms
  "Returns current time in milliseconds using the context's clock.
   This allows for deterministic testing of timeout behavior."
  [context]
  ((:clock context)))
