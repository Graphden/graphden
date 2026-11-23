(ns graphden.input.list-signature
  "Frozen stage-4 signatures for ordinary operands of a verified list builder."
  (:require
    [graphden.input.list-builder :as builder]
    [graphden.input.list-records :as records]
    [graphden.input.migration :as migration]
    [graphden.packages.records.ids :as ids]
    [graphden.types.core :as types]
    [graphden.types.expansion :as expansion]))


(defn- reject!
  [owner reason]
  (throw (ex-info "List construction requires additional signature evidence"
                  {:type :input/source-snapshot :code :list-builder-signature-required :fn-id owner :reason reason})))


(defn- generated-field
  [slots items return-type polymorphic? {:keys [position slot-id]}]
  (let [slot (slots slot-id)
        name (keyword (:name slot))
        type (cond
               (= position (count items)) return-type
               polymorphic? (symbol (str "operand-" position))
               :else (second return-type))]
    {:id slot-id :name name :type type :type-id (:type-fn-id slot) :required (:required slot)}))


(defn- polymorphic-list-return
  [facts _static-return]
  (let [tail (get facts :tail)
        tail-type (if (and (:value-present tail) (= [] (:value tail)))
                    :never
                    (or (types/list-elem (:type tail)) :any))]
    [:list (types/make-union (conj (mapv :type (vals (dissoc facts :tail))) tail-type))]))


(defn- element-type?
  [resolve-named element]
  (or (contains? #{:any :numeric :int :text :bool :keyword} element)
      (and (keyword? element) (= :union (:kind (resolve-named element))))
      (and (vector? element) (= 2 (count element)) (= :list (first element))
           (element-type? resolve-named (second element)))))


(defn entry
  "Verify the full source chain and recover its current contributor's generated
   declarations. Earlier builders enter the closure through true parents.
   Each operand passes the ordinary scoped expression checker."
  ([owner-id names trace] (entry owner-id names {} trace))
  ([owner-id names resolve-named {:keys [fragments field records] :as trace}]
   (let [{:keys [builder-id items target-id] :as fragment} (when (vector? fragments) (peek fragments))
         type (:type field)
         _ (expansion/assert-finite! type)
         element (when (vector? type) (second type))
         polymorphic? (types/type-var? element)]
     (cond
       (nil? fragment) (reject! owner-id :fragment-chain-signature)
       (not= owner-id (:owner-id fragment)) (reject! owner-id :fragment-owner-mismatch)
       (not (and (vector? type) (= 2 (count type)) (= :list (first type))
                 (or polymorphic? (element-type? resolve-named element))))
       (reject! owner-id :list-element-signature)
       (not-every? #(#{:value :ref :projection} (:kind %)) items) (reject! owner-id :list-operand-signature))
     (let [recovered (records/recover-chain (mapv :owner-id fragments) target-id field names records)
           _ (when-not (migration/same-edn? recovered (select-keys trace (keys recovered))) (reject! owner-id :source-fragment-mismatch))
           contribution (records/authored-records (assoc recovered :records records))
           declarations (filterv #(not= :input-binding (:kind %)) contribution)
           edge (some #(when (= (:binding-id fragment) (:id %)) %) (:bindings recovered))
           slots (into {} (map (juxt :id identity)) (filter #(= :slot (:kind %)) declarations))
           members (sort-by :position (filter #(= :fn-slot (:kind %)) declarations))
           fields (mapv (partial generated-field slots items type polymorphic?) members)
           owner (some #(when (= builder-id (:id %)) %) declarations)
           args (into {} (map (juxt :name :type)) fields)
           root-entry (cond-> {:fn-id builder-id :args args :return type :effects #{} :taint-propagate? true}
                        polymorphic? (assoc :return-type-rule polymorphic-list-return))]
       {:owner owner :list-fragment (merge (peek (:fragments recovered)) (select-keys edge [:scope-path :target-path]))
        :input-type-id (:input-type-fn-id owner)
        :contract {:type args :fields fields} :declaration-records declarations
        :bindings (filterv #(and (= builder-id (:fn-id %)) (= :input-binding (:kind %))) contribution)
        :owner-bindings (filterv #(and (= owner-id (:fn-id %)) (= "projection" (:expression-kind %))) contribution)
        :list-implementation {:base-fn-id builder-id :root-entry root-entry
                              :generated-list {:owner-id owner-id :operands (mapv :name (pop fields))
                                               :infer-return? polymorphic?}
                              :argument-paths (into {} (map (fn [{:keys [name id]}] [name [id]])) fields)}}))))


(defn inline-operand?
  "Match an authored inline use to an operand of the regenerated row set.
   The recovered fragment's vector positions came from stored fn-slot rows."
  [{:keys [binding-id owner-id target-id builder-id items scope-path target-path]} edge position child-id]
  (let [item (get items position)
        path (or target-path [target-id])]
    (and (uuid? child-id) (nat-int? position) (= position (:position item)) (= :ref (:kind item))
         (= owner-id (:fn-id edge)) (= path (:target-path edge)) (= (or scope-path []) (:scope-path edge))
         (= builder-id (:ref-fn-id edge)) (= binding-id (:id edge))
         (= child-id (:fn-id item)) (= (:id item) (ids/binding-list-item-id binding-id position)))))


(defn source-link?
  "Identity formula for a proved root source-list link in a scope certificate.
   Source rows and actual use attachment must be verified by the provider."
  [{:keys [from binding-id origin-fn-id target-fn-id source-list-fragment]}]
  (let [{:keys [owner-id target-id builder-id scope-path target-path] :as fragment} source-list-fragment]
    (and (uuid? owner-id) (uuid? target-id)
         (= (builder/origin owner-id target-id)
            (select-keys fragment [:owner-id :target-id :binding-id :builder-id :tail-id]))
         (= [] scope-path) (= [target-id] target-path (second from))
         (= owner-id origin-fn-id) (= (:binding-id fragment) binding-id)
         (= builder-id target-fn-id))))


(defn source-operand?
  "A list operand retains the source list-item UUID, not a new field binding
   UUID. Its preceding checked source link fixes the owner and use scope."
  [prior {:keys [from name binding-id origin-fn-id target-fn-id source-list-operand]}]
  (let [{:keys [builder-id] :as fragment} (:source-list-fragment prior)
        {:keys [position item-id ref-fn-id] list-binding :binding-id} source-list-operand
        [root prefix] (first (:from prior))]
    (and (source-link? prior) (nat-int? position) (uuid? ref-fn-id)
         (= list-binding (:binding-id fragment))
         (= item-id binding-id (ids/binding-list-item-id list-binding position))
         (= builder-id origin-fn-id) (= ref-fn-id target-fn-id)
         (= name (keyword (str "item-" position)))
         (= from [[root (conj prefix list-binding)] [(ids/slot-id builder-id name)]]))))
