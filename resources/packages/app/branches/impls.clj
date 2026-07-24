(ns graphden.packages.app.branches.impls
  "Impls for `app.branches` endpoints. Only thin §3.1 boundary defbases
   live here — URL parsing, query-string parsing, predicate guards, and
   response-envelope wrapping are all graph fn-defs in `fns.edn`."
  (:require
    [clojure.tools.logging :as log]
    [graphden.crud.branches :as branches]
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.services.reconciler :as recon]
    [graphden.system.branch-router :as br]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.merge :as mrg]))


(defbase resolve-branch-ref
  "Look up a branch by ref (UUID string or name). Returns the row, or
   nil when not found / blank ref / non-string ref. Operates on the
   unwrapped base storage — branch context flows through the URL /
   query, not the wrapper. Single library boundary (single delegation
   to `crud.branches/resolve-branch-ref` over `base-storage`); the
   semantics are graph-visible at every call site via this base-fn."
  [ref]
  (cr/record-effect! :db)
  (branches/resolve-branch-ref (branches/base-storage ctx) ref))


;; `:current-branch-id` lives in `storage/branches/impls.clj` so
;; packages below `app` (e.g. `web/crud`) can reference the active
;; branch without taking an `app/branches` dep.


;; =============================================================================
;; GET /api/branches
;; =============================================================================


;; =============================================================================
;; GET /api/branches/:ref
;; =============================================================================


;; =============================================================================
;; GET /api/fns/:fn-id/versions
;; =============================================================================


;; =============================================================================
;; GET /api/branches/:ref/diff?against=<source>
;; =============================================================================

;; --- diff-branches ---



(defbase diff-branches
  "Single library call over `mrg/diff-branches` — symmetric resolved-
   view diff between two branches' ancestor chains. Returns the raw
   `{:source-branch-id :target-branch-id :diffs [...]}` shape. The
   per-row reshape + envelope live in graph (`:_diff-apply-…`)."
  [source-branch-id target-branch-id]
  (cr/record-effect! :db)
  (mrg/diff-branches (branches/base-storage ctx)
                     source-branch-id
                     target-branch-id))


;; =============================================================================
;; POST /api/branches
;; =============================================================================

;; --- create-branch ---



(defbase create-branch!
  "Single library call over `vs/create-branch!` — write a new branch
   row off `:branch-name` + `:base-branch-id` and return the row.
   Atomic §3.1 boundary; the response-shape building lives in
   graph (`:_create-branch-apply` → `:as-json-branch` + `:zipmap`
   envelope), not here."
  [branch-name base-branch-id]
  (cr/record-effect! :db)
  (vs/create-branch! (request/require-storage ctx)
                     branch-name
                     {:base-branch-id base-branch-id}))


;; =============================================================================
;; DELETE /api/branches/:ref
;; =============================================================================

;; --- delete-branch --- The constraint-rejection
;; cases (main-branch / has-children) stay inside apply because they
;; surface as exceptions from `vs/delete-branch!` — pre-checking them
;; would duplicate underlying constraint logic.



