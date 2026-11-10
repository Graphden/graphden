(ns graphden.storage.grouped-read
  "Optional read grouping with scalar fallbacks on the supplied storage stack."
  (:require
    [graphden.storage.protocol.core :as sp])
  (:import
    (java.sql
      Connection)))


(def max-group-size 8)


(def ^:dynamic ^:private *snapshot-cache* nil)


(def ^:dynamic ^:private *grouping-disabled?* false)


(defn call-without-grouping
  "Keep ordinary scalar storage semantics inside a synchronous source trace.
   Nested metadata-cache scopes cannot silently reactivate a raw SQL carrier."
  [f]
  (binding [*grouping-disabled?* true *snapshot-cache* nil] (f)))


(defn snapshot-cache
  "Return metadata cache state only for its still-active owned connection.
   Isolation was checked once on entry; PgJDBC's isolation getter itself sends
   SQL, so looking up cached metadata must not call it again."
  [connection]
  (when (and (not *grouping-disabled?*) *snapshot-cache*
             (identical? connection (:connection *snapshot-cache*))
             @(:active? *snapshot-cache*)
             (not (.isClosed ^Connection connection))
             (not (.getAutoCommit ^Connection connection))
             (.isReadOnly ^Connection connection))
    *snapshot-cache*))


(defn call-with-snapshot-cache
  "Scope schema metadata to one owned read-only REPEATABLE READ transaction.
   Must run inside the JDBC transaction, whose lifetime remains caller-owned.
   No source values or authorization decisions belong in :shapes."
  [connection f]
  (if (and (not *grouping-disabled?*) (instance? Connection connection)
           (not (.getAutoCommit ^Connection connection))
           (.isReadOnly ^Connection connection)
           (= Connection/TRANSACTION_REPEATABLE_READ
              (.getTransactionIsolation ^Connection connection)))
    (let [state {:connection connection :shapes (atom {}) :active? (atom true)}]
      (binding [*snapshot-cache* state]
        (try (f) (finally (reset! (:active? state) false)))))
    (binding [*snapshot-cache* nil] (f))))


(defn scalar-operation
  "Run one request through this exact wrapper. Never unwrap to a raw backend."
  [storage {:keys [op entity ids where opts group-cols version-source max-candidates] :as request}]
  (case op
    :read-ids (if (satisfies? sp/StorageBatchCRUD storage)
                (sp/read-entities storage entity ids)
                (into {} (keep (fn [id]
                                 (when-let [row (sp/read-entity storage entity id)] [id row]))) ids))
    :query (if (contains? request :opts)
             (sp/query-entities storage entity where opts)
             (sp/query-entities storage entity where))
    :latest (sp/query-latest-per-group storage entity where group-cols)
    :candidates (sp/query-identity-candidates storage entity where version-source max-candidates)
    (throw (ex-info "Unsupported grouped read operation"
                    {:type :storage-error/unsupported-operation :operation op}))))


(defn- batch-size
  [requests]
  (if (= :candidates (:op (first requests)))
    1
    (count (take max-group-size (take-while #(not= :candidates (:op %)) requests)))))


(defn read-operations
  "Execute finite request groups, preserving order and scalar result metadata.
   Candidates remain singleton barriers. Backends decide whether a group is
   safe to transport together; an unknown decorator always stays scalar."
  [storage requests]
  (when-not (and (vector? requests)
                 (every? #(and (map? %) (keyword? (:entity %))
                               (contains? #{:read-ids :query :latest :candidates} (:op %))) requests))
    (throw (ex-info "Malformed grouped read requests" {:type :storage-error/invalid-operations})))
  (loop [remaining requests results []]
    (if (empty? remaining)
      results
      (let [size (batch-size remaining)
            batch (subvec remaining 0 size)
            answers (if (and (satisfies? sp/StorageGroupedRead storage)
                             (not= :candidates (:op (first batch))))
                      (sp/read-operations storage batch)
                      (mapv #(scalar-operation storage %) batch))]
        (when-not (and (vector? answers) (= size (count answers)))
          (throw (ex-info "Grouped read result count does not match requests"
                          {:type :storage-error/invalid-grouped-results})))
        (recur (subvec remaining size) (into results answers))))))
