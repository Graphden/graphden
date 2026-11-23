(ns graphden.input.source-forward
  "Locate authored local fields behind unique legacy value-reference uses."
  (:require
    [graphden.input.format :as format]
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


(defn- reference-occurrence
  [prepared {owner-id :identity :keys [source]} field]
  (let [{:keys [defs-by-name name->id]} prepared
        owner-ref (slots/own-ref source defs-by-name)
        owner (get defs-by-name owner-ref)]
    (when-not (= owner-id (get name->id owner-ref) (:fn-id owner))
      (reject! :missing-prepared-owner))
    (let [[target-ref [declaring-ref n]]
          (single (keep (fn [target]
                          (when-let [field-origin (slots/resolve-slot-owner-strict target field defs-by-name #{})]
                            [target field-origin]))
                        (slots/ref-targets-of owner defs-by-name))
                  :nonunique-reference-use)
          target-lineage (slots/chain-of target-ref defs-by-name)
          declaring-id (get name->id declaring-ref)
          target-id (get name->id target-ref)]
      (when-not (and (= field n) (uuid? declaring-id) (uuid? target-id))
        (reject! :unsupported-reference-field-origin))
      {:target-id target-id :field-id (ids/slot-id declaring-id field)
       :direct? (boolean (some #{declaring-ref} target-lineage))
       :field-type (slots/slot-type-of declaring-ref field defs-by-name)
       :return-owner-ref (some #(when (get-in defs-by-name [% :return-type]) %) target-lineage)
       :return-type (some #(get-in defs-by-name [% :return-type]) target-lineage)})))


(defn- checked-reference-binding
  [{:keys [name->id] :as prepared} {owner-id :identity :keys [legacy]} {:keys [target-id return-type] :as target}]
  (let [row (single (filter #(and (= :binding (:kind %)) (= target-id (:ref-fn-id %)))
                            (:records legacy)) :missing-direct-reference-binding)
        argument (single (filter #(= (:id row) (get-in % [:origin :binding-id])) (:arguments legacy))
                         :missing-direct-reference-binding)
        [op ref] (:expression argument)
        expected (get-in argument [:target :type])
        origin (when-not (format/configuration-type? expected) (generic/origin prepared argument row target))]
    (when-not (and (= op :ref) (= target-id (get name->id ref))
                   (= owner-id (:fn-id row) (get-in argument [:origin :fn-id]))
                   (= (:slot-id row) (get-in argument [:origin :slot-id]))
                   (uuid? (:slot-id row)) (= (:id row) (ids/binding-id owner-id (:slot-id row)))
                   (not (:value-present row)) (empty? (:policy argument))
                   (or (format/configuration-type? expected) origin) (format/configuration-type? return-type)
                   (= :value (:mode (reference/classify expected {:return return-type}))))
      (reject! :unproved-reference-binding))
    (cond-> {:binding-id (:id row)} origin (assoc :generic-reference origin))))


(defn verify-forwarded-field
  "Prove the slot and direct value-reference occurrence behind one blocked
   authored field. Uses the prepared legacy context and source-plan ledger.
   Deep occurrence addresses are resolved by the frozen graph verifier.
   Repeated uses of the same ref are ambiguous. Does not write source or
   claim that the full surrounding graph has a checked runtime contract."
  [{:keys [name->id] :as prepared} {owner-id :identity :as entry} field]
  (when-not (and (uuid? owner-id) (simple-keyword? field))
    (reject! :missing-field-origin))
  (let [{:keys [slot membership type]} (linked-declaration entry field name->id)
        {:keys [field-id field-type target-id direct?] :as target} (reference-occurrence prepared entry field)
        {:keys [binding-id generic-reference]} (checked-reference-binding prepared entry target)
        type (qualified-type prepared (:source entry) type)]
    (when-not (and (= field-id (:source-slot-id slot))
                   (or (= type field-type)
                       (and (primitive-field? type) (primitive-field? field-type) (types/subtype? type field-type))))
      (reject! :unproved-forwarded-target))
    (cond-> {:owner-id owner-id :field-id (:id slot) :membership-id (:id membership)
             :source-path [:args field] :target-fn-id target-id :target-field-id field-id :root-use-id binding-id
             :source-type type :target-type field-type
             :projection-id (migration/derived-id :forwarded-field owner-id binding-id field-id)
             :source-address [[owner-id []] [(:id slot)]]
             :target-address (when direct? [[owner-id [binding-id]] [field-id]])}
      generic-reference (assoc :generic-reference generic-reference))))
