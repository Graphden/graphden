(ns graphden.crud.inheritance.snapshot
  (:require [graphden.accounts.crypto :as crypto]
            [graphden.storage.protocol.core :as sp]
            [graphden.util.ns-path :as ns-path]
            [graphden.versioning.storage.core :as vs]
            [graphden.versioning.storage.resolution :as res]))

(defn required-fn
  [storage id]
  (or (sp/read-entity storage :fn id)
      (throw (ex-info "Function is not available" {:type :not-found}))))

(defn closure
  "Bounded ancestry, rejecting incomplete or inaccessible parent identities."
  [storage seeds]
  (loop [seen {} frontier (set (remove nil? seeds))]
    (if (empty? frontier) seen
        (let [rows (sp/read-entities storage :fn (vec frontier))]
          (when-not (= frontier (set (keys rows)))
            (throw (ex-info "The inheritance source is incomplete or unavailable"
                            {:type :inheritance/incomplete-source})))
          (let [all (merge seen rows)]
            (recur all (into #{} (comp (mapcat :parent-ids) (remove #(contains? all %))) (vals rows))))))))

(defn graph-rows
  [storage fn-rows]
  (let [ids (vec (keys fn-rows))
        fn-slots (if (seq ids) (vec (sp/query-entities storage :fn-slot {:fn-id ids})) [])
        bindings (if (seq ids) (vec (sp/query-entities storage :binding {:fn-id ids})) [])
        slot-ids (vec (distinct (concat (keep :slot-id fn-slots) (keep :slot-id bindings))))
        slots (if (seq slot-ids) (sp/read-entities storage :slot slot-ids) {})
        items (if (seq bindings)
                (vec (sp/query-entities storage :binding-list-item {:binding-id (mapv :id bindings)})) [])]
    (when-not (= (set slot-ids) (set (keys slots)))
      (throw (ex-info "An own slot identity is unavailable" {:type :inheritance/incomplete-source})))
    {:fn (vals fn-rows) :fn-slot fn-slots :binding bindings :slot (vals slots)
     :binding-list-item items}))

(defn own-rows
  [rows id]
  (let [bindings (filterv #(= id (:fn-id %)) (:binding rows))
        bids (set (map :id bindings))]
    {:fn-slot (filterv #(= id (:fn-id %)) (:fn-slot rows))
     :binding bindings
     :binding-list-item (filterv #(contains? bids (:binding-id %)) (:binding-list-item rows))}))

(defn descriptors
  [storage fn-rows]
  (let [paths (ns-path/path-map (sp/query-entities storage :ns {}))]
    (into {} (map (fn [row]
                    [(:id row) (cond-> (select-keys row [:id :name :namespace-id])
                                 (:name row)
                                 (assoc :qualified-name
                                        (if-let [path (get paths (:namespace-id row))]
                                          (str path "/" (:name row)) (:name row))))])) fn-rows)))

(defn- canonical
  [value]
  (cond (map? value) (into (sorted-map-by #(compare (pr-str %1) (pr-str %2)))
                          (map (fn [[key v]] [key (canonical v)])) value)
        (set? value) (sort-by pr-str (map canonical value))
        (sequential? value) (mapv canonical value)
        :else value))

(defn fingerprint
  "Rows and latest versions in all branches; a foreign version can alter the
   cross-branch guard even when the current resolved graph is unchanged."
  [storage command rows]
  (let [base (vs/unwrap storage)
        versions (into {}
                       (keep (fn [[entity {:keys [version-entity version-id-field]}]]
                               (when-let [ids (seq (map :id (get rows entity)))]
                                 [version-entity
                                  (sp/query-latest-per-group base version-entity
                                                             {version-id-field (vec ids)}
                                                             [version-id-field :branch-id])])))
                       res/entity-config)
        branch-ids (into #{(:branch-id storage)} (keep :branch-id) (mapcat val versions))
        branches (sp/read-entities base :branch (vec (remove nil? branch-ids)))
        shape {:command (dissoc command :expected-state :accepted-orphan-binding-ids)
               :branch-id (:branch-id storage) :rows (update-vals rows #(sort-by (comp str :id) %))
               :versions (update-vals versions #(sort-by (comp str :id) %)) :branches branches
               :namespaces (sort-by (comp str :id) (sp/query-entities storage :ns {}))}]
    (crypto/sha256-hex (pr-str (canonical shape)))))
