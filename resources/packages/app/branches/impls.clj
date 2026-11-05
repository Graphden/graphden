(ns graphden.packages.app.branches.impls
  "Impls for `app.branches` endpoints. Only thin §3.1 boundary defbases
   live here — URL parsing, query-string parsing, predicate guards, and
   response-envelope wrapping are all graph fn-defs in `fns.edn`."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.crud.branches :as branches]
    [graphden.crud.entities.list :as entity-list]
    [graphden.crud.request :as request]
    [graphden.crud.secrets :as secrets]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.lint.graph :as graph-lint]
    [graphden.services.reconciler :as recon]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.postgres.graph-epoch :as epoch]
    [graphden.storage.postgres.util :as pg-util]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.system.branch-router :as br]
    [graphden.system.branch-router.cache :as br-cache]
    [graphden.system.branch-router.epoch :as br-epoch]
    [graphden.system.branch-router.recheck :as br-recheck]
    [graphden.tenancy.context :as tc]
    [graphden.versioning.merge.core :as merge-policy]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.diff-view :as dv]
    [graphden.versioning.storage.merge :as mrg]))


(defbase _branch-by-id
  "One scoped branch identity read over the base storage."
  [id]
  (cr/record-effect! :db)
  (sp/read-entity (branches/base-storage ctx) :branch id))


(defbase _branch-by-name
  "One scoped exact branch-name lookup over the base storage."
  [ref]
  (cr/record-effect! :db)
  (first (sp/query-entities (branches/base-storage ctx) :branch {:name ref})))


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


(defbase diff-affected-facts
  "Shared reverse-dependency BFS and bounded namespace-path acquisition."
  [groups]
  (cr/record-effect! :db)
  (dv/affected-view-facts (branches/base-storage ctx) (cr/ctx-reverse-deps ctx) groups))


(defbase _diff-view-input
  "Fetch the flat enriched diff facts from `dv/diff-branches-view-facts`.
   Graph fn-defs group and order them for the editor compare view."
  [source-branch-id target-branch-id]
  (cr/record-effect! :db)
  (dv/diff-branches-view-facts (branches/base-storage ctx)
                               source-branch-id
                               target-branch-id))


;; =============================================================================
;; POST /api/branches
;; =============================================================================

;; --- create-branch ---



(def write-policies
  "The valid `:branch.write-policy` values (protected branches, Stage 1).
   \"open\" normalises to nil (the default — anyone the ordinary grants
   admit); \"owner\" admits the branch owner (+ the org's :manage-grants
   holders as the unlock escalation); \"admins\" admits :manage-grants
   holders only. Enforcement lives in the tenancy addon's
   authorize-writer; core stores the policy."
  #{"owner" "admins"})


(defn- checked-write-policy
  "Mandatory fail-closed domain check. Input spelling/default policy is graph-side."
  [policy]
  (when-not (or (nil? policy) (contains? write-policies policy))
    (throw (ex-info (str "invalid write-policy: " policy)
                    {:type :branches/invalid-write-policy
                     :write-policy policy :valid (conj write-policies "open")})))
  policy)


