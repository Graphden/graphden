(ns graphden.input.scoped-source
  "Read and emit certified reference input paths in an offline :bind map."
  (:require
    [graphden.input.core :as input]
    [graphden.input.list-signature :as list-signature]
    [graphden.input.migration :as migration]
    [graphden.input.rows :as rows]
    [graphden.packages.records.ids :as ids]))


(defn- reject!
  [code path]
  (throw (ex-info "Invalid scoped source binding" {:type :input/scoped-source :code code :path path})))


(defn local-form
  [bind]
  (if (map? bind) (reduce dissoc bind (filter vector? (keys bind))) bind))


(defn- read-leaf
  [{:keys [name->id contract scope inline-at]} {:keys [source-names field-id]} path form]
  (when-not (and (vector? form) (= 2 (count form))) (reject! :unsupported-scoped-expression path))
  (let [[op payload] form]
    (case op
      :value {:kind :value :value payload}
      :ref (let [{:keys [inline-body fn-id]} (get inline-at [field-id])
                 target (if (keyword? payload) (get name->id payload)
                            (when (and (map? payload) (migration/same-edn? inline-body payload)) fn-id))]
             (if (uuid? target)
               {:kind :ref :fn-id target}
               (reject! :unresolved-scoped-reference path)))
      :project (do
                 (when-not (and (= payload source-names) (vector? payload)
                                (= 1 (count payload)) (simple-keyword? (first payload)))
                   (reject! :unverified-forwarded-source path))
                 (assoc (select-keys (input/resolve-address contract scope payload) [:scope :path])
                        :kind :projection))
      (reject! :unsupported-scoped-expression path))))


(defn- root-link?
  [{:keys [contract scope inherited-scoped-refs]} state {:keys [from name binding-id target-fn-id origin-fn-id]}]
  (let [[owner] scope
        slot (:path (input/resolve-address contract scope [name]))
        local (get state from)
        inherited (get inherited-scoped-refs binding-id)]
    (and (= from [scope slot]) (= binding-id (ids/binding-id origin-fn-id (first slot)))
         (if (= owner origin-fn-id)
           (and (= binding-id (:binding-id local)) (= {:kind :ref :fn-id target-fn-id} (:expression local)))
           (and (nil? local) (= origin-fn-id (:fn-id inherited)) (= target-fn-id (:ref-fn-id inherited))
                (= slot [(:slot-id inherited)]))))))


(defn- edge-path?
  [owner edges]
  (let [uses (mapv :binding-id edges)]
    (every? true?
            (map-indexed
              (fn [index {:keys [from origin-fn-id binding-id target-fn-id source-list-fragment source-list-operand] :as edge}]
                (let [[[root prefix] [slot :as path]] from]
                  (and (= root owner) (= prefix (subvec uses 0 index)) (= path [slot])
                       (every? uuid? [slot origin-fn-id target-fn-id binding-id])
                       (cond
                         source-list-operand (list-signature/source-operand? (get edges (dec index)) edge)
                         source-list-fragment (list-signature/source-link? edge)
                         :else (= binding-id (ids/binding-id origin-fn-id slot))))))
              edges))))


(defn- checked-route
  [{:keys [scope scoped-inputs declaration-records] :as ctx} local-state public-path]
  (let [{:keys [address edges field-owner owner-parents snapshot-id] :as certificate} (get scoped-inputs public-path)
        [owner] scope
        parents (:parent-ids (some #(when (and (= :fn (:kind %)) (= owner (:id %))) %) declaration-records))]
    (when-not (and certificate (uuid? snapshot-id) (= (:public-path certificate) public-path)
                   (seq edges) (= (count public-path) (inc (count edges))) (every? simple-keyword? public-path)
                   (= (pop public-path) (mapv :name edges)) (uuid? field-owner)
                   (= owner-parents parents) (edge-path? owner edges) (root-link? ctx local-state (first edges)))
      (reject! :unverified-scoped-input public-path))
    (let [use-path (mapv :binding-id edges)
          field-id (ids/slot-id field-owner (peek public-path))]
      (when-not (and (every? uuid? use-path) (= address [[owner use-path] [field-id]]))
        (reject! :scoped-origin-mismatch public-path))
      (assoc certificate :owner owner :use-path use-path :field-id field-id))))


(defn- parse-route
  [ctx local-state public-path form]
  (let [{:keys [address row owner use-path field-id] :as certificate} (checked-route ctx local-state public-path)]
    (when (contains? certificate :configuration) (reject! :unsupported-scoped-expression public-path))
    (let [expression (read-leaf ctx certificate public-path form)
          projection? (= :projection (:kind expression))
          id (if projection?
               (migration/derived-id :forwarded-field owner (if (:nested-reader? certificate) use-path (first use-path)) field-id)
               (ids/binding-id owner field-id))
          parsed (rows/normalize
                   (merge (select-keys row [:org-id])
                          {:kind :input-binding :id id :fn-id owner
                           :scope-path use-path :target-path [field-id]}
                          (case (:kind expression)
                            :value {:expression-kind "value" :value-present true :value (:value expression)}
                            :ref {:expression-kind "ref" :value-present false :ref-fn-id (:fn-id expression)}
                            :projection {:expression-kind "projection" :value-present false
                                         :projection-scope-path (second (:scope expression))
                                         :projection-path (:path expression)})))]
      (when-not (migration/same-edn? parsed row)
        (reject! :scoped-origin-mismatch public-path))
      (cond-> {:address address :row parsed :expression expression}
        (and (= :ref (:kind expression)) (map? (second form))) (assoc :inline-body (second form))
        projection? (assoc :source-names (:source-names certificate))))))


(defn parse-bindings
  [ctx state bind]
  (into {} (keep (fn [[path form]]
                   (when (vector? path) [path (parse-route ctx state path form)])))
        (when (map? bind) bind)))


(defn parse-annotation
  [ctx state path spec]
  (when-not (contains? (get-in ctx [:scoped-inputs path]) :configuration)
    (reject! :unsupported-field-configuration path))
  (let [{:keys [address row owner use-path field-id field-owner configuration]} (checked-route ctx state path)
        pin (:type-override-fn-id row)
        expected (cond-> {:kind :input-binding :id (ids/binding-id owner field-id) :fn-id owner
                          :scope-path use-path :target-path [field-id] :expression-kind nil :value-present false}
                   (:org-id row) (assoc :org-id (:org-id row))
                   (contains? spec :description) (assoc :description (:description spec))
                   (contains? spec :type) (assoc :type-override-fn-id pin))]
    (when-not (and (migration/same-edn? spec configuration)
                   (or (not (contains? spec :type)) (uuid? pin))
                   (migration/same-edn? expected row))
      (reject! :scoped-origin-mismatch path))
    [path (assoc spec :row expected :address address :target-path [field-id] :field-owner field-owner
                 :legacy-path [(peek path)] :type-override-fn-id pin)]))


(defn binding-state
  [local routes]
  (input/merge-bindings local (into {} (map (fn [{:keys [address row expression]}]
                                              [address {:binding-id (:id row) :expression expression}]))
                                    (vals routes))))


(defn emit-bindings
  [local routes id->name]
  (reduce-kv (fn [form path {:keys [expression source-names inline-body]}]
               (assoc form path (case (:kind expression)
                                  :value [:value (:value expression)]
                                  :ref [:ref (or inline-body (get id->name (:fn-id expression)) (reject! :unresolved-scoped-reference path))]
                                  :projection [:project source-names])))
             local routes))
