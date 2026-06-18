(ns graphden.packages.app.execution.impls
  "Implementations for the app/execution package — POST/GET/cancel
   delegate to `graphden.crud.fn-execution`. The implicit `ctx`
   symbol is in scope via defbase; it carries the storage handle the
   stage functions read."
  (:require
    [graphden.crud.fn-execution :as fn-exec]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.services.reconciler :as recon]))


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

;; `:_execute-parsed` is now a graph fn-def — see fns.edn. Graph-
;; composed over `:parse-json-body` + `:get` + per-field transforms
;; (`:parse-uuid` for id, nil-safe `:to-str` for name, `:coalesce`
;; for args + timeout-ms defaults, `:equal? true` for `:persist?`).


;; --- C23 atoms: validate-execute split into one rejection-builder
;; defbase per guard. Each `_..._err` returns the rejection map
;; (`{:ok false :status :rejected :error :error-data}`) or nil.
;; The graph predicate `:some? :_..._err` decides the `:cond`
;; branch, and the SAME `_..._err` is returned as the clause
;; result — call-cache dedupes the work because both reads share
;; the same `parsed` (and `ctx`).

;; `:_execute-no-fn-err` is now a graph fn-def — see fns.edn. `:if`
;; over `:and :nil? :nil?` + `:const` rejection envelope.


;; `:_execute-fn-not-found-err` is now a graph fn-def — see fns.edn.
;; Graph-composed over `:resolve-fn` + `:get :id` + `:and :has-anchor?
;; :nil?` + dynamic `:str` message + `:zipmap` envelope.


;; `:_execute-timeout-bad-err` is now a graph fn-def — see fns.edn.
;; `:if` over `:or :lt :gt` + `:zipmap` rejection envelope citing the
;; bad `:timeout-ms`.


;; `:_execute-args-too-large-err` is now a graph fn-def — see fns.edn.
;; Pure composition over `:to-json-string` + `:count` + `:gt` + `:zipmap`,
;; no new base-fns needed.


;; `:_execute-running-as-svc-err` is now a graph fn-def — see fns.edn.
;; Graph-composed over `:resolve-fn` + `:list-entities :service` +
;; `:first` + lazy `:and :some? :some?` + `:zipmap` envelope.


;; `:_execute-unknown-arg-err` is now a graph fn-def — see fns.edn.
;; Composes new atomic `:free-arg-slot-map` primitive with `:keys` +
;; `:filter :not :position-in` set-diff + `:zipmap` envelope.


;; `:_execute-malformed-ref-err` is now a graph fn-def — see fns.edn.
;; Pure composition over `:keys` + `:filter` + per-key predicate
;; (`:and :is-a? :contains? :nil? :parse-uuid`) + `:map` reshape +
;; `:not :empty?` guard + `:zipmap` envelope. No new base-fns.


(defbase _execute-apply
  [parsed]
  (cr/record-effect! :db)
  (fn-exec/apply-execute ctx parsed))


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
  (cr/record-effect! :process)
  (when (some? id) (fn-exec/cancel-execution! ctx id)))


;; `:_execute-rejected?` is now a graph fn-def — see fns.edn.


;; --- C18 atoms: get-execution + cancel-execution variant-2.
;; Both handlers share the `:_exec-id-parsed` graph parser (URL +
;; UUID coerce in fns.edn) AND the dynamic 404 builder (same text
;; either way); each has its own apply (read vs cancel-mutation).

;; `:_exec-id-parsed` is now a graph fn-def — see fns.edn.


;; GET /api/execute/:id atoms

;; `:_get-exec-loaded` is now a graph fn-def — `:get-execution` of
;; the parsed `:id` (the primitive handles the nil-id guard).


;; `:_get-exec-missing?` is now a graph fn-def — see fns.edn.


;; `:_get-exec-apply` is now a graph fn-def — `:const` of `{:as :loaded}`.


;; POST /api/execute/:id/cancel atoms

;; `:_cancel-exec-applied` is now a graph fn-def — `:cancel-execution!`
;; of the parsed `:id` (the primitive handles the nil-id guard).


;; `:_cancel-exec-missing?` is now a graph fn-def — see fns.edn.


;; `:_cancel-exec-apply` is now a graph fn-def — `:const` of `{:as :applied}`.


;; --- GET /api/executions?fn-id=X (C6 atoms) ---
;; Two query shapes share this endpoint:
;;   ?fn-id=X         → executions of X as it resolves on the current
;;                      branch (drives the execute popover's history)
;;   ?fn-version-id=Y → executions of the SPECIFIC version row Y
;;                      (drives the `⌛` panel's per-version expand)
;; If both are present `fn-version-id` wins. `:_list-executions-by-fn`
;; is now a `:cond` graph fn-def in fns.edn composing these atoms.

;; `:_list-exec-parsed` is now a graph fn-def — see fns.edn. Composes
;; the new `:query-param` primitive (in web/crud) + :parse-uuid +
;; :try-wrapped :parse-int for the optional limit.


;; `:_list-exec-no-anchor?` / `:_list-exec-by-version?` are now graph
;; fn-defs — see fns.edn.


;; `:_list-exec-apply-by-version` and `:_list-exec-apply-by-fn` are now
;; graph fn-defs — see fns.edn. They reuse the SQL/sort/take/clamp
;; composition; `:_list-exec-apply-by-fn` adds a `:resolve-fn-version-id`
;; pre-step that translates the logical fn-id to the current branch's
;; version-id.


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
  (cr/record-effect! :io)
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


;; `:enrich-running` is now a graph fn-def — see fns.edn. Composes
;; `:running-entry` + `:if (some? entry)` over a `:zipmap` reshape
;; with 4 fields. Pre-fix this hardcoded the 4-field response shape
;; in Clojure; admins couldn't rename `:stopper-set?` or add a
;; `:thread-id` without a backend rebuild.


;; --- C17 atoms: list-services linear ETL decomposition.
;; Five named steps glued by a `:cond`-free graph fn-def — pure
;; variant-1 data composition so each stage is visible. The
;; previously-monolithic 20-line body splits into the conceptual
;; pipeline: load services + fn-name-index → enrich each → maybe
;; build legacy fallback → wrap as final response. Each atom is a
;; 1-3-line wrap over the helpers above.

;; `:_list-services-rows` is now a graph fn-def (`:list-entities`
;; `:entity-type "service"`).


;; `:render-execute-result-hiccup` retired — entire result body
;; now graph-composed in `fns.edn` (`:_er-*` chain → `:_er-body`).
;; §3.3 fix.

;; `:render-service-popover-hiccup` retired — entire popover body
;; now graph-composed in `fns.edn` (`:_sp-*` chain →
;; `:_partial-service-popover-body`). §3.3 fix.


;; `:_list-services-fn-names` is now a graph fn-def — `:list-entities`
;; of `:fn` + per-row `[id name]` HOF + `:into {}` fold. The previous
;; `fn-name-by-id` helper isn't needed anymore.


(def impls
  {:resolve-fn                 resolve-fn
   :_execute-apply             _execute-apply
   :get-execution              get-execution
   :cancel-execution!          cancel-execution!
   :resolve-fn-version-id      resolve-fn-version-id
   :_reconcile-services-apply  _reconcile-services-apply
   :running-entry              running-entry})
