(ns graphden.input.scoped-source
  "Read and emit certified reference input paths in an offline :bind map."
  (:require
    [graphden.input.core :as input]
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
  [names path form]
  (when-not (and (vector? form) (= 2 (count form))) (reject! :unsupported-scoped-expression path))
  (let [[op payload] form]
    (case op
      :value {:kind :value :value payload}
      :ref (if-let [id (and (keyword? payload) (get names payload))]
             {:kind :ref :fn-id id}
             (reject! :unresolved-scoped-reference path))
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
              (fn [index {:keys [from origin-fn-id binding-id target-fn-id]}]
                (let [[[root prefix] [slot :as path]] from]
                  (and (= root owner) (= prefix (subvec uses 0 index)) (= path [slot])
                       (every? uuid? [slot origin-fn-id target-fn-id binding-id])
                       (= binding-id (ids/binding-id origin-fn-id slot)))))
              edges))))


(defn- parse-route
  [{:keys [scope name->id scoped-inputs declaration-records] :as ctx} local-state public-path form]
  (let [{:keys [address row edges field-owner owner-parents snapshot-id] :as certificate} (get scoped-inputs public-path)
        [owner] scope
        parents (:parent-ids (some #(when (and (= :fn (:kind %)) (= owner (:id %))) %) declaration-records))]
    (when-not (and certificate (uuid? snapshot-id) (= (:public-path certificate) public-path)
                   (seq edges) (= (count public-path) (inc (count edges))) (every? simple-keyword? public-path)
                   (= (pop public-path) (mapv :name edges)) (uuid? field-owner)
                   (= owner-parents parents) (edge-path? owner edges) (root-link? ctx local-state (first edges)))
      (reject! :unverified-scoped-input public-path))
    (let [use-path (mapv :binding-id edges)
          field-id (ids/slot-id field-owner (peek public-path))
          expression (read-leaf name->id public-path form)
          parsed (rows/normalize
                   (merge (select-keys row [:org-id])
                          {:kind :input-binding :id (ids/binding-id owner field-id) :fn-id owner
                           :scope-path use-path :target-path [field-id]}
                          (case (:kind expression)
                            :value {:expression-kind "value" :value-present true :value (:value expression)}
                            :ref {:expression-kind "ref" :value-present false :ref-fn-id (:fn-id expression)})))]
      (when-not (and (every? uuid? use-path) (= address [[owner use-path] [field-id]]) (migration/same-edn? parsed row))
        (reject! :scoped-origin-mismatch public-path))
      {:address address :row parsed :expression expression})))


(defn parse-bindings
  [ctx state bind]
  (into {} (keep (fn [[path form]]
                   (when (vector? path) [path (parse-route ctx state path form)])))
        (when (map? bind) bind)))


(defn binding-state
  [local routes]
  (input/merge-bindings local (into {} (map (fn [{:keys [address row expression]}]
                                              [address {:binding-id (:id row) :expression expression}]))
                                    (vals routes))))


(defn emit-bindings
  [local routes id->name]
  (reduce-kv (fn [form path {:keys [expression]}]
               (assoc form path (case (:kind expression)
                                  :value [:value (:value expression)]
                                  :ref [:ref (or (get id->name (:fn-id expression)) (reject! :unresolved-scoped-reference path))])))
             local routes))
