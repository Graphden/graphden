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
   `diff-desired` / `plan-pass`. A running copy's instance row and stop
   path live in `services.instances`; exit detection and the exit
   backoff in `services.liveness`."
  (:require
    [clojure.math]
    [clojure.set]
    [clojure.tools.logging :as log]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.compile.deps :as compile-deps]
    [graphden.schema.services.schema :as svc-schema]
    [graphden.services.instances :as instances]
    [graphden.services.liveness :as liveness]
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
;; — even max retries finishes inside ~7s (1+2+4). This loop covers
;; STARTUP failures only (e.g. port-in-use), where `:always` and
;; `:on-failure` behave alike; the runtime distinction (a clean stop
;; restarts under `:always` only) lives in the liveness path,
;; `restart-after-exit?`. Tunable per-call so tests can pin to
;; zero-backoff.
(def ^:private default-max-retries 3)
(def ^:private default-backoff-ms 1000)


(defn- should-retry?
  "Whether `policy` wants another start attempt after a failure."
  [policy]
  ;; Both :always and :on-failure retry on start-exception. :never
  ;; gives up after the first attempt. The clean-stop distinction is
  ;; the liveness path's (`restart-after-exit?`), not this loop's.
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


(defn running-state
  "What THIS pod knows about service `sid`, for the UI —
   `{:state kw :next-attempt-at Instant|nil}`:
   `:running` (a live copy), `:start-failed` (retries exhausted; the
   entry keeps `:start-failed-at`), `:exited` (exited in place, the
   restart policy leaves it down), `:backoff` (exited in place, the
   restart is delayed until `:next-attempt-at`), `:not-our-lock`
   (another pod holds its slot), `:pending` (nothing recorded yet).
   The four placeholders used to reach the editor as a bare
   \"pending\" — a service parked in backoff looked like one that
   never started."
  [running-atom sid]
  (let [e (get @running-atom sid)]
    (cond
      (map? e) {:state (if (:start-failed-at e) :start-failed :running)}
      (= e ::backoff) {:state :backoff
                       :next-attempt-at (some-> (liveness/backoff-until sid)
                                                java.time.Instant/ofEpochMilli)}
      (= e ::exited) {:state :exited}
      (= e ::not-our-lock) {:state :not-our-lock}
      (= e ::start-failed) {:state :start-failed}
      :else {:state :pending})))


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


(declare ^:private reassert-lock-ownership!*)


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
  ([lock-conn running-atom] (reassert-lock-ownership! lock-conn running-atom nil))
  ([lock-conn running-atom storage]
   (reassert-lock-ownership!* lock-conn running-atom storage)))


(defn- reassert-lock-ownership!*
  [lock-conn running-atom storage]
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
        (instances/stop-service! sid entry)
        (instances/delete-instance! storage (:instance-id entry))
        (swap! running-atom dissoc sid)))))


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
   desired-state rows enter the reconciler — makes a nil-branch
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
   the base ctx, so the nil-branch behavior is preserved
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
   ;; running service — on prod a demo org's fn create shut
   ;; down the platform web-server until the next periodic tick (which
   ;; runs unbound = platform) restarted it: a ~15 s total outage any
   ;; tenant write could trigger. Binding here makes every trigger path
   ;; (edge, NOTIFY, tick, CRaC resume) behave like the tick.
   (tctx/with-org tctx/public-org
                  (reconcile-once!* ctx running-atom start-opts))))


