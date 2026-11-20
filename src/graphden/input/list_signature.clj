(ns graphden.input.list-signature
  "Frozen stage-4 signatures for ordinary operands of a verified list builder."
  (:require
    [graphden.input.list-records :as records]
    [graphden.input.migration :as migration]))


(defn- reject!
  [owner reason]
  (throw (ex-info "List construction requires additional signature evidence"
                  {:type :input/source-snapshot :code :list-builder-signature-required :fn-id owner :reason reason})))


(defn- generated-field
  [slots items return-type {:keys [position slot-id]}]
  (let [slot (slots slot-id)
        name (keyword (:name slot))
        type (if (= position (count items)) return-type (second return-type))]
    {:id slot-id :name name :type type :type-id (:type-fn-id slot) :required (:required slot)}))


(defn entry
  "Recover generated declaration and operand rows from one verified source
   trace. The receiving collection supplies the element type. Each operand's
   literal or reference must still pass the ordinary scoped expression checker."
  [owner-id names {:keys [fragments field records] :as trace}]
  (let [[{:keys [builder-id items target-id] :as fragment} & more] fragments
        type (:type field)]
    (cond
      (or (nil? fragment) (seq more)) (reject! owner-id :fragment-chain-signature)
      (not= owner-id (:owner-id fragment)) (reject! owner-id :fragment-owner-mismatch)
      (not (and (vector? type) (= 2 (count type)) (= :list (first type))
                (contains? #{:any :numeric :int :text :bool} (second type))))
      (reject! owner-id :list-element-signature)
      (not-every? #(#{:value :ref} (:kind %)) items) (reject! owner-id :list-operand-signature))
    (let [recovered (records/recover-chain [owner-id] target-id field names records)
          _ (when-not (migration/same-edn? recovered (select-keys trace (keys recovered))) (reject! owner-id :source-fragment-mismatch))
          declarations (filterv #(not= :input-binding (:kind %)) records)
          slots (into {} (map (juxt :id identity)) (filter #(= :slot (:kind %)) declarations))
          members (sort-by :position (filter #(= :fn-slot (:kind %)) declarations))
          fields (mapv (partial generated-field slots items type) members)
          owner (some #(when (= builder-id (:id %)) %) declarations)
          args (into {} (map (juxt :name :type)) fields)]
      {:owner owner :input-type-id (:input-type-fn-id owner)
       :contract {:type args :fields fields} :declaration-records declarations
       :bindings (filterv #(and (= builder-id (:fn-id %)) (= :input-binding (:kind %))) records)
       :list-implementation {:base-fn-id builder-id :root-entry {:fn-id builder-id :args args :return type :effects #{} :taint-propagate? true}
                             :argument-paths (into {} (map (fn [{:keys [name id]}] [name [id]])) fields)}})))
