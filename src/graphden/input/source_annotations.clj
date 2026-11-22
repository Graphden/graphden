(ns graphden.input.source-annotations
  "Exact metadata-only assertions on an existing inherited source field."
  (:require
    [graphden.input.format :as format]
    [graphden.packages.records.ids :as ids]
    [graphden.packages.records.slot-resolution :as slots]
    [graphden.packages.records.types :as records]))


(defn source-form?
  [field spec]
  (and (simple-keyword? field) (map? spec) (= field (:as spec))
       (every? #{:as :type :description} (keys spec))
       (or (not (contains? spec :type)) (format/configuration-type? (:type spec)))
       (or (not (contains? spec :description)) (string? (:description spec)))))


(defn annotation
  "A same-name :as emits no alias slot. Accept only an unchanged inherited
   type and the exact empty legacy binding, including authored description.
   Reference-tree fields and narrowing assertions need separate proofs."
  [{:keys [name->id defs-by-name]} definition rows field]
  (let [spec (get-in definition [:args field])]
    (when (source-form? field spec)
      (let [own-ref (slots/own-ref definition defs-by-name)
            prepared (get defs-by-name own-ref)
            value (get-in prepared [:args field])
            [declaring key] (slots/resolve-slot-owner own-ref field defs-by-name value)
            declaring-id (get name->id declaring)
            owner-id (ids/fn-id (:namespace definition) (:name definition))
            slot-id (when (uuid? declaring-id) (ids/slot-id declaring-id key))
            type-id (when (contains? spec :type) (records/resolve-type-ref (:type value) name->id))
            id (when slot-id (ids/binding-id owner-id slot-id))
            matches (filter #(and (= :binding (:kind %)) (= id (:id %))) rows)
            expected (cond-> {:kind :binding :id id :fn-id owner-id :slot-id slot-id :value-present false}
                       type-id (assoc :type-override-fn-id type-id)
                       (contains? spec :description) (assoc :description (:description spec)))]
        (when (and (uuid? slot-id) (= field key) (= 1 (count matches))
                   (some #{declaring} (rest (slots/chain-of own-ref defs-by-name)))
                   (or (not (contains? spec :type)) (= (:type value) (slots/slot-type-of declaring key defs-by-name)))
                   (= expected (into {} (remove (comp nil? val)) (first matches))))
          (cond-> {:target-path [slot-id]
                   :row (-> expected (dissoc :slot-id) (assoc :kind :input-binding
                                                              :scope-path [] :target-path [slot-id]
                                                              :expression-kind nil))}
            type-id (assoc :type (:type spec) :type-override-fn-id type-id)
            (contains? spec :description) (assoc :description (:description spec))))))))


(defn configuration
  [annotation]
  (select-keys annotation [:type :description]))


(defn exact?
  "An unchanged field type and a metadata-only row require no value or callable
   invocation proof. The caller supplies the proved inherited field; narrower
   types do not match."
  [owner field {:keys [row type target-path type-override-fn-id] :as annotation}]
  (let [id (:id field)
        expected (when (and (uuid? (:id owner)) (uuid? id))
                   (cond-> {:kind :input-binding :id (ids/binding-id (:id owner) id)
                            :fn-id (:id owner) :scope-path [] :target-path [id]
                            :expression-kind nil :value-present false}
                     (contains? annotation :type) (assoc :type-override-fn-id (:type-id field))
                     (contains? annotation :description) (assoc :description (:description annotation))))]
    (and (uuid? (:id owner)) (uuid? id)
         (or (not (contains? annotation :type)) (= type (:type field)))
         (= [id] target-path)
         (= (when (contains? annotation :type) (:type-id field)) type-override-fn-id)
         (= expected row))))
