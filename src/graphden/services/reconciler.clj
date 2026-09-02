(ns graphden.services.reconciler
  "Service-registry reconciler — diff `:service` rows (desired state)
   against `running` (actual state in-process), start missing /
   stop removed.

   Multi-pod-safe: every executor pod runs its own reconciler, and the
   service's `:cardinality` decides how many pods run it:

   - `:singleton` — a per-service Postgres advisory lock ensures only
     one pod runs it. Cron / `:schedule` loops need this; running them
     everywhere would fire each tick N times.
   - `:per-pod` — no lock, every pod runs its own copy. Listeners
     (`:http-server`) need this: behind a load balancer each pod must
     bind its own port.

   Sibling pods receive `service:write:<id>` NOTIFY events on
   `graphden_events` and react within ~1s. Single-pod behaviour is
   identical either way (lock always succeeds; emitter is a no-op when
   ctx has no pg-pool).

   The `running` atom shape is
     `{service-id → {:fn-id … :stopper (fn []) :started-at Instant
                     :cardinality … :locked? bool}}`.
   `:locked?` records whether THIS pod holds the advisory lock, so stop
   releases only locks it actually took.
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
    [graphden.schema.services.schema :as svc-schema]
    [graphden.storage.postgres.advisory-lock :as pg-lock]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.tenancy.context :as tctx]
    [graphden.versioning.storage.resolution :as res])
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


