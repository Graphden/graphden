(ns graphden.versioning.storage.source-projection
  "Fresh structural ownership proof, then ordinary version resolution.
   Neither historical probes nor frozen-version reads transfer literal values."
  (:require
    [clojure.set :as set]
    [graphden.storage.bounded-query :as bounded]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.source-projection :as projection]
    [graphden.versioning.storage.resolution :as resolution]))


(def ^:private configs
  {:fn-slot {:version :fn-slot-version :id-field :fn-slot-id :owner-field :fn-id
             :edges [:id :fn-slot-id :branch-id :created-at :deleted-at :fn-id :slot-id :position]}
   :binding {:version :binding-version :id-field :binding-id :owner-field :fn-id
             :edges [:id :binding-id :branch-id :created-at :deleted-at :fn-id :slot-id
                     :ref-fn-id :type-override-fn-id :resolver-fn-id]}
   :binding-list-item {:version :binding-list-item-version :id-field :item-id :owner-field :binding-id
                       :edges [:id :item-id :branch-id :created-at :deleted-at :binding-id :position :ref-fn-id]}})


(defn- candidate-ids
  [storage entity where budget]
  (let [remaining (- 16384 @budget)
        ids (bounded/candidate-ids storage entity where
                                   (select-keys (get resolution/entity-config entity)
                                                [:version-entity :version-id-field :version-data-fields])
                                   remaining)]
    (swap! budget + (or (::sp/candidate-count (meta ids)) (count ids)))
    (set ids)))


(defn- physical
  [storage scope budget request]
  (when-not (satisfies? sp/StorageSourceProjection storage) (projection/unavailable!))
  (let [remaining (- 65536 @budget)]
    (when (neg? remaining) (projection/unavailable!))
    (let [rows (sp/read-source-projection storage (assoc request :scope scope :max-rows remaining))]
      (swap! budget + (count rows))
      rows)))


(defn- branches
  [plan]
  (into (set (:branch-chain plan)) (map :source-branch-id) (:all-merges plan)))


(defn- probe
  [storage scope budget entity ids plan allowed-owners]
  (let [{:keys [version id-field owner-field]} (get configs entity)
        rows (if (seq ids)
               (physical storage scope budget
                         {:op :query :entity version
                          :fields (vec (distinct [:id id-field :branch-id :created-at :deleted-at owner-field]))
                          :where {id-field (vec ids) :branch-id (vec (branches plan))}})
               [])]
    (when (or (some #(or (nil? (:id %)) (nil? (:created-at %))
                         (not (contains? ids (get % id-field)))
                         (not (contains? allowed-owners (get % owner-field)))) rows)
              (some #(< 1 (count %)) (vals (group-by (juxt id-field :branch-id :created-at) rows)))
              (not= (count rows) (count (set (map :id rows)))))
      (projection/unavailable!))
    rows))


(defn- reject-interface-bodies!
  [owners entity rows]
  (doseq [row rows
          :let [permission (get-in owners [(:fn-id row) :permission])]]
    (when (or (and (= entity :fn-slot) (= :bare-type permission))
              (and (= entity :binding)
                   (or (= :bare-type permission) (:type-contract permission))))
      (projection/unavailable!))))


(defn- version-map
  [entity rows]
  (let [id-field (get-in configs [entity :id-field])]
    (into {} (map (fn [row] [(:id row) {:entity entity :id (get row id-field)}])) rows)))


(defn- acquire
  [storage scope budget entity ids plan probes binding-owners where]
  (let [{:keys [version edges]} (get configs entity)
        edge-scope (projection/versions-scope scope storage (version-map entity probes))
        versions (if (seq probes)
                   (physical storage edge-scope budget
                             {:op :read-ids :entity version :fields edges :ids (mapv :id probes)}) [])
        identities (if (seq ids)
                     (physical storage scope budget
                               {:op :read-ids :entity entity :fields [:id :org-id] :ids (vec ids)}) [])]
    (when-not (= (set (map :id probes)) (set (map :id versions))) (projection/unavailable!))
    (resolution/resolve-source-projection storage entity plan versions
                                          (into {} (map (juxt :id identity)) identities)
                                          binding-owners where)))


(defn collect-edges
  "Only a fresh, live owner authorization witness can request all edge kinds."
  [outer storage branch-id scope]
  (let [{:keys [owners phase]} (projection/scope-data scope outer)
        _ (when-not (= phase :owners) (projection/unavailable!))
        owner-ids (set (keys owners)) budget (atom 0) physical-budget (atom 0)
        where {:fn-id (vec owner-ids)}
        candidates (into {} (map #(vector % (candidate-ids storage % where budget))) [:fn-slot :binding])
        plans (into {} (map (fn [[entity ids]]
                              [entity (resolution/source-projection-plan storage entity ids branch-id)])) candidates)
        scope* (projection/resolution-scope scope outer storage candidates (apply set/union (map branches (vals plans))))
        probes (into {} (map (fn [[entity ids]]
                               [entity (probe storage scope* physical-budget entity ids (get plans entity) owner-ids)])) candidates)
        _ (doseq [[entity rows] probes] (reject-interface-bodies! owners entity rows))
        fn-slots (acquire storage scope* physical-budget :fn-slot (:fn-slot candidates)
                          (:fn-slot plans) (:fn-slot probes) {} where)
        bindings (acquire storage scope* physical-budget :binding (:binding candidates)
                          (:binding plans) (:binding probes) {} where)
        binding-ids (set (map :id bindings))
        raw-bindings (if (seq binding-ids)
                       (physical storage scope* physical-budget
                                 {:op :read-ids :entity :binding :fields [:id :org-id :fn-id] :ids (vec binding-ids)}) [])
        _ (when (some #(and (:fn-id %) (not (contains? owner-ids (:fn-id %)))) raw-bindings)
            (projection/unavailable!))
        item-where {:binding-id (vec binding-ids)}
        item-ids (if (seq binding-ids) (candidate-ids storage :binding-list-item item-where budget) #{})
        item-plan (resolution/source-projection-plan storage :binding-list-item item-ids branch-id)
        item-scope (projection/resolution-scope scope outer storage (assoc candidates :binding-list-item item-ids)
                                                (branches item-plan))
        item-probes (probe storage item-scope physical-budget :binding-list-item item-ids item-plan binding-ids)
        items (acquire storage item-scope physical-budget :binding-list-item item-ids item-plan item-probes
                       (into {} (map (juxt :id :fn-id)) raw-bindings) item-where)]
    {:fn-slots fn-slots :bindings bindings :list-items items :candidate-count @budget}))
