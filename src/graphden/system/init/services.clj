(ns graphden.system.init.services
  "Integrant init-key for the `:service` reconciler: package-service
   seeding, the NOTIFY dispatch callback (reconcile / cache-invalidate /
   execution-cancel), the periodic reconcile ticker, plus the
   init/halt/suspend lifecycle.

   Split out of `graphden.system.core` (which now only loads this ns for
   its `defmethod` side effects). No behaviour change."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.crud.fn-execution.lookup :as fn-lookup]
    [graphden.crud.fn-execution.persist :as persist]
    [graphden.executor.context :as exec-ctx]
    [graphden.packages.loader :as pkg]
    [graphden.packages.records.ids :as ids]
    [graphden.services.reconciler :as recon]
    [graphden.storage.postgres.notify :as pg-notify]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [integrant.core :as ig]))


;; =============================================================================
;; Service reconciler (replaces :http/server)
;;
;; On start: seed package-declared services into the :service table
;; (idempotent — deterministic ids), then read enabled rows and
;; reconcile.
;;
;; On halt: stop every running service.
;;
;; The in-process atom is modified from CRUD endpoints (which call
;; `recon/reconcile-once!` after writing), from NOTIFY events, and from
;; a periodic tick.
;;
;; Level-triggered convergence: a `ScheduledExecutorService` re-runs the
;; reconcile every `reconcile-period-ms` (default 15s). This is what makes
;; the reconciler robust rather than purely edge-triggered:
;;   - a `:singleton`'s pod CRASHES → its advisory lock auto-releases, but
;;     the crash emits no NOTIFY; a sibling that recorded `::not-our-lock`
;;     re-attempts the lock on the next tick and takes over (HA cron);
;;   - an out-of-band DB edit (psql, other tools) is picked up within a tick;
;;   - a transient start failure (port not yet freed) reconverges on a tick
;;     instead of sleeping under `reconcile-monitor` inline.
;; =============================================================================

(defn- resolve-fn-id-by-name
  "Best-effort fn-name → fn-id lookup. Seeder path swallows any
   ExceptionInfo since this runs during early startup where
   storage state may be incomplete."
  [storage fn-name]
  (fn-lookup/query-fn-id-by-name storage fn-name true))


