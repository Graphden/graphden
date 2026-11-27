(ns graphden.input.offline
  "Operator-only snapshot rehearsal. Use a stopped, isolated database copy.
   The artifact retains raw history; applying it never stamps new history."
  (:require
    [clojure.edn :as edn]
    [clojure.walk :as walk]
    [graphden.input.access :as access]
    [graphden.input.migration :as migration]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.input :as input-storage]
    [graphden.versioning.storage.resolution :as resolution])
  (:import
    (java.io
      PushbackReader
      StringReader)
    (java.sql
      Timestamp)
    (java.time
      Instant)))


(defn snapshot
  "Raw rows, including tombstones and merge cutoffs. Never use a versioned
   decorator here: it would irreversibly discard the historical source."
  [storage]
  (when (:base-storage storage)
    (throw (ex-info "Offline capture requires the raw storage"
                    {:type :input/raw-storage-required})))
  (let [entities (sp/current-entities storage)]
    (into {} (map (fn [entity]
                    [entity (if (contains? entities entity)
                              (vec (sort-by (comp str :id) (sp/query-entities storage entity {}))) [])]))
          migration/snapshot-entities)))


(defn artifact
  [source]
  (let [plan (migration/plan source)]
    {:plan plan :source source
     :target-fingerprint (when (empty? (:exceptions plan))
                           (migration/fingerprint (migration/target-snapshot source plan)))}))


(defn encode
  "Safe EDN retains Instant precision; reader evaluation is never enabled."
  [migration-artifact]
  (migration/stable-edn (walk/postwalk #(cond
                                          (instance? Timestamp %) (tagged-literal 'graphden/timestamp (str (Timestamp/.toInstant %)))
                                          (instance? Instant %) (tagged-literal 'graphden/instant (str %))
                                          :else %) migration-artifact)))


(defn decode
  [text]
  (with-open [reader (PushbackReader. (StringReader. text))]
    (let [eof (Object.)
          opts {:eof eof :readers {'graphden/instant #(Instant/parse %)
                                   'graphden/timestamp #(Timestamp/from (Instant/parse %))}}
          decoded (edn/read opts reader)]
      (when (or (identical? eof decoded) (not (identical? eof (edn/read opts reader))))
        (throw (ex-info "Expected exactly one migration artifact" {:type :input/invalid-artifact})))
      decoded)))


(defn- check-artifact!
  [{:keys [plan source target-fingerprint]}]
  (when (seq (:exceptions plan))
    (throw (ex-info "Migration has unsupported definitions"
                    {:type :input/migration-incomplete :exceptions (:exceptions plan)})))
  (when-not (and (= :graphden/structured-input-storage-v2 (:format plan))
                 (= (:source-fingerprint plan) (migration/fingerprint source))
                 (= target-fingerprint (migration/fingerprint (migration/target-snapshot source plan)))
                 ;; Regenerate the plan: callers cannot smuggle extra mutations
                 ;; behind checksums that they recomputed themselves.
                 (= plan (migration/plan source)))
    (throw (ex-info "Migration artifact failed its source/plan checks"
                    {:type :input/invalid-artifact}))))


(def ^:private addition-order
  [:fn :fn-version :fn-slot :fn-slot-version :input-binding :input-binding-version])


(defn- write-plan!
  [storage {:keys [changes additions]}]
  (doseq [entity addition-order
          :let [rows (get additions entity)]
          :when (seq rows)]
    (sp/create-entities storage entity rows))
  (doseq [entity [:fn :fn-version]
          :let [rows (get changes entity)]
          :when (seq rows)]
    (sp/update-entities storage entity (mapv #(select-keys % [:id :input-type-fn-id]) rows))))


(defn- assert-fingerprint!
  [expected actual]
  (when-not (= expected actual)
    (throw (ex-info "Database changed since the migration snapshot"
                    {:type :input/stale-snapshot}))))


(defn apply!
  "Apply one checked artifact atomically. Exact target retries are no-ops.
   All preconditions run under the ordinary global semantic writer lock."
  [storage {:keys [plan target-fingerprint] :as migration-artifact}]
  (check-artifact! migration-artifact)
  (binding [access/*offline?* true]
    (writer/with-write [storage :fn]
                       (let [current (migration/fingerprint (snapshot storage))]
                         (if (= current target-fingerprint)
                           {:status :already-applied}
                           (do
                             (assert-fingerprint! (:source-fingerprint plan) current)
                             (write-plan! storage plan)
                             (resolution/call-with-fresh-memos
                               (fn []
                                 (doseq [branch (sp/query-entities storage :branch {})]
                                   (input-storage/validate-view! (input-storage/read-view storage (:id branch))))))
                             (assert-fingerprint! target-fingerprint (migration/fingerprint (snapshot storage)))
                             {:status :applied}))))))


(defn restore!
  "Exact rehearsal rollback. Refuse to erase any edits made after migration."
  [storage {:keys [plan source target-fingerprint] :as migration-artifact}]
  (check-artifact! migration-artifact)
  (binding [access/*offline?* true]
    (writer/with-write [storage :fn]
                       (assert-fingerprint! target-fingerprint (migration/fingerprint (snapshot storage)))
                       (doseq [entity [:fn :fn-version]
                               :let [changed-ids (set (map :id (get-in plan [:changes entity])))
                                     originals (filterv #(contains? changed-ids (:id %)) (get source entity))]
                               :when (seq originals)]
                         (sp/update-entities storage entity (mapv #(hash-map :id (:id %) :input-type-fn-id nil) originals)))
                       (doseq [entity (reverse addition-order)
                               :let [ids (mapv :id (get-in plan [:additions entity]))]
                               :when (seq ids)]
                         (sp/delete-entities storage entity ids))
                       (assert-fingerprint! (:source-fingerprint plan) (migration/fingerprint (snapshot storage)))
                       {:status :restored})))
