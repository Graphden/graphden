(ns graphden.crud.structured-authoring
  "Server proof for editing direct children of migrated native package roots."
  (:require
    [graphden.crud.structured-run :as run]
    [graphden.input.access :as access]
    [graphden.input.authoring-principal :as principal]
    [graphden.input.migration :as migration]
    [graphden.input.read-view :as view]
    [graphden.packages.owned :as owned]
    [graphden.packages.records.ids :as records]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]
    [graphden.versioning.storage.core :as vs]))


(defn- deny!
  []
  (throw (ex-info "Structured input editing is unavailable" {:type :authz/forbidden})))


(defn- same-fields?
  [{:keys [fields members]} base input]
  (let [original (get members base)
        projected (get members input)
        keys-of (juxt :id :slot-id :position)
        expected (map (fn [{:keys [id slot-id position]}]
                        [(migration/derived-id :membership base id) slot-id position]) original)]
    (and (seq original) (= (count original) (count projected))
         (every? (fn [{:keys [id slot-id]}]
                   (let [field (get fields slot-id)]
                     (and (string? (:name field)) (nil? (:source-slot-id field))
                          (= slot-id (records/slot-id base (:name field)))
                          (= id (records/fn-slot-id base slot-id)))))
                 original)
         (= (set expected) (set (map keys-of projected))))))


(defn- package-contract?
  [ctx frozen base]
  (let [root (get-in frozen [:fns base])
        input (:input-type-fn-id root)
        type-row (get-in frozen [:fns input])]
    (and input (= input (get (::owned/primitive-input-types (meta (:base-fns ctx))) base))
         (every? owned/owned-fn-id? (conj (map :type-fn-id (vals (:fields frozen))) base input))
         (empty? (:parent-ids root)) (:return-type-fn-id root)
         (= input (:id type-row)) (empty? (:parent-ids type-row))
         (not-any? #(some? (get type-row %)) [:return-type-fn-id :base-fn-id :element-fn-id :constraint])
         (same-fields? frozen base input))))


(defn root-extend-offer
  "An authenticated creation offer in the root namespace of this branch.
   The actual Extend rechecks the selected namespace inside its writer."
  [ctx storage frozen parent runnable?]
  (when (and (:authenticated? (principal/current)) (vs/versioned-storage? storage)
             (package-contract? ctx frozen parent)
             (or runnable? (run/authorable-package-root? ctx frozen parent)))
    (try
      (binding [vs/*enforce-require-merge?* true]
        (writer/assert-creation-authorized!
          storage {:id (random-uuid) :namespace-id nil :parent-ids [parent]
                   :input-type-fn-id (get-in frozen [:fns parent :input-type-fn-id])}
          (vs/current-branch-id storage)))
      {:extend-available true :extend-namespace-id nil}
      (catch clojure.lang.ExceptionInfo error
        (case (:type (ex-data error))
          (:authz/forbidden :authz/branch-protected :branch/merge-required) nil
          (throw error))))))


(defn- no-legacy-bindings?
  [storage owner]
  (try
    (empty? (sp/query-bounded-entities storage :binding {:fn-id owner} 1))
    (catch clojure.lang.ExceptionInfo error
      (case (:type (ex-data error))
        (:storage-error/candidate-limit :storage-error/bounded-query-unsupported) false
        (throw error)))))


(defn editable-child?
  [ctx storage frozen owner]
  (let [child (get-in frozen [:fns owner])
        [parent & more] (:parent-ids child)]
    (and (:authenticated? (principal/current)) (vs/versioned-storage? storage)
         parent (empty? more) (not (owned/owned-fn-id? owner))
         (not-any? #(some? (get child %)) [:return-type-fn-id :base-fn-id :element-fn-id :constraint :lambda-params :expects-effects])
         (empty? (get-in frozen [:members owner]))
         (= (:input-type-fn-id child) (get-in frozen [:fns parent :input-type-fn-id]))
         (package-contract? ctx frozen parent) (run/authorable-package-root? ctx frozen parent)
         (no-legacy-bindings? storage owner))))


(defn structured-parents?
  [storage parents]
  (when (seq parents)
    (some :input-type-fn-id (vals (sp/read-entities storage :fn parents)))))


(defn assert-public-input!
  [entity data]
  (when (and (#{:fn :fn-version} entity) (contains? data :input-type-fn-id))
    (deny!)))


(defn assert-parent-update!
  [storage entity id data]
  (when (and (= :fn entity) (contains? data :parent-ids)
             (or (:input-type-fn-id (sp/read-entity storage entity id))
                 (structured-parents? storage (:parent-ids data))))
    (deny!)))


(defn create!
  "Derive the child input inside the ordinary authorized writer."
  [ctx storage {:keys [parent-ids] :as data}]
  (assert-public-input! :fn data)
  (if (structured-parents? storage parent-ids)
    (let [[parent & more] parent-ids]
      (when-not (and (:authenticated? (principal/current)) (vs/versioned-storage? storage)
                     (empty? more) (every? #{:id :name :parent-ids :namespace-id :description} (keys data)))
        (deny!))
      (let [frozen (view/collect storage parent (tenancy/browser-source-policy storage))
            input (get-in frozen [:fns parent :input-type-fn-id])
            id (or (:id data) (random-uuid))
            child (assoc data :id id :input-type-fn-id input)]
        (when-not (and (package-contract? ctx frozen parent) (run/authorable-package-root? ctx frozen parent)
                       (not (owned/owned-fn-id? id)) (nil? (sp/read-entity storage :fn id)))
          (deny!))
        (binding [vs/*enforce-require-merge?* true access/*package-input-types* {id input}]
          (writer/assert-creation-authorized! storage child (vs/current-branch-id storage))
          (sp/create-entity storage :fn child))))
    (sp/create-entity storage :fn data)))
