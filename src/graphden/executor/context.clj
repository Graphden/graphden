(ns graphden.executor.context
  "Execution context for the function executor."
  (:require
    [graphden.crud.fn-execution.free-arg-cache :as free-arg-cache]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.registry :as registry]
    [graphden.storage.protocol.core :as sp]))


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
     recompiled. The rest of the registry stays warm. `:graph-cache`
     is still cleared because callers expect a fresh read on the
     next access. Falls back to a full rebuild when the reverse-
     index isn't yet populated (cold start) or when `changed-fn-ids`
     is empty.

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
                (when-let [c (:graph-cache ctx)]
                  (reset! c nil))
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
                    (reset! (:compiled-registry ctx) nil)
                    (cr/refresh-type-registries-from-storage! ctx))))]
     (cr/call-with-invalidation-lock ctx body))))


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
                outside it — the cloud sandbox boundary (PLATFORM_PLAN
                §5). `nil`/absent (the default) = unrestricted
                (self-hosted / mixed).
   - :auth-provider  Optional `graphden.auth.provider/AuthProvider` — the
                authentication seam (§3.0). Read by the
                `:authenticate-request` base-fn. Absent → that base-fn
                fails closed (`{:authenticated? false}`).
   - :request-scope  Optional request-scope seam (§3.0 B4) — a fn
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
           execute-guard app-router set-org-handler verify-domain user-ops
           executor-orgs byo-executor?]}]
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
      ;; Self-serve deploy seam (§3.4 4b) — `(fn [ctx fn-id] …)` the
      ;; `:invoke-set-org-handler` base-fn calls so a tenant can point its
      ;; own org at its own handler fn. Addon-only.
      (cond-> set-org-handler (assoc :set-org-handler set-org-handler))
      ;; Self-serve DNS-verify seam (§3.4 #2) — `(fn [ctx hostname] …)` the
      ;; `:invoke-verify-domain` base-fn calls so a tenant can prove ownership
      ;; of its own custom domain (DNS-TXT) and flip it verified. Addon-only.
      (cond-> verify-domain (assoc :verify-domain verify-domain))
      ;; User-model seam (§4.1) — `{:create-user … :login …}` the
      ;; `:invoke-create-user` / `:invoke-login` base-fns call. Login mints a
      ;; session `:token`; the storage-token-provider resolves it. Addon-only.
      (cond-> user-ops (assoc :user-ops user-ops))
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
      (cond-> byo-executor? (assoc :byo-executor? true))))


(defn current-time-ms
  "Returns current time in milliseconds using the context's clock.
   This allows for deterministic testing of timeout behavior."
  [context]
  ((:clock context)))
