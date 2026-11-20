(ns graphden.input.list-records
  "Offline declarations and ordinary input-binding rows for a generated list
   callable. Keep the ordered operand rows reconstructible by stable IDs."
  (:require
    [graphden.input.core :as input]
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
  [input-id {:keys [owner-id builder-id]} use-path element-id {:keys [id position kind fn-id value source-id]}]
  (let [[field membership] (field-rows input-id builder-id (str "item-" position) position element-id (not= :projection kind))
        binding (rows/normalize
                  (merge {:kind :input-binding :id id :fn-id builder-id :scope-path [] :target-path [(:id field)]}
                         (case kind
                           :projection {:fn-id owner-id :scope-path use-path :expression-kind "projection" :value-present false
                                        :projection-scope-path [] :projection-path [source-id]}
                           :value {:expression-kind "value" :value-present true :value value}
                           :ref {:expression-kind "ref" :value-present false :ref-fn-id fn-id}
                           (reject! :invalid-list-item))))]
    [field membership binding]))


(defn materialize
  "Generate the builder callable, declared input type, ordered operand fields
   and input bindings from one source fragment. The optional tail belongs to
   this builder. link-fragments supplies its external ref and any closed seal.
   Field descriptor and prepared names are supplied by the source context."
  ([fragment field name->id] (materialize fragment field name->id [(:binding-id fragment)]))
  ([{:keys [builder-id tail-id items] :as fragment} field name->id use-path]
   (builder/link-fragments [fragment])
   (builder/compiler-items fragment)
   (let [element-id (item-type-id field name->id)
         input (assoc (migration/input-type-record {:id builder-id}) :kind :fn)
         input-id (:id input)
         owner {:kind :fn :id builder-id :parent-ids [] :input-type-fn-id input-id :return-type-fn-id (:type-id field)}
         tail (field-rows input-id builder-id :tail (count items) (:type-id field) false)]
     (when-not (= tail-id (:id (first tail))) (reject! :invalid-list-chain))
     (into (into [owner input] (mapcat (partial operand-rows input-id fragment use-path element-id)) items) tail))))


(defn- read-operand
  [by-field membership]
  (let [row (get by-field (:slot-id membership))
        item {:id (:id row) :position (:position membership)}]
    (case (:expression-kind row)
      "value" (assoc item :kind :value :value (:value row))
      "projection" (assoc item :kind :projection :source-id (first (:projection-path row)))
      "ref" (assoc item :kind :ref :fn-id (:ref-fn-id row))
      (reject! :missing-list-operand))))


(defn recover
  "Recover a fragment from its generated declaration rows and the chain's
   independently known open/closed state. Regenerate and compare every local
   row so swapped slots, membership positions or policies cannot slip through.
   Pass only materialize's declaration rows, excluding link-fragments' edges."
  ([origin field name->id records] (recover origin field name->id records [(:binding-id origin)]))
  ([origin field name->id records use-path]
   (let [members (sort-by :position (filter #(= :fn-slot (:kind %)) records))
         bindings (filter #(= :input-binding (:kind %)) records)
         by-field (into {} (map (juxt (comp first :target-path) identity)) bindings)
         result (assoc (dissoc origin :items) :items (mapv (partial read-operand by-field) (butlast members)))
         order #(sort-by (juxt :kind (comp str :id)) %)]
     (when-not (migration/same-edn? (order records) (order (materialize result field name->id use-path)))
       (reject! :list-declaration-mismatch))
     result)))


(defn materialize-chain
  "Produce all source-derived declaration and binding rows for a complete
   chain, including the owner/use ref edges and the optional final seal."
  [fragments field name->id]
  (let [paths (rest (reductions conj [] (map :binding-id fragments)))]
    (into (:bindings (builder/link-fragments fragments))
          (mapcat #(materialize %1 field name->id %2) fragments paths))))


(defn- read-contribution
  [rows-by-kind owner-id target-id field name->id use-path]
  (let [{:keys [builder-id tail-id] :as origin} (builder/origin owner-id target-id)
        input-id (:id (migration/input-type-record {:id builder-id}))
        memberships (filter #(= input-id (:fn-id %)) (:fn-slot rows-by-kind))
        field-ids (set (map :slot-id memberships))
        own (filter #(or (= builder-id (:fn-id %))
                         (and (= owner-id (:fn-id %)) (= use-path (:scope-path %))
                              (contains? field-ids (first (:target-path %))))) (:input-binding rows-by-kind))
        seal-id (ids/binding-id builder-id tail-id)
        declarations (concat (filter #(contains? #{builder-id input-id} (:id %)) (:fn rows-by-kind))
                             (filter #(contains? field-ids (:id %)) (:slot rows-by-kind))
                             memberships (remove #(= seal-id (:id %)) own))
        tail (if (some #(= seal-id (:id %)) own) :closed :open)]
    (recover (assoc origin :tail tail) field name->id declarations use-path)))


(defn recover-chain
  "Read a frozen row set using the contributing owners and original field
   in verified inheritance order. Compare every regenerated row, including
   the actual continuation address, its owner, and any sealed empty value."
  [owners target-id field name->id records]
  (let [by-kind (group-by :kind records)
        paths (rest (reductions conj [] (map #(ids/binding-id % target-id) owners)))
        fragments (mapv #(read-contribution by-kind %1 target-id field name->id %2) owners paths)
        order #(sort-by (juxt :kind (comp str :id)) %)]
    (when-not (migration/same-edn? (order records) (order (materialize-chain fragments field name->id)))
      (reject! :list-declaration-mismatch))
    (assoc (builder/link-fragments fragments) :fragments fragments)))


(defn parse-contribution
  "Read the current fragment against frozen list ancestry. Reconstruct the
   complete chain before exporting this contribution's own declarations."
  [{:keys [fn-id scope binding-id name->id list-history list-type-names contract]} field form]
  (let [target (:id field)
        path [target]
        prior (get list-history path)
        origin (builder/origin fn-id target)]
    (when-not (vector? prior) (reject! :list-history-required))
    (when-not (= (:binding-id origin) (binding-id scope path)) (reject! :list-binding-origin-mismatch))
    (let [fragment (merge origin (builder/parse-fragment (:binding-id origin) form name->id fn-id))
          descriptor (select-keys field [:type :type-id])
          names (or list-type-names name->id)
          fragments (conj prior fragment)
          records (materialize-chain fragments descriptor names)]
      (when-not (every? (set (map :id (:fields contract))) (keep :source-id (:items fragment)))
        (reject! :positional-list-field-mismatch))
      (assoc (recover-chain (mapv :owner-id fragments) target descriptor names records) :field descriptor :records records))))


(defn- use-records
  [origins records]
  (let [builders (set (map :builder-id origins))
        inputs (into #{} (map #(:id (migration/input-type-record {:id %}))) builders)
        owners (set (map :owner-id origins))
        links (set (map :binding-id origins))
        first-origin (first origins)
        members? #(or (contains? inputs (:fn-id %)) (contains? builders (:fn-id %)))
        slots (into #{} (keep #(when (and (= :fn-slot (:kind %)) (members? %)) (:slot-id %))) records)]
    (filterv (fn [{:keys [kind id fn-id scope-path target-path] :as row}]
               (case kind
                 :fn (or (contains? builders id) (contains? inputs id))
                 :slot (contains? slots id)
                 :fn-slot (members? row)
                 :input-binding (or (contains? links id) (contains? builders fn-id) (contains? inputs fn-id)
                                    (and (contains? owners fn-id)
                                         (or (and (contains? links (peek scope-path)) (contains? slots (first target-path)))
                                             (= (:binding-id first-origin) (first scope-path))
                                             (and (empty? scope-path) (= target-path [(:target-id first-origin)])))))
                 false))
             records)))


(defn authored-records
  "Select the declarations and binding rows written by the current
   contribution. Ancestor builders can belong to another source module."
  [{:keys [fragments records]}]
  (use-records [(peek fragments)] records))


(defn- check-position-fields!
  [fragments records]
  (let [groups (group-by :kind records)
        fields (into {} (map (juxt :id identity)) (:slot groups))
        owners (into {} (map (juxt :id identity)) (:fn groups))
        members (set (map (juxt :fn-id :slot-id) (:fn-slot groups)))]
    (doseq [{:keys [owner-id items]} fragments
            source (keep :source-id items)]
      (let [field (get fields source)
            input-type (get-in owners [owner-id :input-type-fn-id])]
        (when-not (and (string? (:name field)) (= source (ids/slot-id owner-id (:name field)))
                       (contains? members [input-type source]))
          (reject! :positional-list-field-mismatch))))))


(defn recover-use
  "Read one use from the combined records of validated v2 modules. Owners
   are the contributing declarations in verified parent order. Extra writes
   at this use, including an unrelated overlay on its tail, must fail the
   complete chain comparison. Sibling uses retain independent bindings."
  [owners target-id field name->id records]
  (when-not (and (vector? owners) (seq owners)) (reject! :invalid-list-chain))
  (let [origins (mapv #(builder/origin % target-id) owners)
        trace (recover-chain owners target-id field name->id (use-records origins records))]
    (when (some #(some :source-id (:items %)) (:fragments trace))
      (check-position-fields! (:fragments trace) records))
    trace))


(defn locate-contributions
  "A child appends at the preceding builder's open tail in this use of the
   inherited field. Move only its newly authored ref to the proven address."
  [state scope builders]
  (reduce-kv
    (fn [bindings path {:keys [fragments] :as trace}]
      (if (< 1 (count fragments))
        (let [{:keys [binding-id builder-id]} (peek fragments)
              edge (some #(when (= binding-id (:id %)) %) (:bindings trace))
              address [[(:fn-id edge) (:scope-path edge)] (:target-path edge)]
              entry (get bindings [scope path])]
          (when-not (and (= binding-id (:binding-id entry)) (= builder-id (:ref-fn-id edge))
                         (= builder-id (get-in entry [:expression :fn-id])))
            (reject! :list-binding-origin-mismatch))
          (input/merge-bindings (dissoc bindings [scope path]) {address entry}))
        bindings))
    state builders))
