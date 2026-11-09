(ns graphden.input.descriptor
  "Resolve declared rich types and their stored field identities together.
   Structural data is a view of the existing type system, not another checker."
  (:require
    [graphden.input.context :as context]
    [graphden.input.core :as input]
    [graphden.packages.records.ids :as ids]
    [graphden.types.core :as types]
    [graphden.types.expansion :as expansion]))


(def ^:private primitive-by-id
  (into {} (map (fn [[name id]] [id name])) (ids/primitive-fn-ids)))


(def ^:dynamic ^:private *remaining-nodes* nil)


(defn- consume-node!
  []
  (when (neg? (vswap! *remaining-nodes* dec))
    (context/reject! :type-expansion-budget {})))


(defn- field-rows
  [{:keys [fields members]} type-id]
  (mapv (fn [membership]
          (or (get fields (:slot-id membership))
              (context/reject! :missing-field {:type-id type-id :field-id (:slot-id membership)})))
        (sort-by (juxt :position (comp str :id)) (get members type-id))))


(declare ^:private type-body)


(defn- stored-body
  [{:keys [fns members] :as ctx} type-id seen]
  (let [row (get fns type-id)
        resolve-type #(type-body ctx % seen)]
    (cond
      (get primitive-by-id type-id) (get primitive-by-id type-id)
      (:base-fn-id row) (let [base (resolve-type (:base-fn-id row))]
                          (if-some [constraint (:constraint row)] [:refine base constraint] base))
      (:element-fn-id row) [:list (resolve-type (:element-fn-id row))]
      (vector? (:constraint row)) (:constraint row)
      (contains? members type-id)
      (into {} (map (fn [field] [(keyword (:name field)) (resolve-type (:type-fn-id field))]))
            (field-rows ctx type-id))
      :else (context/reject! :missing-type-body {:type-id type-id}))))


(defn- type-body
  [{:keys [type-bodies] :as ctx} type-id seen]
  (consume-node!)
  (when (or (contains? seen type-id) (>= (count seen) 64))
    (context/reject! :recursive-type {:type-id type-id}))
  (let [source (if (contains? type-bodies type-id)
                 (get type-bodies type-id)
                 (stored-body ctx type-id (conj seen type-id)))
        _ (expansion/assert-finite! source)
        source (if (and (vector? source) (= :variant (first source)))
                 (or (types/desugar-variant source)
                     (context/reject! :invalid-variant {:type-id type-id})) source)
        resolved (types/resolve-alias source)]
    (when-not (types/well-formed? resolved)
      (context/reject! :invalid-type-body {:type-id type-id}))
    resolved))


(defn- structural-base
  [rich]
  (cond
    (types/refine-type? rich) (recur (types/refine-base rich))
    (types/marker-type? rich) (recur (types/marker-inner rich))
    :else rich))


(defn- membership-owner
  [{:keys [fns members type-bodies alias-ids] :as ctx} type-id seen]
  (when (contains? seen type-id)
    (context/reject! :recursive-type {:type-id type-id}))
  (if (contains? members type-id)
    type-id
    (when-let [base-id (or (:base-fn-id (get fns type-id))
                           (get alias-ids (structural-base (get type-bodies type-id))))]
      (membership-owner ctx base-id (conj seen type-id)))))


(declare ^:private resolve-node)


(defn- record-fields
  [ctx type-id rich seen]
  (let [owner (or (membership-owner ctx type-id #{})
                  (context/reject! :missing-field-identities {:type-id type-id}))
        fields (field-rows ctx owner)
        names (mapv #(keyword (:name %)) fields)
        ids (mapv :id fields)]
    (when-not (and (= (set names) (set (keys rich)))
                   (= (count names) (count (set names)))
                   (= (count ids) (count (set ids))) (every? uuid? ids))
      (context/reject! :field-identity-mismatch {:type-id type-id}))
    (mapv (fn [field]
            (let [name (keyword (:name field))
                  child (resolve-node ctx (:type-fn-id field) seen)]
              (when-not (= (get rich name) (:type child))
                (context/reject! :field-type-mismatch {:type-id type-id :field-id (:id field)}))
              (merge child (select-keys field [:description])
                     {:id (:id field) :name name
                      :required (if (contains? field :required)
                                  (boolean (:required field))
                                  (:field-default-required? ctx))}))) fields)))


(defn- resolve-node
  [ctx type-id seen]
  (when (or (contains? seen type-id) (>= (count seen) 64))
    (context/reject! :recursive-type {:type-id type-id}))
  (let [rich (type-body ctx type-id #{})
        structural (structural-base rich)]
    (cond-> {:type rich :type-id type-id}
      (types/record-type? structural)
      (assoc :fields (record-fields ctx type-id structural (conj seen type-id)))
      (types/union-type? structural)
      (assoc :alternatives (vec (types/union-members structural))))))


(defn resolve-descriptor
  "Keep markers/refinements on :type while exposing record fields by stable
   identity. Dictionaries remain atomic. Unions retain alternatives and have
   no guessed :fields; partial union selection belongs to the typed analyzer."
  [ctx type-id]
  (binding [*remaining-nodes* (volatile! 10000)]
    (context/call-with-types ctx #(resolve-node ctx type-id #{}))))


(defn resolve-contract
  "Resolve one stored declaration, never the previously computed residual.
   Parent composition and referenced availability are separate analysis steps."
  [ctx fn-id]
  (let [type-id (get-in ctx [:fns fn-id :input-type-fn-id])
        descriptor (when type-id (resolve-descriptor ctx type-id))]
    (when-not descriptor (context/reject! :missing-input-contract {:fn-id fn-id}))
    (when (:alternatives descriptor)
      (context/reject! :ambiguous-record-input {:fn-id fn-id :type-id type-id}))
    (input/input-contract descriptor)))