(defn- seed-package-services!
  "Idempotently materialise each entry from `(:seeded-services
   packages)` as a `:service` row.

   Service id is deterministic on `(package-name, service-name)` via
   `ids/seeded-service-id` so re-running the seeder lands on the same
   row — letting an admin's `:enabled?` toggle survive restart.

   If the `:fn-name` doesn't resolve at boot (e.g. package didn't load
   the fn for some reason), the entry is logged and skipped — the
   admin can re-create the fn and the next boot will pick it up.

   `:cardinality` is BACKFILLED onto an existing row when that row's
   value is nil — i.e. it was written before the field existed. Without
   this, upgrading a live deployment would leave the seeded
   `:web-server` row at nil ≡ `:singleton`, and only one pod would ever
   bind a port. Backfill only touches nil, so an admin who deliberately
   set a cardinality keeps it, same as `:enabled?`.

   Returns a vector of `{:id :seeded? :fn-name :package-name}` maps
   for logging."
  [storage packages]
  (vec
    (keep
      (fn [{:keys [package-name name fn-name enabled? restart-policy cardinality]}]
        (let [svc-id (ids/seeded-service-id package-name name)
              fn-id (resolve-fn-id-by-name storage (clojure.core/name fn-name))
              existing (first (sp/query-entities storage :service {:id svc-id}))]
          (cond
            (nil? fn-id)
            (do (log/warn "seeded service skipped — fn-name didn't resolve"
                          {:package package-name :service name :fn-name fn-name})
                nil)

            existing
            ;; Idempotent: existing row may have a different
            ;; :enabled? (admin toggled it) — don't overwrite.
            (do
              (when (and cardinality (nil? (:cardinality existing)))
                (log/info "backfilling :cardinality on pre-existing service row"
                          {:service-id svc-id :cardinality cardinality})
                (sp/update-entity storage :service svc-id {:cardinality cardinality}))
              {:id svc-id :seeded? false :fn-name fn-name :package-name package-name})

            :else
            (try
              (sp/create-entity storage :service
                                {:id svc-id
                                 :fn-id fn-id
                                 :enabled? (if (false? enabled?) false true)
                                 :restart-policy (or restart-policy :always)
                                 :cardinality (or cardinality :singleton)})
              {:id svc-id :seeded? true :fn-name fn-name :package-name package-name}
              (catch Exception e
                ;; Race window: two pods may seed concurrently with
                ;; the same deterministic id. One wins the unique-
                ;; constraint, the other gets PG's `duplicate key`
                ;; — treat as success (the row exists, that's what
                ;; we wanted).
                (if (re-find #"duplicate key|unique constraint" (or (ex-message e) ""))
                  {:id svc-id :seeded? false :fn-name fn-name :package-name package-name}
                  (throw e)))))))
      (pkg/get-seeded-services packages))))


(defn- invalidate-from-notify!
  "Apply a sibling pod's `fn:invalidate` event to THIS pod's caches.

   Mirrors the local write path in `crud.entities/invalidate!`: the
   branch the write landed on, plus every cached branch that inherits
   from it. When the payload carries no branch-id (an older build's
   emitter) fall back to the base ctx, which is what this callback did
   before branch-ids rode along.

   Empty `id` ≡ full clear; a populated id is one delta seed."
  [ctx id branch-id]
  (let [seeds (when-not (str/blank? id) [(java.util.UUID/fromString id)])
        router (br/current-router)
        branch-uuid (when-not (str/blank? branch-id)
                      (java.util.UUID/fromString branch-id))]
    (if (and router branch-uuid)
      (do (br/invalidate-cached-branch! router branch-uuid seeds)
          (br/invalidate-affected-ctxs! router branch-uuid seeds))
      (if seeds
        (exec-ctx/invalidate-graph-cache! ctx seeds)
        (exec-ctx/invalidate-graph-cache! ctx)))))


(defn- on-notify
  "Multi-purpose listener callback. `reconcile!` is the injected
   reconcile fn (see the init-key's `:reconcile-fn` opt) —
   `recon/reconcile-once!` in production.

   - `:service` events → trigger a reconcile pass (managed-service
     ownership re-evaluation).
   - `:fn :invalidate` events → invalidate the affected fn-id on every
     cached ctx that can see the write (see `invalidate-from-notify!`).
     Pod A writes 5 binding rows under one request → emits 5 events →
     sibling pod B fires 5 invalidates. Each is cheap (delta path on the
     reverse-deps index).
   - `:execution :cancel` events → cancel the execution if THIS pod is
     the one running it. Every pod gets the event; at most one owns the
     future, the rest no-op."
  [ctx reconcile!]
  (fn [{:keys [kind op id branch-id epochs] :as event}]
    (try
      (case kind
        ;; Retry-free: a start failure isn't retried inline (which would sleep
        ;; under `reconcile-monitor` and block the listener thread + every
        ;; other reconcile trigger) — the periodic tick reconverges instead.
        :service   (reconcile! ctx recon/running {:max-retries 0 :backoff-ms 0})
        :fn        (when (= op :invalidate)
                     (invalidate-from-notify! ctx id branch-id)
                     ;; Delta applied — mark the writer's exact bump
                     ;; values COVERED so the lazy epoch validation
                     ;; doesn't heal over what this event just did.
                     ;; Old-format events without epochs mark nothing:
                     ;; the gap stays visible and costs one coarse
                     ;; heal — safe, never wrong.
                     (when (seq epochs)
                       (br/note-graph-epoch-covered! (:storage ctx) epochs)))
        :execution (when (and (= op :cancel) (not (str/blank? id)))
                     (persist/cancel-local! (java.util.UUID/fromString id)))
        nil)
      (catch Exception e
        (log/error e "NOTIFY dispatch threw" {:event event})))))


(defn- start-reconcile-ticker!
  "Spawn a scheduled tick that re-runs `reconcile!` (the injected
   reconcile fn) every `period-ms`, retry-free (a failed start
   reconverges on the next tick rather than sleeping under
   `reconcile-monitor`). Returns the scheduler for halt."
  [ctx reconcile! period-ms]
  (let [scheduler (java.util.concurrent.Executors/newSingleThreadScheduledExecutor)]
    (log/info "Starting service reconcile ticker — period" period-ms "ms")
    (java.util.concurrent.ScheduledExecutorService/.scheduleAtFixedRate
      scheduler
      ^Runnable (fn []
                  (try (reconcile! ctx recon/running {:max-retries 0 :backoff-ms 0})
                       (catch Exception e
                         (log/warn e "periodic reconcile failed"))))
      period-ms period-ms
      java.util.concurrent.TimeUnit/MILLISECONDS)
    scheduler))


(defmethod ig/init-key :exec/service-reconciler
  [_ {:keys [context packages notify-listener service-locks reconcile-period-ms
             reconcile-fn stop-all-fn]}]
  ;; `:reconcile-fn` / `:stop-all-fn` are injectable-DI test seams
  ;; (defaulting to `recon/reconcile-once!` / `recon/stop-all!`) — a
  ;; test drives the lifecycle with counting stubs via plain opts
  ;; instead of `with-redefs` (a root rebind is process-global and
  ;; forced a `^:serial` pin on `graphden.system.core-test`;
  ;; serial-reduction batch 4). Closures carry the injected fn to the
  ;; NOTIFY callback + ticker threads too, which a thread-local
  ;; `binding` could not.
  (log/info "Starting service reconciler...")
  ;; Production singleton — clear any stale state from a previous run
  ;; (e.g. test fixture or REPL reset) before reconciling.
  (reset! recon/running {})
  (let [reconcile! (or reconcile-fn recon/reconcile-once!)
        storage (:storage context)
        ;; Thread the lock HOLDER through ctx so reconcile-once! can use it
        ;; without changing its arglist contract. The holder (not a bare
        ;; connection) is what lets a pass reconnect a dropped lock conn +
        ;; re-assert ownership.
        ctx (cond-> context
              service-locks (assoc :service-locks-holder service-locks))
        seeded (seed-package-services! storage packages)
        new-seeds (filterv :seeded? seeded)
        enabled-services (sp/query-entities storage :service {:enabled? true})]
    (when (seq new-seeds)
      (log/info "Seeded" (count new-seeds) "package-declared :service rows"
                {:rows (mapv (fn [s] (select-keys s [:fn-name :package-name])) new-seeds)}))
    (when (seq enabled-services)
      (log/info "Reconciling" (count enabled-services) "enabled :service rows")
      (reconcile! ctx recon/running))
    ;; Hook into the NOTIFY transport — reconcile when a sibling pod
    ;; mutates `:service`. Callback closes over the lock-augmented
    ;; ctx so per-NOTIFY reconciles use the same advisory-lock path
    ;; as boot.
    (let [callback (when notify-listener
                     (pg-notify/register! notify-listener (on-notify ctx reconcile!)))
          ticker (start-reconcile-ticker! ctx reconcile! (or reconcile-period-ms 15000))]
      (cond-> {:running recon/running
               :context ctx
               :notify-listener notify-listener
               :notify-callback callback
               :ticker ticker}
        stop-all-fn (assoc :stop-all-fn stop-all-fn)))))


(defmethod ig/halt-key! :exec/service-reconciler
  [_ {:keys [running notify-listener notify-callback ticker stop-all-fn]}]
  (log/info "Stopping service reconciler...")
  (when ticker
    (java.util.concurrent.ExecutorService/.shutdown ^java.util.concurrent.ExecutorService ticker)
    (try (java.util.concurrent.ExecutorService/.awaitTermination
           ^java.util.concurrent.ExecutorService ticker 5 java.util.concurrent.TimeUnit/SECONDS)
         (catch InterruptedException _ nil)))
  (when (and notify-listener notify-callback)
    (pg-notify/unregister! notify-listener notify-callback))
  (when running ((or stop-all-fn recon/stop-all!) running))
  (log/info "Service reconciler stopped"))


(defmethod ig/suspend-key! :exec/service-reconciler [_ {:keys [running stop-all-fn]}]
  ;; Same as halt — services don't have a suspend state distinct from stop.
  (when running ((or stop-all-fn recon/stop-all!) running)))
