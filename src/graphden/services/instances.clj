(ns graphden.services.instances
  "A RUNNING copy of a service: where it answers, whether it is still
   heartbeating, and how it is stopped.

   The `:service` row is desired state; a `:service-instance` row is one
   RUNNING copy — which pod, where it answers (`:host` / `:port`, from the
   `:endpoint` metadata a listener's handle carries — `:http-server` sets
   it), and `:seen-at`, the heartbeat this pod refreshes every tick. The
   row is created on start and deleted on every stop path; a crashed pod's
   row goes stale instead (`svc-schema/default-stale-after-ms`), and the
   reconciler on any pod eventually deletes it (`reap-stale-instances!`).
   `:service-endpoint` (web/service) resolves a service fn to a LIVE
   instance, so a consumer names the service and gets an address that is
   actually answering.

   Stopping a copy (`stop-and-forget!`) is the other half of its life: call
   its stopper, delete its row, release the advisory slot THIS pod took, and
   drop it from the reconciler's `running` map."
  (:require
    [clojure.tools.logging :as log]
    [graphden.schema.services.schema :as svc-schema]
    [graphden.services.endpoint :as endpoint]
    [graphden.storage.postgres.advisory-lock :as pg-lock]
    [graphden.storage.protocol.core :as sp]))


(defn- self-executor-id
  "This pod's identity in instance rows — its fleet `:executor-id`, or
   \"local\" on a single pod."
  [ctx]
  (or (:executor-id ctx) "local"))


(defn- self-host
  "The host other pods reach THIS pod by: its fleet `:executor-id` (a
   pod-FQDN in k8s), or loopback on a single pod."
  [ctx]
  (or (:executor-id ctx) "127.0.0.1"))


(defn handle-meta
  "The metadata a service handle carries (`:endpoint` / `:alive?` /
   `:exit`), or nil for a handle that isn't an `IObj`."
  [stopper]
  (when (instance? clojure.lang.IObj stopper) (meta stopper)))


(defn endpoint-of
  "The `{:host :port}` a just-started service answers on, read off its
   handle's `:endpoint` metadata and completed with this pod's host —
   nil when the handle carries none (a cron loop, a fire-and-forget).
   The port half of the instance row."
  [ctx stopper]
  (when-let [ep (:endpoint (handle-meta stopper))]
    (assoc ep :host (self-host ctx))))


(defn create-instance!
  "Write this pod's `:service-instance` row for a just-started copy.
   Returns the row id, or nil when the write failed (logged) — the copy
   runs regardless; it just cannot be resolved by consumers."
  [ctx storage svc stopper]
  (when storage
    (try
      (let [now (java.time.Instant/now)
            service-id (:id svc)
            ep (endpoint-of ctx stopper)
            ;; The tenant is the service row's (the pass's own `:service`
            ;; query carries `:org-id` — no re-read). The reconciler is the
            ;; trusted system path (public org, no principal), and the
            ;; tenancy decorator keeps an EXPLICIT `:org-id` for that path
            ;; instead of stamping `public` over it — a public-stamped copy
            ;; was readable by every org.
            org-id (:org-id svc)]
        (:id (sp/create-entity storage :service-instance
                               (cond-> {:service-id service-id
                                        :executor-id (self-executor-id ctx)
                                        :host (self-host ctx)
                                        :port (:port ep)
                                        :started-at now
                                        :seen-at now}
                                 org-id (assoc :org-id org-id)))))
      (catch Exception e
        (log/warn e "service instance write failed" {:service-id (:id svc)})
        nil))))


(defn delete-instance!
  "Best-effort delete of a copy's instance row on stop."
  [storage instance-id]
  (when (and storage instance-id)
    (try
      (sp/delete-entity storage :service-instance instance-id)
      (catch Exception e
        (log/warn e "service instance delete failed" {:instance-id instance-id})))))


(defn heartbeat-instance!
  "Refresh a copy's `:seen-at` — the fact consumers and the stale-row GC
   read."
  [storage instance-id]
  (when (and storage instance-id)
    (try
      (sp/update-entity storage :service-instance instance-id
                        {:seen-at (java.time.Instant/now)})
      (catch Exception e
        (log/warn e "service instance heartbeat failed" {:instance-id instance-id})))))


(defn- reap-due?
  "Gate the stale-row reap to once per stale window PER RECONCILER (the
   clock rides on `running-atom`'s metadata, so every reconciler — and
   every test's fresh atom — reaps on its first pass). Reconcile also
   runs on every graph write (the delta restart), and staleness is
   measured in tens of seconds: scanning `service-instance` on each
   fn create paid a round trip for nothing (`perf/budgets.edn`
   `:sql/create-fn`)."
  [running-atom now-ms]
  (let [last-ms (::last-reap-ms (meta running-atom))]
    (when (or (nil? last-ms) (>= (- now-ms last-ms) svc-schema/default-stale-after-ms))
      (alter-meta! running-atom assoc ::last-reap-ms now-ms)
      true)))


(defn reap-stale-instances!
  "Delete instance rows nobody has heartbeat for ten stale windows —
   the copies of a pod that crashed. Level-triggered, any pod; the rows
   were already ignored by `resolve-endpoint` after one window. Runs at
   most once per stale window per reconciler (`reap-due?`)."
  [running-atom storage]
  (when (and storage (reap-due? running-atom (System/currentTimeMillis)))
    (try
      (let [cutoff-ms (- (System/currentTimeMillis)
                         (* 10 svc-schema/default-stale-after-ms))]
        (doseq [row (sp/query-entities storage :service-instance {})
                :when (some-> (endpoint/seen-at-ms row) (< cutoff-ms))]
          (log/info "reaping stale service instance"
                    {:instance-id (:id row) :executor-id (:executor-id row)})
          (delete-instance! storage (:id row))))
      (catch Exception e
        (log/warn e "stale service-instance reap failed")))))


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


(defn release-slot-quietly!
  "Release advisory slot `slot` of `sid` on `lock-conn`. Best-effort: a
   failed release is logged and the caller carries on (the session's locks
   die with the connection anyway)."
  [lock-conn sid slot]
  (try (pg-lock/release-slot! lock-conn sid slot)
       (catch Exception e
         (log/warn e "advisory lock release failed — continuing"
                   {:service-id sid :slot slot}))))


(defn stop-and-forget!
  "Stop `sid`, release its advisory lock if THIS pod took one, and drop
   it from `running-atom`. The three restart/stop paths all need exactly
   this, and all three used to release unconditionally — which asks
   Postgres to unlock a key the session never held every time a
   `:per-pod` service stops.

   `::not-our-lock` / `::exited` / `::backoff` placeholders have nothing
   to stop and no lock to release. A running entry deletes its instance
   row."
  [lock-conn running-atom sid storage]
  (let [entry (get @running-atom sid)]
    (when (map? entry)
      (stop-service! sid entry)
      (delete-instance! storage (:instance-id entry))
      (when (and lock-conn (:locked? entry))
        (release-slot-quietly! lock-conn sid (:pool-slot entry 0))))
    (swap! running-atom dissoc sid)))
