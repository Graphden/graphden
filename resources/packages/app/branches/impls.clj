(ns graphden.packages.app.branches.impls
  "Impls for `app.branches` endpoints. Only thin §3.1 boundary defbases
   live here — URL parsing, query-string parsing, predicate guards, and
   response-envelope wrapping are all graph fn-defs in `fns.edn`."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.crud.branches :as branches]
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.services.reconciler :as recon]
    [graphden.storage.postgres.graph-epoch :as epoch]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.tenancy.context :as tc]
    [graphden.versioning.merge.core :as merge-policy]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.diff-view :as dv]
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


(defbase diff-branches-view
  "Single library call over `dv/diff-branches-view` — the GROUPED
   display model the branch-diff partial renders: entries grouped
   under their owning fn, per-field before/after pairs for modified
   rows, ids resolved to names. The JSON API keeps serving the flat
   v1 shape via `diff-branches`."
  [source-branch-id target-branch-id]
  (cr/record-effect! :db)
  (dv/diff-branches-view (branches/base-storage ctx)
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


(defn- normalize-write-policy
  "nil/blank/\"open\" → nil; a valid policy string → itself; anything
   else → `:branches/invalid-write-policy`."
  [p]
  (let [p (some-> p str/trim)]
    (cond
      (or (nil? p) (= "" p) (= "open" p)) nil
      (contains? write-policies p) p
      :else (throw (ex-info (str "invalid write-policy: " p)
                            {:type :branches/invalid-write-policy
                             :write-policy p
                             :valid (conj write-policies "open")})))))


(defbase create-branch!
  "Single library call over `vs/create-branch!` — write a new branch
   row off `:branch-name` + `:base-branch-id` (+ the optional
   `:forbid-invalid?` merge-policy flag, error-tolerance Phase 5, and
   the optional `:write-policy` protection level) and return the row.
   The creating principal's stable id is stamped as `:owner-id`
   whenever one is bound — provenance always, enforcement only when a
   policy is set. Atomic §3.1 boundary; the response-shape building
   lives in graph (`:_create-branch-apply` → `:as-json-branch` +
   `:zipmap` envelope), not here."
  [branch-name base-branch-id forbid-invalid? write-policy require-merge?]
  (cr/record-effect! :db)
  (let [policy (normalize-write-policy write-policy)
        owner (:user-id tc/*current-principal*)
        row (vs/create-branch! (request/require-storage ctx)
                               branch-name
                               (cond-> {:base-branch-id base-branch-id}
                                 (some? forbid-invalid?)
                                 (assoc :forbid-invalid? forbid-invalid?)
                                 (some? owner)
                                 (assoc :owner-id owner)
                                 (some? policy)
                                 (assoc :write-policy policy)
                                 (some? require-merge?)
                                 (assoc :require-merge? (boolean require-merge?))))]
    ;; A fresh branch needs no invalidation (its ctx builds lazily),
    ;; but the epoch bump must still be NOTED — un-noted it ages past
    ;; grace and triggers a spurious heal ~10s after every branch
    ;; creation (audit-7 e2e: recurring mid-suite heals).
    (br/note-graph-epoch-validated! (request/require-storage ctx))
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
    ;; Eager work done — advance the epoch watermark past the delete's
    ;; bump (an aborted request self-heals on the next ctx fetch).
    (br/note-graph-epoch-validated! (request/require-storage ctx))
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
   `vs/merge-branch!`. Returns the merge record on success. Throws
   `ex-info` on `:merge-conflict` / `:merge-protection-violation`
   (the graph `:on-throw` handler dispatches on `:ex-data → :type`).
   The three steps share the SAME switched-to-target storage OBJECT —
   a runtime value no graph slot can carry — so they stay one
   base-fn; everything around them (post-commit invalidation, the
   `:skipped` audit) is graph composition (`:_merge-apply-record`)."
  [source-branch-id target-branch-id resolutions]
  (cr/record-effect! :db)
  (let [t0 (System/nanoTime)
        ms-since (fn [t] (/ (- (System/nanoTime) t) 1e6))
        storage (vs/switch-branch (request/require-storage ctx) target-branch-id)
        ;; Error-tolerance Phase 5 — when the TARGET branch row carries
        ;; `:forbid-invalid?`, refuse while recorded type diagnostics
        ;; exist on either side (throws :merge-protection-violation).
        ;; Part of the atomic boundary: it must judge the same
        ;; switched-to-target storage the merge itself uses.
        _ (merge-policy/validate-branch-policy! storage source-branch-id)
        ;; Review policy — when the TARGET requires N approvals, refuse
        ;; unless the source has that many valid (non-stale, distinct,
        ;; non-author-unless-allowed) approvals (throws
        ;; :branch/approval-required). Same atomic boundary + target
        ;; storage as the branch-policy gate above.
        _ (merge-policy/validate-approval-policy! storage source-branch-id)
        policy-ms (ms-since t0)
        t1 (System/nanoTime)
        record (vs/merge-branch! storage source-branch-id
                                 {:conflict-resolutions resolutions})]
    ;; Phase timings — the merge is the slowest write in the product
    ;; (~2 s on a 5k-fn self-host graph, ~8 s on the cloud demo) and
    ;; nothing said where the time went. Counts only, no tenant data.
    (log/info "merge commit"
              {:source source-branch-id :target target-branch-id
               :policy-ms (long policy-ms) :merge-ms (long (ms-since t1))})
    record))


(defn- run-merge-post-commit!
  "The post-commit thread's body — invalidate the TARGET ctx, re-check
   the affected set into its registry slices, fan the invalidation out
   cross-pod, restart the target's affected services, note the epoch bumps.
   Everything thread-hostile (dynamic router / org / bump-log state)
   is CAPTURED by the caller on the request thread and passed in."
  [ctx router request-org merge-bumps source-branch-id target-branch-id]
  (let [t0 (System/nanoTime)
        timings (atom {})
        lap! (fn [k t] (swap! timings assoc k (long (/ (- (System/nanoTime) t) 1e6))))
        affected (mrg/merge-affected-fn-ids
                   (branches/base-storage ctx) source-branch-id)
        _ (lap! :affected-ms t0)]
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
        ;; the target's own slice (async, bounded), under the tenant
        ;; org captured on the request thread so the PER-ORG slice
        ;; learns them too.
        (let [t-re (System/nanoTime)]
          (tc/with-org request-org
                       (br/recheck-ctx-types! target-ctx target-branch-id affected))
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
        ;; listener) and everybody else a blip (prod log, 2026-09-03).
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
      (br/note-graph-epoch-validated!
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
  [source-branch-id target-branch-id]
  (cr/record-effect! :db)
  (let [merge-bumps (some-> epoch/*request-bump-log* deref seq vec)
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
        ;; convey. Capture the tenant org HERE (request thread) so the
        ;; recheck below can re-record into the target's PER-ORG slice
        ;; too; without it `record-rich-types-raw!` skips the per-org
        ;; mirror (org-gated) and tenant reads on the target keep
        ;; serving the fork-time entry (the slices are branch-scoped
        ;; now — no shared global to paper over it).
        request-org (tc/current-org)
        post-commit!
        (fn []
          (run-merge-post-commit! ctx router request-org merge-bumps
                                  source-branch-id target-branch-id))
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


(defbase merge-skipped-branch-local
  "Audit read — fns that have a version on the source branch but won't
   surface on the target after merge because their effective
   `:branch-local?` filtered them out at the resolver. One library
   call (`mrg/skipped-as-branch-local`); the `{:branch-local […]}`
   wrapper and its attachment to the merge record are graph steps."
  [source-branch-id]
  (cr/record-effect! :db)
  (mrg/skipped-as-branch-local (branches/base-storage ctx) source-branch-id))


(defbase set-branch-policy!
  "Update a branch's `:write-policy` (protected branches, Stage 1).
   `write-policy` ∈ {\"open\"→nil, \"owner\", \"admins\"} — anything
   else throws `:branches/invalid-write-policy`. Writes through the
   still-org-scoped base storage, so the tenancy addon's
   authorize-writer gates WHO may flip a policy (owner / org
   :manage-grants); without the addon the write is open, matching the
   rest of single-tenant. Explicit nil IS written (clears back to
   open)."
  [branch-id write-policy]
  (cr/record-effect! :db)
  (let [policy (normalize-write-policy write-policy)]
    (sp/update-entity (branches/base-storage ctx) :branch branch-id
                      {:write-policy policy})
    (epoch/bump! (branches/base-storage ctx) :branch)
    policy))


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
    (epoch/bump! (branches/base-storage ctx) :branch)
    flag))


(defbase set-review-state!
  "Set a branch's `:review-state` — the change-proposal handoff. `proposed?`
   truthy → \"proposed\" (this branch is submitted for review into its base);
   falsy → nil (withdraw). Writes through the same still-org-scoped base
   storage as `set-branch-policy!`, so WHO may propose/withdraw is the
   tenancy authorize-writer's call on the branch row (its owner); without
   the addon the write is open (single-tenant). Returns the state set
   (\"proposed\" or nil)."
  [branch-id proposed?]
  (cr/record-effect! :db)
  (let [state (when proposed? "proposed")]
    (sp/update-entity (branches/base-storage ctx) :branch branch-id
                      {:review-state state})
    (epoch/bump! (branches/base-storage ctx) :branch)
    state))


(defn- normalize-approver-ids
  "Coerce the body's `approver-ids` to a vector of user-id strings (the
   JSONB shape stored on the branch). nil/empty → nil (clears). Rejects a
   non-sequential value (e.g. a bare string, which `seq` would shred into
   characters) with a clean 400 rather than storing garbage."
  [approver-ids]
  (cond
    (nil? approver-ids) nil
    (sequential? approver-ids) (when (seq approver-ids) (mapv str approver-ids))
    :else (throw (ex-info "approver-ids must be a list of user ids"
                          {:type :validation-error/approver-ids :value approver-ids}))))


(defn- normalize-required-approvals
  "Coerce `required-approvals` to a non-negative int (nil ≡ off). Rejects
   a negative or non-integer value with a clean 400 instead of silently
   treating it as off (negative) or 500ing (`(int \"x\")`)."
  [n]
  (cond
    (nil? n) nil
    ;; JSON integers parse as Long → nat-int?. A float / negative / non-number
    ;; is a clean rejection (a client sending 2.0 should send 2).
    (nat-int? n) (int n)
    :else (throw (ex-info "required-approvals must be a non-negative integer"
                          {:type :validation-error/required-approvals :value n}))))


(defbase set-branch-review-policy!
  "Set a branch's review policy (the merge TARGET's rules): how many
   approvals a proposal needs (`required-approvals`), whether the author's
   own approval counts (`allow-self-approval?`), and an explicit reviewer
   allow-list (`approver-ids`). PATCH semantics: an arg of `:keep` (the
   sentinel the route supplies for a JSON key ABSENT from the request
   body) leaves that column untouched; any other value — including `nil`
   — sets it (nil/empty clears). This is why the editor's ⚙ menu, which
   POSTs only `required-approvals` + `allow-self-approval`, no longer wipes
   an API/MCP-set `approver-ids` allow-list (the audit-2 clobber).
   Writes through the same still-org-scoped base storage as
   `set-branch-policy!`, so WHO may set it is the authorize-writer's call.
   Returns the EFFECTIVE `{:required-approvals :allow-self-approval? :approver-ids}`."
  [branch-id required-approvals allow-self-approval? approver-ids]
  (cr/record-effect! :db)
  (let [keep? #(= :keep %)
        row (cond-> {}
              (not (keep? required-approvals))
              (assoc :required-approvals (normalize-required-approvals required-approvals))
              (not (keep? allow-self-approval?))
              (assoc :allow-self-approval? (when (some? allow-self-approval?)
                                             (boolean allow-self-approval?)))
              (not (keep? approver-ids))
              (assoc :approver-ids (normalize-approver-ids approver-ids)))]
    (when (seq row)
      (sp/update-entity (branches/base-storage ctx) :branch branch-id row)
      (epoch/bump! (branches/base-storage ctx) :branch))
    (-> (sp/read-entity (branches/base-storage ctx) :branch branch-id)
        (select-keys [:required-approvals :allow-self-approval? :approver-ids]))))


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
  (let [base-storage (branches/base-storage ctx)
        source-row (sp/read-entity base-storage :branch source-branch-id)
        target-id (:base-branch-id source-row)
        target-row (when target-id (sp/read-entity base-storage :branch target-id))
        uid (:user-id tc/*current-principal*)]
    (when-not (may-approve? target-row uid)
      (throw (ex-info "You are not allowed to approve merges into this branch."
                      {:type :authz/forbidden :capability :approve-merge})))
    (let [approver (or uid "anonymous")]
      (sp/create-entity base-storage :branch-approval
                        {:source-branch-id source-branch-id
                         ;; Bind the approval to the branch it was AUTHORIZED
                         ;; for. The merge gate counts it only when this equals
                         ;; the actual merge target, so approvals gathered for
                         ;; one branch can't satisfy a merge into another.
                         :target-branch-id target-id
                         :approver-id approver
                         :content-stamp (merge-policy/branch-content-stamp
                                          base-storage source-branch-id)
                         :created-at (java.time.Instant/now)})
      approver)))


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


(defbase proposal-approval-status
  "Read-only projection for the reviewer UI: the approval status of
   proposal branch `source-branch-id` — its target's `:required-approvals`,
   the current count of VALID (non-stale, distinct, author-adjusted)
   approvals, whether that satisfies the requirement, and each recorded
   approver with a `stale` flag. Read-only, org-scoped via the source
   branch id."
  [source-branch-id]
  (cr/record-effect! :db)
  (let [base-storage (branches/base-storage ctx)
        source-row (sp/read-entity base-storage :branch source-branch-id)
        target-id (:base-branch-id source-row)
        target-row (when target-id (sp/read-entity base-storage :branch target-id))
        required (or (:required-approvals target-row) 0)
        allow-self? (merge-policy/self-approval-allowed? target-row)
        approver-ids (set (:approver-ids target-row))
        author-id (:owner-id source-row)
        stamp (merge-policy/branch-content-stamp base-storage source-branch-id)
        approvals (sp/query-entities base-storage :branch-approval
                                     {:source-branch-id source-branch-id})
        ;; reuse the stamp + approvals just fetched — the I/O arity of
        ;; count-valid-approvals would re-run the 5-table stamp query + a
        ;; second approvals query (the audit-2 double-compute). The count is
        ;; judged against the proposal's own target (`target-id`), matching
        ;; the merge gate — an approval stamped for a different target, or one
        ;; outside a restrictive `:approver-ids` allow-list, doesn't count.
        have (merge-policy/count-valid-approvals* stamp target-id approver-ids
                                                  approvals author-id allow-self?)]
    {:required required
     :have have
     :satisfied (>= have required)
     :approvers (mapv (fn [a]
                        {:approver-id (:approver-id a)
                         :stale (or (not= stamp (:content-stamp a))
                                    (not= target-id (:target-branch-id a))
                                    (and (seq approver-ids)
                                         (not (contains? approver-ids (:approver-id a)))))})
                      approvals)}))


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


(defbase add-branch-comment!
  "Record a review comment on proposal branch `source-branch-id` by the
   current principal (`\"anonymous\"` single-tenant). WHO may comment =
   whoever can resolve the branch (org-scoped upstream) — a comment is
   conversation, not a mutation of the branch. Optional `entity-name` +
   `entity-id` anchor the comment to one diffed graph element (both nil
   = the general branch thread; a half-anchor or unknown kind is a 400).
   Rejects a body over `max-comment-body-chars` with
   `:validation-error/comment-too-long` (400). Returns the new row's id."
  [source-branch-id body entity-name entity-id]
  (cr/record-effect! :db)
  (let [text (str body)
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
          row (sp/create-entity (branches/base-storage ctx) :branch-comment
                                (cond-> {:source-branch-id source-branch-id
                                         :author-id author
                                         :body text
                                         :created-at (java.time.Instant/now)}
                                  ename (assoc :entity-name ename
                                               :entity-id entity-id)))]
      (str (:id row)))))


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
  {:resolve-branch-ref         resolve-branch-ref
   :diff-branches              diff-branches
   :diff-branches-view         diff-branches-view
   :create-branch!             create-branch!
   :delete-branch!             delete-branch!
   :detect-conflicts           detect-conflicts
   :merge-branch!              merge-branch!
   :merge-post-commit!         merge-post-commit!
   :merge-skipped-branch-local merge-skipped-branch-local
   :set-branch-policy!         set-branch-policy!
   :set-branch-require-merge!  set-branch-require-merge!
   :set-review-state!          set-review-state!
   :set-branch-review-policy!  set-branch-review-policy!
   :approve-proposal!          approve-proposal!
   :dismiss-my-approval!       dismiss-my-approval!
   :proposal-approval-status   proposal-approval-status
   ;; taint-propagate: list returns caller-authored comment bodies.
   :add-branch-comment!        add-branch-comment!
   :list-branch-comments       {:impl list-branch-comments :taint-propagate? true}
   :delete-branch-comment!     delete-branch-comment!})
