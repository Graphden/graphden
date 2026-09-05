(ns graphden.services.endpoint
  "Where a service ANSWERS — the read side of the endpoint the reconciler
   records (docs/SERVICES.md § Endpoints).

   `resolve-endpoint` turns a service fn's id into `{:host :port :url}`: from the
   fn's enabled `:service` row on the caller's branch (the reconciler
   wrote its `:endpoint` when the listener started), else from the
   `resolver` seam an addon installs (on the cloud a tenant fn is served
   as an `:app-route` behind a public domain, and service-to-service
   traffic goes through that domain like any outbound call — no internal
   address to exempt from the egress guard), else `:service/not-running`.

   The `:service-endpoint` base-fn (web/service) is a thin adapter over
   this; keeping the logic here lets the tenancy addon require the seam
   from the classpath (package impls are loaded, not compiled)."
  (:require
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]))


(defonce ^{:doc "Installed by an addon: `(fn [ctx fn-id] → {:host :port :url} | nil)`
                 for fns served other than as a `:service` row. nil ⇒ only
                 service rows resolve. `defonce` so a namespace reload keeps
                 the installed fn."}
  resolver
  (atom nil))


(defn row-endpoint
  "`{:host :port :url}` from a `:service` row's recorded `:endpoint`, or
   nil when the row carries none (not running, or not a listener)."
  [{:keys [endpoint]}]
  (let [{:keys [host port]} endpoint]
    (when (and host port)
      {:host host
       :port port
       :url (str "http://" host ":" port)})))


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


(defn resolve-endpoint
  "The `{:host :port :url}` the fn `fn-id` answers on — see the ns doc for
   the three sources. Throws `:service/not-running` when none knows it."
  [ctx storage fn-id]
  (let [rows (service-rows-for storage fn-id)
        resolved (or (some row-endpoint rows)
                     (when-let [f @resolver] (f ctx fn-id)))]
    (or resolved
        (throw (ex-info (str "Service is not running: no endpoint recorded for fn " fn-id
                             (if (seq rows)
                               " (the service row exists but has not started, or is not a listener)"
                               " (no enabled service row on this branch)"))
                        {:type :service/not-running
                         :fn-id fn-id
                         :service-rows (count rows)})))))
