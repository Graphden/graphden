(ns graphden.input.list-records
  "Offline declarations and ordinary input-binding rows for a generated list
   callable. Keep the ordered operand rows reconstructible by stable IDs."
  (:require
    [graphden.input.list-builder :as builder]
    [graphden.input.migration :as migration]
    [graphden.input.rows :as rows]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.types :as types]))


(defn- reject!
  [code]
  (throw (ex-info "Cannot materialize list builder declaration" {:type :input/list-builder :code code})))


(defn- item-type-id
  [{:keys [type type-id]} name->id]
  (let [element (cond (= :sequence type) :any
                      (and (vector? type) (= 2 (count type)) (= :list (first type))) (second type))]
    (when-not (and element (uuid? type-id) (= type-id (types/resolve-type-ref type name->id))
                   (empty? (types/inline-fn-type-rows-from-form element))
                   (not (map? element)))
      (reject! :list-element-declarations-required))
    (types/resolve-type-ref element name->id)))


(defn- field-rows
  [input-id owner field-name position type-id required?]
  (let [id (ids/slot-id owner field-name)]
    [{:kind :slot :id id :name (name field-name) :type-fn-id type-id :required required?}
     {:kind :fn-slot :id (ids/fn-slot-id input-id id) :fn-id input-id :slot-id id :position position}]))


(defn- operand-rows
  [input-id owner element-id {:keys [id position kind fn-id value]}]
  (let [[field membership] (field-rows input-id owner (str "item-" position) position element-id true)
        binding (rows/normalize
                  (merge {:kind :input-binding :id id :fn-id owner :scope-path [] :target-path [(:id field)]}
                         (case kind
                           :value {:expression-kind "value" :value-present true :value value}
                           :ref {:expression-kind "ref" :value-present false :ref-fn-id fn-id}
                           (reject! :invalid-list-item))))]
    [field membership binding]))


(defn materialize
  "Generate the builder callable, declared input type, ordered operand fields
   and input bindings from one source fragment. The optional tail belongs to
   this builder. link-fragments supplies its external ref and any closed seal.
   Field descriptor and prepared names are supplied by the source context."
  [{:keys [builder-id tail-id items] :as fragment} field name->id]
  (builder/link-fragments [fragment])
  (builder/compiler-items fragment)
  (let [element-id (item-type-id field name->id)
        input (assoc (migration/input-type-record {:id builder-id}) :kind :fn)
        input-id (:id input)
        owner {:kind :fn :id builder-id :parent-ids [] :input-type-fn-id input-id :return-type-fn-id (:type-id field)}
        tail (field-rows input-id builder-id :tail (count items) (:type-id field) false)]
    (when-not (= tail-id (:id (first tail))) (reject! :invalid-list-chain))
    (into (into [owner input] (mapcat (partial operand-rows input-id builder-id element-id)) items) tail)))


(defn- read-operand
  [by-field membership]
  (let [row (get by-field (:slot-id membership))
        item {:id (:id row) :position (:position membership)}]
    (case (:expression-kind row)
      "value" (assoc item :kind :value :value (:value row))
      "ref" (assoc item :kind :ref :fn-id (:ref-fn-id row))
      (reject! :missing-list-operand))))


(defn recover
  "Recover a fragment from its generated declaration rows and the chain's
   independently known open/closed state. Regenerate and compare every local
   row so swapped slots, membership positions or policies cannot slip through.
   Pass only materialize's declaration rows, excluding link-fragments' edges."
  [origin field name->id records]
  (let [members (sort-by :position (filter #(= :fn-slot (:kind %)) records))
        bindings (filter #(= :input-binding (:kind %)) records)
        by-field (into {} (map (juxt (comp first :target-path) identity)) bindings)
        result (assoc (dissoc origin :items) :items (mapv (partial read-operand by-field) (butlast members)))
        order #(sort-by (juxt :kind (comp str :id)) %)]
    (when-not (migration/same-edn? (order records) (order (materialize result field name->id)))
      (reject! :list-declaration-mismatch))
    result))


(defn materialize-chain
  "Produce all source-derived declaration and binding rows for a complete
   chain, including the owner/use ref edges and the optional final seal."
  [fragments field name->id]
  (into (:bindings (builder/link-fragments fragments))
        (mapcat #(materialize % field name->id)) fragments))


(defn- read-contribution
  [rows-by-kind owner-id target-id field name->id]
  (let [{:keys [builder-id tail-id] :as origin} (builder/origin owner-id target-id)
        input-id (:id (migration/input-type-record {:id builder-id}))
        memberships (filter #(= input-id (:fn-id %)) (:fn-slot rows-by-kind))
        field-ids (set (map :slot-id memberships))
        own (filter #(= builder-id (:fn-id %)) (:input-binding rows-by-kind))
        seal-id (ids/binding-id builder-id tail-id)
        declarations (concat (filter #(contains? #{builder-id input-id} (:id %)) (:fn rows-by-kind))
                             (filter #(contains? field-ids (:id %)) (:slot rows-by-kind))
                             memberships (remove #(= seal-id (:id %)) own))
        tail (if (some #(= seal-id (:id %)) own) :closed :open)]
    (recover (assoc origin :tail tail) field name->id declarations)))


(defn recover-chain
  "Read a frozen row set using the contributing owners and original field
   in verified inheritance order. Compare every regenerated row, including
   the actual continuation address, its owner, and any sealed empty value."
  [owners target-id field name->id records]
  (let [by-kind (group-by :kind records)
        fragments (mapv #(read-contribution by-kind % target-id field name->id) owners)
        order #(sort-by (juxt :kind (comp str :id)) %)]
    (when-not (migration/same-edn? (order records) (order (materialize-chain fragments field name->id)))
      (reject! :list-declaration-mismatch))
    (assoc (builder/link-fragments fragments) :fragments fragments)))


(defn parse-direct
  "Read a direct builder in a declared field with frozen list ancestry.
   An inherited contribution changes the continuation address."
  [{:keys [fn-id scope binding-id name->id list-history list-type-names]} field form]
  (let [target (:id field)
        path [target]
        origin (builder/origin fn-id target)]
    (when-not (vector? (get list-history path)) (reject! :list-history-required))
    (when (seq (get list-history path)) (reject! :list-continuation-lowering-required))
    (when-not (= (:binding-id origin) (binding-id scope path)) (reject! :list-binding-origin-mismatch))
    (let [fragment (merge origin (builder/parse-fragment (:binding-id origin) form name->id))
          descriptor (select-keys field [:type :type-id])
          names (or list-type-names name->id)
          records (materialize-chain [fragment] descriptor names)]
      (assoc (recover-chain [fn-id] target descriptor names records) :field descriptor :records records))))