;; Serialises `reconcile-once!` — it's called from BOTH the HTTP
;; `/api/services/reconcile` handler thread AND the NOTIFY listener
;; thread, and it mutates `running` + acquires advisory locks on the
;; (non-thread-safe) lock connection. Concurrent passes could compute
;; the same `to-start` diff and start a service twice, orphaning one
;; future. The monitor is re-entrant, so restart-* helpers that call
;; `reconcile-once!` while holding it don't deadlock.
(defonce ^:private reconcile-monitor (Object.))


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
   returns a stopper thunk immediately).

   The `execute` runs inside `cr/run-service-scoped`: for a tenant service
   (`:org-id` set) the tenancy addon's seam binds the org's effect gate +
   org context, so a persistent service is sandboxed exactly like a
   request-path execute — and the future conveyance carries that gate into
   the worker thread the service spawns. Platform services (no `:org-id`, and
   every service in single-tenant mode) run unrestricted. Keeping the seam
   OUTSIDE the try means a startup-time plan violation (a forbidden effect
   fired synchronously during start) surfaces here as `start-error` — the
   service simply fails to start rather than running unsandboxed."
  [ctx svc args]
  (let [fn-id (:fn-id svc)
        svc-id (:id svc)]
    (try
      (log/info "service start" svc-id "fn-id" fn-id)
      (cr/run-service-scoped svc (fn [] (cr/execute ctx fn-id args)))
      (catch Exception e
        (log/error e "service start failed" svc-id "fn-id" fn-id)
        start-error))))


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
       (let [stopper (start-service-once! ctx svc args)]
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
   Prefers the reconnecting holder (`:service-locks-holder`, production);
   falls back to a raw `:service-locks-connection` (test contexts). When
   neither is present (in-memory storage), returns nil — callers degrade
   gracefully (single-pod path: every lock attempt `succeeds` because
   there's no contention)."
  ^Connection [ctx]
  (if-let [holder (:service-locks-holder ctx)]
    (pg-lock/holder-conn holder)
    (:service-locks-connection ctx)))


(defn- reassert-lock-ownership!
  "Called after the lock connection reconnected: the new Postgres session
   holds NONE of the locks this pod took, so re-take them. For each running
   `:singleton` service we believed we owned (`:locked?`), `try-lock!` on
   the fresh connection:

   - succeeds → nobody grabbed it during the outage; keep running.
   - fails    → a sibling won it while we were disconnected; STOP the local
                copy and drop it. That sibling is now the single owner —
                exactly the double-run this whole mechanism prevents.

   `:per-pod` entries never took a lock, so they're skipped. A `:pool`
   entry re-takes the SAME slot it held (`:pool-slot`); if a sibling grabbed
   that slot during the outage it stops locally, and the diff below re-fills
   any now-free slot on a fresh acquire."
  [lock-conn running-atom]
  (doseq [[sid entry] @running-atom
          :when (and (map? entry) (:locked? entry))]
    (let [slot (:pool-slot entry 0)
          reacquired? (try (pg-lock/try-acquire-slot! lock-conn sid slot)
                           (catch Exception e
                             (log/warn e "re-acquire try-lock failed after reconnect"
                                       {:service-id sid :slot slot})
                             false))]
      (when-not reacquired?
        (log/warn "lost service ownership during lock-conn outage — stopping local copy"
                  {:service-id sid})
        (stop-service! sid entry)
        (swap! running-atom dissoc sid)))))


(defn- stop-and-forget!
  "Stop `sid`, release its advisory lock if THIS pod took one, and drop
   it from `running-atom`. The three restart/stop paths all need exactly
   this, and all three used to release unconditionally — which asks
   Postgres to unlock a key the session never held every time a
   `:per-pod` service stops.

   `::not-our-lock` placeholders have nothing to stop and no lock to
   release."
  [lock-conn running-atom sid]
  (let [entry (get @running-atom sid)]
    (when (map? entry)
      (stop-service! sid entry)
      (when (and lock-conn (:locked? entry))
        (try (pg-lock/release-slot! lock-conn sid (:pool-slot entry 0))
             (catch Exception e
               (log/warn e "advisory lock release failed — continuing"
                         {:service-id sid})))))
    (swap! running-atom dissoc sid)))


(defn- acquire-pool-slot!
  "For a lock-gated service that may run on up to `n` pods, try slots
   0..n-1 on this pod's lock connection and return the FIRST slot acquired,
   or nil when all n are held by siblings. A `:singleton` is n=1 (slot 0).
   A throw on any slot is treated as not-owned for that slot (logged) and
   the search moves on."
  [lock-conn service-id ^long n]
  (loop [slot 0]
    (when (< slot n)
      (if (try (pg-lock/try-acquire-slot! lock-conn service-id slot)
               (catch Exception e
                 (log/warn e "advisory try-lock failed — treating slot as not-owned"
                           {:service-id service-id :slot slot})
                 false))
        slot
        (recur (inc slot))))))


(defn- effective-branch-id
  "The branch `svc` runs on: its own `:branch-id`, or the active
   router's default branch when the row pre-dates the field / the
   admin left the branch unset. Normalizing here — the single point
   desired-state rows enter the reconciler — makes a legacy nil-branch
   row indistinguishable from an explicit default-branch row, so drift
   detection and `restart-services-on-branch!` treat them alike
   (before this, a nil-branch service silently missed the post-merge
   restart of the default branch). Nil only when no router is
   registered (tests that bypass branch routing)."
  [svc]
  (or (:branch-id svc)
      (:default-branch-id (br/current-router))))


(defn- ctx-for-service
  "Pick the ExecutionContext to start `svc` in. When a branch-router
   is registered (`branch-router/set-active-router!` was called by
   `:exec/branch-router` at init), look up the per-branch ctx for
   the service's effective branch (`effective-branch-id` — the row's
   `:branch-id`, defaulting to the router's default branch). Falls
   back to the reconciler's base `ctx` only when no router is
   registered — tests that bypass the router. For a default-branch
   service `br/ctx-for` returns the router's seeded entry, which IS
   the base ctx, so the legacy nil-branch behavior is preserved
   exactly.

   Lazy: `br/ctx-for` builds the per-branch ctx on first request
   (compile + cache), so a freshly-created branch with services
   pays the compile cost on first reconcile."
  [base-ctx svc]
  (if-let [branch-id (and (br/current-router) (effective-branch-id svc))]
    (try
      (br/ctx-for (br/current-router) branch-id)
      (catch Exception e
        ;; Do NOT fall back to base: the service declared THAT
        ;; branch, and running it against base silently executes a
        ;; different branch's fn versions. Skip this pass — the
        ;; level-triggered periodic tick retries the start once the
        ;; branch ctx builds (same crash-failover semantics services
        ;; already rely on).
        (log/error e "per-branch ctx build failed — service start SKIPPED this pass (will retry on next tick)"
                   {:service-id (:id svc) :branch-id branch-id})
        ::branch-ctx-failed))
    base-ctx))


(defn service-in-shard?
  "Whether THIS pod's reconciler should run `svc`, given the pod's
   `:executor-orgs` shard (task #6 / FLEET_RFC §7.1). A PLATFORM service (no
   `:org-id` — web-server, vault, cron; seeded at boot, never org-stamped) runs
   on EVERY pod. A TENANT service (`:org-id` set) runs ONLY on a pod whose shard
   EXPLICITLY names its org — NOT on a compile-all (`nil` shard) pod.

   Without this, a shared pod (which compiles every org's graph) would start a
   dedicated tenant's service on shared, cgroup-unbounded hardware — defeating
   the whole point of the dedicated shard. `executor-orgs` is nil (self-hosted /
   shared default), a set, or a hash-shard fn; a set / fn is called as a
   predicate, and nil short-circuits tenant services to false."
  [executor-orgs svc]
  (if-let [org (:org-id svc)]
    (boolean (and executor-orgs (executor-orgs org)))
    true))


(declare ^:private reconcile-once!*)


(defn reconcile-once!
  "One pass: read enabled `:service` rows, compute diff vs
   `running-atom`'s contents, start missing + stop removed. Mutates
   `running-atom` in place.

   A lock-gated service's start is gated on `pg_try_advisory_lock`
   on the pod's dedicated lock connection (via `:service-locks-holder`
   on ctx): a `:singleton` races for slot 0, a `:pool` races for the
   first free of its N slots. When every slot is held by siblings we
   record a `::not-our-lock` placeholder. That placeholder is TRANSIENT
   — the top of each pass drops it, so the service is re-attempted every
   reconcile; a sibling whose owner crashed (auto-releasing the slot,
   with no NOTIFY) re-acquires it on the next periodic tick. A `:per-pod`
   service skips the lock and always starts here.

   Before the diff, `pg-lock/ensure-live!` heals a dropped lock
   connection. A drop released every advisory lock the pod held, so a
   reconnect triggers `reassert-lock-ownership!` — each `:singleton`
   we were running re-takes its lock, and any a sibling stole during the
   outage stops locally. Without this a `:per-pod`-vs-`:singleton` pair
   could double-run one service until a `:service` edit happened by.

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
   ;; The reconciler is a PLATFORM actor — pin the pass to the platform
   ;; org regardless of the CALLER's thread bindings. The edge-triggered
   ;; pass fires from CRUD writes on an abort-shield thread that CONVEYS
   ;; the requester's `*current-org*`; under a TENANT binding the
   ;; `:service` read below returns [] (`:service` is tenant-forbidden in
   ;; OrgScopedStorage), so desired = ∅ and the pass STOPPED every
   ;; running service — on prod (2026-08-05) a demo org's fn create shut
   ;; down the platform web-server until the next periodic tick (which
   ;; runs unbound = platform) restarted it: a ~15 s total outage any
   ;; tenant write could trigger. Binding here makes every trigger path
   ;; (edge, NOTIFY, tick, CRaC resume) behave like the tick.
   (tctx/with-org tctx/public-org
                  (reconcile-once!* ctx running-atom start-opts))))


