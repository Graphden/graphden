(ns graphden.versioning.storage.history
  "Bounded change metadata, coherent historical snapshots and field diffs for
   one fn's owned graph. The metadata feed never includes version payloads;
   snapshot callers must use a scoped storage and redact resolver values."
  (:require
    [clojure.set :as set]
    [clojure.string :as str]
    [graphden.storage.postgres.util :as pg-util]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tenancy]
    [graphden.versioning.branch-local :as branch-local]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.field-diff :as field-diff]
    [graphden.versioning.storage.resolution :as resolution]
    [next.jdbc :as jdbc]
    [next.jdbc.transaction :as jdbc-tx])
  (:import
    (java.sql
      Connection)))


(def ^:private max-page-size 100)
(def ^:private max-offset 10000)


(def ^:private event-source-sql
  (str "SELECT entity_kind, entity_id, branch_id, change_id, change_revision, created_at, "
       "deleted_at, author_label FROM ("
       "SELECT 'fn' AS entity_kind, id AS entity_id, branch_id, change_id, change_revision, "
       "created_at, deleted_at, author_label FROM fn_version WHERE fn_id = ? "
       "UNION ALL "
       "SELECT 'fn-slot', id, branch_id, change_id, change_revision, created_at, deleted_at, "
       "author_label FROM fn_slot_version WHERE fn_id = ? "
       "UNION ALL "
       "SELECT 'binding', id, branch_id, change_id, change_revision, created_at, deleted_at, "
       "author_label FROM binding_version WHERE fn_id = ? "
       "UNION ALL "
       "SELECT 'list-item', v.id, v.branch_id, v.change_id, v.change_revision, v.created_at, "
       "v.deleted_at, v.author_label FROM binding_list_item_version v "
       "JOIN binding b ON b.id = v.binding_id WHERE b.fn_id = ?"
       ") e WHERE branch_id IN (%s) "))


(def ^:private event-order-sql
  "ORDER BY change_revision DESC NULLS LAST, created_at DESC, entity_id DESC LIMIT ? OFFSET ?")


(def ^:private group-order-sql
  (str "GROUP BY COALESCE(change_id, entity_id), change_id "
       "ORDER BY change_revision DESC NULLS LAST, created_at DESC, group_id DESC LIMIT ? OFFSET ?"))


(defn- page-params
  [limit offset]
  (when-not (and (integer? limit) (<= 1 limit max-page-size)
                 (integer? offset) (<= 0 offset max-offset))
    (throw (ex-info "History page is outside the supported bounds"
                    {:type :history/invalid-page})))
  [limit offset])


(defn- event-view
  [row]
  {:kind (:entity_kind row)
   :version-id (str (:entity_id row))
   :branch-id (some-> (:branch_id row) str)
   :change-id (some-> (:change_id row) str)
   :change-revision (:change_revision row)
   :created-at (str (:created_at row))
   :deleted? (some? (:deleted_at row))
   :author-label (:author_label row)
   :has-change-id? (some? (:change_id row))
   :has-change-revision? (some? (:change_revision row))})


(defn- group-view
  [row]
  {:group-id (str (:group_id row))
   :change-id (some-> (:change_id row) str)
   :change-revision (:change_revision row)
   :created-at (str (:created_at row))
   :branch-id (some-> (:branch_id row) str)
   :kinds (:kinds row)
   :event-count (:event_count row)
   :deleted? (:deleted row)
   :author-label (:author_label row)
   :has-change-id? (some? (:change_id row))
   :has-change-revision? (some? (:change_revision row))})


