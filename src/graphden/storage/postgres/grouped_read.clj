(ns graphden.storage.postgres.grouped-read
  "Bounded native typed read transport inside one explicitly owned snapshot."
  (:require
    [graphden.storage.grouped-read :as grouped]
    [graphden.storage.postgres.codec :as codec]
    [graphden.storage.postgres.junction :as junction]
    [graphden.storage.postgres.read-plan :as plan]
    [graphden.storage.postgres.util :as util]
    [honey.sql :as sql])
  (:import
    (java.util
      Locale)))


(def ^:private max-columns 256)
(def ^:private max-cells 262144)
(def ^:private max-sql-bytes 262144)


(defn- cardinality
  [value]
  (if (and (coll? value) (not (map? value))) (count value) 1))


(defn- row-bound
  [{:keys [op ids where group-cols]}]
  (case op
    :read-ids (count ids)
    :query (when (contains? where :id) (cardinality (:id where)))
    :latest (when (and (seq group-cols) (every? #(contains? where %) group-cols))
              (reduce *' 1 (map #(cardinality (get where %)) group-cols)))
    nil))


(defn- operation-plan
  [request fields]
  (let [{:keys [op entity ids where opts group-cols]} request
        bound (row-bound request)]
    (when (and fields bound (pos? bound) (<= bound max-cells)
               (or (not= op :query) (empty? opts)))
      (let [query (case op
                    :read-ids (plan/by-ids entity ids)
                    :query (plan/query entity where fields opts)
                    :latest (plan/latest entity where group-cols fields))
            parameters (sql/format query {:quoted true})]
        {:request request :entity entity :fields fields :query query :bound bound
         :parameter-count (dec (count parameters))}))))


(defn- plan-requests
  [requests fields-for]
  (loop [remaining requests result [] parameters 0]
    (if (empty? remaining)
      result
      (let [request (first remaining)
            planned (operation-plan request (fields-for (:entity request)))
            total (+ parameters (or (:parameter-count planned) 0))]
        ;; The zero-row type anchor repeats every subquery's parameters.
        (when (and planned (<= (* 2 total) util/*max-statement-params*))
          (recur (next remaining) (conj result planned) total))))))


(defn- shape-query
  [entities]
  (let [tables (mapv (comp keyword util/kw->snake-case) entities)
        locks (mapcat (fn [index table]
                        ;; Relation references acquire ACCESS SHARE locks during
                        ;; parse/analysis, even though these joins return no rows.
                        [[{:select [[[:inline 1] :unused]] :from [table]
                           :where [:= [:inline 1] [:inline 0]]}
                          (keyword (str "shape_lock_" index))]
                         [:= [:inline 1] [:inline 1]]])
                      (range) tables)]
    {:select [:c.relname :c.relkind :a.attname :t.typtype]
     :from [[:pg_catalog.pg_attribute :a]]
     :join [[:pg_catalog.pg_class :c] [:= :c.oid :a.attrelid]
            [:pg_catalog.pg_type :t] [:= :t.oid :a.atttypid]]
     :left-join (vec locks)
     :where [:and [:in :c.oid (mapv #(vector :to_regclass (name %)) tables)]
             [:> :a.attnum [:inline 0]] [:not :a.attisdropped]]
     :order-by [:c.relname :a.attnum]}))


(defn- physical-shapes!
  [connection cache entities]
  (let [missing (vec (remove #(contains? @cache %) (distinct entities)))]
    (when (seq missing)
      (let [rows (util/exec! connection (sql/format (shape-query missing) {:quoted true}))
            by-table (group-by :relname rows)
            shapes (into {}
                         (map (fn [entity]
                                (let [columns (get by-table (util/kw->snake-case entity))]
                                  [entity (when (and (seq columns)
                                                     (every? #(contains? #{"r" "p"} (str (:relkind %))) columns)
                                                     (not-any? #(= "d" (str (:typtype %))) columns)
                                                     (every? #(re-matches #"[a-z][a-z0-9_]*" (:attname %)) columns))
                                            (mapv (comp keyword :attname) columns))])))
                         missing)]
        (swap! cache merge shapes)))
    (select-keys @cache entities)))


(defn- layout
  [plans shapes]
  (vec (mapcat (fn [index {:keys [entity]}]
                 (map-indexed (fn [position column]
                                {:group index
                                 :column (keyword (.toLowerCase (name column) Locale/ROOT))
                                 :sql-alias (keyword (str "g" index "_c" position))
                                 :qualified (keyword (str "g" index "." (name column)))})
                              (get shapes entity)))
               (range) plans)))


(defn- transport-query
  [{:keys [query request]}]
  (if (= :latest (:op request))
    ;; DISTINCT ON chooses the same winners first. An outer window records its
    ;; scalar ORDER BY without depending on UNION branch/append ordering.
    {:select [:ordered_source.*
              [[:over [[:row_number] {:order-by (:order-by query)}]] :__gd_row_order]]
     :from [[query :ordered_source]]}
    query))


(defn- carrier
  [plans shapes]
  (when (every? #(let [columns (get shapes (:entity %))]
                   (and (seq columns) (not (some #{:__gd_row_order} columns)))) plans)
    (let [columns (layout plans shapes)
          cells (*' (+ 2 (count columns)) (reduce + (map :bound plans)))]
      (when (and (<= (count columns) max-columns) (<= cells max-cells))
        (let [subquery (fn [index] [(transport-query (nth plans index)) (keyword (str "g" index))])
              ;; Pairwise NULL UNION NULL resolves to text before later branches
              ;; are considered. Native zero-row columns anchor every SQL type.
              header {:select (into [[[:inline -1] :group_id] [[:inline 0] :row_order]]
                                    (map (juxt :qualified :sql-alias)) columns)
                      :from (mapv subquery (range (count plans)))
                      :where [:= [:inline 1] [:inline 0]]}
              branches (mapv (fn [index]
                               {:select (into [[[:inline index] :group_id]
                                               [(if (= :latest (get-in plans [index :request :op]))
                                                  (keyword (str "g" index ".__gd_row_order"))
                                                  [:inline 0]) :row_order]]
                                              (map (fn [{:keys [group qualified sql-alias]}]
                                                     [(when (= index group) qualified) sql-alias])) columns)
                                :from [(subquery index)]}) (range (count plans)))
              params (sql/format {:union-all (into [header] branches)} {:quoted true})]
          (when (and (<= (dec (count params)) util/*max-statement-params*)
                     (<= (alength (.getBytes ^String (first params) "UTF-8")) max-sql-bytes))
            {:params params :columns columns}))))))


(defn- split-rows
  [size columns rows]
  (let [by-group (group-by :group columns)
        groups (group-by :group_id rows)]
    (mapv (fn [index]
            (mapv (fn [row]
                    (into {} (map (fn [{:keys [column sql-alias]}] [column (get row sql-alias)]))
                          (get by-group index)))
                  (sort-by :row_order (get groups index []))))
          (range size))))


(defn- decode
  [connection {:keys [request fields entity]} rows]
  (let [read? (= :read-ids (:op request))
        decoded (mapv #(codec/row->entity % (when-not read? fields)) rows)
        populated (if (junction/has-ref-many? fields)
                    ;; Keep scalar junction ordering. UNION outer row order is
                    ;; not a replacement for owner_id/ord in junction queries.
                    (junction/populate-ref-many-fields connection entity decoded fields)
                    decoded)]
    (if read? (into {} (map (juxt :id identity)) populated) populated)))


(defn read-operations
  "Group bounded reads under the matching active RR cache. Latest-per-group
   carries an explicit scalar-order ordinal; ordinary ordered queries fallback.
   Unsupported groups stay scalar; candidate barriers never perform a shape
   read or payload query here. Actual physical columns preserve SELECT * rows,
   including legacy columns absent from schema metadata."
  [connection requests fields-for scalar-operation]
  (let [fallback #(mapv scalar-operation requests)]
    (if-let [cache (and (<= 2 (count requests) grouped/max-group-size)
                        (not-any? #(= :candidates (:op %)) requests)
                        (grouped/snapshot-cache connection))]
      (if-let [planned (plan-requests requests fields-for)]
        (let [shapes (physical-shapes! connection (:shapes cache) (map :entity planned))]
          (if-let [{:keys [params columns]} (carrier planned shapes)]
            (util/with-sql-error-handling
              "Database error" :read-operations {:operation-count (count requests)}
              (let [rows (util/exec! connection params)
                    groups (split-rows (count planned) columns rows)]
                (mapv #(decode connection %1 %2) planned groups)))
            (fallback)))
        (fallback))
      (fallback))))
