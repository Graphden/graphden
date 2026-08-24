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
  (let [storage (vs/switch-branch (request/require-storage ctx) target-branch-id)
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
        _ (merge-policy/validate-approval-policy! storage source-branch-id)]
    (vs/merge-branch! storage source-branch-id
                      {:conflict-resolutions resolutions})))


(defbase merge-post-commit!
  "Post-commit finisher for a COMMITTED merge — delta-invalidate the
   TARGET branch's ctx (seeded by `mrg/merge-affected-fn-ids`, so just
   the touched fns + reverse-deps recompile, and a merged-in type-row
   re-registers), then restart the target's services (cron loops hold
   their fn-graph in a closed-over reference and would keep firing the
   pre-merge graph forever; best-effort — no reconciler wired = no-op),
   then note the epoch bumps.

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
        post-commit!
        (fn []
          (let [affected (mrg/merge-affected-fn-ids
                           (branches/base-storage ctx) source-branch-id)]
            (when (seq affected)
              (exec-ctx/invalidate-graph-cache!
                (if router
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
                        {:target-branch-id target-branch-id})))
          ;; Eager work done — mark this merge's bumps applied.
          ;; `merge-bumps` was captured on the REQUEST thread: dynamic
          ;; bindings don't convey to a raw Thread, so the 1-arity
          ;; (request-log-draining) note would see nothing here.
          (br/note-graph-epoch-validated!
            (request/require-storage ctx) merge-bumps))
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
   allow-list (`approver-ids`). nil/absent leaves each column cleared (off).
   Writes through the same still-org-scoped base storage as
   `set-branch-policy!`, so WHO may set it is the authorize-writer's call.
   Returns the stored `{:required-approvals :allow-self-approval? :approver-ids}`."
  [branch-id required-approvals allow-self-approval? approver-ids]
  (cr/record-effect! :db)
  (let [row {:required-approvals (normalize-required-approvals required-approvals)
             :allow-self-approval? (when (some? allow-self-approval?)
                                     (boolean allow-self-approval?))
             :approver-ids (normalize-approver-ids approver-ids)}]
    (sp/update-entity (branches/base-storage ctx) :branch branch-id row)
    (epoch/bump! (branches/base-storage ctx) :branch)
    row))


(defn- may-approve?
  "Predicate: may the current principal (`uid`) approve merges into the
   target branch `target-row`? Mirrors the target's `:write-policy` (who
   may write/merge it) plus its explicit `:approver-ids` allow-list. Open
   in single-tenant (no addon → no principals → `:manage-grants` seam is
   default-deny, so `nil`/`open` policy admits everyone, which is the
   correct self-host degrade)."
  [target-row uid]
  (let [policy (:write-policy target-row)
        owner (:owner-id target-row)
        approver-ids (set (:approver-ids target-row))
        admin? (or (tc/current-platform-tier?)
                   (tc/current-has-org-cap? :manage-grants))]
    (boolean
      (or (and uid (contains? approver-ids uid))
          (case policy
            ("owner") (or admin? (and uid owner (= uid owner)))
            ("admins") admin?
            ;; nil / "" / "open" → open (no write-policy restriction).
            (nil "" "open") true
            ;; anything else → DENY (hardening): write-policy is validated
            ;; to the known set at set time, so an unknown value here is
            ;; anomalous — fail closed rather than fall open.
            false)))))


(defbase approve-proposal!
  "Record the current principal's approval of proposal branch
   `source-branch-id` for merge into its base. WHO may approve = who may
   write the TARGET (base): its `:write-policy` (owner/admins/open) OR the
   target's explicit `:approver-ids` allow-list — else `:authz/forbidden`.
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
   approver with a `stale` flag. Pure read; org-scoped via the source
   branch id."
  [source-branch-id]
  (let [base-storage (branches/base-storage ctx)
        source-row (sp/read-entity base-storage :branch source-branch-id)
        target-id (:base-branch-id source-row)
        target-row (when target-id (sp/read-entity base-storage :branch target-id))
        required (or (:required-approvals target-row) 0)
        allow-self? (merge-policy/self-approval-allowed? target-row)
        author-id (:owner-id source-row)
        stamp (merge-policy/branch-content-stamp base-storage source-branch-id)
        approvals (sp/query-entities base-storage :branch-approval
                                     {:source-branch-id source-branch-id})
        have (merge-policy/count-valid-approvals base-storage source-branch-id
                                                 author-id allow-self?)]
    {:required required
     :have have
     :satisfied (>= have required)
     :approvers (mapv (fn [a]
                        {:approver-id (:approver-id a)
                         :stale (not= stamp (:content-stamp a))})
                      approvals)}))


(defbase add-branch-comment!
  "Record a review comment on proposal branch `source-branch-id` by the
   current principal (`\"anonymous\"` single-tenant). WHO may comment =
   whoever can resolve the branch (org-scoped upstream) — a comment is
   conversation, not a mutation of the branch. Returns the new row's id."
  [source-branch-id body]
  (cr/record-effect! :db)
  (let [author (or (:user-id tc/*current-principal*) "anonymous")
        row (sp/create-entity (branches/base-storage ctx) :branch-comment
                              {:source-branch-id source-branch-id
                               :author-id author
                               :body (str body)
                               :created-at (java.time.Instant/now)})]
    (str (:id row))))


(defbase list-branch-comments
  "Comments on proposal branch `source-branch-id`, oldest first —
   `[{:id :author-id :body :created-at} …]` (ids/timestamps stringified
   for the JSON wire)."
  [source-branch-id]
  (->> (sp/query-entities (branches/base-storage ctx) :branch-comment
                          {:source-branch-id source-branch-id})
       (sort-by :created-at)
       (mapv (fn [c]
               {:id (str (:id c))
                :author-id (:author-id c)
                :body (:body c)
                :created-at (str (:created-at c))}))))


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
