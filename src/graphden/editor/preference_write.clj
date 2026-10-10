(ns graphden.editor.preference-write
  "Owner-scoped preference writes serialized across editor instances. CRUD
   stays on the decorated storage; only the transaction lock uses JDBC."
  (:require
    [graphden.storage.postgres.util :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tenancy]))


(defn- expected-valid?
  [expected]
  (and (map? expected)
       (= #{:present :value} (set (keys expected)))
       (boolean? (:present expected))))


(defn write!
  "Store one current-owner preference. Conditional mode compares row presence
   and value inside the same locked transaction as the write. Never returns the
   current value on a conflict. Unsupported conditional storage fails closed."
  [storage key value conditional? expected]
  (cond
    (and conditional? (not (expected-valid? expected))) :invalid
    (and conditional? (nil? (tx/datasource storage))) :unsupported
    :else
    (let [owner (tenancy/current-user-id)]
      (tx/in-transaction
        storage
        (fn [storage]
          (when-let [connection (tx/datasource storage)]
            ;; PG built-in RPC; values are parameters, not SQL fragments.
            (pg/exec! connection
                      ["SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"
                       (pr-str ["graphden|ui-pref" owner (str key)])] {}))
          (let [existing (first (sp/query-entities storage :ui-pref {:owner-id owner :key (str key)}))]
            (if (and conditional?
                     (not= expected {:present (boolean existing) :value (:value existing)}))
              :stale
              (let [now (java.time.Instant/now)]
                (if existing
                  (sp/update-entity storage :ui-pref (:id existing) {:value value :updated-at now})
                  (sp/create-entity storage :ui-pref {:owner-id owner :key (str key) :value value
                                                      :org-id (tenancy/current-org) :updated-at now}))
                :saved))))))))
