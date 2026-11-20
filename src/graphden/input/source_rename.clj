(ns graphden.input.source-rename
  "Origin checks for a scalar rename of an immediate parent field."
  (:require
    [graphden.input.migration :as migration]
    [graphden.packages.records.ids :as ids]))


(def ^:private scalar-types
  (select-keys (ids/primitive-fn-ids) [:any :null :uuid :text :int :bool :numeric :timestamptz :bytes :keyword :float]))


(defn origin
  "Match the emitted alias slot, its source FK and its legacy binding to
   their deterministic identities and the parent's authored field type.
   A richer declaration erased to :any cannot qualify by storage ID alone."
  [owner records field spec parent-type]
  (when (and (= 1 (count (:parent-ids owner))) (map? spec) (contains? #{#{:as} #{:as :type}} (set (keys spec)))
             (simple-keyword? (:as spec)) (not= field (:as spec)))
    (let [exposed-name (:as spec)
          alias-id (ids/slot-id (:id owner) exposed-name)
          field-id (ids/slot-id (first (:parent-ids owner)) field)
          slot (some #(when (= alias-id (:id %)) %) (:slot records))
          type (when-let [expected (get scalar-types parent-type)]
                 (when (= expected (:type-fn-id slot)) parent-type))]
      (when (and type (or (not (contains? spec :type)) (= type (:type spec))) (= field-id (:source-slot-id slot))
                 (= (name exposed-name) (:name slot)) (false? (:required slot))
                 (some #(and (= alias-id (:slot-id %)) (= (:id owner) (:fn-id %))
                             (= (ids/fn-slot-id (:id owner) alias-id) (:id %))) (:fn-slot records)))
        (cond-> {:alias exposed-name :alias-id alias-id :field-id field-id
                 :declaration {:type type :required false}}
          (contains? spec :type) (assoc :pin-id (:type-fn-id slot)))))))


(defn legacy-binding
  "Invert a verified projection only through the authored alias-slot FK.
   All other rows use the existing shallow storage inverse."
  [declarations row]
  (if (= "projection" (:expression-kind row))
    (let [[exposed-name] (:projection-path row)
          [field] (:target-path row)
          owner (:fn-id row)
          slot (some #(when (and (= :slot (:kind %)) (= exposed-name (:id %))) %) declarations)]
      (when-not (and (= [] (:scope-path row) (:projection-scope-path row))
                     (= [exposed-name] (:projection-path row)) (= [field] (:target-path row))
                     (= field (:source-slot-id slot))
                     (= exposed-name (ids/slot-id owner (:name slot))) (= (:id row) (ids/binding-id owner field)))
        (throw (ex-info "Projection differs from the legacy rename" {:code :rename-origin-mismatch})))
      (migration/legacy-binding (-> row (assoc :expression-kind nil) (dissoc :projection-path :projection-scope-path))))
    (migration/legacy-binding row)))
