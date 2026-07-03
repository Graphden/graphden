(ns graphden.services.reconciler
  "Service-registry reconciler — diff `:service` rows (desired state)
   against `running` (actual state in-process), start missing /
   stop removed.

   Multi-pod-safe: every executor pod runs its own reconciler, but
   per-service Postgres advisory locks ensure only one pod actually
   runs each enabled service. Sibling pods receive
   `service:write:<id>` NOTIFY events on `graphden_events` and react
   within ~1s. Single-pod behaviour is identical (lock always
   succeeds; emitter is a no-op when ctx has no pg-pool).

   The `running` atom shape is
     `{service-id → {:fn-id … :stopper (fn []) :started-at Instant}}`.
   `:stopper` is the value the started fn returned. Web-server-shaped
   fns (http-kit) return a thunk that stops the listener; arbitrary
   fns may return anything — we only call `:stopper` if it's callable.

   The reconciler is intentionally side-effect-y but the policy
   decisions (which IDs to start/stop) are pure and tested via
   `diff-desired`."
  (:require
    [clojure.math]
    [clojure.set]
    [clojure.tools.logging :as log]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.compile.deps :as compile-deps]
    [graphden.storage.postgres.advisory-lock :as pg-lock]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br])
  (:import
    (java.sql
      Connection)))


;; =============================================================================
;; Production singleton — set on integrant init, drained on halt, read
;; by the /api/services/reconcile endpoint. Tests construct their own
;; atoms and pass to reconcile-once! / stop-all! directly; this
;; defonce is purely the production handle (mirrors the
;; futures-registry pattern in `fn-execution.persist`).
;; =============================================================================

(defonce running
  (atom {}))


;; =============================================================================
;; Pure: compute the start/stop set from desired (DB rows) vs running.
;; =============================================================================

(defn diff-desired
  "Pure: return `{:to-start [service-id …] :to-stop [service-id …]}`
   given the set of enabled service IDs (from DB) and the set of
   currently-running service IDs (from the in-process atom).

   `to-start` = enabled ∖ running (rows that exist + enabled but
   we haven't started yet).
   `to-stop` = running ∖ enabled (rows we're running that have been
   disabled, deleted, or never existed). Disabled rows are stopped
   alongside deletions — uniform shutdown path."
  [enabled-ids running-ids]
  (let [enabled-set (set enabled-ids)
        running-set (set running-ids)]
    {:to-start (vec (clojure.set/difference enabled-set running-set))
     :to-stop  (vec (clojure.set/difference running-set enabled-set))}))


;; =============================================================================
;; Start / stop a single service.
;;
;; Services don't carry args of their own — the fn pointed at by
;; `:fn-id` is expected to have ZERO free arguments (every slot bound
;; via fn-defs / bindings). To run the same impl with different
;; parameters, create a derived fn-def that binds the slots
;; differently and declare a :service for THAT fn. See
;; `schema/services/schema.clj` for the full rationale.
;; =============================================================================

(def ^:private start-error
  "Sentinel returned by `start-service-once!` when the start THREW. Distinct
   from a `nil` return, which is a legitimate stopper for a fire-and-forget
   service — treating those two the same made a nil-returning service look
   like a failure and get pointlessly retried + stamped `:start-failed-at`."
  ::start-error)


(defn- start-service-once!
  "One attempt: invoke the fn via the executor. Returns the stopper (the fn's
   return value — which MAY legitimately be nil for a fire-and-forget
   service) on success, or the `start-error` sentinel on exception. The fn is
   called synchronously — any startup throw (port-in-use, etc.) is caught.
   Long-running fns block INSIDE the impl, not at this call site (web-server
   returns a stopper thunk immediately)."
  [ctx fn-id args svc-id]
  (try
    (log/info "service start" svc-id "fn-id" fn-id)
    (cr/execute ctx fn-id args)
    (catch Exception e
      (log/error e "service start failed" svc-id "fn-id" fn-id)
      start-error)))


;; Supervisor retry tuning. Bounded to keep reconcile-once! responsive
;; — even max retries finishes inside ~7s (1+2+4). Currently catches
;; STARTUP failures only (e.g. port-in-use); runtime crashes aren't
;; detected (no healthcheck) so `:always` ≡ `:on-failure` in
;; behaviour. Tunable per-call so tests can pin to zero-backoff.
(def ^:private default-max-retries 3)
(def ^:private default-backoff-ms 1000)


