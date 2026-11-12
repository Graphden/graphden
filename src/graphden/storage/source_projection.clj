(ns graphden.storage.source-projection
  "Sealed, snapshot-local authority for structural source discovery.
   These witnesses are internal objects, never accepted from EDN/HTTP graphs."
  (:require
    [graphden.storage.grouped-read :as grouped]
    [graphden.storage.tx :as tx]))


(defn unavailable!
  []
  (throw (ex-info "Structural source projection unavailable"
                  {:type :storage-error/source-projection-unsupported})))


(deftype ^:private Scope
  [storage snapshot check! owners identities branches versions phase])


(defn authorize-scope!
  "Authorize every fresh metadata owner before any structural source query."
  ([storage rows authorize! validate-owner!]
   (authorize-scope! storage rows authorize! validate-owner! (fn [])))
  ([storage rows authorize! validate-owner! check!]
   (check!)
   (let [snapshot (grouped/snapshot-cache (tx/datasource storage))]
     (when-not snapshot (unavailable!))
     (let [owners (into {} (map (fn [row]
                                  (check!)
                                  (let [permission (authorize! row)]
                                    (validate-owner! row permission)
                                    [(:id row) {:row row :permission permission}]))) rows)]
       (Scope. storage snapshot check! owners {} #{} {} :owners)))))


;; Generated deftype fields have no equivalent method-value accessor.
^{:splint/disable [#_{:clj-kondo/ignore [:unresolved-namespace]} lint/prefer-method-values]}
(defn scope-data
  "Only the exact decorated immutable snapshot may consume a witness."
  [scope storage]
  (when-not (and (instance? Scope scope) (identical? storage (.-storage ^Scope scope))
                 (identical? (.-snapshot ^Scope scope)
                             (grouped/snapshot-cache (tx/datasource storage))))
    (unavailable!))
  ((.-check! ^Scope scope))
  {:snapshot (.-snapshot ^Scope scope) :check! (.-check! ^Scope scope)
   :owners (.-owners ^Scope scope) :identities (.-identities ^Scope scope)
   :branches (.-branches ^Scope scope) :versions (.-versions ^Scope scope)
   :phase (.-phase ^Scope scope)})


(defn retarget
  "A known decorator explicitly preserves the seal when delegating inward."
  [scope from to]
  (let [{:keys [snapshot check! owners identities branches versions phase]} (scope-data scope from)]
    (when-not (identical? snapshot (grouped/snapshot-cache (tx/datasource to))) (unavailable!))
    (Scope. to snapshot check! owners identities branches versions phase)))


(defn resolution-scope
  "Called only by the branch resolver, with its own fresh chain/merge branches."
  [scope outer storage identities branches]
  (let [{:keys [snapshot check! owners phase]} (scope-data scope outer)]
    (when-not (= :owners phase) (unavailable!))
    (when-not (identical? snapshot (grouped/snapshot-cache (tx/datasource storage))) (unavailable!))
    (Scope. storage snapshot check! owners identities (set branches) {} :ownership)))


(defn versions-scope
  "Freeze version UUIDs only after their complete physical ownership proof."
  [scope storage versions]
  (let [{:keys [snapshot check! owners identities branches phase]} (scope-data scope storage)]
    (when-not (= :ownership phase) (unavailable!))
    (Scope. storage snapshot check! owners identities branches versions :edges)))


(def physical-fields
  {:fn-slot #{:id :org-id :fn-id}
   :binding #{:id :org-id :fn-id}
   :binding-list-item #{:id :org-id :binding-id}
   :fn-slot-version #{:id :fn-slot-id :branch-id :created-at :deleted-at :fn-id :slot-id :position}
   :binding-version #{:id :binding-id :branch-id :created-at :deleted-at :fn-id :slot-id
                      :ref-fn-id :type-override-fn-id :resolver-fn-id}
   :binding-list-item-version #{:id :item-id :branch-id :created-at :deleted-at :binding-id :position :ref-fn-id}})


(def version-identities
  {:fn-slot-version [:fn-slot :fn-slot-id]
   :binding-version [:binding :binding-id]
   :binding-list-item-version [:binding-list-item :item-id]})


(defn validate-physical!
  "A transport may neither widen the projection nor read another branch/UUID."
  [storage {:keys [scope op entity fields ids where max-rows]}]
  (let [{:keys [identities branches versions phase] :as authority} (scope-data scope storage)
        [identity-entity identity-field] (get version-identities entity)
        allowed (get physical-fields entity)
        requested (set fields)
        owner-fields (cond-> #{:id :org-id :branch-id :created-at :deleted-at :fn-id :binding-id}
                       identity-field (conj identity-field))]
    (when-not (and (contains? #{:ownership :edges} phase)
                   (seq allowed) (seq requested) (every? allowed requested)
                   (integer? max-rows) (<= 0 max-rows 65536)
                   (case op
                     :read-ids (and (coll? ids)
                                    (or (= :edges phase) (every? owner-fields requested))
                                    (if identity-entity
                                      (every? #(= identity-entity (:entity (get versions %))) ids)
                                      (every? (get identities entity #{}) ids)))
                     :query (and identity-entity (= :ownership phase)
                                 (= #{identity-field :branch-id} (set (keys where)))
                                 (every? owner-fields requested)
                                 (every? (get identities identity-entity #{}) (get where identity-field))
                                 (every? branches (:branch-id where)))
                     false))
      (unavailable!))
    authority))
