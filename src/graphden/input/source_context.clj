(ns graphden.input.source-context
  "Frozen declaration context for the offline v1-to-v2 source transition."
  (:require
    [graphden.input.migration :as migration]
    [graphden.packages.rebase :as rebase]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.records.types :as types]))


(defn- reject!
  [code path]
  (throw (ex-info "Cannot resolve structured source context"
                  {:type :input/source-context :code code :path path})))


(defn environment
  "Capture the package's resolved dependency index and reuse legacy parses."
  [{:keys [defs-by-name name->id] :as context}]
  (let [definitions (concat (vals defs-by-name) (:fn-defs context))]
    {:context context
     :names-by-id (group-by val (filter (comp uuid? val) name->id))
     :definition-by-id (into {} (map (fn [d] [(ids/fn-id (:namespace d) (:name d)) d]))
                             definitions)
     :records-of (memoize (fn [definition]
                            (parse/parse-prepared-module (assoc context :fn-defs [definition]))))}))


(defn- resolved-names
  "Only known origins participate in the replay. Other refs, even to a
   real package function, cannot be introduced by this migration."
  [{:keys [context names-by-id]} module-ns binding-origins]
  (let [aliases (mapcat names-by-id (into #{} (keep :ref-fn-id) binding-origins))]
    (reduce (fn [resolved [ref id]]
              (let [resolved (assoc resolved ref id)
                    bare (keyword (name ref))]
                (if (and (= (or module-ns "") (namespace ref))
                         (= types/ambiguous-name (get-in context [:name->id bare])))
                  (assoc resolved bare id)
                  resolved)))
            {} aliases)))


(defn- scope-entry
  [definition records]
  (let [id (ids/fn-id (:namespace definition) (:name definition))
        rows (group-by :kind records)
        owner (some #(when (= id (:id %)) %) (:fn rows))]
    (when-not owner
      (reject! :missing-owner-declaration []))
    {:definition definition :records rows :owner owner}))


(defn- parent-order
  "Use row identities and declared parent order, visiting a diamond once."
  [{:keys [definition-by-id records-of]} own]
  (loop [pending (conj clojure.lang.PersistentQueue/EMPTY own) seen #{} result []]
    (if-let [entry (peek pending)]
      (let [id (get-in entry [:owner :id])]
        (if (contains? seen id)
          (recur (pop pending) seen result)
          (recur (into (pop pending)
                       (map (fn [parent-id]
                              (if-let [definition (get definition-by-id parent-id)]
                                (scope-entry definition (records-of definition))
                                (reject! :missing-parent-input [:parents]))))
                       (get-in entry [:owner :parent-ids]))
                 (conj seen id) (conj result entry))))
      result)))


(defn- ensure-acyclic!
  [entries]
  (loop [pending (into {} (map (fn [{:keys [owner]}] [(:id owner) (:parent-ids owner)])) entries)]
    (when (seq pending)
      (let [roots (keep (fn [[id parents]] (when-not (some #(contains? pending %) parents) id)) pending)]
        (when-not (seq roots) (reject! :cyclic-parent-input [:parents]))
        (recur (apply dissoc pending roots))))))


(defn- memberships
  [records owner-id]
  (->> (:fn-slot records)
       (filter #(= owner-id (:fn-id %)))
       (sort-by (juxt :position (comp str :id)))
       vec))


(defn- record-node
  [type-id fields]
  {:type (into {} (map (juxt :name :type)) fields)
   :type-id type-id :fields fields})


(declare ^:private fields)


(defn- field
  [records slots specs path membership]
  (let [{:keys [id name type-fn-id required description source-slot-id]} (get slots (:slot-id membership))
        key (keyword name)
        field-path (conj path key)
        spec (get specs key)
        type (if (types/type-spec-map? spec) (:type spec) spec)]
    (when-not (and (uuid? id) (contains? specs key) (some? type))
      (reject! :missing-field-declaration field-path))
    (when source-slot-id
      (reject! :field-projection-required field-path))
    (merge {:id id :name key :required required :description description :declaration spec}
           ;; Named and wrapped shapes retain their rich type. This bridge can
           ;; address inline record children whose rows the declaration emitted.
           (if (and (map? type) (seq type) (not (types/type-spec-map? type)))
             (record-node type-fn-id (fields records type-fn-id type field-path))
             {:type type :type-id type-fn-id}))))


(defn- fields
  [records owner-id specs path]
  (let [slots (into {} (map (juxt :id identity)) (:slot records))
        members (memberships records owner-id)]
    (when-not (and (= (count specs) (count members)) (every? simple-keyword? (keys specs)))
      (reject! :unsupported-field-declarations path))
    (mapv #(field records slots specs path %) members)))


(defn- field-specs
  [{list-type :list :keys [args type refine parent parents]}]
  (cond
    (or parent parents) (into {} (filter (comp types/own-slot-declaration? val)) args)
    type type
    list-type {:items :sequence}
    refine {:value (:base refine)}
    :else args))


(defn- declared-input
  [source-environment own binding-origins]
  (let [{:keys [owner]} own
        ordered (parent-order source-environment own)
        _ (ensure-acyclic! ordered)
        input-fields (into [] (mapcat (fn [{:keys [records definition owner]}]
                                        (fields records (:id owner) (field-specs definition) [:input])))
                           ordered)
        members (into [] (mapcat #(memberships (:records %) (get-in % [:owner :id]))) ordered)
        id (:id owner)
        descriptor (migration/input-type-record owner)
        input-id (:id descriptor)
        mapping (migration/identity-mapping {id members} binding-origins)]
    (when (or (not= (count input-fields) (count (set (map :id input-fields))))
              (not= (count input-fields) (count (set (map :name input-fields)))))
      (reject! :ambiguous-inherited-input [:input]))
    {:scope [id []] :input-type-id input-id
     :contract (record-node input-id input-fields)
     :declaration-records (into [(assoc descriptor :kind :fn)]
                                (map #(migration/input-membership-record owner %))
                                members)
     :origin-ledger {:source-owner id :mapping mapping}
     :binding-id (partial migration/binding-origin {:mapping mapping})}))


(defn declaration-context
  "Resolve replayed source declarations and their inherited field membership.
   Input binding identities come exclusively from the frozen legacy ledger."
  [source-environment definition records binding-origins]
  (let [{:keys [owner] :as entry} (scope-entry definition records)
        id (:id owner)
        common {:fn-id id :declaration-records (into [] (filter #(contains? #{:fn :slot :fn-slot} (:kind %))) records)
                :origin-ledger {:source-owner id}}]
    (if (or (:return-type-fn-id owner) (seq (:parent-ids owner)))
      (let [declared (declared-input source-environment entry binding-origins)
            input-id (:input-type-id declared)]
        (-> (merge common declared)
            (assoc :name->id (resolved-names source-environment (:namespace definition) binding-origins))
            (assoc :resolve-config-type
                   (fn [type]
                     (let [names (get-in source-environment [:context :name->id])
                           resolved (rebase/rewrite-type-references
                                      type #(parse/normalize-ref % names (:namespace definition)))]
                       (when (seq (types/inline-fn-type-rows-from-form resolved))
                         (reject! :configuration-requires-inline-type-rows [:configure]))
                       {:type resolved :type-override-fn-id (types/resolve-type-ref resolved names)})))
            (update :declaration-records into
                    (map #(if (and (= :fn (:kind %)) (= id (:id %)))
                            (assoc % :input-type-fn-id input-id)
                            %))
                    (:declaration-records common))))
      common)))


(defn own-field-declaration?
  "The composed field declaration forms currently supported by the source migrator."
  [spec]
  (and (types/own-slot-declaration? spec)
       (every? #{:type :required :description} (keys spec))))


(defn lower-declarations
  "Temporary offline compatibility with the existing declaration lowerer.
   The new syntax reader handles :bind separately. Remove this legacy
   lowering adapter when the v2 package loader replaces the v1 loader."
  [{:keys [context]} definition]
  (let [source (dissoc definition :input :bind :configure)
        source (if (contains? definition :input)
                 (let [declaration (:input definition)]
                   (when-not (and (map? declaration)
                                  (or (not (or (:parent source) (:parents source)))
                                      (every? own-field-declaration? (vals declaration))))
                     (reject! :unsupported-input-declaration [:input]))
                   (assoc source :args declaration))
                 source)
        normalized (rebase/rewrite-references
                     source #(parse/normalize-ref % (:name->id context) (:namespace source)))]
    [normalized (parse/parse-prepared-module (assoc context :fn-defs [normalized]))]))
