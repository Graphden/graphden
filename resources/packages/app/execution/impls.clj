(ns graphden.packages.app.execution.impls
  "Implementations for the app/execution package — POST/GET/cancel
   delegate to `graphden.crud.fn-execution`. The implicit `ctx`
   symbol is in scope via defbase; it carries the storage handle the
   stage functions read."
  (:require
    [graphden.crud.debug-capture :as debug-capture]
    [graphden.crud.fn-execution :as fn-exec]
    [graphden.crud.fn-execution.errors :as exec-errors]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.crud.fn-execution.stats :as exec-stats]
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.services.reconciler :as recon]
    [graphden.tenancy.context :as tc]
    [graphden.versioning.storage.core :as vs]))


(defn- stats-pool
  "Raw datasource for the usage-stat / error-log reads. Prefer the
   privileged `:pg-storage` (present on tenancy-assembled ctxs), else
   unwrap the branch `:storage` down to the PG record — the
   single-tenant executor ctx carries NO `:pg-storage`, which left
   every rollup surface (the 7d history strip, the Stats/Errors
   panels) reading zeros while the write-side bump landed rows
   (tutorial finding 2026-08-26)."
  [ctx]
  (or (:pool (:pg-storage ctx))
      (:pool (vs/unwrap (:storage ctx)))))


(defbase unresolved-failure-counts
  [days]
  ;; The failed-runs LENS's data: the same unresolved predicate as
  ;; `recent-failures`, tallied per fn — one read the editor caches.
  (cr/record-effect! :db)
  (exec-errors/unresolved-failure-counts ctx (stats-pool ctx) (tc/current-org) days))


(defbase resolve-fn
  "Resolve a `parsed` request shape (with `:fn-id` UUID or `:fn-name`
   text) to the full `:fn` row, in a single storage round-trip.
   Returns nil when neither identifier resolves.

   Single-library boundary over `lookup/resolve-fn`. The helper handles
   storage-shape ambiguity for `fn.name` (text-vs-keyword codec) — that
   defensive dual-codec retry is infrastructure, not user logic, so it
   stays inside the Clojure primitive. Admins who need a different
   fn-lookup strategy compose at the graph layer (e.g. by-namespace,
   by-alias) wrapping the canonical shape `{:fn-id ?  :fn-name ?}`."
  [parsed]
  (cr/record-effect! :db)
  (lookup/resolve-fn (request/require-storage ctx) parsed))


;; `:free-arg-slot-map` lives in `web/crud/impls.clj` so CRUD-write-
;; time guards (`:_create-service-free-args-rej`) can reference it.
;; This package's validation chain pulls it transitively via the
;; `app → web` package dependency.


;; --- POST /api/execute ---


;; --- validate-execute split into one rejection-builder
;; defbase per guard. Each `_..._err` returns the rejection map
;; (`{:ok false :status :rejected :error :error-data}`) or nil.
;; The graph predicate `:some? :_..._err` decides the `:cond`
;; branch, and the SAME `_..._err` is returned as the clause
;; result — call-cache dedupes the work because both reads share
;; the same `parsed` (and `ctx`).



(defbase _execute-apply
  ;; `fn-row` arrives from the validation stage's `:_execute-fn-row`
  ;; (result-cached per request) — the apply core no longer re-resolves
  ;; it. What stays inside is the §3.3 concurrency core (slot acquire →
  ;; future → deref-with-timeout → 4-way outcome + ownership release)
  ;; plus its perf-critical cached lookups (free-arg-slot-map-cached).
  [parsed fn-row]
  (cr/record-effect! :db)
  (fn-exec/apply-execute ctx parsed fn-row))


(defbase get-execution
  "Read a `:fn-execution` row by id, with all child arg + arg-item
   rows folded in. Returns nil when the id doesn't resolve.

   Single-library boundary over `fn-exec/get-execution` — the
   multi-row read + tree-shape reconstruction is the §3.3 invariant
   carve-out."
  [id]
  (cr/record-effect! :db)
  (when (some? id) (fn-exec/get-execution ctx id)))


(defbase cancel-execution!
  "Cancel an in-flight execution by id — sets `:cancel-requested?` on
   the persisted row AND `future-cancel`s the in-process handle.
   Returns `{:ok true :cancel-requested true}` or nil when the id
   doesn't resolve.

   Single-library boundary; the two side effects (DB write +
   future-cancel) are intrinsically coupled — splitting them would
   break the cancellation guarantee."
  [id]
  (cr/record-effect! :db)
  ;; `:state`, not `:process`: the future-cancel interrupts a thread the
  ;; PLATFORM started for this very execution — a mutation of the
  ;; in-process execution registry, not spawning or killing processes
  ;; (`:future` / `:http-server` are `:process`). `:state` is inside the
  ;; cloud's request-level gate, so a tenant can cancel its own run.
  (cr/record-effect! :state)
  (when (some? id) (fn-exec/cancel-execution! ctx id)))


