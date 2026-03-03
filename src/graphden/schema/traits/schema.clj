(ns graphden.schema.traits.schema
  "Value traits schema definition.

   Extends graph-data-schema with entities for assigning traits to arg-values:
   - trait: definition of a trait (name, description)
   - value-trait: association between arg-value and trait

   Traits allow marking values with special behaviors:
   - merge-protected: value should not be transferred during branch merge
   - encrypted: value should be stored encrypted (future)
   - sensitive: value should be masked in UI (future)

   Addons can define their own traits and assign them to values."
  (:require
    [graphden.schema.protocol.protocol :as ds]))


;; === Stable UUIDs for value traits schema elements ===
;;
;; See graph-data-schema for explanation of UUID stability.
;; IMPORTANT: Never change these UUIDs!

;; Entity UUIDs
;; NOTE: These must be unique across all schema extensions!
;; Previously used UUIDs that conflicted with versioned-data-schema have been replaced.
(def ^:private trait-entity-uuid
  #uuid "71727374-7576-4a7b-8c9d-0e1f2a3b4c5d")


(def ^:private value-trait-entity-uuid
  #uuid "72737475-7677-4b8c-9d0e-1f2a3b4c5d6e")


;; Field UUIDs for :trait
(def ^:private trait-name-field-uuid
  #uuid "73747576-7778-4c9d-0e1f-2a3b4c5d6e7f")


(def ^:private trait-description-field-uuid
  #uuid "74757677-7879-4d0e-1f2a-3b4c5d6e7f8a")


;; Field UUIDs for :value-trait
(def ^:private value-trait-arg-value-id-field-uuid
  #uuid "75767778-7980-4e1f-2a3b-4c5d6e7f8a9b")


(def ^:private value-trait-trait-id-field-uuid
  #uuid "76777879-8081-4f2a-3b4c-5d6e7f8a9b0c")


;; Well-known trait UUIDs (for seeding)
(def merge-protected-trait-uuid
  "UUID for the merge-protected trait. Use this when seeding or querying."
  #uuid "11111111-1111-4111-8111-111111111111")


(defn extend-builder
  "Extends a builder with value traits entities.
   Returns the builder for further extension or finalization.

   Expects :arg-value entity to be defined (from graph-data-schema)."
  [builder]
  (-> builder
      ;; trait: definition of a trait
      (ds/add-entity :trait trait-entity-uuid
                     {:name {:uuid trait-name-field-uuid
                             :type :text}
                      :description {:uuid trait-description-field-uuid
                                    :type :text
                                    :nullable? true}})
      (ds/add-constraint :trait {:type :unique :fields [:name]})

      ;; value-trait: association between arg-value and trait
      (ds/add-entity :value-trait value-trait-entity-uuid
                     {:arg-value-id {:uuid value-trait-arg-value-id-field-uuid
                                     :type :ref :ref-entity :arg-value}
                      :trait-id {:uuid value-trait-trait-id-field-uuid
                                 :type :ref :ref-entity :trait}})
      (ds/add-constraint :value-trait {:type :unique :fields [:arg-value-id :trait-id]})))


(defn seed-traits!
  "Seeds the standard traits into storage. Idempotent.
   Call this after initializing storage with the schema."
  [storage]
  (let [sp (requiring-resolve 'graphden.storage.protocol.core/create-entity)
        read-entity (requiring-resolve 'graphden.storage.protocol.core/read-entity)]
    ;; Only seed if not exists
    (when-not (@read-entity storage :trait merge-protected-trait-uuid)
      (@sp storage :trait
           {:id merge-protected-trait-uuid
            :name "merge-protected"
            :description "Value will not be transferred during branch merge"}))))


(def trait-entities
  "Set of entity names that are trait-specific."
  #{:trait :value-trait})


(def well-known-traits
  "Map of well-known trait names to their UUIDs."
  {:merge-protected merge-protected-trait-uuid})
