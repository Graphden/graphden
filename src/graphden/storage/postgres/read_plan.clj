(ns graphden.storage.postgres.read-plan
  "Shared HoneySQL read plans. Scalar and grouped execution retain one query contract."
  (:require
    [graphden.storage.postgres.codec :as codec]
    [graphden.storage.postgres.util :as util]
    [graphden.storage.protocol.core :as sp]))


(defn where-clause
  "Builds the HoneySQL `:where` vector from a `{field value}` map,
   with the same semantics `query-entities` exposes: nil → IS NULL,
   collection → IN, else → =. Returns nil when `where` is empty so
   callers can `cond-> ... when-let`-style splice."
  [where fields]
  (when (seq where)
    (into [:and]
          (map (fn [[k v]]
                 (let [col (keyword (util/kw->snake-case k))
                       field-spec (get fields k)
                       encoded-v (codec/encode-value v field-spec)]
                   (cond
                     (nil? encoded-v)
                     [:is col nil]

                     ;; Collection = IN clause (for batch lookups). An EMPTY
                     ;; collection can't be `col IN ()` (invalid SQL in
                     ;; PostgreSQL) — it matches nothing, so emit an
                     ;; always-false predicate and let the query return [].
                     (and (or (vector? v) (set? v) (seq? v))
                          (not (map? v)))
                     (if (empty? v)
                       [:= [:inline 1] [:inline 0]]
                       [:in col (vec (map #(codec/encode-value % field-spec) v))])

                     :else
                     [:= col encoded-v])))
               where))))


(defn by-ids
  [entity ids]
  {:select [:*] :from [(keyword (util/kw->snake-case entity))]
   :where [:in :id (vec ids)]})


(defn query
  [entity where fields opts]
  (sp/standard-query-validations! entity fields where)
  (sp/validate-query-opts! entity opts)
  (let [predicate (where-clause where fields)
        order (when-let [columns (:order-by opts)]
                (mapv (fn [[column direction]]
                        [(keyword (util/kw->snake-case column)) direction]) columns))]
    (cond-> {:select [:*] :from [(keyword (util/kw->snake-case entity))]}
      predicate (assoc :where predicate)
      order (assoc :order-by order)
      (:limit opts) (assoc :limit (:limit opts))
      (:offset opts) (assoc :offset (:offset opts)))))


(defn latest
  [entity where group-cols fields]
  (sp/standard-query-validations! entity fields where)
  (when (or (empty? group-cols) (not (every? keyword? group-cols)))
    (throw (ex-info "query-latest-per-group requires non-empty keyword group-cols"
                    {:type :storage-error/invalid-group-cols
                     :entity-name entity :group-cols group-cols})))
  (let [columns (mapv #(keyword (util/kw->snake-case %)) group-cols)
        predicate (where-clause where fields)]
    (cond-> {:select-distinct-on (into [columns] [:*])
             :from [(keyword (util/kw->snake-case entity))]
             :order-by (conj columns [:created_at :desc])}
      predicate (assoc :where predicate))))