(defbase delete-branch!
  "Atomic library boundary over `vs/delete-branch!` — removes the
   branch by id. Throws `ex-info` with
   `:type :constraint-violation/root-branch-undeletable` or
   `:type :constraint-violation/branch-has-children` (latter carries
   a `:child-branch-ids` vec); the graph `:on-throw` handler
   dispatches on `:type` via `:case`. Returns nil on success.

   After a successful delete, drops the branch's entry from the
   branch-router's per-branch ctx cache AND clears any cached
   `name → id` ref so a same-name recreate doesn't hand back the
   stale id (see `branch-router/forget-ref-cache-for-branch!`).
   Without this, deleting `foo` then creating a new `foo` routes
   subsequent /api/* requests to the dead branch's compiled
   registry — the closures point at nothing and every dispatch
   throws `Branch handler closure missing`.

   Also stops any service whose `:branch-id` matched the deleted
   branch — `vs/delete-branch!` soft-disables them, but the
   reconciler won't pick that up until its next pass, so trigger
   it eagerly via `recon/restart-services-on-branch!` (which then
   reconcile-once!'s the deleted branch and removes its now-
   disabled entries from `running-atom`)."
  [branch-id]
  (cr/record-effect! :db)
  (let [result (vs/delete-branch! (request/require-storage ctx) branch-id)]
    (when-let [router (br/current-router)]
      (br/invalidate! router branch-id))
    (try
      (recon/restart-services-on-branch! ctx recon/running branch-id)
      (catch Exception e
        (log/warn e "post-delete service reconcile failed"
                  {:branch-id branch-id})))
    result))


;; =============================================================================
;; GET /api/branches/:ref/conflicts?source=<ref>
;; =============================================================================

;; --- preview-conflicts ---



(defbase detect-conflicts
  "Single library call over `mrg/detect-conflicts` — returns
   `{:conflicts [...] :fork-point <uuid-or-nil>}` for the merge-
   oriented framing (source → target). The per-row reshape +
   envelope live in graph (`:_conflicts-apply-…`)."
  [source-branch-id target-branch-id]
  (cr/record-effect! :db)
  (mrg/detect-conflicts (branches/base-storage ctx)
                        source-branch-id
                        target-branch-id))


;; =============================================================================
;; POST /api/branches/:ref/merge
;; =============================================================================

;; --- merge-branch ---



(defbase merge-branch!
  "Atomic library boundary over `vs/switch-branch` + `vs/merge-branch!` —
   switches to the target branch, then folds source's history in.
   Returns the merge record on success. Throws `ex-info` on
   `:merge-conflict` (which the graph `:on-throw` handler dispatches
   on via `:ex-data → :type`). The switch + merge are intrinsically
   coupled — splitting them would break the atomic semantics, so the
   single base-fn is the natural §3.1 unit.

   After a successful merge, invalidates the TARGET branch's
   cached per-branch handler / compiled-registry in the branch
   router — the merge changes which versions resolve on target but
   writes no row that the standard CRUD-notify path would touch.
   Without this invalidate the target branch keeps serving its
   pre-merge compiled closures and the merged-in versions are
   invisible to readers until something else triggers a recompile
   (verified by manual `bb deploy`).

   Also restarts every running service whose `:branch-id` matches
   the target branch via `recon/restart-services-on-branch!`.
   HTTP-server services pick up new closures lazily on the next
   request (the per-branch Ring-callable re-reads its registry),
   but cron-loop services hold their fn-graph in a closed-over
   reference and would otherwise keep firing the pre-merge graph
   forever. The restart is best-effort — when no reconciler
   singleton is wired (test contexts without `:exec/service-
   reconciler`) the call is a no-op."
  [source-branch-id target-branch-id resolutions]
  (cr/record-effect! :db)
  (let [storage (vs/switch-branch (request/require-storage ctx) target-branch-id)
        result (vs/merge-branch! storage source-branch-id
                                 {:conflict-resolutions resolutions})]
    ;; Invalidate only the fns the merge touched (source's version
    ;; owners) — a DELTA, so the next request recompiles just those +
    ;; their reverse-deps, not the whole registry. `invalidate-graph-
    ;; cache!` runs `delta-recompile!`, which expands the seeds to their
    ;; reverse-deps AND re-registers type-aliases (so a merged-in
    ;; type-row resolves). With the branch-router, invalidate the
    ;; merge's TARGET ctx — not the request's current ctx — which keeps
    ;; a cross-branch merge correct. Without a router (single-branch /
    ;; test harness), the request's own ctx IS the only view, so
    ;; invalidate that. Only reached on SUCCESS: a `:merge-conflict`
    ;; throws out of `vs/merge-branch!` above, so a rejected merge
    ;; invalidates nothing.
    ;; The invalidate + service restart run on a DEDICATED thread and
    ;; the request thread joins it: the merge record is already
    ;; committed, and an aborted client interrupts the http-kit worker
    ;; — running these post-commit steps inline would let that
    ;; interrupt skip them, leaving the target branch serving
    ;; pre-merge closures until an unrelated recompile (a committed
    ;; but invisible merge). On the dedicated thread they always
    ;; finish; an interrupt during join only re-flags the worker.
    (let [post-commit!
          (fn []
            (let [affected (mrg/merge-affected-fn-ids
                             (branches/base-storage ctx) source-branch-id)]
              (when (seq affected)
                (exec-ctx/invalidate-graph-cache!
                  (if-let [router (br/current-router)]
                    (br/ctx-for router target-branch-id)
                    ctx)
                  affected)))
            (try
              (recon/restart-services-on-branch! ctx recon/running
                                                 target-branch-id)
              (catch Exception e
                ;; Restart is observability-grade — the merge already
                ;; succeeded; surface the failure but don't fail the API.
                (log/warn e "post-merge service restart failed"
                          {:target-branch-id target-branch-id}))))
          t (Thread. ^Runnable post-commit! "merge-post-commit")]
      (Thread/.start t)
      (try
        (Thread/.join t)
        (catch InterruptedException _
          (log/warn "client aborted during post-merge invalidation — invalidation continues on its own thread"
                    {:target-branch-id target-branch-id})
          (Thread/.interrupt (Thread/currentThread)))))
    ;; Attach the audit log — fns that have a version on the source
    ;; branch but won't surface on the target after merge because
    ;; their effective `:branch-local?` filtered them out at the
    ;; resolver. API consumers (and the editor's post-merge alert)
    ;; consume this directly. nil-safe shape: empty :branch-local
    ;; key always present so downstream :zipmap envelopes don't
    ;; have to special-case the no-skips case.
    (assoc result
           :skipped {:branch-local
                     (mrg/skipped-as-branch-local
                       (branches/base-storage ctx) source-branch-id)})))


(def impls
  {:resolve-branch-ref  resolve-branch-ref
   :diff-branches       diff-branches
   :create-branch!      create-branch!
   :delete-branch!      delete-branch!
   :detect-conflicts    detect-conflicts
   :merge-branch!       merge-branch!})
