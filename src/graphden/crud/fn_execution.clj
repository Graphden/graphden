(ns graphden.crud.fn-execution
  "Parse / validate / apply stages for the `/api/execute` endpoint.

   An execution submits a fn-graph to the executor in a background
   future, returns the result inline if it finishes within
   `:timeout-ms` (default 10s, capped at 60s), else flips to async
   mode — persists a `:fn-execution` row with `:status :pending` and
   returns `{:status :pending :execution-id}` for polling.

   Persistence policy (see auto-persist matrix in docs/EXECUTION.md):

   | Condition                              | Persist? |
   |----------------------------------------|----------|
   | client `persist?=true`                 | yes      |
   | `declared-effects` ≠ #{} (audit trail) | yes      |
   | timeout fired (need polling target)    | yes      |
   | pure fn finished inline AND ¬persist?  | NO       |

   Cancellation is best-effort: POST /api/execute/:id/cancel sets the
   row's `:cancel-requested?` flag and calls `future-cancel`. The
   executor's `*cancel-check*` dyn-var is bound to a closure that
   reads the flag; on the next caller→callee transition it throws
   `InterruptedException` — the future catches and writes
   `:status :cancelled`. Long-running JDBC / blocking-IO inside a fn
   won't respond to interrupt without explicit `Statement.cancel()`
   (documented as soft).

   This namespace is the public API + orchestrator. Lookups
   (read-only DB navigation) live in `.lookup`; row writes + future
   plumbing + size caps live in `.persist`."
  (:require
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.crud.request :as request]
    [graphden.storage.protocol.core :as sp]))


;; Re-export: tests + the cancel endpoint look up futures by id.
(def lookup-future persist/lookup-future)


;; =============================================================================
;; Parse — JSON body → in-memory parsed map
;; =============================================================================

;; Validation (Stage 2) runs entirely in the graph — `:_execute-validation`
;; in `app/execution/fns.edn`, a `:cond` over per-guard rejection-builders
;; (`:_execute-no-fn-err`, `:_execute-fn-not-found-err`, …). No Clojure
;; mirror: the sole caller is POST /api/execute → `:execute`.


;; =============================================================================
;; Apply — submit future, deref with timeout, dispatch persist
;; =============================================================================

(defn- finalize-inline-outcome
  "Shared tail for the two inline-resolution arms (`:succeeded` /
   `:failed`) of `apply-execute`. Stamps secret-flow metadata, runs
   the redactor, logs effect drift, writes the row + unregisters the
   future when persisted, and attaches `:execution-id` when there is
   one. Pure on `base-outcome`; side effects scoped to `ctx`.

   `ctx` keys: `:storage` `:row` `:fn-name` `:declared-effects`
   `:runtime-effects`."
  [base-outcome {:keys [storage row fn-name declared-effects runtime-effects]}]
  (let [outcome (->> (cond-> base-outcome
                       runtime-effects (assoc :runtime-effects runtime-effects))
                     (persist/stamp-touched-secret fn-name)
                     (persist/redact-outcome fn-name))]
    (persist/log-effect-drift! (some-> row :id) declared-effects runtime-effects)
    (when row
      (persist/write-finished! storage (:id row) outcome)
      (persist/unregister-future! (:id row)))
    (cond-> outcome
      row (assoc :execution-id (str (:id row))))))