;; --- get-execution + cancel-execution ---
;; Both handlers share the `:_exec-id-parsed` graph parser (URL +
;; UUID coerce in fns.edn) AND the dynamic 404 builder (same text
;; either way); each has its own apply (read vs cancel-mutation).


;; GET /api/execute/:id atoms



;; POST /api/execute/:id/cancel atoms



(defbase resolve-fn-version-id
  "Resolve a logical fn-id to its current-branch version-id. Returns
   nil when the fn has no version visible on the active branch (never
   created here AND not inherited). §3.1 single library call over
   `fn-execution.lookup/resolve-fn-version-id`."
  [fn-id]
  (cr/record-effect! :db)
  (lookup/resolve-fn-version-id ctx fn-id))


;; --- POST /api/services/reconcile ---
;;
;; Hot-reload trigger for the service reconciler. Phase 1 has no
;; periodic poll — admins call this endpoint after creating /
;; modifying / disabling :service rows through generic CRUD so the
;; in-process running atom catches up without a pod restart.

(defbase _reconcile-services-apply
  [_request]
  (cr/record-effect! :db)
  ;; Starting / stopping supervised service threads is `:process` (what
  ;; `:future` and `:http-server` record), not a file/classpath read.
  (cr/record-effect! :process)
  (recon/reconcile-once! ctx recon/running))


;; --- GET /api/services ---
;;
;; List every :service row merged with its in-process running state.
;; Used by the editor's "Only services" sidebar filter, the
;; "Make service" row-actions popover, and the per-fn service badge.

(defbase running-entry
  "Atomic library boundary — pull the per-service entry off the
   reconciler's `@running` atom by `:service-id`. Returns the raw
   entry map `{:stopper :started-at :start-attempts :start-failed-at}`
   or nil when nothing is registered. The downstream reshape into a
   JSON-safe shape lives in the `:enrich-running` graph fn-def
   (see fns.edn) so admins can add fields (e.g. `:thread-id`,
   `:port`) by composing on top — no Clojure edit."
  [service-id]
  (get @recon/running service-id))


;; --- list-services ---
;; Five named steps glued by a graph fn-def — pure data composition
;; so each stage is visible: load services + fn-name-index → enrich
;; each → maybe build legacy fallback → wrap as final response. Each
;; atom is a 1-3-line wrap over the helpers above.


(defbase recent-failures
  [days limit]
  ;; Branch-scoped UNRESOLVED failures (error text/data already write-side
  ;; redacted + scrubbed), newest first — see the four-part unresolved
  ;; predicate in the `crud.fn-execution.errors` ns docstring.
  (cr/record-effect! :db)
  (exec-errors/recent-unresolved-failures ctx (stats-pool ctx) (tc/current-org) days limit))


(defbase recent-executions
  [limit]
  ;; Branch-scoped run audit (this branch only, every status) — the
  ;; review dialog's "Verified on this branch" digest.
  (cr/record-effect! :db)
  (exec-errors/recent-executions ctx (stats-pool ctx) (tc/current-org) limit))


(defbase failure-ack
  [execution-id]
  ;; Explicit dismiss of one failure row (org-guarded UPDATE).
  (cr/record-effect! :db)
  (exec-errors/acknowledge! (stats-pool ctx) (tc/current-org)
                            (request/parse-uuid-or-clear execution-id)))


(defbase failure-ack-all
  [days]
  ;; Dismiss everything the current branch view lists.
  (cr/record-effect! :db)
  (exec-errors/acknowledge-all! ctx (stats-pool ctx) (tc/current-org) days))


(defbase fn-stats-raw
  [fn-id days]
  ;; Phase C1 rollup read — counts + durations only (never args/results), so
  ;; it is privacy-safe for any caller that can see the fn. Scoped to the
  ;; CURRENT org explicitly (tenant sees their own runs; public/single-tenant
  ;; sees the platform's). Nil-defaulting is boundary coercion (nil pool /
  ;; no rows → zeros); the DERIVED :avg-ms and the display envelope are
  ;; graph composition (`:usage-fn-stats` in fns.edn).
  (cr/record-effect! :db)
  (let [{:keys [runs failed cancelled duration-ms-sum]
         :or {runs 0 failed 0 cancelled 0 duration-ms-sum 0}}
        (exec-stats/fn-stats (stats-pool ctx) (tc/current-org) fn-id days)]
    {:runs runs
     :failed failed
     :cancelled cancelled
     :duration-ms-sum duration-ms-sum}))