(defn- begin-pass!
  "The housekeeping every pass does BEFORE it diffs, in order; returns the
   (possibly reconnected) lock connection the rest of the pass uses:

   1. heal a dropped lock connection — it released every advisory lock this
      pod held, so ownership is re-asserted before the diff trusts
      `:locked?` entries;
   2. drop the transient `::not-our-lock` / `::start-failed` placeholders, so
      every service we don't currently run is RE-ATTEMPTED this pass. This
      is what makes the periodic tick heal a crashed owner: the crash
      released its advisory slot (no NOTIFY), and here a sibling re-acquires
      it. A service still fully held by siblings is simply re-marked below;
   3. drop `backoff` placeholders whose delay elapsed;
   4. liveness + heartbeat over this pod's copies, so a copy that died in
      place is restarted (or parked) this pass;
   5. the level-triggered reap of instance rows a crashed pod left."
  [ctx running-atom]
  (when-let [holder (:service-locks-holder ctx)]
    (when (pg-lock/ensure-live! holder)
      (reassert-lock-ownership! (pg-lock/holder-conn holder) running-atom (:storage ctx))))
  (swap! running-atom (fn [m] (into {} (remove (fn [[_ v]] (contains? #{::not-our-lock ::start-failed} v))) m)))
  (liveness/drop-due-backoffs! running-atom (System/currentTimeMillis))
  (let [storage (:storage ctx)
        lock-conn (lock-conn-from-ctx ctx)]
    (liveness/check-liveness! running-atom lock-conn storage)
    (instances/reap-stale-instances! running-atom storage)
    lock-conn))


(defn- entry-drifted?
  "Config drift: a service that is enabled AND already running but whose
   running `entry` no longer matches the desired row `svc` — its :fn-id /
   :branch-id / :restart-policy / :cardinality / pool size was edited via a
   `:service` PUT. The membership diff misses these (the id is in both
   sets), so the edit was silently ignored until a pod restart. A pool-size
   edit (e.g. 3→2) restarts the pod on the now-out-of-range slot, which
   fails to re-acquire, shrinking the pool. Placeholders never drift."
  [entry svc]
  (and (map? entry)
       (or (not= (:fn-id entry) (:fn-id svc))
           (not= (:branch-id entry) (effective-branch-id svc))
           (not= (:restart-policy entry) (:restart-policy svc))
           (not= (:cardinality entry) (svc-schema/service-cardinality svc))
           (not= (:pool-size entry) (svc-schema/effective-pool-size svc)))))


(defn- plan-pass
  "What this pass stops and starts, given the enabled `:service` rows this
   pod serves and the `running` map: the membership diff (`diff-desired`)
   plus every drifted entry on BOTH lists (stop + restart picks the edit
   up). No I/O beyond `effective-branch-id`'s router read."
  [enabled-services running-now]
  (let [enabled-by-id (into {} (map (juxt :id identity)) enabled-services)
        {:keys [to-start to-stop]} (diff-desired (keys enabled-by-id) (keys running-now))
        drifted (filterv #(entry-drifted? (get running-now %) (get enabled-by-id %))
                         (keys enabled-by-id))]
    {:enabled-by-id enabled-by-id
     :to-stop (vec (concat to-stop drifted))
     :to-start (vec (concat to-start drifted))}))


(defn- running-entry
  "The `running`-map entry for a just-started copy of `svc`. Records the
   EFFECTIVE :branch-id (row's, or the router's default for nil-branch rows)
   so stop time and `restart-services-on-branch!` can tell which branch this
   run belonged to; :cardinality / :pool-size mirror the row so drift
   detection sees an admin flipping them; :locked? = THIS pod holds a slot,
   :pool-slot = which one (for release + reassert); :instance-id = its
   instance row, so stop deletes it."
  [started svc slot instance-id]
  (let [eff-branch (effective-branch-id svc)]
    (cond-> (assoc started
                   :cardinality (svc-schema/service-cardinality svc)
                   :pool-size (svc-schema/effective-pool-size svc)
                   :locked? (some? slot)
                   :pool-slot slot)
      eff-branch (assoc :branch-id eff-branch)
      instance-id (assoc :instance-id instance-id))))


(defn- start-one!
  "Start `svc` on this pod if it may run here, recording the outcome in
   `running-atom`. Returns one of:

   - `:branch-ctx-failed` — its branch's ctx did not build (already logged);
     the row stays un-started so the periodic tick retries, and any slot
     acquired is NOT held for a start we didn't make;
   - `:not-our-lock` — a lock-gated service whose every slot a sibling
     holds; recorded as the transient `::not-our-lock` placeholder;
   - `:start-failed` — retries exhausted (port taken, missing file on THIS
     pod). The slot is released so a healthy sibling can fail over, and the
     give-up is the transient `::start-failed` placeholder — NOT an entry
     counted as running forever — so the next tick re-attempts. This is the
     reconvergence SERVICES.md promises;
   - `:started` — a live copy, its instance row written.

   `:per-pod` services skip the lock (every pod runs its own); `:singleton`
   races for slot 0, `:pool` for the first free of its N slots."
  [ctx running-atom lock-conn svc start-opts]
  (let [sid (:id svc)
        svc-ctx (ctx-for-service ctx svc)
        lock-gated? (svc-schema/lock-gated? svc)
        slot (when (and lock-gated? (some? lock-conn))
               (acquire-pool-slot! lock-conn sid (svc-schema/effective-pool-size svc)))
        acquired? (or (not lock-gated?) (some? slot) (nil? lock-conn))]
    (cond
      (= ::branch-ctx-failed svc-ctx)
      (do (when (some? slot) (instances/release-slot-quietly! lock-conn sid slot))
          :branch-ctx-failed)

      (not acquired?)
      (do (swap! running-atom assoc sid ::not-our-lock)
          :not-our-lock)

      :else
      (let [started (start-service! svc-ctx svc start-opts)]
        (if (:start-failed-at started)
          (do (when (some? slot) (instances/release-slot-quietly! lock-conn sid slot))
              (swap! running-atom assoc sid ::start-failed)
              :start-failed)
          (let [instance-id (instances/create-instance! ctx (:storage ctx) svc (:stopper started))]
            (swap! running-atom assoc sid (running-entry started svc slot instance-id))
            :started))))))


(defn- reconcile-once!*
  [ctx running-atom start-opts]
  (locking reconcile-monitor
    (let [lock-conn (begin-pass! ctx running-atom)
          storage (:storage ctx)
          ;; Shard filter (task #6): drop tenant services whose org this pod
          ;; doesn't serve, so a dedicated tenant's services run only on its
          ;; own cgroup-limited pod, never on a shared compile-all pod.
          enabled (filterv #(service-in-shard? (:executor-orgs ctx) %)
                           (sp/query-entities storage :service {:enabled? true}))
          {:keys [enabled-by-id to-stop to-start]} (plan-pass enabled @running-atom)]
      (doseq [sid to-stop]
        ;; A row that was disabled / deleted / edited starts its exit
        ;; history afresh when it comes back.
        (liveness/forget-exits! sid)
        (instances/stop-and-forget! lock-conn running-atom sid storage))
      (let [outcomes (into {} (map (fn [sid]
                                     [sid (start-one! ctx running-atom lock-conn
                                                      (get enabled-by-id sid) start-opts)]))
                           to-start)
            not-started (filterv #(not= :started (outcomes %)) to-start)]
        {:started (vec (remove (set not-started) to-start))
         :stopped to-stop
         :not-our-lock not-started}))))


(def ^:private empty-pass
  "The `reconcile-once!` result shape for a pass that did nothing."
  {:started [] :stopped [] :not-our-lock []})


(def ^:private restart-start-opts
  "Retry-free start for the edge-triggered restarts. They run on the CRUD
   invalidation thread and the NOTIFY listener, under `reconcile-monitor`:
   the default 1+2+4 s supervisor backoff would block every other reconcile
   trigger ~7 s on a start that keeps failing (a port conflict). A failed
   restart parks as `::start-failed` and the periodic tick reconverges —
   the same contract as the NOTIFY and tick triggers."
  {:max-retries 0 :backoff-ms 0})


(defn- restart-matching!
  "Stop every running entry `(pred entry)` accepts, then reconcile so the
   still-enabled rows restart against fresh per-branch contexts. Nothing
   matched → nothing to restart, and NO pass runs: every write fires this
   hook, and a pass re-attempts every `::start-failed` placeholder, so an
   unconditional pass re-started a port-conflicted service on every edit.

   The stop→release-lock→dissoc phase mutates `running` and touches the
   NON-thread-safe advisory-lock connection, so it holds `reconcile-monitor`
   — otherwise a concurrent `reconcile-once!` (NOTIFY listener thread)
   interleaves and two threads use the lock connection at once. The monitor
   is reentrant, so the trailing `reconcile-once!` doesn't deadlock."
  [ctx running-atom pred msg log-data]
  (locking reconcile-monitor
    (let [to-restart (into [] (keep (fn [[sid entry]] (when (and (map? entry) (pred entry)) sid)))
                           @running-atom)]
      (if (empty? to-restart)
        empty-pass
        (let [lock-conn (lock-conn-from-ctx ctx)]
          (doseq [sid to-restart]
            (instances/stop-and-forget! lock-conn running-atom sid (:storage ctx)))
          (log/info "Stopping" (count to-restart) msg (assoc log-data :service-ids to-restart))
          ;; The pass sees the just-stopped rows as to-start (still enabled
          ;; in DB) and restarts them with `ctx-for-service`.
          (reconcile-once! ctx running-atom restart-start-opts))))))


(defn restart-services-on-branch!
  "Stop every running service whose entry was started against
   `target-branch-id`, then call `reconcile-once!` so the still-
   enabled rows pick up fresh per-branch ExecutionContexts. Wired
   into the merge endpoint so cron loops (which hold their fn-graph
   closures by reference) actually pick up post-merge fn-versions —
   `branch-router.cache/invalidate!` clears the per-branch ctx, but the
   running closures don't observe that on their own.

   `running-atom` carries `:branch-id` on each entry (set by
   `reconcile-once!` from the row's EFFECTIVE branch — a nil-branch
   row is normalized to the router's default branch, so nil-branch rows
   participate in a default-branch restart instead of silently
   running stale closures). Entries without a recorded branch only
   occur when no router is registered (tests that bypass branch
   routing) and are LEFT ALONE.

   Returns the `reconcile-once!` result map (`:started :stopped
   :not-our-lock`) so the caller can log / observe."
  [ctx running-atom target-branch-id]
  (restart-matching! ctx running-atom
                     #(= target-branch-id (:branch-id %))
                     "services on branch for restart"
                     {:branch-id target-branch-id}))


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
   ;; `:compile-deps` holds `{:forward-deps :reverse-deps}`; only the
   ;; reverse side matters for the service-restart blast walk.
   (let [reverse-deps (some-> (:compile-deps ctx) deref :reverse-deps)]
     (if (or (nil? reverse-deps) (empty? changed-fn-ids))
       empty-pass
       (let [blast (compile-deps/transitive-blast reverse-deps changed-fn-ids)
             storage (:storage ctx)
             base (or (:base-storage storage) storage)
             sees-edit? (fn [entry-branch]
                          (or (nil? edit-branch-id)
                              (nil? entry-branch)
                              (some #(= edit-branch-id %)
                                    (res/collect-branch-chain base entry-branch))))]
         (restart-matching! ctx running-atom
                            #(and (contains? blast (:fn-id %)) (sees-edit? (:branch-id %)))
                            "services whose closure depends on edited fn"
                            {:changed-fn-ids changed-fn-ids}))))))


(defn stop-all!
  "Shutdown helper — drains `running-atom` by calling every stopper,
   clears the atom. Called from the integrant `halt-key!`. With `ctx`
   (its `:storage`), also clears the endpoints this pod recorded, so a
   clean shutdown leaves no stale address on the rows.

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
  ([running-atom] (stop-all! running-atom nil))
  ([running-atom ctx]
   (locking reconcile-monitor
     (doseq [[sid entry] @running-atom
             :when (map? entry)]
       (instances/stop-service! sid entry)
       (instances/delete-instance! (:storage ctx) (:instance-id entry)))
     (reset! running-atom {}))))
