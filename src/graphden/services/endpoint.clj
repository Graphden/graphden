(ns graphden.services.endpoint
  "Where a service ANSWERS — the read side of the `:service-instance` rows
   the reconciler keeps (docs/SERVICES.md § Endpoints).

   `resolve-endpoint` turns a service fn's id into `{:host :port :url}`:
   from a LIVE instance (a row whose heartbeat is fresh and that has a
   port) of the fn's enabled `:service` row on the caller's branch —
   preferring a copy on this very pod — else from the `resolver` seam an
   addon installs (on the cloud a tenant fn is served as an `:app-route`
   behind a public domain, and service-to-service traffic goes through
   that domain like any outbound call — no internal address to exempt
   from the egress guard), else `:service/not-running`.

   The `:service-endpoint` base-fn (web/service) is a thin adapter over
   this; keeping the logic here lets the tenancy addon require the seam
   from the classpath (package impls are loaded, not compiled)."
  (:require
    [graphden.schema.services.schema :as svc-schema]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]))


(defonce ^{:doc "Installed by an addon: `(fn [ctx fn-id] → {:host :port :url} | nil)`
                 for fns served other than as a `:service` row. nil ⇒ only
                 service rows resolve. `defonce` so a namespace reload keeps
                 the installed fn."}
  resolver
  (atom nil))


(defn instance-endpoint
  "`{:host :port :url}` for a listener instance row, or nil when the copy
   has no port (a cron loop)."
  [{:keys [host port]}]
  (when (and host port)
    {:host host
     :port port
     :url (str "http://" host ":" port)}))


(defn seen-at-ms
  "An instance row's heartbeat as epoch millis. The codec hands a
   timestamptz back as whatever the driver produced (`java.sql.Timestamp`
   today; an `Instant` / `Date` from a test) — `inst-ms` reads all of
   them. nil when the row has none."
  [{:keys [seen-at]}]
  (when seen-at (inst-ms seen-at)))


(defn live?
  "Is the instance's heartbeat fresh — `seen-at` within
   `svc-schema/default-stale-after-ms` of `now-ms`? A crashed pod never
   deletes its row; a stale heartbeat is how it stops answering."
  ([row] (live? row (System/currentTimeMillis)))
  ([row now-ms]
   (boolean (some-> (seen-at-ms row)
                    (>= (- now-ms svc-schema/default-stale-after-ms))))))


(defn service-rows-for
  "Enabled `:service` rows for `fn-id` visible from `storage`'s branch:
   the row's `:branch-id` matches, or the row has none — a package-seeded
   / legacy row, one listener that serves every branch."
  [storage fn-id]
  (let [branch-id (vs/current-branch-id storage)]
    (filterv (fn [row]
               (or (nil? (:branch-id row))
                   (= branch-id (:branch-id row))))
             (sp/query-entities storage :service {:fn-id fn-id :enabled? true}))))


(defn live-instances-for
  "The live listener instances of the given service rows, this pod's own
   copy first (loopback beats a hop), then freshest heartbeat first."
  [storage rows self-executor-id]
  (let [now-ms (System/currentTimeMillis)
        ids (mapv :id rows)]
    (when (seq ids)
      (->> (sp/query-entities storage :service-instance {:service-id ids})
           (filter #(and (live? % now-ms) (:port %)))
           (sort-by (fn [row]
                      [(if (= self-executor-id (:executor-id row)) 0 1)
                       (- (seen-at-ms row))]))))))


(defn resolve-endpoint
  "The `{:host :port :url}` the fn `fn-id` answers on — see the ns doc for
   the three sources. Throws `:service/not-running` when none knows it."
  [ctx storage fn-id]
  (let [rows (service-rows-for storage fn-id)
        instance (first (live-instances-for storage rows (or (:executor-id ctx) "local")))
        resolved (or (some-> instance instance-endpoint)
                     (when-let [f @resolver] (f ctx fn-id)))]
    (or resolved
        (throw (ex-info (str "Service is not running: no live instance for fn " fn-id
                             (if (seq rows)
                               " (the service row exists but no copy is alive, or it is not a listener)"
                               " (no enabled service row on this branch)"))
                        {:type :service/not-running
                         :fn-id fn-id
                         :service-rows (count rows)})))))
