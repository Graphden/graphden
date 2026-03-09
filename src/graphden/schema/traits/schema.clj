(ns graphden.schema.traits.schema
  "Argument traits schema.

   Extends graph-schema with:
   - trait: trait definition (name, description)
   - arg-trait: association between arg and trait

   Traits mark args with special behaviors:
   - merge-protected: value should not transfer during branch merge"
  (:require
    [graphden.schema.protocol.protocol :as ds]))


;; Entity UUIDs
(def ^:private trait-entity-uuid
  #uuid "71727374-7576-4a7b-8c9d-0e1f2a3b4c5d")


(def ^:private arg-trait-entity-uuid
  #uuid "72737475-7677-4b8c-9d0e-1f2a3b4c5d6e")


;; Field UUIDs for :trait
(def ^:private trait-name-field-uuid
  #uuid "73747576-7778-4c9d-0e1f-2a3b4c5d6e7f")


(def ^:private trait-description-field-uuid
  #uuid "74757677-7879-4d0e-1f2a-3b4c5d6e7f8a")


;; Field UUIDs for :arg-trait
(def ^:private arg-trait-arg-id-field-uuid
  #uuid "75767778-7980-4e1f-2a3b-4c5d6e7f8a9b")


(def ^:private arg-trait-trait-id-field-uuid
  #uuid "76777879-8081-4f2a-3b4c-5d6e7f8a9b0c")


;; Well-known trait UUIDs
(def merge-protected-trait-uuid
  #uuid "11111111-1111-4111-8111-111111111111")


(defn extend-builder
  "Extends a builder with trait entities."
  [builder]
  (-> builder
      ;; trait
      (ds/add-entity :trait trait-entity-uuid
                     {:name {:uuid trait-name-field-uuid
                             :type :text}
                      :description {:uuid trait-description-field-uuid
                                    :type :text
                                    :nullable? true}})
      (ds/add-constraint :trait {:type :unique :fields [:name]})

      ;; arg-trait
      (ds/add-entity :arg-trait arg-trait-entity-uuid
                     {:arg-id {:uuid arg-trait-arg-id-field-uuid
                               :type :ref :ref-entity :arg}
                      :trait-id {:uuid arg-trait-trait-id-field-uuid
                                 :type :ref :ref-entity :trait}})
      (ds/add-constraint :arg-trait {:type :unique :fields [:arg-id :trait-id]})))


(defn seed-traits!
  "Seeds standard traits. Idempotent."
  [storage]
  (let [sp (requiring-resolve 'graphden.storage.protocol.core/create-entity)
        read-entity (requiring-resolve 'graphden.storage.protocol.core/read-entity)]
    (when-not (@read-entity storage :trait merge-protected-trait-uuid)
      (@sp storage :trait
           {:id merge-protected-trait-uuid
            :name "merge-protected"
            :description "Value will not be transferred during branch merge"}))))


(def trait-entities
  #{:trait :arg-trait})


(def well-known-traits
  {:merge-protected merge-protected-trait-uuid})
