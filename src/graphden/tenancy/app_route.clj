(ns graphden.tenancy.app-route
  "Read-side of the `:app-route` registry (Track C — see
   `graphden.tenancy.app-route-schema`). Resolves a GLOBAL app label
   (`shop.graphden.app` → the `shop` app-route → its org + handler), and lists
   an org's apps for the management UI. Writes go through the org-stamped
   platform-storage seam (like `:service` / `:domain`), never this namespace.

   Reads run in the platform context — app-routing happens before the request
   scope binds an org, so `sp/query-entities` here sees every org's rows."
  (:require
    [clojure.string :as str]
    [graphden.storage.protocol.core :as sp]))


(defn normalize-label
  "Lower-case + trim an app label. `nil` / blank → nil (no default app row)."
  [label]
  (when label
    (let [l (str/lower-case (str/trim label))]
      (when-not (str/blank? l) l))))


(defn route-by-label
  "The `:app-route` row for a GLOBAL `label` (`{:org :label :handler-fn-id …}`),
   or nil when unrouted. `label` is normalized before lookup. The apps-domain is
   a flat namespace, so the label alone identifies the app + its owning org."
  [storage label]
  (when-let [label (normalize-label label)]
    (first (sp/query-entities storage :app-route {:label label}))))


(defn routes-for-org
  "Every `:app-route` row owned by `org` (for the management UI). Sorted by
   label so the listing is stable."
  [storage org]
  (->> (sp/query-entities storage :app-route {:org org})
       (sort-by :label)))
