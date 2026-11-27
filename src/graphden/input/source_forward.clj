(ns graphden.input.source-forward
  "Locate authored local fields behind unique legacy value-reference uses."
  (:require
    [graphden.input.format :as format]
    [graphden.input.list-builder :as list-builder]
    [graphden.input.migration :as migration]
    [graphden.input.source-forward-generic :as generic]
    [graphden.input.source-rich-types :as rich]
    [graphden.packages.rebase :as rebase]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.parse :as parse]
    [graphden.packages.records.slot-resolution :as slots]
    [graphden.packages.records.types :as record-types]
    [graphden.types.core :as types]
    [graphden.types.reference :as reference]))


(defn- reject!
  [code]
  (throw (ex-info "Cannot prove the forwarded source field"
                  {:type :input/source-forward :code code})))


(defn- single
  [matches code]
  (let [[found & more] matches]
    (if (and found (empty? more)) found (reject! code))))


(defn- primitive-field?
  [type]
  (and (keyword? type) (types/primitive? type) (not (#{:fn :fn-ref :input-stream} type))))


(defn- qualified-type
  [prepared source type]
  (rebase/rewrite-type-references type #(parse/normalize-ref % (:name->id prepared) (:namespace source))))


(defn- linked-declaration
  [{owner-id :identity :keys [source legacy blockers]} field name->id]
  (let [source-path [:args field]
        spec (get-in source source-path)
        slot-id (ids/slot-id owner-id field)
        rows (:records legacy)
        argument (single (filter #(= source-path (:source-path %)) (:arguments legacy)) :missing-field-origin)
        slot (single (filter #(and (= :slot (:kind %)) (= slot-id (:id %))) rows) :missing-field-origin)
        membership (single (filter #(and (= :fn-slot (:kind %)) (= slot-id (:slot-id %))) rows) :missing-field-origin)]
    (when-not (and (some #(= [source-path :field-projection-required] %) (map (juxt :source-path :code) blockers))
                   (every? #(= :field-projection-required (:code %)) blockers)
                   (= :declaration (:kind argument)) (record-types/own-slot-declaration? spec)
                   (migration/same-edn? spec (:spec argument))
                   (or (format/configuration-type? (:type spec))
                       (rich/authored-callable-field? (group-by :id rows) (:type-fn-id slot) (:type spec)))
                   (= (:type-fn-id slot) (record-types/resolve-type-ref (:type spec) name->id))
                   (= (name field) (:name slot))
                   (= (:source-slot-id argument) (:source-slot-id slot))
                   (uuid? (:source-slot-id slot)) (not= slot-id (:source-slot-id slot))
                   (= owner-id (:fn-id membership)) (= (ids/fn-slot-id owner-id slot-id) (:id membership))
                   (nat-int? (:position membership)))
      (reject! :unproved-field-origin))
    {:slot slot :membership membership :type (:type spec)}))


(defn- reference-occurrences
  [prepared {owner-id :identity :keys [source]} field]
  (let [{:keys [defs-by-name name->id]} prepared
        owner-ref (slots/own-ref source defs-by-name)
        owner (get defs-by-name owner-ref)]
    (when-not (= owner-id (get name->id owner-ref) (:fn-id owner))
      (reject! :missing-prepared-owner))
    (into []
          (keep (fn [target-ref]
                  (when-let [[declaring-ref name] (slots/resolve-slot-owner-strict target-ref field defs-by-name #{})]
                    (let [lineage (slots/chain-of target-ref defs-by-name)
                          declaring-id (get name->id declaring-ref)
                          target-id (get name->id target-ref)
                          field-type (slots/slot-type-of declaring-ref field defs-by-name)
                          untyped-rename? (and (nil? field-type)
                                               (slots/rename-target (get defs-by-name declaring-ref) field))
                          return-owner (some #(when (get-in defs-by-name [% :return-type]) %) lineage)]
                      (when-not (and (= field name) (uuid? declaring-id) (uuid? target-id))
                        (reject! :unsupported-reference-field-origin))
                      {:target-ref target-ref :target-id target-id :field-id (ids/slot-id declaring-id field)
                       :direct? (boolean (some #{declaring-ref} lineage))
                       :field-type field-type :untyped-rename? (boolean untyped-rename?)
                       :return-owner-ref return-owner
                       :return-type (get-in defs-by-name [return-owner :return-type])}))))
          (slots/ref-targets-of owner defs-by-name))))


(defn- conditional-branch?
  [prepared {:keys [target]} binding]
  (let [base-id (ids/fn-id "core.logic" :if)
        base (get-in prepared [:defs-by-name (:owner target)])
        field (:field target)
        variable ({:then 'a :else 'b} field)]
    (and variable (= base-id (:fn-id base))
         (= (:slot-id binding) (ids/slot-id base-id field))
         (= variable (:type target) (get-in base [:args field :type]))
         (= '[:union a b] (:return-type base)))))


(defn- checked-direct-reference
  [{:keys [name->id] :as prepared} {owner-id :identity :keys [legacy]}
   {:keys [target-id return-type multiple-readers?] :as target}]
  (let [row (single (filter #(and (= :binding (:kind %)) (= target-id (:ref-fn-id %)))
                            (:records legacy)) :missing-direct-reference-binding)
        argument (single (filter #(= (:id row) (get-in % [:origin :binding-id])) (:arguments legacy))
                         :missing-direct-reference-binding)
        [op ref] (:expression argument)
        expected (get-in argument [:target :type])
        origin (when-not (format/configuration-type? expected) (generic/origin prepared argument row target))
        ;; The stock checker instantiates the independent :if branch types.
        ;; The frozen replay must still prove a value edge and retain checks.
        conditional? (and multiple-readers? (conditional-branch? prepared argument row))]
    (when-not (and (= op :ref)
                   (or (= target-id (get name->id ref))
                       (and (map? ref) (= target-id (:inline-fn-id argument))
                            (format/inline-origin ref)))
                   (= owner-id (:fn-id row) (get-in argument [:origin :fn-id]))
                   (= (:slot-id row) (get-in argument [:origin :slot-id]))
                   (uuid? (:slot-id row)) (= (:id row) (ids/binding-id owner-id (:slot-id row)))
                   (not (:value-present row)) (empty? (:policy argument))
                   (or conditional?
                       (and (or (format/configuration-type? expected) origin)
                            (format/configuration-type? return-type)
                            (= :value (:mode (reference/classify expected {:return return-type}))))
                       (when-let [verify (::value-edge? prepared)]
                         (verify owner-id row))))
      (reject! :unproved-reference-binding))
    (cond-> {:binding-id (:id row)} origin (assoc :generic-reference origin))))


(defn- checked-list-reference
  [{owner-id :identity :keys [legacy]} target-id]
  (let [{:keys [fragment item argument]}
        (single (for [{:keys [list-builder origin] :as argument} (:arguments legacy)
                      :let [fragment (peek (:fragments list-builder))]
                      :when (and (:records list-builder) (= owner-id (:owner-id fragment))
                                 (= (:binding-id origin) (:binding-id fragment)))
                      item (:items fragment)
                      :when (= target-id (:fn-id item))]
                  {:fragment fragment :item item :argument argument})
                :missing-direct-reference-binding)
        {:keys [binding-id builder-id]} fragment
        {:keys [position id]} item
        {:keys [expression list-ref-names list-inline-items]} argument
        row (single (filter #(and (= :binding-list-item (:kind %)) (= id (:id %))) (:records legacy))
                    :missing-direct-reference-binding)]
    (when-not (and (= :ref (:kind item)) (= target-id (:ref-fn-id row)) (nil? (:value row))
                   (= binding-id (:binding-id row)) (= position (:position row))
                   (= id (ids/binding-list-item-id binding-id position))
                   (= {:kind :ref :fn-id builder-id}
                      (list-builder/reference-expression {:fragments [fragment]} expression list-ref-names list-inline-items)))
      (reject! :unproved-reference-binding))
    {:binding-id binding-id :root-target-id builder-id
     :list-operand {:binding-id binding-id :position position :item-id id :ref-fn-id target-id}}))


(defn- checked-reference-binding
  [prepared entry {:keys [target-id] :as target}]
  (if (some #(and (= :binding (:kind %)) (= target-id (:ref-fn-id %))) (get-in entry [:legacy :records]))
    (checked-direct-reference prepared entry target)
    (checked-list-reference entry target-id)))


(defn- proof
  [prepared {owner-id :identity :as entry} field {:keys [slot membership type]}
   {:keys [field-id field-type target-id direct? multiple-readers? untyped-rename?] :as target}]
  (let [{:keys [binding-id generic-reference root-target-id list-operand]}
        (checked-reference-binding prepared entry target)]
    (when-not (and (= field-id (:source-slot-id slot))
                   (or (= type field-type)
                       (and (nil? field-type) (or list-operand (and multiple-readers? untyped-rename?)))
                       (and (primitive-field? type) (primitive-field? field-type) (types/subtype? type field-type))))
      (reject! :unproved-forwarded-target))
    (cond-> {:owner-id owner-id :field-id (:id slot) :membership-id (:id membership)
             :source-path [:args field] :target-fn-id (or root-target-id target-id)
             :target-field-id field-id :root-use-id binding-id
             :source-type type :target-type field-type
             :projection-id (migration/derived-id :forwarded-field owner-id binding-id field-id)
             :source-address [[owner-id []] [(:id slot)]]
             :target-address (when direct?
                               [[owner-id (cond-> [binding-id] list-operand (conj (:item-id list-operand)))] [field-id]])}
      list-operand (assoc :list-operand list-operand)
      (and multiple-readers? untyped-rename?) (assoc :untyped-rename? true)
      generic-reference (assoc :generic-reference generic-reference)
      multiple-readers? (assoc :multiple-readers? true))))


(defn- local-reader-steps
  [definition rows name->id defs-by-name hidden]
  (let [owner (:fn-id definition)
        items (group-by :binding-id (filter #(= :binding-list-item (:kind %)) rows))
        children (group-by name->id (distinct (slots/ref-targets-of definition defs-by-name)))]
    (for [row rows
          :when (and (= :binding (:kind row)) (= owner (:fn-id row)) (not (hidden (:slot-id row)))
                     (= (:id row) (ids/binding-id owner (:slot-id row))))
          [target step] (if-let [target (:ref-fn-id row)]
                          [[target [(:id row)]]]
                          (for [item (get items (:id row))
                                :let [position (:position item)]
                                :when (and (:ref-fn-id item) (nat-int? position)
                                           (= (:id item) (ids/binding-list-item-id (:id row) position)))]
                            [(:ref-fn-id item) [(:id row) (:id item)]]))
          child (get children target)]
      [child step])))


(defn- authored-reference-steps
  [{:keys [name->id defs-by-name]} ref]
  (:steps
    (reduce (fn [{:keys [steps hidden]} ancestor]
              (let [definition (get defs-by-name ancestor)
                    rows (parse/parse-fn-def definition name->id defs-by-name)
                    fields (keep #(when (= (:fn-id definition) (:fn-id %))
                                    (case (:kind %) :binding (:slot-id %) :slot (:source-slot-id %) nil)) rows)]
                {:steps (into steps (local-reader-steps definition rows name->id defs-by-name hidden))
                 :hidden (into hidden fields)}))
            {:steps [] :hidden #{}} (slots/chain-of ref defs-by-name))))


(defn- authored-reader-paths
  [{:keys [name->id defs-by-name] :as prepared} ref field field-id prefix seen]
  (when-not (contains? seen ref)
    (let [[declaring key] (slots/resolve-slot-owner-strict ref field defs-by-name #{})
          declaring-id (get name->id declaring)]
      (when (and (uuid? declaring-id) (= field key) (= field-id (ids/slot-id declaring-id key)))
        (if (some #{declaring} (slots/chain-of ref defs-by-name))
          [prefix]
          (for [[child step] (authored-reference-steps prepared ref)
                path (authored-reader-paths prepared child field field-id (into prefix step) (conj seen ref))]
            path))))))


(defn- reader-proofs
  [prepared entry field declaration {:keys [direct? field-id target-ref multiple-readers?] :as target}]
  (let [{:keys [owner-id root-use-id list-operand] :as base} (proof prepared entry field declaration target)
        prefix (cond-> [root-use-id] list-operand (conj (:item-id list-operand)))
        paths (if direct?
                [prefix]
                (when (or multiple-readers? (nil? list-operand))
                  (authored-reader-paths prepared target-ref field field-id prefix #{})))]
    (if (or (next paths) (and multiple-readers? list-operand))
      (do
        (when-not (seq paths) (reject! :unproved-forwarded-target))
        (mapv (fn [path]
                (assoc base :multiple-readers? true :nested-reader? true
                       :target-address [[owner-id path] [field-id]]
                       :projection-id (migration/derived-id :forwarded-field owner-id path field-id))) paths))
      [base])))


(defn verify-forwarded-uses
  "Find the original slot and candidate reference uses for one declared field.
   Distinct references can reach the same declared slot. The frozen graph
   must locate one exact reader under each reference and account for all readers."
  [{:keys [name->id] :as prepared} {owner-id :identity :as entry} field]
  (when-not (and (uuid? owner-id) (simple-keyword? field))
    (reject! :missing-field-origin))
  (let [{:keys [slot type] :as declaration} (linked-declaration entry field name->id)
        targets (reference-occurrences prepared entry field)
        multiple? (> (count targets) 1)
        declaration (assoc declaration :type (qualified-type prepared (:source entry) type))]
    (when-not (and (seq targets)
                   (or (not multiple?)
                       (and (format/configuration-type? type)
                            (= (count targets) (count (set (map :target-id targets))))
                            (every? #(and (= (:source-slot-id slot) (:field-id %))
                                          (or (= type (:field-type %)) (:untyped-rename? %))) targets))))
      (reject! :nonunique-reference-use))
    (into [] (mapcat #(reader-proofs prepared entry field declaration (cond-> % multiple? (assoc :multiple-readers? true)))) targets)))


(defn verify-forwarded-field
  "Prove one unambiguous value-reference occurrence for a field."
  [prepared entry field]
  (single (verify-forwarded-uses prepared entry field) :nonunique-reference-use))
