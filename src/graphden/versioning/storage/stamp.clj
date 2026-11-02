(ns graphden.versioning.storage.stamp
  "One server-owned framework stamp for every version-row writer."
  (:require
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tenancy-context]))


(defn version-record
  "Select version data and stamp the transaction, branch, author and time.
   Callers cannot supply framework fields through `data`."
  [version-config entity-id branch-id timestamp data]
  (let [{:keys [version-id-field version-data-fields]} version-config]
    (-> (select-keys data version-data-fields)
        (assoc :id (random-uuid)
               version-id-field entity-id
               :branch-id branch-id
               :change-id (or (:change-id tx/*transaction-context*)
                              (random-uuid))
               :change-revision (some-> tx/*transaction-context*
                                        :change-revision deref)
               :created-at timestamp
               :author-id (tenancy-context/current-user-id)
               :author-label (tenancy-context/current-user-label)))))
