(ns graphden.schema.traits.schema
  "Trait schema. Marks bindings with special behaviours.

   Entities:
   - trait         — trait definition (name, description).
   - binding-trait — association between a binding and a trait.

   Built-in traits:
   - merge-protected — binding's value should not transfer during
     branch merge."
  (:require
    [graphden.schema.protocol.protocol :as ds]))


(def ^:private trait-entity-uuid
  #uuid "71727374-7576-4a7b-8c9d-0e1f2a3b4c5d")


(def ^:private binding-trait-entity-uuid
  #uuid "72737475-7677-4b8c-9d0e-1f2a3b4c5d6e")


(def ^:private trait-name-field-uuid
  #uuid "73747576-7778-4c9d-0e1f-2a3b4c5d6e7f")


(def ^:private trait-description-field-uuid
  #uuid "74757677-7879-4d0e-1f2a-3b4c5d6e7f8a")


(def ^:private binding-trait-binding-id-field-uuid
  #uuid "75767778-7980-4e1f-2a3b-4c5d6e7f8a9b")


(def ^:private binding-trait-trait-id-field-uuid
  #uuid "76777879-8081-4f2a-3b4c-5d6e7f8a9b0c")


(def merge-protected-trait-uuid
  #uuid "11111111-1111-4111-8111-111111111111")


(defn extend-builder
  [builder]
  (-> builder
      (ds/add-entity :trait trait-entity-uuid
                     {:name {:uuid trait-name-field-uuid
                             :type :text}
                      :description {:uuid trait-description-field-uuid
                                    :type :text
                                    :nullable? true}})
      (ds/add-constraint :trait {:type :unique :fields [:name]})

      (ds/add-entity :binding-trait binding-trait-entity-uuid
                     {:binding-id {:uuid binding-trait-binding-id-field-uuid
                                   :type :ref :ref-entity :binding}
                      :trait-id {:uuid binding-trait-trait-id-field-uuid
                                 :type :ref :ref-entity :trait}})
      (ds/add-constraint :binding-trait {:type :unique :fields [:binding-id :trait-id]})))


(defn seed-traits!
  [storage]
  (let [sp (requiring-resolve 'graphden.storage.protocol.core/create-entity)
        read-entity (requiring-resolve 'graphden.storage.protocol.core/read-entity)]
    (when-not (@read-entity storage :trait merge-protected-trait-uuid)
      (@sp storage :trait
           {:id merge-protected-trait-uuid
            :name "merge-protected"
            :description "Value will not be transferred during branch merge"}))))


(def trait-entities
  #{:trait :binding-trait})


(def well-known-traits
  {:merge-protected merge-protected-trait-uuid})
