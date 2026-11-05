(ns graphden.services.endpoint
  "Where a service ANSWERS — the read side of the `:service-instance` rows
   the reconciler keeps (docs/SERVICES.md § Endpoints).

   Native reads for the graph-composed `web.service/:service-endpoint`.
   A LIVE instance has a fresh heartbeat and a port; this pod's copy is
   preferred. The graph chooses that result before a finite HTTP publication
   or the addon `resolver` seam (on the cloud a tenant fn is served as an
   `:app-route` behind a public domain). The final not-running choice also
   lives in the graph. The addon resolver atom remains a classpath seam."
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


(defonce ^{:doc "The finite HTTP host's `(ctx, fn-id) → endpoint` callback.
                 Installed and cleared with the branch router lifecycle."}
  temporary-resolver
  (atom nil))


(defn seen-at-ms
  "An instance row's heartbeat as epoch millis. The codec hands a
   timestamptz back as whatever the driver produced (`java.sql.Timestamp`
   today; an `Instant` / `Date` from a test) — `inst-ms` reads all of
   them. nil when the row has none."
  [{:keys [seen-at]}]
  (when seen-at (inst-ms seen-at)))


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


(defn instance-facts
  "One branch/owner-scoped heartbeat read and clock capture. Epoch conversion
   is native; freshness, preference, ordering and endpoint rendering are graph policy."
  [storage rows self-executor-id]
  (let [now-ms (System/currentTimeMillis)
        ids (mapv :id rows)
        instances (if (seq ids) (sp/query-entities storage :service-instance {:service-id ids}) [])]
    {:now-ms now-ms :stale-after-ms svc-schema/default-stale-after-ms
     :self-executor-id self-executor-id
     :instances (mapv (fn [row]
                        {:host (:host row) :port (:port row)
                         :executor-id (:executor-id row) :seen-at-ms (seen-at-ms row)})
                      instances)}))