(defn- should-retry?
  "Whether `policy` wants another start attempt after a failure."
  [policy]
  ;; Both :always and :on-failure retry on start-exception. :never
  ;; gives up after the first attempt. Future phases distinguish
  ;; :always (also restart on clean stop) once we have a runtime
  ;; watcher.
  (contains? #{:always :on-failure} policy))


(defn start-service!
  "Run the service's fn through the supervisor: try `start-service-once!`,
   on nil-stopper (start failure) sleep + retry up to N times per the
   row's `:restart-policy`. Returns the `running`-atom entry shape:
   `{:fn-id :restart-policy :stopper :started-at :start-attempts}`.

   `:start-attempts` records how many tries it took (1 = success on
   first attempt). When all retries are exhausted, `:stopper` is nil
   and `:start-failed-at` is set — reconcile keeps the entry so we
   don't busy-loop trying to start again on every reconcile pass.

   The fn is invoked with an empty args map — services require the
   target fn to have no free args (enforced at service-create time)."
  ([ctx svc] (start-service! ctx svc {}))
  ([ctx svc {:keys [max-retries backoff-ms]
             :or {max-retries default-max-retries
                  backoff-ms default-backoff-ms}}]
   (let [fn-id (:fn-id svc)
         args {}
         policy (:restart-policy svc)
         max-attempts (if (should-retry? policy) (inc max-retries) 1)]
     (loop [attempt 1]
       (let [stopper (start-service-once! ctx fn-id args (:id svc))]
         (cond
           ;; Success — the fn ran without throwing. Its return is the stopper,
           ;; which may legitimately be nil (fire-and-forget); `stop-service!`
           ;; tolerates a nil stopper, so only a THROWN start (the sentinel)
           ;; counts as failure.
           (not= stopper start-error)
           {:fn-id fn-id
            :restart-policy policy
            :stopper stopper
            :started-at (java.time.Instant/now)
            :start-attempts attempt}

           ;; Exhausted retries — give up, record the give-up time so
           ;; admin can tell from the running map that we're stuck.
           (>= attempt max-attempts)
           (do (when (> attempt 1)
                 (log/error "service start exhausted retries"
                            {:service-id (:id svc) :attempts attempt}))
               {:fn-id fn-id
                :restart-policy policy
                :stopper nil
                :started-at (java.time.Instant/now)
                :start-attempts attempt
                :start-failed-at (java.time.Instant/now)})

           ;; Retry — exponential backoff (1s, 2s, 4s, …).
           :else
           (let [delay-ms (* (long backoff-ms) (long (clojure.math/pow 2 (dec attempt))))]
             (log/warn "service start failed, retrying"
                       {:service-id (:id svc)
                        :attempt attempt
                        :next-delay-ms delay-ms
                        :policy policy})
             (Thread/sleep delay-ms)
             (recur (inc attempt)))))))))


(defn stop-service!
  "Best-effort stop: call the stopper if it's a fn (http-kit and
   similar return a callable). Other return values are logged and
   dropped — the service won't have an in-process effect to undo."
  [service-id {:keys [stopper] :as entry}]
  (try
    (cond
      (fn? stopper)
      (do (log/info "service stop" service-id)
          (stopper))

      (nil? stopper)
      (log/info "service stop" service-id "(no stopper — start had failed)")

      :else
      (log/warn "service stop" service-id
                "could not stop — fn returned non-callable"
                (type stopper)))
    (catch Exception e
      (log/error e "service stop threw" service-id)))
  entry)


;; =============================================================================
;; One reconciliation pass — read desired, compute diff, apply.
;; =============================================================================

(defn- lock-conn-from-ctx
  "Pull the service-locks Connection off the executor context.
   When the ctx wasn't built with a `:service-locks-connection` (test
   contexts using an in-memory storage), returns nil — callers
   degrade gracefully (single-pod path: every lock attempt
   `succeeds` because there's no contention)."
  ^Connection [ctx]
  (:service-locks-connection ctx))


(defn- ctx-for-service
  "Pick the ExecutionContext to start `svc` in. When a branch-router
   is registered (`branch-router/set-active-router!` was called by
   `:exec/branch-router` at init), look up the per-branch ctx for
   the service's `:branch-id`. Falls back to the reconciler's base
   `ctx` when no router or no `:branch-id` is set — both apply to
   tests that bypass the router, and to legacy rows that pre-date
   the field.

   Lazy: `br/ctx-for` builds the per-branch ctx on first request
   (compile + cache), so a freshly-created branch with services
   pays the compile cost on first reconcile."
  [base-ctx svc]
  (or (when-let [router (br/current-router)]
        (when-let [branch-id (:branch-id svc)]
          (try
            (br/ctx-for router branch-id)
            (catch Exception e
              (log/warn e "per-branch ctx build failed — falling back to base"
                        {:service-id (:id svc) :branch-id branch-id})
              nil))))
      base-ctx))


(defn reconcile-once!
  "One pass: read enabled `:service` rows, compute diff vs
   `running-atom`'s contents, start missing + stop removed. Mutates
   `running-atom` in place.

   Each new service start is gated on
   `pg_try_advisory_lock(service-id-hash)` on the pod's dedicated
   lock connection (`:service-locks-connection` on ctx). When the
   lock is unavailable — another pod owns this service — we record a
   `:not-our-lock` entry in `running-atom` so the next reconcile pass
   doesn't re-try until a NOTIFY tells us the situation changed.

   `start-opts` (optional) is passed straight to `start-service!`,
   e.g. `{:max-retries 0 :backoff-ms 0}` keeps tests responsive when
   they intentionally cause start failures.

   Per-branch services: each row carries `:branch-id`. The service
   is started against THAT branch's ExecutionContext (looked up via
   `branch-router/ctx-for`), so the same fn-id can run with branch-
   specific bindings (dev port, prod port). Nil `:branch-id` falls
   back to the reconciler's base ctx — matches pre-Phase-2 rows.

   Returns `{:started [service-id …] :stopped [service-id …]
              :not-our-lock [service-id …]}` for logging / tests."
  ([ctx running-atom]
   (reconcile-once! ctx running-atom {}))
  ([ctx running-atom start-opts]
   (let [storage (:storage ctx)
         lock-conn (lock-conn-from-ctx ctx)
         enabled-services (vec (sp/query-entities storage :service {:enabled? true}))
         enabled-by-id    (into {} (map (juxt :id identity)) enabled-services)
         {:keys [to-start to-stop]} (diff-desired (keys enabled-by-id)
                                                  (keys @running-atom))
         not-our-lock (atom [])]
     (doseq [sid to-stop]
       (let [entry (get @running-atom sid)]
         (when (and entry (not= ::not-our-lock entry)) (stop-service! sid entry))
         (when (and lock-conn entry (not= ::not-our-lock entry))
           (try (pg-lock/release-lock! lock-conn sid)
                (catch Exception e
                  (log/warn e "advisory lock release failed — continuing"
                            {:service-id sid}))))
         (swap! running-atom dissoc sid)))
     (doseq [sid to-start]
       (let [svc (get enabled-by-id sid)
             svc-ctx (ctx-for-service ctx svc)
             acquired? (if lock-conn
                         (try (pg-lock/try-lock! lock-conn sid)
                              (catch Exception e
                                (log/warn e "advisory try-lock failed — treating as not-owned"
                                          {:service-id sid})
                                false))
                         true)]
         (cond
           acquired?
           (let [entry (start-service! svc-ctx svc start-opts)
                 ;; Record :branch-id on the entry so stop time can
                 ;; tell which branch this run belonged to (for
                 ;; observability + future per-branch reconcile).
                 entry' (cond-> entry
                          (:branch-id svc) (assoc :branch-id (:branch-id svc)))]
             (swap! running-atom assoc sid entry'))

           :else
           (do (swap! not-our-lock conj sid)
               (swap! running-atom assoc sid ::not-our-lock)))))
     {:started (vec (remove (set @not-our-lock) to-start))
      :stopped to-stop
      :not-our-lock @not-our-lock})))


(defn restart-services-on-branch!
  "Stop every running service whose entry was started against
   `target-branch-id`, then call `reconcile-once!` so the still-
   enabled rows pick up fresh per-branch ExecutionContexts. Wired
   into the merge endpoint so cron loops (which hold their fn-graph
   closures by reference) actually pick up post-merge fn-versions —
   `branch-router/invalidate!` clears the per-branch ctx, but the
   running closures don't observe that on their own.

   `running-atom` carries `:branch-id` on each entry (set by
   `reconcile-once!` from the row's `:branch-id`). Entries without
   a recorded branch are LEFT ALONE — they were started under the
   legacy no-branch-id path and the per-branch invalidate isn't
   relevant to them.

   Returns the `reconcile-once!` result map (`:started :stopped
   :not-our-lock`) so the caller can log / observe."
  [ctx running-atom target-branch-id]
  (let [lock-conn (lock-conn-from-ctx ctx)
        to-restart (->> @running-atom
                        (filter (fn [[_ entry]]
                                  (and (map? entry)
                                       (= target-branch-id (:branch-id entry)))))
                        (mapv first))]
    (doseq [sid to-restart]
      (let [entry (get @running-atom sid)]
        (when entry (stop-service! sid entry))
        (when lock-conn
          (try (pg-lock/release-lock! lock-conn sid)
               (catch Exception e
                 (log/warn e "advisory lock release failed during branch restart"
                           {:service-id sid :branch-id target-branch-id}))))
        (swap! running-atom dissoc sid)))
    (when (seq to-restart)
      (log/info "Stopping" (count to-restart) "services on branch for restart"
                {:branch-id target-branch-id :service-ids to-restart}))
    ;; reconcile-once! sees the just-stopped rows as to-start (still
    ;; enabled in DB) and restarts them with `ctx-for-service` →
    ;; fresh per-branch ctx from `branch-router/ctx-for`.
    (reconcile-once! ctx running-atom)))


(defn restart-services-depending-on!
  "Stop every running service whose fn-id appears in the
   compile-deps reverse-dep closure of `changed-fn-ids`, then call
   `reconcile-once!` so the still-enabled rows pick up fresh
   per-branch ExecutionContexts. Covers the gap where an admin
   edits a fn-graph node used INSIDE a service's closure — HTTP
   handlers re-read the registry lazily on the next request, but
   cron loops hold the closure by reference and would keep firing
   the pre-edit graph forever.

   `changed-fn-ids` — the set of fn-ids the CRUD invalidate just
   touched. Looks them up against `:compile-deps` on `ctx` to
   compute the blast radius; services whose fn-id is in that
   radius get stopped + restarted.

   Returns the `reconcile-once!` result (`:started :stopped
   :not-our-lock`) so the caller can log / observe. No-op when
   compile-deps isn't populated yet (cold start) or when no running
   service is affected."
  [ctx running-atom changed-fn-ids]
  ;; `:compile-deps` now holds `{:forward-deps :reverse-deps}` since
  ;; the incremental-update refactor; only the reverse side matters
  ;; for the service-restart blast walk.
  (let [reverse-deps (some-> (:compile-deps ctx) deref :reverse-deps)]
    (if (or (nil? reverse-deps) (empty? changed-fn-ids))
      {:started [] :stopped [] :not-our-lock []}
      (let [blast (compile-deps/transitive-blast reverse-deps changed-fn-ids)
            lock-conn (lock-conn-from-ctx ctx)
            to-restart (->> @running-atom
                            (filter (fn [[_ entry]]
                                      (and (map? entry)
                                           (contains? blast (:fn-id entry)))))
                            (mapv first))]
        (doseq [sid to-restart]
          (let [entry (get @running-atom sid)]
            (when entry (stop-service! sid entry))
            (when lock-conn
              (try (pg-lock/release-lock! lock-conn sid)
                   (catch Exception e
                     (log/warn e "advisory lock release failed during fn-edit restart"
                               {:service-id sid}))))
            (swap! running-atom dissoc sid)))
        (when (seq to-restart)
          (log/info "Stopping" (count to-restart)
                    "services whose closure depends on edited fn"
                    {:changed-fn-ids changed-fn-ids
                     :service-ids to-restart}))
        (reconcile-once! ctx running-atom)))))


(defn stop-all!
  "Shutdown helper — drains `running-atom` by calling every stopper,
   clears the atom. Called from the integrant `halt-key!`.

   `not-our-lock` placeholder entries are skipped (no stopper to
   call). Advisory locks held by THIS pod are released by closing
   the lock connection at the `:db/service-locks` halt-key, so we
   don't need to release per-service here."
  [running-atom]
  (doseq [[sid entry] @running-atom]
    (when (not= ::not-our-lock entry)
      (stop-service! sid entry)))
  (reset! running-atom {}))