(defn apply-execute
  "Stage 3 — submit the future, deref with timeout. Returns one of:
     {:status :succeeded :result … :execution-id?}
     {:status :pending :execution-id …}
     {:status :failed :error … :error-data … :execution-id?}

   Persistence policy:
   - Pre-create row when we know we need polling capability
     (timeout > a few hundred ms is enough to justify the write —
     keeps the polling client able to find the row even if completion
     races our response).
   - For pure fast fns with `:persist? false`, finalise without ever
     writing a row."
  [ctx parsed]
  (let [storage (request/require-storage ctx)
        ;; Single round-trip for both `:id` and `:name`; the older
        ;; flow did `resolve-fn-id` + a separate `read-entity` to pull
        ;; the name.
        fn-row (lookup/resolve-fn storage parsed)
        fn-id (:id fn-row)
        fn-name (:name fn-row)
        fn-version-id (lookup/resolve-fn-version-id ctx fn-id)
        free-slots (lookup/free-arg-slot-map ctx fn-id)
        declared-eff (persist/declared-effects-of fn-name)
        need-persist? (or (:persist? parsed) (seq declared-eff))
        executor-args (into {}
                            (keep (fn [[k v]]
                                    (when (contains? free-slots (keyword k))
                                      [(keyword k)
                                       (if (persist/ref-arg? v)
                                         (persist/parse-ref-fn-id v)
                                         v)])))
                            (:args parsed))
        cancel-flag (atom false)
        pre-persisted? need-persist?
        row (when pre-persisted?
              (persist/create-pending-with-args!
                storage fn-version-id declared-eff
                (:user-id parsed) (:args parsed) free-slots))
        [fut trace] (persist/run-future ctx fn-id executor-args cancel-flag)
        _   (when row (persist/register-future! (:id row) fut cancel-flag))
        result (try (deref fut (:timeout-ms parsed) ::pending)
                    (catch java.util.concurrent.ExecutionException ee
                      {::ex (java.util.concurrent.ExecutionException/.getCause ee)}))
        ;; Closure (not eager) — only the inline-success/failure
        ;; branches snapshot the trace; timeout branches hand the atom
        ;; off to `record-completion!` which snapshots when the future
        ;; resolves.
        runtime-eff (fn [] (persist/snapshot-runtime-effects trace))]
    (cond
      ;; Timeout AND we haven't pre-persisted — persist lazily so the
      ;; client gets an id to poll. record-completion! tails the future
      ;; to update the row when it finally resolves.
      (and (= ::pending result) (not pre-persisted?))
      (let [r (persist/create-pending-with-args!
                storage fn-version-id declared-eff
                (:user-id parsed) (:args parsed) free-slots)]
        (persist/register-future! (:id r) fut cancel-flag)
        (persist/record-completion! storage (:id r) fn-name fut trace declared-eff)
        {:status :pending :execution-id (str (:id r))})

      ;; Timeout AND we pre-persisted — record-completion's tail-future
      ;; fills in :result; client polls our row.
      (= ::pending result)
      (do (persist/record-completion! storage (:id row) fn-name fut trace declared-eff)
          {:status :pending :execution-id (str (:id row))})

      ;; Inline failure — write outcome to the row synchronously (if
      ;; persisted) so the polling-by-id case is consistent. Redaction
      ;; lifts a tainted fn-def's :error/:error-data into a generic
      ;; hidden form so the secret doesn't leak via the exception
      ;; message (a string that may have wrapped the value).
      (and (map? result) (::ex result))
      (let [cause (::ex result)]
        (finalize-inline-outcome
          {:status :failed
           :error (or (ex-message cause) (str cause))
           :error-data (ex-data cause)}
          {:storage storage :row row :fn-name fn-name
           :declared-effects declared-eff :runtime-effects (runtime-eff)}))

      ;; Inline success — same: write synchronously so the GET endpoint
      ;; immediately returns :succeeded, no race window. Redaction
      ;; lifts a tainted fn-def's :result into nil + `:tainted? true`
      ;; so the JSON response carries metadata only.
      :else
      (-> (finalize-inline-outcome
            {:status :succeeded :result result}
            {:storage storage :row row :fn-name fn-name
             :declared-effects declared-eff :runtime-effects (runtime-eff)})
          (assoc :declared-effects declared-eff)))))


;; =============================================================================
;; GET /api/execute/:id — read a row + nested args
;; =============================================================================

(defn- args-for-execution
  [storage execution-id]
  (let [arg-rows (sp/query-entities storage :fn-execution-arg
                                    {:execution-id execution-id})
        ;; Push the arg-id set into storage as a SQL IN clause; the
        ;; alternative — full-table-scanning :fn-execution-arg-item and
        ;; grouping in memory — is fine for one row but quadratic when
        ;; GET /api/execute/:id is hit by the polling loop.
        item-rows (when (seq arg-rows)
                    (sp/query-entities storage :fn-execution-arg-item
                                       {:execution-arg-id (mapv :id arg-rows)}))
        items-by-arg (group-by :execution-arg-id item-rows)]
    (mapv (fn [a]
            (assoc a :items
                   (->> (get items-by-arg (:id a) [])
                        (sort-by :position)
                        vec)))
          arg-rows)))


(defn get-execution
  "Public handler for GET /api/execute/:id — returns the row + nested
   args list. nil when not found."
  [ctx execution-id]
  (let [storage (request/require-storage ctx)
        row (sp/read-entity storage :fn-execution execution-id)]
    (when row
      (assoc row :args (args-for-execution storage execution-id)))))


;; =============================================================================
;; GET /api/executions?fn-id=X — list recent runs for one fn (across
;; all of its versions). Used by the editor's history sidebar.
;; =============================================================================

(def ^:private default-history-limit 20)
(def ^:private max-history-limit 100)


(defn- clamp-history-limit
  [limit]
  (cond
    (or (nil? limit) (not (number? limit)) (< limit 1)) default-history-limit
    (> limit max-history-limit)                         max-history-limit
    :else                                               (long limit)))