(defbase _create-branch-row
  "Create with trusted owner stamping and mandatory epoch publication."
  [branch-name options]
  (cr/record-effect! :db)
  (let [create-options (select-keys options [:id :base-branch-id :forbid-invalid? :write-policy :require-merge?])
        _ (checked-write-policy (:write-policy create-options))
        owner (:user-id tc/*current-principal*)
        row (vs/create-branch! (request/require-storage ctx) branch-name
                               (cond-> create-options owner (assoc :owner-id owner)))]
    (br-epoch/note-graph-epoch-validated! (request/require-storage ctx))
    row))


;; =============================================================================
;; DELETE /api/branches/:ref
;; =============================================================================

;; --- delete-branch --- The constraint-rejection
;; cases (main-branch / has-children) stay inside apply because they
;; surface as exceptions from `vs/delete-branch!` — pre-checking them
;; would duplicate underlying constraint logic.
;;
;; DECISION (2026-08 decomposition audit): delete stays ONE base-fn.
;; The post-delete steps (router cache drop, eager service stop, epoch
;; note) are write+invalidation coupling — the same accepted class as
;; `:fork-package-fns` / `:materialize-package-fns`: they are
;; router/epoch CONSISTENCY MACHINERY of the delete itself, not domain
;; composition a user should be able to vary (a delete composed
;; without them serves a dead branch's compiled registry). Contrast
;; merge, whose post-commit carries a dedicated-thread invariant and
;; a cross-branch target — worth its own primitive.



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
   stale id (see `branch-router.cache/forget-ref-cache-for-branch!`).
   Without this, deleting `foo` then creating a new `foo` routes
   subsequent /api/* requests to the dead branch's compiled
   registry — the closures point at nothing and every dispatch
   throws `Branch handler closure missing`.

   Also stops any service whose `:branch-id` matched the deleted
   branch — `vs/delete-branch!` soft-disables them, but the
   reconciler won't pick that up until its next pass, so trigger
   it eagerly via `recon/restart-services-on-branch!` (which then
   reconcile-once!'s the deleted branch and removes its now-
   disabled entries from `running-atom`).

   Also reclaims the vault values of secrets bound only on the deleted
   branch, and drops the branch's type-rows from the alias registry."
  [branch-id]
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)
        base (vs/unwrap storage)
        ;; The branch's org owns every binding bound on it — the vault
        ;; reclaim deletes in that org's scope (`secrets/delete-orphan!`).
        branch-org (:org-id (sp/read-entity base :branch branch-id))
        result (vs/delete-branch!
                 storage branch-id
                 {:reclaim-secrets!
                  #(secrets/sweep-orphan-secrets! base (secrets/secret-refs % (constantly branch-org)))})]
    (graph-lint/forget-branch! branch-id)
    (entity-list/forget-branch! branch-id)
    (when-let [router (br/current-router)]
      (br-cache/invalidate! router branch-id))
    ;; The type-rows the branch declared stop resolving — unless another
    ;; branch still declares the same name.
    (cr/forget-alias-source! branch-id)
    (try
      (recon/restart-services-on-branch! ctx recon/running branch-id)
      (catch Exception e
        (log/warn e "post-delete service reconcile failed"
                  {:branch-id branch-id})))
    ;; Eager work done — advance the epoch watermark past the delete's
    ;; bump (an aborted request self-heals on the next ctx fetch).
    (br-epoch/note-graph-epoch-validated! (request/require-storage ctx))
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
  "The atomic merge core: `vs/switch-branch` → policy check →
   `merge-transitively!` (the inherited branches the target lacks first,
   each gated the same way, then the source). Returns the merge record
   (+ `:merged-first`) on success. Throws `ex-info` on `:merge-conflict`
   / `:merge-protection-violation` / `:branch/approval-required` (the
   graph `:on-throw` handler dispatches on `:ex-data → :type`).
   The three steps share the SAME switched-to-target storage OBJECT —
   a runtime value no graph slot can carry — so they stay one
   base-fn; everything around them (post-commit invalidation, the
   `:skipped` audit) is graph composition (`:_merge-apply-record`)."
  [source-branch-id target-branch-id resolutions]
  (cr/record-effect! :db)
  (let [ms-since (fn [t] (/ (- (System/nanoTime) t) 1e6))
        storage (vs/switch-branch (request/require-storage ctx) target-branch-id)
        ;; The two policy gates, run for the source AND for every branch
        ;; merged in first on its behalf (`merge-transitively!`):
        ;; - error-tolerance Phase 5 — when the TARGET branch row carries
        ;;   `:forbid-invalid?`, refuse while recorded type diagnostics
        ;;   exist on either side (throws :merge-protection-violation);
        ;; - review policy — when the TARGET requires N approvals, refuse
        ;;   unless the branch has that many valid (non-stale, distinct,
        ;;   non-author-unless-allowed) approvals (throws
        ;;   :branch/approval-required).
        ;; Both judge the same switched-to-target storage the merge uses.
        gate! (fn [step-storage bid]
                (merge-policy/validate-branch-policy! step-storage bid)
                (merge-policy/validate-approval-policy! step-storage bid))
        t1 (System/nanoTime)
        record (merge-policy/merge-transitively! storage source-branch-id
                                                 {:conflict-resolutions resolutions}
                                                 gate!)]
    ;; Phase timings — the merge is the slowest write in the product
    ;; (~2 s on a 5k-fn self-host graph, ~8 s on the cloud demo) and
    ;; nothing said where the time went. Counts only, no tenant data.
    (log/info "merge commit"
              {:source source-branch-id :target target-branch-id
               :merged-first (count (:merged-first record))
               :merge-ms (long (ms-since t1))})
    record))


(defn- archive-landed-source!
  "The source landed into ITS OWN base: it is done. Fold it into the
   popover's \"Merged\" group (`:archived-at`) — it cannot be deleted
   (the target resolves through its rows) and would otherwise sit in
   the list forever. A sync the other way (base → feature) or a merge
   into a sibling leaves the source active. Best-effort, base storage
   (no epoch bump — nothing compiled changes); ONE conditional UPDATE,
   not read-then-update — the merge path is perf-budgeted per round trip
   (`:sql/merge-fork`)."
  [base source-branch-id target-branch-id]
  (try
    ;; Through any decorator (the cloud's org-scoped layer has no `:pool`
    ;; of its own) — else the source was never archived on the cloud.
    (when-let [pool (tx/datasource base)]
      (pg-util/exec! pool ["UPDATE branch SET archived_at = now() WHERE id = ? AND base_branch_id = ?"
                           source-branch-id target-branch-id]))
    (catch Exception e
      (log/warn e "merge post-commit: archiving the source failed — it stays listed"
                {:source-branch-id source-branch-id}))))


(defn- run-merge-post-commit!
  "The post-commit thread's body — invalidate the TARGET ctx, re-check
   the affected set into its registry slices, fan the invalidation out
   cross-pod, restart the target's affected services, note the epoch bumps.
   Everything thread-hostile (dynamic router / bump-log state) is
   CAPTURED by the caller on the request thread and passed in; the tenant
   org and the other conveyed bindings are re-bound around this call."
  [ctx router merge-bumps source-branch-id target-branch-id merged-first-ids]
  (let [t0 (System/nanoTime)
        timings (atom {})
        lap! (fn [k t] (swap! timings assoc k (long (/ (- (System/nanoTime) t) 1e6))))
        base (branches/base-storage ctx)
        ;; A transitive merge landed the branches the target lacked BEFORE
        ;; the source (`merge-transitively!`); their touched fns moved on the
        ;; target too and must seed the same delta — seeding from the source
        ;; alone left R's change invisible after an S-through-R merge.
        affected (into (mrg/merge-affected-fn-ids base source-branch-id)
                       (mapcat #(mrg/merge-affected-fn-ids base %))
                       merged-first-ids)
        _ (lap! :affected-ms t0)]
    (archive-landed-source! base source-branch-id target-branch-id)
    (when (seq affected)
      (let [t-ctx (System/nanoTime)
            target-ctx (if router
                         (br/ctx-for router target-branch-id)
                         ctx)
            _ (lap! :ctx-for-ms t-ctx)
            t-inv (System/nanoTime)]
        (exec-ctx/invalidate-graph-cache! target-ctx affected)
        (lap! :invalidate-ms t-inv)
        ;; The target's rich-types SLICE learned nothing from the
        ;; merged fns (their checks ran under the SOURCE's slice) —
        ;; and the default branch's entry is pinned, so no rebuild
        ;; will ever re-record them. Re-check the affected set into
        ;; the target's own slice (async, bounded) — under the tenant
        ;; org the caller bound for this thread, so the PER-ORG slice
        ;; learns them too.
        (let [t-re (System/nanoTime)]
          (br-recheck/recheck-ctx-types! target-ctx target-branch-id affected)
          (lap! :recheck-ms t-re)))
      ;; Cross-pod: the local invalidate + restart-services-depending-on!
      ;; below fire only on THIS pod. A merge writes no per-fn NOTIFY of
      ;; its own (registries heal cross-pod via the graph epoch), but a
      ;; RUNNING cron/loop closure on a sibling pod never re-fetches —
      ;; it keeps firing the pre-merge graph. Emit the same fn:invalidate
      ;; event an edit emits, per affected fn on the target branch, so
      ;; sibling pods invalidate AND restart their own singletons
      ;; (init.services/on-notify). org-id rides along for the SSE relay's
      ;; per-org fan-out, exactly as the edit path does.
      (when-let [emit (:notify-emitter ctx)]
        (let [t-em (System/nanoTime)
              branch-str (str target-branch-id)
              org-id (:org-id (sp/read-entity (branches/base-storage ctx)
                                              :branch target-branch-id))]
          (doseq [fid affected]
            (emit (cond-> {:kind :fn :op :invalidate :id (str fid)
                           :branch-id branch-str}
                    org-id (assoc :org-id org-id))))
          (lap! :notify-ms t-em))))
    (try
      (let [t-rs (System/nanoTime)]
        ;; ONLY the services whose closure the merge touched — the same delta
        ;; the invalidation above used, the same walk the edit path and the
        ;; cross-pod hook run. This used to restart EVERY running service on
        ;; the target branch: on the cloud every org's `main` IS the shared
        ;; main, so a tenant merging a probe fn restarted the platform's own
        ;; `web-server` — the merger got a 502 (its socket died with the
        ;; listener) and everybody else a blip (prod log).
        (when (seq affected)
          (recon/restart-services-depending-on! ctx recon/running (set affected)
                                                target-branch-id))
        (lap! :restart-ms t-rs))
      (catch Exception e
        ;; Restart is observability-grade — the merge already
        ;; succeeded; surface the failure but don't fail the API.
        (log/warn e "post-merge service restart failed"
                  {:target-branch-id target-branch-id})))
    ;; Eager work done — mark this merge's bumps applied. `merge-bumps`
    ;; was captured on the REQUEST thread: dynamic bindings don't convey
    ;; to a raw Thread, so the 1-arity (request-log-draining) note would
    ;; see nothing here.
    (let [t-note (System/nanoTime)]
      (br-epoch/note-graph-epoch-validated!
        (request/require-storage ctx) merge-bumps)
      (lap! :note-epoch-ms t-note))
    (log/info "merge post-commit"
              (assoc @timings
                     :target target-branch-id
                     :affected (count affected)
                     :total-ms (long (/ (- (System/nanoTime) t0) 1e6))))))


(defbase merge-post-commit!
  "Post-commit finisher for a COMMITTED merge — delta-invalidate the
   TARGET branch's ctx (seeded by `mrg/merge-affected-fn-ids`, so just
   the touched fns + reverse-deps recompile, and a merged-in type-row
   re-registers), then restart the target's services WHOSE CLOSURE the
   merge touched (cron loops hold their fn-graph in a closed-over
   reference and would keep firing the pre-merge graph forever;
   best-effort — no reconciler wired = no-op), then note the epoch
   bumps.

   The three steps run on a DEDICATED thread the request thread joins:
   an aborted client interrupts the http-kit worker, and running them
   inline would let that interrupt skip them — a committed but
   invisible merge (the target keeps serving pre-merge closures until
   an unrelated recompile). The thread + join + interrupt handling is
   the indivisible invariant; the steps inside run on that thread and
   therefore cannot be graph steps. With the branch-router this
   invalidates the merge's TARGET ctx — not the request's current ctx
   — which keeps a cross-branch merge correct. Returns nil."
  [source-branch-id target-branch-id merged-first]
  (cr/record-effect! :db)
  (let [merge-bumps (some-> epoch/*request-bump-log* deref seq vec)
        merged-first-ids (into [] (keep (fn [m] (some-> (:id m) str parse-uuid))) merged-first)
        ;; Capture the router on the REQUEST thread. Like `merge-bumps`
        ;; above, `current-router` reads dynamic/per-thread state
        ;; (`*active-router-override*`) that does NOT convey to the raw
        ;; post-commit Thread — reading it there falls back to the
        ;; process-global (nil under the parallel-test override), so the
        ;; invalidation would target `ctx` (the request's own branch)
        ;; instead of a cross-branch merge's TARGET ctx, leaving a merged
        ;; fn invisible on a NON-main target until an unrelated recompile.
        router (br/current-router)
        ;; The finisher runs on a RAW thread — dynamic bindings do not
        ;; convey. Capture the tenant org HERE (request thread) and run the
        ;; whole finisher under it: the recheck re-records into the
        ;; target's PER-ORG slice only under the org (without it
        ;; `record-rich-types-raw!` skips the org-gated per-org mirror and
        ;; tenant reads on the target keep serving the fork-time entry),
        ;; and every read below is org-scoped.
        request-org (tc/current-org)
        ;; …and the caller's epoch STATE. In production it is nil (one
        ;; global state per JVM); under the parallel test plugin each NS
        ;; thread has its own, and a raw thread validating against the
        ;; GLOBAL one advanced + pruned the shared ledger from a watermark
        ;; the NS thread had not reached — phantom foreign gaps, a heal
        ;; per merge. Carrying the state closes that split at
        ;; its source; the ledger's retention window is the backstop.
        epoch-state br-epoch/*epoch-state-override*
        ;; …and every binding a background thread must carry (the tenant
        ;; org, the effect gate — `cr/register-conveyed-var!`). The whole
        ;; body reads the tenant's rows: under the public org
        ;; `merge-affected-fn-ids` saw none of them (an empty delta — the
        ;; target kept serving pre-merge closures), the archive UPDATE ran
        ;; outside the tenant's row-level security, and the cross-pod
        ;; invalidation events went out without the org.
        conveyed (cr/capture-conveyed-bindings)
        post-commit!
        (fn []
          (with-bindings conveyed
            (tc/with-org request-org
                         (binding [br-epoch/*epoch-state-override* epoch-state]
                           (run-merge-post-commit! ctx router merge-bumps
                                                   source-branch-id target-branch-id
                                                   merged-first-ids)))))
        t (Thread. ^Runnable post-commit! "merge-post-commit")]
    (Thread/.start t)
    (try
      (Thread/.join t)
      (catch InterruptedException _
        ;; Do NOT re-interrupt: this is a POOLED http-kit worker, and
        ;; a re-set flag survives the return to the pool and kills the
        ;; NEXT request on this thread (audit-7 e2e: a series of
        ;; aborted merges poisoned enough workers that /health failed
        ;; for 60s). The post-commit thread finishes regardless; the
        ;; "preserve interrupt status" idiom is for threads the caller
        ;; owns, which a pool thread is not.
        (log/warn "client aborted during post-merge invalidation — invalidation continues on its own thread"
                  {:target-branch-id target-branch-id})))
    nil))


(defbase merge-skipped-facts
  "Bounded bulk read with effective inheritance and viewer visibility intact."
  [source-branch-id]
  (cr/record-effect! :db)
  (mrg/branch-local-source-rows (branches/base-storage ctx) source-branch-id))


(defbase set-branch-policy!
  "Update a branch's `:write-policy` (protected branches, Stage 1).
   `write-policy` ∈ {\"open\"→nil, \"owner\", \"admins\"} — anything
   else throws `:branches/invalid-write-policy`. Writes through the
   still-org-scoped base storage, so the tenancy addon's
   authorize-writer gates WHO may flip a policy (owner / org
   :manage-grants); without the addon the write is open, matching the
   rest of single-tenant. Explicit nil IS written (clears back to
   open)."
  [branch-id policy]
  (cr/record-effect! :db)
  ;; No graph-epoch bump here (nor in the other branch-row dials below):
  ;; policy / require-merge / review-state / review-policy are read
  ;; straight off the row on every use, nothing compiled or cached
  ;; depends on them, and a bump nobody notes ages past grace into a
  ;; spurious heal 45 s after every flip (the e2e suite's residual
  ;; heals, one per merge, from stage 2b's review-state
  ;; clear, and one per propose / protect click).
  (let [normalized-policy (checked-write-policy policy)]
    (sp/update-entity (branches/base-storage ctx) :branch branch-id
                      {:write-policy normalized-policy})
    normalized-policy))


(defbase set-branch-require-merge!
  "Set a branch's `:require-merge?` flag — GitHub-style 'push only via
   merge request' (protected branches, Stage 2). When true, DIRECT graph
   writes to the branch (editor CRUD + bundle import) are refused
   (`:branch/merge-required`, enforced in open core by
   `versioning.storage.core`); the only way in is a merge from another
   branch. Writes through the same base storage as `set-branch-policy!`,
   so WHO may flip it is the tenancy authorize-writer's call (owner /
   org :manage-grants); the ENFORCEMENT works without the addon, so a
   solo self-hoster can protect main too. Returns the boolean set."
  [branch-id require-merge?]
  (cr/record-effect! :db)
  (let [flag (boolean require-merge?)]
    (sp/update-entity (branches/base-storage ctx) :branch branch-id
                      {:require-merge? flag})
    flag))


(defbase _set-branch-archived-at
  "Authorized timestamp write. Time acquisition and archive command policy are graph-side."
  [branch-id archived-at]
  (cr/record-effect! :db)
  (sp/update-entity (branches/base-storage ctx) :branch branch-id {:archived-at archived-at})
  archived-at)


(defbase _set-branch-review-state
  "Authorized state write with the canonical workflow domain enforced."
  [branch-id state]
  (cr/record-effect! :db)
  (when-not (or (nil? state) (= "proposed" state))
    (throw (ex-info "Invalid branch review state" {:type :validation-error/review-state})))
  (sp/update-entity (branches/base-storage ctx) :branch branch-id {:review-state state})
  state)


(defbase _branch-int-value
  "Canonical checked fixed-width integer conversion."
  [value]
  (int value))


(defbase _patch-branch-review-policy
  "Apply only validated review columns through the authorized scoped writer,
   then read the effective row. Editable input coercion cannot bypass policy domains."
  [branch-id patch]
  (cr/record-effect! :db)
  (let [checked-patch (select-keys patch [:required-approvals :allow-self-approval? :approver-ids])
        required (:required-approvals checked-patch)
        self? (:allow-self-approval? checked-patch)
        approvers (:approver-ids checked-patch)]
    (when-not (or (nil? required) (nat-int? required))
      (throw (ex-info "required-approvals must be a non-negative integer"
                      {:type :validation-error/required-approvals :value required})))
    (when-not (or (nil? self?) (boolean? self?))
      (throw (ex-info "allow-self-approval must be a boolean"
                      {:type :validation-error/allow-self-approval})))
    (when-not (or (nil? approvers)
                  (and (sequential? approvers) (every? string? approvers)))
      (throw (ex-info "approver-ids must be a list of user ids"
                      {:type :validation-error/approver-ids :value approvers})))
    (when (seq checked-patch)
      (sp/update-entity (branches/base-storage ctx) :branch branch-id checked-patch))
    (sp/read-entity (branches/base-storage ctx) :branch branch-id)))


(defn- may-approve?
  "Predicate: may the current principal (`uid`) approve merges into the
   target branch `target-row`?

   When the target sets a non-empty `:approver-ids`, that list is
   RESTRICTIVE: ONLY those users (∪ org-admins / platform tier, as an
   unlock escalation) may approve — regardless of `:write-policy`. This
   is GitHub-parity (\"require review from these users\") and the whole
   point of naming reviewers: an `:approver-ids` that merely OR'd with an
   open `:write-policy` restricted no one (the ⚙ menu leaves write-policy
   open), so a named-reviewer requirement was silently a no-op.

   With no `:approver-ids`, WHO may approve mirrors the target's
   `:write-policy` (who may write/merge it): owner / org-admins / open.
   Open in single-tenant (no addon → no principals → `:manage-grants`
   seam is default-deny, so `nil`/`open` admits everyone — the correct
   self-host degrade).

   A NIL `target-row` (the target didn't resolve — missing
   `:base-branch-id`, or a cross-org ref OrgScoped filtered) fails
   CLOSED: a nil ROW means \"no target\", not \"open\"."
  [target-row uid]
  (if (nil? target-row)
    false
    (let [policy (:write-policy target-row)
          owner (:owner-id target-row)
          approver-ids (set (:approver-ids target-row))
          admin? (or (tc/current-platform-tier?)
                     (tc/current-has-org-cap? :manage-grants))]
      (boolean
        (if (seq approver-ids)
          ;; RESTRICTIVE allow-list: only the named reviewers, plus the
          ;; admin unlock — NOT the write-policy roles.
          (or admin? (and uid (contains? approver-ids uid)))
          (case policy
            ("owner") (or admin? (and uid owner (= uid owner)))
            ("admins") admin?
            ;; nil / "" / "open" → open (no write-policy restriction).
            (nil "" "open") true
            ;; anything else → DENY (hardening): write-policy is validated
            ;; to the known set at set time, so an unknown value here is
            ;; anomalous — fail closed rather than fall open.
            false))))))


(defbase approve-proposal!
  "Record the current principal's approval of proposal branch
   `source-branch-id` for merge into its base. WHO may approve: if the
   target sets `:approver-ids`, ONLY those users (∪ org-admins) — else the
   target's `:write-policy` roles (owner/admins/open); otherwise
   `:authz/forbidden`. The row is stamped with the TARGET it was
   authorized for (`:target-branch-id`) so it can't be counted toward a
   merge into a different branch.
   The approval is stamped with the source's current content fingerprint,
   so a later edit auto-dismisses it (counted valid only while the stamp
   matches — `merge.core/count-valid-approvals`). Re-approving after an
   edit simply records a fresh, current-stamped row. Returns the approver
   id recorded."
  [source-branch-id]
  (cr/record-effect! :db)
  (let [base-storage (branches/base-storage ctx)]
    (writer/with-write [base-storage {:entity :branch :ids [source-branch-id]}]
                       (let [source-row (sp/read-entity base-storage :branch source-branch-id)
                             target-id (:base-branch-id source-row)
                             target-row (when target-id (sp/read-entity base-storage :branch target-id))
                             uid (:user-id tc/*current-principal*)]
                         (when-not (may-approve? target-row uid)
                           (throw (ex-info "You are not allowed to approve merges into this branch."
                                           {:type :authz/forbidden :capability :approve-merge})))
                         (let [approver (or uid "anonymous")]
                           (sp/create-entity base-storage :branch-approval
                                             {:source-branch-id source-branch-id
                                              ;; Bind approval to the target authorized under this lock.
                                              :target-branch-id target-id
                                              :approver-id approver
                                              :content-stamp (merge-policy/branch-content-stamp
                                                               base-storage source-branch-id)
                                              :created-at (java.time.Instant/now)})
                           approver)))))


(defbase dismiss-my-approval!
  "Withdraw the current principal's own approval(s) of proposal branch
   `source-branch-id`. Returns the number of approval rows removed."
  [source-branch-id]
  (cr/record-effect! :db)
  (let [base-storage (branches/base-storage ctx)
        uid (or (:user-id tc/*current-principal*) "anonymous")
        mine (->> (sp/query-entities base-storage :branch-approval
                                     {:source-branch-id source-branch-id})
                  (filter #(= uid (:approver-id %)))
                  (mapv :id))]
    (when (seq mine)
      (sp/delete-entities base-storage :branch-approval mine))
    (count mine)))


(defbase branch-content-stamp
  "The proposal branch's content fingerprint (`merge.core/branch-content-stamp`)
   — what an approval is stamped with, and compared against to call it
   stale."
  [source-branch-id]
  (cr/record-effect! :db)
  (merge-policy/branch-content-stamp (branches/base-storage ctx) source-branch-id))


(defbase branch-approvals
  "Every recorded `:branch-approval` row for proposal `source-branch-id`."
  [source-branch-id]
  (cr/record-effect! :db)
  (vec (sp/query-entities (branches/base-storage ctx) :branch-approval
                          {:source-branch-id source-branch-id})))


(defbase approval-rejection
  "The merge gate's canonical per-approval verdict; display is graph policy."
  [stamp target-branch-id approver-ids approval author-id allow-self?]
  (merge-policy/approval-rejection stamp target-branch-id (set approver-ids)
                                   author-id allow-self? approval))


(defbase self-approval-allowed?
  "Whether the proposal author's own approval counts toward `target`'s
   required approvals (`merge.core/self-approval-allowed?` — the merge
   gate's own default: on unless the target turns it off)."
  [target]
  (merge-policy/self-approval-allowed? target))


(defbase count-valid-approvals
  "How many DISTINCT approvers count toward the merge
   (`merge.core/count-valid-approvals*` — the merge gate's own rule)."
  [stamp target-branch-id approver-ids approvals author-id allow-self?]
  (merge-policy/count-valid-approvals* stamp target-branch-id (set approver-ids)
                                       approvals author-id allow-self?))


(def ^:private max-comment-body-chars
  "Upper bound on a review comment body. A comment is a sentence or two of
   conversation; the cap keeps a tenant from bloating its org's DB (and the
   diff modal that re-serves every comment) with a multi-MB body — http-kit's
   ~8 MB max-body was the only prior bound. Generous enough for real prose."
  10000)


(def ^:dynamic *max-comments-per-branch*
  "Upper bound on how many review comments one branch's thread may hold.
   Comments have no fairness-quota coverage and the compare-mode client
   downloads the whole thread per entry — without a row cap a tenant
   could grow an unbounded thread (an intra-org self-DoS, but also an
   unbounded response for every reader). 500 is far past any real
   review conversation. Dynamic for tests."
  500)


(def ^:private comment-anchor-entities
  "Valid `:entity-name` anchor kinds for a review comment — the four
   versioned entity kinds the branch diff walks."
  #{"fn" "fn-slot" "binding" "binding-list-item"})


(defbase _branch-comment-limits
  "Mandatory comment resource bound and canonical anchor domain."
  []
  {:max max-comment-body-chars :valid comment-anchor-entities})


(defbase _create-branch-comment
  "Constrained current-author insert. Resource caps and anchor domain remain
   enforced even when graph preflight/row policy is customized."
  [row]
  (cr/record-effect! :db)
  (let [{:keys [source-branch-id body entity-name entity-id]} row
        text (str body)
        ename (some-> entity-name str)]
    (when (> (count text) max-comment-body-chars)
      (throw (ex-info (str "Comment too long: " (count text) " chars (max "
                           max-comment-body-chars ").")
                      {:type :validation-error/comment-too-long
                       :max max-comment-body-chars})))
    (let [existing (count (sp/query-entities (branches/base-storage ctx)
                                             :branch-comment
                                             {:source-branch-id source-branch-id}))]
      (when (>= existing *max-comments-per-branch*)
        (throw (ex-info (str "Comment limit reached: this branch already holds "
                             existing " comments (max " *max-comments-per-branch*
                             "). Resolve and delete old threads first.")
                        {:type :validation-error/comment-limit
                         :max *max-comments-per-branch*}))))
    (when (or (and ename (not (contains? comment-anchor-entities ename)))
              (not= (some? ename) (some? entity-id)))
      (throw (ex-info (str "Invalid comment anchor: " (pr-str ename) " / "
                           (pr-str entity-id)
                           " (need both a known entity kind and an id, or neither).")
                      {:type :validation-error/invalid-comment-anchor
                       :entity-name ename
                       :valid comment-anchor-entities})))
    (let [author (or (:user-id tc/*current-principal*) "anonymous")
          inserted (sp/create-entity (branches/base-storage ctx) :branch-comment
                                     (cond-> {:source-branch-id source-branch-id
                                              :author-id author
                                              :body text
                                              :created-at (java.time.Instant/now)}
                                       ename (assoc :entity-name ename
                                                    :entity-id entity-id)))]
      inserted)))


(defbase list-branch-comments
  "RAW `:branch-comment` rows for proposal branch `source-branch-id` —
   one query, unsorted and unshaped. Ordering and the wire reshape
   (key subset + id/timestamp stringification) are graph composition
   (`:_branch-comments-shaped` in fns.edn)."
  [source-branch-id]
  (cr/record-effect! :db)
  (sp/query-entities (branches/base-storage ctx) :branch-comment
                     {:source-branch-id source-branch-id}))


(defbase delete-branch-comment!
  "Delete comment `comment-id` — the AUTHOR's own only (`:authz/forbidden`
   otherwise; single-tenant nil-principal ≡ \"anonymous\" so a solo user
   can always delete their own). Returns true when a row was removed."
  [comment-id]
  (cr/record-effect! :db)
  (let [base-storage (branches/base-storage ctx)
        row (sp/read-entity base-storage :branch-comment comment-id)
        caller (or (:user-id tc/*current-principal*) "anonymous")]
    (when (and row (not= caller (:author-id row)))
      (throw (ex-info "Only the comment's author may delete it."
                      {:type :authz/forbidden :capability :delete-comment})))
    (when row
      (sp/delete-entity base-storage :branch-comment comment-id))
    (some? row)))


(def impls
  {:_branch-by-id _branch-by-id
   :_branch-by-name _branch-by-name
   :diff-branches              diff-branches
   :_diff-view-input           _diff-view-input
   :_diff-affected-facts       diff-affected-facts
   :_create-branch-row {:impl _create-branch-row :taint-propagate? true}
   :delete-branch!             delete-branch!
   :detect-conflicts           detect-conflicts
   :merge-branch!              {:impl merge-branch! :taint-propagate? true}
   :merge-post-commit!         merge-post-commit!
   :_merge-skipped-facts       merge-skipped-facts
   :_set-branch-policy         {:impl set-branch-policy! :taint-propagate? true}
   :set-branch-require-merge!  {:impl set-branch-require-merge! :taint-propagate? true}
   :_set-branch-review-state {:impl _set-branch-review-state :taint-propagate? true}
   :_set-branch-archived-at {:impl _set-branch-archived-at :taint-propagate? true}
   :_patch-branch-review-policy {:impl _patch-branch-review-policy :taint-propagate? true}
   :_branch-int-value {:impl _branch-int-value :taint-propagate? true}
   :approve-proposal!          approve-proposal!
   :dismiss-my-approval!       dismiss-my-approval!
   :branch-content-stamp       branch-content-stamp
   :branch-approvals           branch-approvals
   :_approval-rejection        {:impl approval-rejection :taint-propagate? true}
   :count-valid-approvals      {:impl count-valid-approvals :taint-propagate? true}
   :self-approval-allowed?     self-approval-allowed?
   ;; taint-propagate: list returns caller-authored comment bodies.
   :_create-branch-comment {:impl _create-branch-comment :taint-propagate? true}
   :_branch-comment-limits _branch-comment-limits
   :list-branch-comments       {:impl list-branch-comments :taint-propagate? true}
   :delete-branch-comment!     delete-branch-comment!})