(defn- visible-page
  [storage fn-id limit offset row-query]
  (let [[limit offset] (page-params limit offset)]
    (when (sp/read-entity storage :fn fn-id)
      (let [base (vs/unwrap storage)
            branches (sp/query-entities base :branch {} {:limit 1001})
            _ (when (> (count branches) 1000)
                (throw (ex-info "History branch scope is too large"
                                {:type :history/branch-scope-too-large})))
            branch-ids (mapv :id branches)
            placeholders (str/join "," (repeat (count branch-ids) "?"))]
        (if (empty? branch-ids)
          []
          (pg-util/exec! (tx/datasource base)
                         (into [(row-query placeholders)]
                               (concat (repeat 4 fn-id) branch-ids
                                       [(inc limit) offset]))))))))


(defn change-events
  "One page of raw change metadata across a fn, its fn-slots, bindings and
   list items. A missing or unreadable fn returns nil. Old rows without a
   change-id cannot be grouped into a transaction. No payload values are returned."
  [storage fn-id limit offset]
  (when-let [rows (visible-page storage fn-id limit offset
                                #(str (format event-source-sql %)
                                      event-order-sql))]
    {:events (mapv event-view (take limit rows))
     :next-offset (when (> (count rows) limit) (+ offset limit))}))


(defn change-groups
  "Page transactions, keeping all writes from one change-id together. Old
   rows each form a separate ungrouped entry; no historical snapshot is
   inferred for them. The query aggregates in PostgreSQL before paging."
  [storage fn-id limit offset]
  (when-let [rows
             (visible-page
               storage fn-id limit offset
               #(str "SELECT COALESCE(change_id, entity_id) AS group_id, "
                     "change_id, MAX(change_revision) AS change_revision, "
                     "MAX(created_at) AS created_at, "
                     "MIN(branch_id::text) AS branch_id, "
                     "STRING_AGG(DISTINCT entity_kind, ', ' ORDER BY entity_kind) AS kinds, "
                     "COUNT(*) AS event_count, "
                     "BOOL_OR(deleted_at IS NOT NULL) AS deleted, "
                     "MIN(author_label) AS author_label FROM ("
                     (format event-source-sql %) ") scoped_events "
                     group-order-sql))]
    {:groups (mapv group-view (take limit rows))
     :next-offset (when (> (count rows) limit) (+ offset limit))}))


(def ^:private max-snapshot-rows 2000)


(defn- owned-rows
  "Find every identity ever owned by this fn, including rows later removed or
   moved. Membership lives in version rows, so the current identity projection
   cannot establish historical ownership. Both reads are bounded and batched."
  [storage entity-name version-entity id-field where]
  (let [versions (sp/query-entities storage version-entity where
                                    {:limit 10001})
        _ (when (> (count versions) 10000)
            (throw (ex-info "Historical ownership scan exceeds its bound"
                            {:type :history/too-large})))
        ids (into [] (comp (keep id-field) (distinct)) versions)]
    (when (> (count ids) max-snapshot-rows)
      (throw (ex-info "Historical graph exceeds its identity bound"
                      {:type :history/too-large})))
    (if (seq ids)
      (vec (vals (sp/read-entities storage entity-name ids)))
      [])))


(defn- rows-at
  [base entity-name identities branch-id revision]
  (let [ids (mapv :id identities)
        candidates (resolution/winning-candidates-at
                     base entity-name ids branch-id revision)
        fields (get-in resolution/entity-config [entity-name :version-data-fields])]
    (->> ids
         (keep (fn [id]
                 (when-let [version (get-in candidates [id :version])]
                   (when-not (resolution/tombstone? version)
                     (assoc (select-keys version fields) :id id)))))
         (sort-by (comp str :id))
         vec)))


