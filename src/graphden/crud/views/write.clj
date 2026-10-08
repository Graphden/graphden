(ns graphden.crud.views.write
  "The SQL-only Save view operation over ordinary fn/binding/list-item rows."
  (:require
    [graphden.crud.entities :as entities]
    [graphden.crud.entities.views :as views]
    [graphden.crud.package-guard :as package-guard]
    [graphden.crud.views.command :as command]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.merge :as merge-storage]
    [graphden.versioning.storage.uniqueness :as uniqueness]
    [honey.sql :as sql]
    [next.jdbc :as jdbc]))


(defn assert-create-id-available!
  "Only availability is exposed. Query identities below decorator visibility,
   keeping the connection's RLS policy; a hidden PK collision still rolls back."
  [storage create-id]
  (when create-id
    (let [available? (if-let [source (tx/datasource storage)]
                       (nil? (jdbc/execute-one! source
                                                (sql/format {:select [:id] :from [:fn]
                                                             :where [:= :id create-id]})))
                       (nil? (sp/read-entity (vs/unwrap storage) :fn create-id)))]
      (when-not available?
        (throw (ex-info "The proposed view identity is unavailable"
                        {:type :constraint-violation/unique}))))))


(defn- lock-create-id!
  [storage create-id]
  (when (and create-id (tx/datasource storage))
    ;; Match ordinary CRUD's branch -> identity ordering, before the fresh
    ;; availability check. Another org can otherwise claim the same new UUID.
    (when-let [branch-id (vs/current-branch-id storage)]
      (merge-storage/lock-branches! storage branch-id))
    (uniqueness/xact-lock! (tx/datasource storage)
                           (uniqueness/identity-lock-keys :fn [create-id]))))


(defn- graph
  "Fresh decorated reads inside the writer transaction, never a UI graph memo."
  [storage]
  {:fns (vec (sp/query-entities storage :fn {}))
   :slots (vec (sp/query-entities storage :slot {}))
   :fn-slots (vec (sp/query-entities storage :fn-slot {}))
   :bindings (vec (sp/query-entities storage :binding {}))
   :list-items (vec (sp/query-entities storage :binding-list-item {}))})


(defn- checked
  [row]
  (or row (command/reject! "The storage refused the view write")))


(defn- assert-package-writable!
  [storage entity row]
  (when-let [reason (package-guard/write-rejection storage entity row)]
    (throw (ex-info reason {:type :packages/read-only :reason reason}))))


(defn- create!
  [ctx entity data]
  (assert-package-writable! (:storage ctx) entity data)
  (checked (entities/create-entity entity data ctx)))


