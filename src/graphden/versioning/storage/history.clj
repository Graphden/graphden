(ns graphden.versioning.storage.history
  "Bounded metadata feed for changes to one fn and its owned graph rows.
   Version payloads stay in storage; this feed does not grant source access."
  (:require
    [clojure.string :as str]
    [graphden.storage.postgres.util :as pg-util]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.versioning.storage.core :as vs]))


(def ^:private max-page-size 100)
(def ^:private max-offset 10000)


(def ^:private event-source-sql
  (str "SELECT entity_kind, entity_id, branch_id, change_id, created_at, "
       "deleted_at, author_label FROM ("
       "SELECT 'fn' AS entity_kind, id AS entity_id, branch_id, change_id, "
       "created_at, deleted_at, author_label FROM fn_version WHERE fn_id = ? "
       "UNION ALL "
       "SELECT 'fn-slot', id, branch_id, change_id, created_at, deleted_at, "
       "author_label FROM fn_slot_version WHERE fn_id = ? "
       "UNION ALL "
       "SELECT 'binding', id, branch_id, change_id, created_at, deleted_at, "
       "author_label FROM binding_version WHERE fn_id = ? "
       "UNION ALL "
       "SELECT 'list-item', v.id, v.branch_id, v.change_id, v.created_at, "
       "v.deleted_at, v.author_label FROM binding_list_item_version v "
       "JOIN binding b ON b.id = v.binding_id WHERE b.fn_id = ?"
       ") e WHERE branch_id IN (%s) "))


(def ^:private event-order-sql
  "ORDER BY created_at DESC, entity_id DESC LIMIT ? OFFSET ?")


(def ^:private group-order-sql
  (str "GROUP BY COALESCE(change_id, entity_id), change_id "
       "ORDER BY created_at DESC, group_id DESC LIMIT ? OFFSET ?"))


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
   :created-at (str (:created_at row))
   :deleted? (some? (:deleted_at row))
   :author-label (:author_label row)
   :has-change-id? (some? (:change_id row))})


(defn- group-view
  [row]
  {:group-id (str (:group_id row))
   :change-id (some-> (:change_id row) str)
   :created-at (str (:created_at row))
   :branch-id (some-> (:branch_id row) str)
   :kinds (:kinds row)
   :event-count (:event_count row)
   :deleted? (:deleted row)
   :author-label (:author_label row)
   :has-change-id? (some? (:change_id row))})


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
                     "change_id, MAX(created_at) AS created_at, "
                     "MIN(branch_id::text) AS branch_id, "
                     "STRING_AGG(DISTINCT entity_kind, ', ' ORDER BY entity_kind) AS kinds, "
                     "COUNT(*) AS event_count, "
                     "BOOL_OR(deleted_at IS NOT NULL) AS deleted, "
                     "MIN(author_label) AS author_label FROM ("
                     (format event-source-sql %) ") scoped_events "
                     group-order-sql))]
    {:groups (mapv group-view (take limit rows))
     :next-offset (when (> (count rows) limit) (+ offset limit))}))