(defn- dependency-anchors
  "Pin readable referenced functions to their version at this revision.
   Names come from that version, never from today's identity projection."
  [base fn-id branch-id revision rows]
  (let [slot-ids (into #{} (keep :slot-id)
                       (concat (:fn-slots rows) (:bindings rows)))
        slots (when (seq slot-ids)
                (vals (sp/read-entities base :slot (vec slot-ids))))
        ids (disj (field-diff/ref-ids
                    (concat (when-let [f (:fn rows)] [f])
                            (:fn-slots rows) (:bindings rows)
                            (:list-items rows) slots))
                  fn-id)]
    (when (> (count ids) max-snapshot-rows)
      (throw (ex-info "Historical dependency set exceeds its bound"
                      {:type :history/too-large})))
    (if (empty? ids)
      []
      (let [visible-ids (keys (sp/read-entities base :fn (vec ids)))
            candidates (resolution/winning-candidates-at
                         base :fn visible-ids branch-id revision)]
        (->> visible-ids
             (map (fn [id]
                    (let [version (get-in candidates [id :version])]
                      {:fn-id (str id)
                       :version-id (some-> version :id str)
                       :name (when (and version
                                        (not (resolution/tombstone? version)))
                               (:name version))
                       :revision-pinned? (boolean version)
                       :deleted? (boolean (and version
                                               (resolution/tombstone? version)))})))
             (sort-by :fn-id)
             vec)))))


(defn- graph-identities
  "Load the bounded identity set once for both sides of a diff."
  [storage fn-id]
  (let [base (vs/unwrap storage)
        bindings (owned-rows base :binding :binding-version :binding-id
                             {:fn-id fn-id})
        binding-ids (mapv :id bindings)
        slots (owned-rows base :fn-slot :fn-slot-version :fn-slot-id
                          {:fn-id fn-id})
        items (if (seq binding-ids)
                (owned-rows base :binding-list-item :binding-list-item-version
                            :item-id {:binding-id binding-ids})
                [])]
    (when (> (+ (count slots) (count bindings) (count items)) max-snapshot-rows)
      (throw (ex-info "Historical graph exceeds its identity bound"
                      {:type :history/too-large})))
    {:fn-identity (sp/read-entity base :fn fn-id)
     :slots slots
     :bindings bindings
     :items items}))


(defn- current-version-anchors
  "The effective version IDs are a value-free CAS basis. One batched lookup
   per entity kind also captures winning tombstones and branch merges."
  [base branch-id {:keys [fn-identity slots bindings items]}]
  (->> [[:fn (some-> fn-identity :id vector)]
        [:fn-slot (mapv :id slots)]
        [:binding (mapv :id bindings)]
        [:binding-list-item (mapv :id items)]]
       (mapcat (fn [[kind ids]]
                 (when (seq ids)
                   (let [winners (resolution/winning-candidates-at
                                   base kind ids branch-id Long/MAX_VALUE)]
                     (keep (fn [id]
                             (when-let [version (get-in winners [id :version])]
                               [(name kind) (str id) (str (:id version))
                                (:change-revision version)]))
                           ids)))))
       (sort-by (juxt first second))
       vec))


