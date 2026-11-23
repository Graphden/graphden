(ns graphden.input.source-forward-list
  "Exact authored list-item origins for a forwarded reference input."
  (:require
    [graphden.input.format :as format]
    [graphden.input.list-builder :as builder]
    [graphden.input.list-signature :as signature]
    [graphden.input.migration :as migration]
    [graphden.input.source-list :as source-list]
    [graphden.packages.records.ids :as ids]
    [graphden.types.core :as types]
    [graphden.types.reference :as reference]))


(defn- reject!
  []
  (throw (ex-info "Cannot prove the forwarded list occurrence"
                  {:type :input/source-forward :code :unproved-list-reference-binding})))


(defn- single
  [values]
  (let [[value & more] values]
    (if (and value (empty? more)) value (reject!))))


(defn origin
  "Recheck the complete original contribution, including all item positions.
   Only a root contribution with ordinary value delivery is admitted here."
  [{:keys [name->id]} {owner :identity :keys [source legacy]} item {:keys [target-id return-type]}]
  (let [binding (single (filter #(and (= :binding (:kind %)) (= (:binding-id item) (:id %))) (:records legacy)))
        argument (single (filter #(= (:id binding) (get-in % [:origin :binding-id])) (:arguments legacy)))
        path (:source-path argument)
        trace (:list-builder argument)
        fragment (peek (:fragments trace))
        names (source-list/field-names name->id (:namespace source) (get-in source path))
        item-rows (filter #(and (= :binding-list-item (:kind %)) (= (:id binding) (:binding-id %))) (:records legacy))
        recovered (builder/source-fragment binding item-rows (get-in source path) names)
        element (when (types/list-type? (get-in argument [:target :type]))
                  (types/list-elem (get-in argument [:target :type])))
        position (:position item)
        link (single (filter #(= (:id binding) (:id %)) (:bindings trace)))]
    (when-not (and (= [:args (get-in argument [:target :field])] path)
                   (= owner (:fn-id binding) (:owner-id fragment) (get-in argument [:origin :fn-id]))
                   (= (:slot-id binding) (:target-id fragment) (get-in argument [:origin :slot-id]))
                   (= (:id binding) (:binding-id fragment))
                   (:records trace) (migration/same-edn? recovered fragment)
                   (= [] (:scope-path link)) (= [(:slot-id binding)] (:target-path link))
                   (= (:builder-id fragment) (:ref-fn-id link))
                   (nat-int? position) (= (:id item) (ids/binding-list-item-id (:id binding) position))
                   (= {:id (:id item) :position position :kind :ref :fn-id target-id} (get (:items fragment) position))
                   (format/configuration-type? element) (not (types/callable-type? element)) (not= :fn-ref element)
                   (format/configuration-type? return-type)
                   (= :value (:mode (reference/classify element {:return return-type}))))
      (reject!))
    {:binding-id (:id binding)
     :list-reference (assoc (select-keys fragment [:owner-id :target-id :binding-id :builder-id :tail-id])
                            :position position :item-id (:id item) :target-fn-id target-id)}))


(defn matches-path?
  "Match this source witness against the already checked builder and operand
   edges. Their existing validators retain owner, scope and item identities."
  [{:keys [owner-id target-id binding-id builder-id tail-id position item-id target-fn-id]} edges]
  (let [[link operand] edges]
    (and (signature/source-link? link) (signature/source-operand? link operand)
         (= {:owner-id owner-id :target-id target-id :binding-id binding-id :builder-id builder-id :tail-id tail-id}
            (select-keys (:source-list-fragment link) [:owner-id :target-id :binding-id :builder-id :tail-id]))
         (= {:binding-id binding-id :position position :item-id item-id :ref-fn-id target-fn-id}
            (:source-list-operand operand)))))
