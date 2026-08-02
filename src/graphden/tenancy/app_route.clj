(ns graphden.tenancy.app-route
  "Read-side of the `:app-route` registry (Track C — see
   `graphden.tenancy.app-route-schema`). Resolves a routing key
   `(org, label)` to the app's handler fn, and lists an org's apps for the
   management UI. Writes go through the org-stamped platform-storage seam
   (like `:service` / `:domain`), never this namespace.

   Reads run in the platform context — app-routing happens before the request
   scope binds an org, so `sp/query-entities` here sees every org's rows; the
   caller supplies the `org` filter."
  (:require
    [clojure.string :as str]
    [graphden.storage.protocol.core :as sp]))


(defn normalize-label
  "Lower-case + trim an app label. `nil` / blank → nil (no default app row)."
  [label]
  (when label
    (let [l (str/lower-case (str/trim label))]
      (when-not (str/blank? l) l))))


(defn handler-fn-id-for
  "The handler-fn-id for `(org, label)`, or nil when no app is routed at that
   key. `label` is normalized before lookup."
  [storage org label]
  (when-let [label (normalize-label label)]
    (:handler-fn-id
      (first (sp/query-entities storage :app-route {:org org :label label})))))


(defn routes-for-org
  "Every `:app-route` row owned by `org` (for the management UI). Sorted by
   label so the listing is stable."
  [storage org]
  (->> (sp/query-entities storage :app-route {:org org})
       (sort-by :label)))