(defn list-executions-for-fn-version
  "Return up to `limit` :fn-execution rows for the SPECIFIC
   `fn-version-id`, latest first. Drives the `⌛` fn-versions panel's
   per-version expand-to-see-runs UI."
  ([ctx fn-version-id]
   (list-executions-for-fn-version ctx fn-version-id nil))
  ([ctx fn-version-id limit]
   (let [storage (request/require-storage ctx)
         lim (clamp-history-limit limit)]
     ;; ORDER BY + LIMIT push into SQL (:fn-execution is non-versioned,
     ;; so the VersionedStorage decorator delegates opts straight to
     ;; base); :fn-version-id is a :ref (indexed), so Postgres filters
     ;; on the index and returns only the newest `lim` rows instead of
     ;; transferring + sorting a hot fn's whole run history.
     (vec (sp/query-entities storage :fn-execution
                             {:fn-version-id fn-version-id}
                             {:order-by [[:started-at :desc]] :limit lim})))))


(defn list-executions-for-fn
  "Return up to `limit` (default 20, cap 100) :fn-execution rows for
   logical `fn-id` AS IT RESOLVES ON THE CURRENT BRANCH — i.e. only
   runs of the version the editor would actually execute if the user
   clicked ▶ right now. Ordered by `:started-at` desc.

   This MATCHES the editor's mental model: branch X is a coherent
   functional view; executions of OTHER versions live behind the `⌛`
   history panel where each version row carries its own count + an
   expand-to-see-runs affordance. Pre-fix this returned `:fn-execution`
   rows for EVERY version of the fn-id regardless of branch, which
   conflated runs that may have had different arg shapes / behaviour
   into one list (and broke Repeat for runs whose arg-shape no longer
   matched the current version).

   Returns `[]` when the fn has no version visible on the current
   branch (e.g. fn never created here AND not inherited) or no runs
   yet."
  ([ctx fn-id]
   (list-executions-for-fn ctx fn-id nil))
  ([ctx fn-id limit]
   (if-let [version-id (lookup/resolve-fn-version-id ctx fn-id)]
     (list-executions-for-fn-version ctx version-id limit)
     [])))


;; =============================================================================
;; GET /api/executions parsing (C6 atoms)
;; =============================================================================

(defn query-param
  "Pull a named query-string parameter from `request`, tolerating both
   reitit's enriched shapes AND raw http-kit requests that haven't
   gone through the enrich middleware. Values from the raw fallback
   are URL-decoded so callers see the same shape regardless of which
   path produced them (reitit's enriched `:query-params` already
   decodes; the regex-extracted fallback didn't, which broke any
   handler whose param value carried percent-encoded JSON / spaces /
   non-ASCII — see `/partials/fn-picker-incompat?expected=%22int%22`
   parsing as a literal `%22int%22` instead of the JSON string
   `\"int\"`).

   Also surfaced as the `:query-param` base-fn in
   `web/crud/impls.clj`."
  [request param-name]
  (or (get-in request [:query-params param-name])
      (get-in request [:query-params (keyword param-name)])
      (some->> (:query-string request)
               (re-find (re-pattern (str "(?:^|&)" param-name "=([^&]+)")))
               second
               (#(java.net.URLDecoder/decode ^String % "UTF-8")))))


;; =============================================================================
;; POST /api/execute/:id/cancel
;; =============================================================================

(defn cancel-execution!
  "Mark the row's `:cancel-requested?` true, set the in-process
   cancel-flag (executor's `*cancel-check*` will observe), and
   `future-cancel`. Best-effort — JDBC / blocking-IO in flight won't
   respond to interrupt."
  [ctx execution-id]
  (let [storage (request/require-storage ctx)
        row (sp/read-entity storage :fn-execution execution-id)
        entry (persist/lookup-future execution-id)]
    (when row
      (sp/update-entity storage :fn-execution execution-id
                        {:cancel-requested? true})
      (when entry
        (reset! (:cancel-flag entry) true)
        (future-cancel (:future entry)))
      {:ok true :cancel-requested true})))


;; Server-side rendering for `/partials/execute-result?id=X` moved
;; to the graph (`:_er-*` chain in `app/execution/fns.edn`, entry
;; `:_er-body`). §3.3 fix — the previous Clojure-side walker
;; (render-scalar/list/record/tainted/error/succeeded-body helpers
;; + the public render-execute-result-hiccup) hid text labels,
;; cap constants, and shape-dispatch policy in private defns where
;; admins couldn't reach. Graph version exposes:
;; - `:_er-max-list-items` (was Clojure const `max-list-items`)
;; - `:_er-max-json-preview-bytes` (was `max-json-preview-bytes`)
;; - every label, every conditional, every per-item type-dispatch
;;   as named `_er-*` fn-defs.