(defbase usage-org-summary
  [days]
  ;; Phase C — org-scoped headline rollup: total runs / failed / avg-ms over
  ;; the trailing window. Counts + durations ONLY (no args/results/errors),
  ;; so it is privacy-safe and always scoped to the CURRENT org (tenant sees
  ;; their own workspace; public/single-tenant sees the platform). nil pool → zeros.
  (cr/record-effect! :db)
  (exec-stats/org-summary (stats-pool ctx) (tc/current-org) days))


(defbase usage-org-daily
  [days]
  ;; Per-day series for the org over the window — the Stats panel's trend
  ;; bars. Same org-scoping + privacy contract as `usage-org-summary`.
  (cr/record-effect! :db)
  (exec-stats/org-daily (stats-pool ctx) (tc/current-org) days))


(defbase usage-org-fn-stats
  [days limit]
  ;; Busiest fns for the org over the window, fn NAME joined for display.
  ;; Same org-scoping + privacy contract; a since-deleted fn shows its id.
  (cr/record-effect! :db)
  (exec-stats/org-fn-stats-named (stats-pool ctx) (tc/current-org) days limit))


(defbase usage-all-org-stats
  [days limit]
  ;; Cross-org rollup — the platform context (no tenant org bound, or the
  ;; platform org itself) OR a holder of the `:view-all-stats` platform right
  ;; (a delegate; platform-admin implies it via the umbrella) sees every org's
  ;; counts; ANY other tenant context gets [] so the Stats panel's by-org
  ;; section simply never renders for them. The guard lives HERE, impl-side, so
  ;; no graph composition can reach cross-org data from a tenant ctx.
  (cr/record-effect! :db)
  (if (or (tc/current-platform-tier?) (tc/current-has-platform-cap? :view-all-stats))
    (exec-stats/org-all-stats (stats-pool ctx) days limit)
    []))


;; --- /api/debug/catch — the one-shot «catch next request» trap ---
;; Runtime-only state in `crud.debug-capture` (the `*traced-fn-ids*`
;; doctrine); each base-fn is a single library call, org-scoping comes
;; from the request scope inside the ns.


(defbase execute-trace-rows
  "Display payload for one execution's stored `:path-trace` — the
   depth-first call tree the `/partials/execute-trace` panel renders.
   Single call over `fn-exec/trace-display-rows` (READ-time
   re-redaction + tree reassembly + name join are one cohesive
   read-shaping algorithm — the §3.3 invariant carve-out)."
  [id]
  (cr/record-effect! :db)
  (fn-exec/trace-display-rows ctx id))


(defbase debug-catch-arm!
  "Arm (or re-arm) the current org's one-shot request trap on
   `branch-id` — the next matching HTTP request through the branch
   router runs path-traced and persists a `:fn-execution` row. Single
   library call over `debug-capture/arm!`."
  [branch-id path-prefix capture-values? ttl-ms]
  (cr/record-effect! :state)
  (debug-capture/arm! branch-id {:path-prefix path-prefix
                                 :capture-values? capture-values?
                                 :ttl-ms ttl-ms}))


(defbase debug-catch-disarm!
  "Remove the current org's trap on `branch-id`. `{:disarmed bool}` —
   false when nothing was armed. Single library call."
  [branch-id _request]
  (cr/record-effect! :state)
  {:disarmed (debug-capture/disarm! branch-id)})


(defbase debug-catch-status
  "The current org's live trap on `branch-id` (`{:armed bool
   :trap …|null :last-captured-execution-id uuid|null}`). Pure
   runtime-state read, like `:running-entry`. `:request` pins the
   request scope so the answer is never call-cached across requests."
  [branch-id _request]
  (let [t (debug-capture/trap-status branch-id)]
    {:armed (some? t)
     :trap t
     :last-captured-execution-id (debug-capture/last-captured-execution-id branch-id)}))


(def impls
  {:unresolved-failure-counts unresolved-failure-counts
   :resolve-fn                 resolve-fn
   :execute-trace-rows         execute-trace-rows
   :debug-catch-arm!           debug-catch-arm!
   :debug-catch-disarm!        debug-catch-disarm!
   :debug-catch-status         debug-catch-status
   :_execute-apply             _execute-apply
   :get-execution              get-execution
   :cancel-execution!          cancel-execution!
   :resolve-fn-version-id      resolve-fn-version-id
   :_reconcile-services-apply  _reconcile-services-apply
   :running-entry              running-entry
   :fn-stats-raw fn-stats-raw
   :usage-org-summary usage-org-summary
   :usage-org-daily usage-org-daily
   :usage-org-fn-stats usage-org-fn-stats
   :usage-all-org-stats usage-all-org-stats
   :recent-failures recent-failures
   :recent-executions          recent-executions
   :failure-ack failure-ack
   :failure-ack-all failure-ack-all})