(defn- reconcile-once!*
  [ctx running-atom start-opts]
  (locking reconcile-monitor
    ;; First, heal the lock connection if it dropped. A dead connection
    ;; released every advisory lock this pod held, so on reconnect we must
    ;; re-assert ownership BEFORE the diff below trusts `:locked?` entries.
    (when-let [holder (:service-locks-holder ctx)]
      (when (pg-lock/ensure-live! holder)
        (reassert-lock-ownership! (pg-lock/holder-conn holder) running-atom)))
    ;; Drop stale ::not-our-lock placeholders so every lock-gated service
    ;; we don't currently own is RE-ATTEMPTED this pass. This is what makes
    ;; the periodic reconcile tick heal a crashed owner: the crash released
    ;; its advisory slot (no NOTIFY), and here a sibling re-acquires it. A
    ;; service still fully held by siblings is simply re-marked ::not-our-lock
    ;; below, so the placeholder is transient, recomputed each pass.
    (swap! running-atom (fn [m] (into {} (remove (fn [[_ v]] (contains? #{::not-our-lock ::start-failed} v))) m)))
    (let [storage (:storage ctx)
          lock-conn (lock-conn-from-ctx ctx)
          ;; Shard filter (task #6): drop tenant services whose org this pod
          ;; doesn't serve, so a dedicated tenant's services run only on its
          ;; own cgroup-limited pod, never on a shared compile-all pod.
          enabled-services (filterv #(service-in-shard? (:executor-orgs ctx) %)
                                    (sp/query-entities storage :service {:enabled? true}))
          enabled-by-id    (into {} (map (juxt :id identity)) enabled-services)
          running-now          @running-atom
          {:keys [to-start to-stop]} (diff-desired (keys enabled-by-id)
                                                   (keys running-now))
          ;; Config drift: a service that is enabled AND already running
          ;; but whose running entry no longer matches the desired row —
          ;; its :fn-id / :branch-id / :restart-policy was edited via a
          ;; `:service` PUT. The membership diff misses these (the id is in
          ;; both sets), so the edit was silently ignored until a pod
          ;; restart. Stop+restart them to pick it up. `map?` skips the
          ;; ::not-our-lock placeholder.
          drifted (filterv (fn [sid]
                             (let [entry (get running-now sid)
                                   svc (get enabled-by-id sid)]
                               (and (map? entry)
                                    (or (not= (:fn-id entry) (:fn-id svc))
                                        (not= (:branch-id entry)
                                              (effective-branch-id svc))
                                        (not= (:restart-policy entry)
                                              (:restart-policy svc))
                                        (not= (:cardinality entry)
                                              (svc-schema/service-cardinality svc))
                                        ;; pool-size edit (e.g. 3→2): the pod on
                                        ;; the now-out-of-range slot restarts and
                                        ;; fails to re-acquire, shrinking the pool.
                                        (not= (:pool-size entry)
                                              (svc-schema/effective-pool-size svc))))))
                           (keys enabled-by-id))
          to-stop  (vec (concat to-stop drifted))
          to-start (vec (concat to-start drifted))
          not-our-lock (atom [])]
      (doseq [sid to-stop]
        (stop-and-forget! lock-conn running-atom sid))
      (doseq [sid to-start]
        (let [svc (get enabled-by-id sid)
              svc-ctx (ctx-for-service ctx svc)
              branch-ctx-failed? (= ::branch-ctx-failed svc-ctx)
              ;; `:per-pod` services (listeners behind a load balancer) skip
              ;; the lock entirely — every pod runs its own (pool-size nil).
              ;; `:singleton` races for slot 0 (pool-size 1); `:pool` races for
              ;; the first free of its N slots. `slot` = the acquired slot, or
              ;; nil when a sibling holds every slot (or no lock connection).
              pool-size (svc-schema/effective-pool-size svc)
              ;; via the schema-layer resolver (its docstring is the
              ;; contract) — not a local (some? pool-size) re-derive.
              lock-gated? (svc-schema/lock-gated? svc)
              slot (when (and lock-gated? (some? lock-conn))
                     (acquire-pool-slot! lock-conn sid pool-size))
              acquired? (or (not lock-gated?) (some? slot) (nil? lock-conn))]
          (cond
            ;; Branch ctx unavailable — error already logged; leave
            ;; the row un-started so the periodic tick retries. Any
            ;; acquired slot is NOT held for a start we didn't make.
            branch-ctx-failed?
            (do (swap! not-our-lock conj sid)
                (when (some? slot)
                  (try (pg-lock/release-slot! lock-conn sid slot)
                       (catch Exception e
                         (log/warn e "advisory lock release failed — continuing"
                                   {:service-id sid :slot slot})))))

            acquired?
            (let [entry (start-service! svc-ctx svc start-opts)]
              (if (:start-failed-at entry)
                ;; Start FAILED (retries exhausted, e.g. port taken /
                ;; missing file on THIS pod). Don't HOLD the advisory
                ;; slot — a healthy sibling must be able to fail over
                ;; (S1) — and don't record the give-up entry as
                ;; "running" forever, which stalled reconvergence (S2:
                ;; diff-desired saw the id as running). Mark it the
                ;; transient `::start-failed` that the top-of-pass drop
                ;; clears, so the next tick RE-ATTEMPTS (the tick is
                ;; retry-free → one cheap attempt). This is the
                ;; reconvergence SERVICES.md promises.
                (do (when (some? slot)
                      (try (pg-lock/release-slot! lock-conn sid slot)
                           (catch Exception e
                             (log/warn e "advisory lock release after start-failure failed"
                                       {:service-id sid :slot slot}))))
                    (swap! not-our-lock conj sid)
                    (swap! running-atom assoc sid ::start-failed))
                ;; Record the EFFECTIVE :branch-id (row's, or the router's
                ;; default for legacy nil-branch rows) so stop time and
                ;; `restart-services-on-branch!` can tell which branch this
                ;; run belonged to. :cardinality mirrors the row so drift
                ;; detection sees an admin flipping it. :locked? = THIS pod
                ;; holds a slot; :pool-slot = which one (for release +
                ;; reassert).
                (let [eff-branch (effective-branch-id svc)
                      entry' (cond-> (assoc entry
                                            :cardinality (svc-schema/service-cardinality svc)
                                            :pool-size pool-size
                                            :locked? (some? slot)
                                            :pool-slot slot)
                               eff-branch (assoc :branch-id eff-branch))]
                  (swap! running-atom assoc sid entry'))))

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
   `reconcile-once!` from the row's EFFECTIVE branch — a nil-branch
   row is normalized to the router's default branch, so legacy rows
   participate in a default-branch restart instead of silently
   running stale closures). Entries without a recorded branch only
   occur when no router is registered (tests that bypass branch
   routing) and are LEFT ALONE.

   Returns the `reconcile-once!` result map (`:started :stopped
   :not-our-lock`) so the caller can log / observe."
  [ctx running-atom target-branch-id]
  ;; The stop→release-lock→dissoc phase mutates `running` and touches the
  ;; NON-thread-safe advisory-lock connection, so it MUST hold
  ;; `reconcile-monitor` — otherwise a concurrent `reconcile-once!` (fired
  ;; from the NOTIFY-listener thread on a `:service` event) interleaves and
  ;; two threads use the lock connection at once. The monitor is reentrant,
  ;; so the trailing `reconcile-once!` (which self-locks) doesn't deadlock —
  ;; exactly what the `reconcile-monitor` docstring anticipates.
  (locking reconcile-monitor
    (let [lock-conn (lock-conn-from-ctx ctx)
          to-restart (->> @running-atom
                          (filter (fn [[_ entry]]
                                    (and (map? entry)
                                         (= target-branch-id (:branch-id entry)))))
                          (mapv first))]
      (doseq [sid to-restart]
        (stop-and-forget! lock-conn running-atom sid))
      (when (seq to-restart)
        (log/info "Stopping" (count to-restart) "services on branch for restart"
                  {:branch-id target-branch-id :service-ids to-restart}))
      ;; reconcile-once! sees the just-stopped rows as to-start (still
      ;; enabled in DB) and restarts them with `ctx-for-service` →
      ;; fresh per-branch ctx from `branch-router/ctx-for`.
      (reconcile-once! ctx running-atom))))


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

   `edit-branch-id` (4-arity) scopes the restart to services whose
   branch actually SEES the edit: fn-ids are deterministic per
   `(namespace, name)`, so the same fn-id runs on many branches with
   different version data — restarting a sibling branch whose view
   didn't change is pure churn. An entry restarts when the edited
   branch is on its branch CHAIN (itself or an ancestor); entries
   without a recorded branch and callers that can't name the edit
   branch (3-arity) restart conservatively. A MERGE is the same
   shape: the merge endpoint seeds this with `merge-affected-fn-ids`
   and the target branch (`restart-services-on-branch!` — every
   service on the branch — is for branch delete and the unseeded
   cross-pod full-clear only; on the cloud every org's `main` is the
   shared main, so a branch-wide restart on merge bounced the
   platform's own web-server).

   Returns the `reconcile-once!` result (`:started :stopped
   :not-our-lock`) so the caller can log / observe. No-op when
   compile-deps isn't populated yet (cold start) or when no running
   service is affected."
  ([ctx running-atom changed-fn-ids]
   (restart-services-depending-on! ctx running-atom changed-fn-ids nil))
  ([ctx running-atom changed-fn-ids edit-branch-id]
   ;; `:compile-deps` now holds `{:forward-deps :reverse-deps}` since
   ;; the incremental-update refactor; only the reverse side matters
   ;; for the service-restart blast walk.
   (let [reverse-deps (some-> (:compile-deps ctx) deref :reverse-deps)]
     (if (or (nil? reverse-deps) (empty? changed-fn-ids))
       {:started [] :stopped [] :not-our-lock []}
       ;; Hold `reconcile-monitor` across the stop→release→dissoc phase +
       ;; the trailing reconcile: it mutates `running` and the non-thread-safe
       ;; advisory-lock connection, which a concurrent NOTIFY-driven
       ;; `reconcile-once!` must not race. Reentrant, so the inner
       ;; reconcile-once! self-lock doesn't deadlock.
       (locking reconcile-monitor
         (let [blast (compile-deps/transitive-blast reverse-deps changed-fn-ids)
               lock-conn (lock-conn-from-ctx ctx)
               storage (:storage ctx)
               base (or (:base-storage storage) storage)
               sees-edit? (fn [entry-branch]
                            (or (nil? edit-branch-id)
                                (nil? entry-branch)
                                (some #(= edit-branch-id %)
                                      (res/collect-branch-chain base entry-branch))))
               to-restart (->> @running-atom
                               (filter (fn [[_ entry]]
                                         (and (map? entry)
                                              (contains? blast (:fn-id entry))
                                              (sees-edit? (:branch-id entry)))))
                               (mapv first))]
           (doseq [sid to-restart]
             (stop-and-forget! lock-conn running-atom sid))
           (when (seq to-restart)
             (log/info "Stopping" (count to-restart)
                       "services whose closure depends on edited fn"
                       {:changed-fn-ids changed-fn-ids
                        :service-ids to-restart}))
           (reconcile-once! ctx running-atom)))))))


(defn stop-all!
  "Shutdown helper — drains `running-atom` by calling every stopper,
   clears the atom. Called from the integrant `halt-key!`.

   `not-our-lock` placeholder entries are skipped (no stopper to
   call). Advisory locks held by THIS pod are released by closing
   the lock connection at the `:db/service-locks` halt-key, so we
   don't need to release per-service here.

   Holds `reconcile-monitor` across the drain + reset (L3): halt's
   `awaitTermination` caps the ticker wait at 5s, so a reconcile pass can
   still be in flight here. Every OTHER running-mutating path takes this
   monitor, so without it a straggler `reconcile-once!` could
   `swap! running-atom assoc` a just-started service back in AFTER our
   `reset!` — a leaked running service nothing would ever stop. Taking the
   monitor makes us observe a quiesced running map. Reentrant +
   process-local, so no deadlock with a caller that already holds it."
  [running-atom]
  (locking reconcile-monitor
    (doseq [[sid entry] @running-atom]
      (when (not= ::not-our-lock entry)
        (stop-service! sid entry)))
    (reset! running-atom {})))
