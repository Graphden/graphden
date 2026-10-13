(ns graphden.editor.preference-write
  "Owner-scoped preference writes serialized across editor instances. CRUD
   stays on the decorated storage; only the transaction lock uses JDBC."
  (:require
    [graphden.graph.metadata :as metadata]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.postgres.util :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tenancy]))


(defn- expected-valid?
  [expected]
  (and (map? expected)
       (= #{:present :value} (set (keys expected)))
       (boolean? (:present expected))))


(defn call-with-lock
  "Serialize a current-owner preference operation on the active transaction
   connection. This preserves the decorated storage. Callers needing atomic
   conditional behavior must refuse storage without a transaction datasource."
  [storage key f]
  (let [owner (tenancy/current-user-id)]
    (tx/in-transaction
      storage
      (fn [storage]
        (when-let [connection (tx/datasource storage)]
          (pg/exec! connection
                    ["SELECT pg_advisory_xact_lock(hashtextextended(?, 0))"
                     (pr-str ["graphden|ui-pref" owner (str key)])] {}))
        (f storage)))))


(defn- ordinary-editor?
  [storage key]
  (when (= "components" (str key))
    (when-let [home (tenancy/current-personal-namespace)]
      (when-let [namespace-row (metadata/namespace-row storage (:namespace-path home) (tenancy/current-org))]
        (metadata/function-row storage (:id namespace-row) "editor" (tenancy/current-org)
                               (tenancy/browser-source-policy storage))))))


(defn- write-locked!
  [storage owner key value conditional? expected]
  (let [editor (ordinary-editor? storage key)
        existing (first (sp/query-entities storage :ui-pref {:owner-id owner :key (str key)}))]
    ;; Preference-only callers cannot prove freshness of an authored ordinary
    ;; root. Its immutable activation API owns the root AND preference CAS.
    (if (or editor
            (and conditional?
                 (not= expected {:present (boolean existing) :value (:value existing)})))
      :stale
      (let [now (java.time.Instant/now)]
        (if existing
          (sp/update-entity storage :ui-pref (:id existing) {:value value :updated-at now})
          (sp/create-entity storage :ui-pref {:owner-id owner :key (str key) :value value
                                              :org-id (tenancy/current-org) :updated-at now}))
        :saved))))


(defn write!
  "Store one current-owner preference. Conditional mode compares row presence
   and value inside the same locked transaction as the write. Never returns the
   current value on a conflict. Unsupported conditional storage fails closed.
   Legacy components writes stop once an ordinary editor root exists."
  [storage key value conditional? expected]
  (cond
    (and conditional? (not (expected-valid? expected))) :invalid
    (and (or conditional? (= "components" (str key))) (nil? (tx/datasource storage))) :unsupported
    :else
    (let [owner (tenancy/current-user-id)
          write (fn [storage]
                  (call-with-lock storage key #(write-locked! % owner key value conditional? expected)))]
      ;; Same ordering as ordinary root activation: graph writer, then owner
      ;; preference lock. Absence cannot race with creation of named editor.
      (if (= "components" (str key))
        (writer/call-with-write storage :graph write)
        (write storage)))))