(defn- snapshot-data
  [storage fn-id branch-id revision {:keys [fn-identity slots bindings items]}]
  (let [base (vs/unwrap storage)
        fn-row (first (rows-at base :fn [fn-identity] branch-id revision))
        slots-at (when fn-row
                   (filterv #(= fn-id (:fn-id %))
                            (rows-at base :fn-slot slots branch-id revision)))
        bindings-at (when fn-row
                      (filterv #(= fn-id (:fn-id %))
                               (rows-at base :binding bindings branch-id revision)))
        active-binding-ids (into #{} (map :id) bindings-at)
        items-at (when fn-row
                   (filterv #(contains? active-binding-ids (:binding-id %))
                            (rows-at base :binding-list-item items branch-id revision)))
        rows {:fn fn-row :fn-slots slots-at :bindings bindings-at
              :list-items items-at}]
    {:fn fn-row
     :fn-slots slots-at
     :bindings bindings-at
     :list-items items-at
     :dependency-versions (dependency-anchors base fn-id branch-id revision rows)
     :branch-id (str branch-id)
     :revision revision
     :current-namespace-id (:namespace-id fn-identity)
     :current-parent-ids (:parent-ids fn-identity)
     :parent-topology-historical? false
     :branch-topology-historical? false
     :complete? true}))


(defn- redact-resolver-values
  [snapshot]
  (let [secret-binding-ids (into #{} (comp (filter :resolver-fn-id) (map :id))
                                 (:bindings snapshot))]
    (-> snapshot
        (update :bindings
                (fn [rows]
                  (mapv (fn [row]
                          (if (contains? secret-binding-ids (:id row))
                            (assoc row :value nil :value-redacted? true)
                            row))
                        rows)))
        (update :list-items
                (fn [rows]
                  (mapv (fn [row]
                          (if (contains? secret-binding-ids (:binding-id row))
                            (assoc row :value nil :literal nil :value-redacted? true)
                            row))
                        rows))))))


(defn- with-coherent-read
  [storage fn-id branch-id f]
  (let [datasource (tx/datasource storage)]
    (when (or (nil? datasource) (instance? Connection datasource))
      (throw (ex-info "Historical snapshot needs an independent database pool"
                      {:type :history/snapshot-storage-unsupported})))
    (binding [jdbc-tx/*nested-tx* :prohibit
              resolution/*branch-chain-cache* (atom {})
              resolution/*merges-memo* (atom {})
              branch-local/*storage-caches-override* (atom {})]
      (jdbc/with-transaction [connection datasource
                              {:isolation :repeatable-read :read-only true}]
                             (let [storage (tx/with-connection storage connection)]
                               (when-let [fn-row (sp/read-entity (vs/unwrap storage) :fn fn-id)]
                                 (when (sp/read-entity storage :branch branch-id)
                                   ;; The identity may be readable while its composition is not.
                                   ;; Authorize before loading any historical binding payload.
                                   ((tenancy/browser-source-policy storage) fn-row)
                                   (f storage))))))))


(defn- exact-or-incomplete
  [context f]
  (try
    (f)
    (catch clojure.lang.ExceptionInfo e
      (if (= :history/incomplete-provenance (:type (ex-data e)))
        (assoc context :complete? false :reason :legacy-provenance)
        (throw e)))))


(defn snapshot-at
  "A coherent historical view of one readable fn and its owned graph rows.
   Exact snapshots refuse legacy rows without revision provenance; callers can
   still show their metadata feed with :complete? false. Resolver-bound values
   are redacted because they may contain a secret path. Referenced fn versions
   are pinned by version ID. Parent and branch topology are current identity
   data and are labelled accordingly."
  [storage fn-id branch-id revision]
  (with-coherent-read
    storage fn-id branch-id
    (fn [st]
      (exact-or-incomplete
        {:branch-id (str branch-id) :revision revision}
        #(redact-resolver-values
           (snapshot-data st fn-id branch-id revision (graph-identities st fn-id)))))))


(defn restore-snapshots-in-transaction
  "Trusted restore planner input: the historical target, current effective
   graph and their identity rows from ONE caller-owned transaction. The
   snapshots are unredacted; never return them to an HTTP client. Authorization
   is repeated here because apply calls this under a new writer transaction."
  [storage fn-id branch-id revision]
  (when-not (instance? Connection (tx/datasource storage))
    (throw (ex-info "Restore snapshots require a transaction-bound storage"
                    {:type :history/transaction-required})))
  (when-not (and (integer? revision) (pos? revision))
    (throw (ex-info "Restore revision must be positive"
                    {:type :history/invalid-revision})))
  (let [base (vs/unwrap storage)]
    (when-let [fn-identity (sp/read-entity base :fn fn-id)]
      (when (sp/read-entity storage :branch branch-id)
        ((tenancy/browser-source-policy storage) fn-identity)
        (binding [resolution/*branch-chain-cache* (atom {})
                  resolution/*merges-memo* (atom {})
                  branch-local/*storage-caches-override* (atom {})]
          (let [identities (graph-identities storage fn-id)]
            {:target (snapshot-data storage fn-id branch-id revision identities)
             :current (snapshot-data storage fn-id branch-id Long/MAX_VALUE identities)
             :current-anchors (current-version-anchors base branch-id identities)
             :identities identities}))))))


(defn with-restore-snapshots
  "Run a trusted planner within one read-only REPEATABLE READ transaction.
   The raw snapshots never escape the transaction or an HTTP response."
  [storage fn-id branch-id revision f]
  (with-coherent-read storage fn-id branch-id
    #(when-let [pair (restore-snapshots-in-transaction
                       % fn-id branch-id revision)]
       (f % pair))))


(defn- snapshot-index
  [snapshot]
  (into {}
        (for [[kind rows] [[:fn (some-> (:fn snapshot) vector)]
                           [:fn-slot (:fn-slots snapshot)]
                           [:binding (:bindings snapshot)]
                           [:list-item (:list-items snapshot)]]
              row rows]
          [[kind (:id row)] row])))


(defn- diff-entry
  [[kind id] before after secret-item-ids]
  (let [secret? (or (and (= kind :binding)
                         (or (:resolver-fn-id before) (:resolver-fn-id after)))
                    (and (= kind :list-item) (contains? secret-item-ids id)))
        fields (remove #{:id} (field-diff/changed-fields before after))]
    (when (seq fields)
      {:kind (name kind)
       :id (str id)
       :status (cond (nil? before) "added" (nil? after) "removed" :else "changed")
       :fields
       (mapv (fn [field]
               {:field (name field)
                :before (if (and secret? (#{:value :literal} field)) "•••"
                            (field-diff/display-value {} field (get before field)))
                :after (if (and secret? (#{:value :literal} field)) "•••"
                           (field-diff/display-value {} field (get after field)))
                :redacted? (boolean (and secret? (#{:value :literal} field)))})
             fields)})))


(defn diff-snapshots
  "Redacted owned-row diff for two snapshots already read in one transaction.
   The caller supplies the revision labels; values under resolver bindings
   remain masked even when the caller has their raw storage rows."
  [before-snapshot after-snapshot]
  (let [before (snapshot-index before-snapshot)
        after (snapshot-index after-snapshot)
        secret-binding-ids (into #{}
                                 (comp (filter :resolver-fn-id) (map :id))
                                 (concat (:bindings before-snapshot)
                                         (:bindings after-snapshot)))
        secret-item-ids (into #{}
                              (comp (filter #(contains? secret-binding-ids
                                                        (:binding-id %)))
                                    (map :id))
                              (concat (:list-items before-snapshot)
                                      (:list-items after-snapshot)))
        ids (sort-by (fn [[kind id]] [(name kind) (str id)])
                     (set/union (set (keys before)) (set (keys after))))
        entries (into []
                      (keep (fn [id]
                              (diff-entry id (get before id) (get after id)
                                          secret-item-ids)))
                      ids)]
    (when (> (count entries) max-snapshot-rows)
      (throw (ex-info "Historical diff exceeds its row bound"
                      {:type :history/too-large})))
    {:from-dependency-versions (:dependency-versions before-snapshot)
     :to-dependency-versions (:dependency-versions after-snapshot)
     :entries entries}))


(defn diff-at
  "A bounded, redacted field diff between two exact revisions on one branch.
   Both snapshots share one REPEATABLE READ transaction; dependency labels are
   short IDs because present-day names could mislabel historical references."
  [storage fn-id branch-id from-revision to-revision]
  (with-coherent-read
    storage fn-id branch-id
    (fn [st]
      (exact-or-incomplete
        {:branch-id (str branch-id)
         :from-revision from-revision :to-revision to-revision}
        (fn []
          (let [identities (graph-identities st fn-id)
                before-snapshot (snapshot-data st fn-id branch-id from-revision
                                               identities)
                after-snapshot (snapshot-data st fn-id branch-id to-revision
                                              identities)]
            (merge {:branch-id (str branch-id)
                    :from-revision from-revision
                    :to-revision to-revision
                    :complete? true}
                   (diff-snapshots before-snapshot after-snapshot))))))))