(defn- delete-binding!
  [storage row]
  (assert-package-writable! storage :binding row)
  (binding [vs/*tombstone-delete?* true]
    (doseq [item (sp/query-entities storage :binding-list-item {:binding-id (:id row)})]
      (checked (sp/delete-entity storage :binding-list-item (:id item))))
    (checked (sp/delete-entity storage :binding (:id row)))))


(defn- root-named
  [indices fn-name]
  (some #(when (and (= fn-name (:name %)) (empty? (:parent-ids %))) %)
        (vals (:fn-map indices))))


(defn- model
  [indices row]
  (merge (select-keys row [:id :name :namespace-id]) (views/decode-view indices (:id row))))


(defn- target!
  [ctx {:keys [id create-id name namespace-id] :as command} indices]
  (let [storage (:storage ctx)
        base (or (root-named indices views/explorer-view-base-name)
                 (command/reject! "The explorer-view function is not installed"))
        existing (when id (sp/read-entity storage :fn id))
        _ (when (and id (not-any? #{(:id base)} (lookups/inheritance-chain* id indices)))
            (command/reject! "The selected function is not an explorer view"))
        data (cond-> {:name name}
               (contains? command :namespace-id) (assoc :namespace-id namespace-id))
        data (if id data (cond-> (assoc data :parent-ids [(:id base)])
                           create-id (assoc :id create-id)))]
    (if id
      (do
        (when-not existing (command/reject! "The view no longer exists"))
        (assert-package-writable! storage :fn existing)
        (writer/assert-write-authorized! storage :fn (merge existing data) id)
        (checked (entities/update-entity :fn id data ctx)))
      (create! ctx :fn data))))


(defn- slot-map
  [indices fn-id]
  (into {} (map (juxt (comp keyword :name) :id)) (lookups/root-slots fn-id indices)))


(defn- remove-own-axis!
  [storage fn-id slot-ids]
  (let [wanted (set slot-ids)]
    (doseq [row (sp/query-entities storage :binding {:fn-id fn-id})
            :when (contains? wanted (:slot-id row))]
      (delete-binding! storage row))))


(defn- identity-constant!
  [ctx {:keys [const-id value-slot]} owner axis target-id]
  (let [id (random-uuid)
        row (create! ctx :fn {:id id :name (str "_view-" (name axis) "-" (subs (str id) 0 8))
                              :namespace-id (:namespace-id owner) :parent-ids [const-id]})]
    (create! ctx :binding {:fn-id id :slot-id value-slot :ref-fn-id target-id
                           :type-override-fn-id ids/fn-ref-type-id})
    row))


(defn- reusable-identities
  [indices owner-id slot-id]
  (let [binding (get-in indices [:binding-by-fn-slot [owner-id slot-id]])]
    (into {}
          (keep (fn [item]
                  (when-let [ref (:ref-fn-id item)]
                    (let [target (views/constant-value indices ref #{})]
                      (when (uuid? target) [target (get-in indices [:fn-map ref])])))))
          (get-in indices [:items-by-binding (:id binding)]))))


(defn- bind-identities!
  [ctx indices slots owner axis target-ids]
  (let [storage (:storage ctx)
        [legacy list-axis] (case axis :uses [:uses :uses-all] :views [:also :also-all])
        list-slot (or (get slots list-axis) (command/reject! "This deployment cannot save all view references"))
        const-row (or (root-named indices "const") (command/reject! "The const function is not installed"))
        const-spec {:const-id (:id const-row) :value-slot (get (slot-map indices (:id const-row)) :value)}
        reusable (reusable-identities indices (:id owner) list-slot)]
    (remove-own-axis! storage (:id owner) [(get slots legacy) list-slot])
    (let [binding-row (create! ctx :binding {:fn-id (:id owner) :slot-id list-slot :list-append true})]
      (mapv (fn [position target-id]
              (let [adapter (or (get reusable target-id)
                                (identity-constant! ctx const-spec owner axis target-id))]
                (create! ctx :binding-list-item {:binding-id (:id binding-row) :position position
                                                 :ref-fn-id (:id adapter)})
                adapter))
            (range) target-ids))))


(defn- bind-value!
  [ctx slots owner axis value]
  (let [sid (or (get slots axis) (command/reject! (str "Missing view slot: " (name axis))))
        value (if (contains? #{:kinds :problems :effects} axis) (mapv name value) value)]
    (remove-own-axis! (:storage ctx) (:id owner) [sid])
    (create! ctx :binding {:fn-id (:id owner) :slot-id sid :value value :value-present true})))


(defn- assert-targets!
  [indices filters]
  (let [known (:fn-map indices)
        base-id (:id (root-named indices views/explorer-view-base-name))]
    (doseq [id (concat (:uses filters) (:views filters))]
      (when-not (contains? known id) (command/reject! "A referenced function is no longer readable")))
    (doseq [id (:views filters)]
      (when-not (some #{base-id} (lookups/inheritance-chain* id indices))
        (command/reject! "An also reference must name an explorer view")))))


(defn- authorize-update!
  "Authorize the identity before reading its composition or diagnosing clauses.
   Missing and inaccessible targets share the same opaque refusal."
  [storage {:keys [id name] :as command}]
  (when id
    (let [existing (or (sp/read-entity storage :fn id)
                       (throw (ex-info "The view update is not allowed"
                                       {:type :authz/forbidden})))
          data (cond-> (assoc existing :name name)
                 (contains? command :namespace-id)
                 (assoc :namespace-id (:namespace-id command)))]
      (writer/assert-write-authorized! storage :fn data id))))


(defn- clause-value
  [value]
  (if (sequential? value) (set value) value))


(defn- same-filters?
  [left right]
  (= (update-vals left clause-value) (update-vals right clause-value)))


(defn apply-command!
  "Re-read and validate under the outer writer guard, then apply a full filter
   replacement. A final inherited clause that cannot be removed rejects the
   transaction instead of silently changing the requested filter."
  [ctx {:keys [filters] :as command}]
  (let [storage (:storage ctx)
        _ (authorize-update! storage command)
        _ (lock-create-id! storage (:create-id command))
        _ (assert-create-id-available! storage (:create-id command))
        before (graph storage)
        indices (lookups/build-lookups before)
        _ (assert-targets! indices filters)
        previous (when-let [id (:id command)] (views/decode-view indices id))
        _ (when (:unsupported previous)
            (command/reject! "Edit this view's computed clauses on the graph; saving chips would discard them"))
        owner (target! ctx command indices)
        ;; A new fn is needed in the lookup only for its inherited root slots.
        indices (if (:id command) indices
                    (lookups/build-lookups (update before :fns conj owner)))
        slots (slot-map indices (:id owner))
        old (views/normalise-filters (:filters previous))
        changed (filter #(not= (clause-value (get old %)) (clause-value (get filters %))) (keys filters))
        adapters (reduce (fn [rows axis]
                           (if (contains? #{:uses :views} axis)
                             (into rows (bind-identities! ctx indices slots owner axis (get filters axis)))
                             (do (bind-value! ctx slots owner axis (get filters axis)) rows)))
                         [] changed)
        after (graph storage)
        actual (model (lookups/build-lookups after) owner)]
    (when (or (:unsupported actual)
              (not (same-filters? filters (views/normalise-filters (:filters actual)))))
      (command/reject! "Inherited clauses prevent this filter replacement; save a new view instead"))
    ;; Incremental graph caches must know the new identity compositions before
    ;; publishing the view that references them. Existing adapters are unchanged.
    {:view actual
     :publication-rows (conj (into [] (remove #(contains? (:fn-map indices) (:id %))) adapters) owner)}))
