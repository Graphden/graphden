(ns graphden.memory-storage.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.memory-storage.interface :as mem]
    [graphden.storage-protocol.interface :as sp]))


;; === Test helpers ===

(defn- make-schema
  "Creates a simple schema with one entity and one enum for testing."
  [& {:keys [entity-name entity-uuid fields enum-name enum-uuid enum-values]
      :or {entity-name :user
           entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
           fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                          :type :text}}}}]
  (cond-> (mds/create-builder)
    (and enum-name enum-uuid enum-values)
    (ds/add-enum enum-name enum-uuid enum-values)

    true
    (ds/add-entity entity-name entity-uuid fields)

    true
    ds/build))


;; === First-time initialization tests ===

(deftest first-initialization-test
  (testing "initializing empty storage creates entities"
    (let [storage (mem/create-storage)
          schema (make-schema)
          changes (sp/initialize storage schema)]
      (is (= [:user] (:created (:entities changes))))
      (is (= {} (:renamed (:entities changes))))
      (is (= #{{:entity :user :field :name}} (set (:created (:fields changes)))))
      (is (= [] (:renamed (:fields changes))))))

  (testing "initializing with enum creates enum"
    (let [storage (mem/create-storage)
          schema (make-schema :enum-name :status
                              :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                              :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                             :value :active}
                                            {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                             :value :inactive}])
          changes (sp/initialize storage schema)]
      (is (= [:status] (:created (:enums changes))))
      (is (= {} (:renamed (:enums changes))))
      (is (= #{{:enum :status :value :active}
               {:enum :status :value :inactive}}
             (set (:created (:enum-values changes))))))))


;; === StorageIntrospection tests ===

(deftest introspection-test
  (testing "current-entities returns entity names"
    (let [storage (mem/create-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (is (= #{:user} (sp/current-entities storage)))))

  (testing "current-fields returns field definitions"
    (let [storage (mem/create-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}
                                       :email {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                               :type :text
                                               :nullable? true}})]
      (sp/initialize storage schema)
      (is (= {:name {:type :text :nullable? false}
              :email {:type :text :nullable? true}}
             (sp/current-fields storage :user)))))

  (testing "current-fields returns nil for unknown entity"
    (let [storage (mem/create-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (is (nil? (sp/current-fields storage :unknown)))))

  (testing "current-enums returns enum names"
    (let [storage (mem/create-storage)
          schema (make-schema :enum-name :status
                              :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                              :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                             :value :active}])]
      (sp/initialize storage schema)
      (is (= #{:status} (sp/current-enums storage)))))

  (testing "current-enum-values returns values"
    (let [storage (mem/create-storage)
          schema (make-schema :enum-name :status
                              :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                              :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                             :value :active}
                                            {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                             :value :inactive}])]
      (sp/initialize storage schema)
      (is (= #{:active :inactive} (sp/current-enum-values storage :status)))))

  (testing "schema-metadata returns uuid mappings"
    (let [storage (mem/create-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema (make-schema :entity-uuid entity-uuid
                              :fields {:name {:uuid field-uuid :type :text}})]
      (sp/initialize storage schema)
      (let [metadata (sp/schema-metadata storage)]
        (is (= :user (get (:entities metadata) entity-uuid)))
        (is (= {:entity :user :field :name} (get (:fields metadata) field-uuid)))))))


;; === Re-initialization (no changes) tests ===

(deftest no-changes-test
  (testing "re-initializing with same schema reports no changes"
    (let [storage (mem/create-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (let [changes (sp/initialize storage schema)]
        (is (= [] (:created (:entities changes))))
        (is (= {} (:renamed (:entities changes))))
        (is (= [] (:created (:fields changes))))
        (is (= [] (:renamed (:fields changes))))))))


;; === Adding new entities/fields tests ===

(deftest adding-test
  (testing "adding new entity"
    (let [storage (mem/create-storage)
          schema1 (make-schema)
          _ (sp/initialize storage schema1)
          schema2 (-> (mds/create-builder)
                      (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :text}})
                      (ds/add-entity :post #uuid "00000000-0000-0000-0000-000000000020"
                                     {:title {:uuid #uuid "00000000-0000-0000-0000-000000000021"
                                              :type :text}})
                      ds/build)
          changes (sp/initialize storage schema2)]
      (is (= [:post] (:created (:entities changes))))
      (is (= #{{:entity :post :field :title}} (set (:created (:fields changes)))))))

  (testing "adding new field to existing entity"
    (let [storage (mem/create-storage)
          schema1 (make-schema)
          _ (sp/initialize storage schema1)
          schema2 (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                               :type :text}
                                        :email {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                :type :text}})
          changes (sp/initialize storage schema2)]
      (is (= [] (:created (:entities changes))))
      (is (= [{:entity :user :field :email}] (:created (:fields changes))))))

  (testing "adding new enum"
    (let [storage (mem/create-storage)
          schema1 (make-schema)
          _ (sp/initialize storage schema1)
          schema2 (make-schema :enum-name :status
                               :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                               :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                              :value :active}])
          changes (sp/initialize storage schema2)]
      (is (= [:status] (:created (:enums changes))))
      (is (= [{:enum :status :value :active}] (:created (:enum-values changes))))))

  (testing "adding new enum value"
    (let [storage (mem/create-storage)
          schema1 (make-schema :enum-name :status
                               :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                               :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                              :value :active}])
          _ (sp/initialize storage schema1)
          schema2 (make-schema :enum-name :status
                               :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                               :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                              :value :active}
                                             {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                              :value :inactive}])
          changes (sp/initialize storage schema2)]
      (is (= [] (:created (:enums changes))))
      (is (= [{:enum :status :value :inactive}] (:created (:enum-values changes)))))))


;; === Renaming tests ===

(deftest renaming-test
  (testing "renaming entity (same UUID, different name)"
    (let [storage (mem/create-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :entity-name :user
                               :entity-uuid entity-uuid
                               :fields {:name {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-name :person
                               :entity-uuid entity-uuid
                               :fields {:name {:uuid field-uuid :type :text}})
          changes (sp/initialize storage schema2)]
      (is (= [] (:created (:entities changes))))
      (is (= {:user :person} (:renamed (:entities changes))))
      (is (= #{:person} (sp/current-entities storage)))))

  (testing "renaming field (same UUID, different name)"
    (let [storage (mem/create-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :entity-uuid entity-uuid
                               :fields {:name {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-uuid entity-uuid
                               :fields {:full-name {:uuid field-uuid :type :text}})
          changes (sp/initialize storage schema2)]
      (is (= [] (:created (:fields changes))))
      (is (= [{:entity :user :old-field :name :new-field :full-name}]
             (:renamed (:fields changes))))
      (is (contains? (sp/current-fields storage :user) :full-name))
      (is (not (contains? (sp/current-fields storage :user) :name)))))

  (testing "renaming enum (same UUID, different name)"
    (let [storage (mem/create-storage)
          enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
          value-uuid #uuid "00000000-0000-0000-0000-000000000011"
          schema1 (make-schema :enum-name :status
                               :enum-uuid enum-uuid
                               :enum-values [{:uuid value-uuid :value :active}])
          _ (sp/initialize storage schema1)
          schema2 (make-schema :enum-name :state
                               :enum-uuid enum-uuid
                               :enum-values [{:uuid value-uuid :value :active}])
          changes (sp/initialize storage schema2)]
      (is (= [] (:created (:enums changes))))
      (is (= {:status :state} (:renamed (:enums changes))))
      (is (= #{:state} (sp/current-enums storage))))))


;; === Destructive changes tests (should throw) ===

(deftest destructive-changes-test
  (testing "removing entity throws"
    (let [storage (mem/create-storage)
          schema1 (-> (mds/create-builder)
                      (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :text}})
                      (ds/add-entity :post #uuid "00000000-0000-0000-0000-000000000020"
                                     {:title {:uuid #uuid "00000000-0000-0000-0000-000000000021"
                                              :type :text}})
                      ds/build)
          _ (sp/initialize storage schema1)
          schema2 (make-schema)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Destructive change: entities removed"
            (sp/initialize storage schema2)))))

  (testing "removing field throws"
    (let [storage (mem/create-storage)
          schema1 (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                               :type :text}
                                        :email {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                               :type :text}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Destructive change: fields removed"
            (sp/initialize storage schema2)))))

  (testing "removing enum throws"
    (let [storage (mem/create-storage)
          schema1 (make-schema :enum-name :status
                               :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                               :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                              :value :active}])
          _ (sp/initialize storage schema1)
          schema2 (make-schema)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Destructive change: enums removed"
            (sp/initialize storage schema2)))))

  (testing "removing enum value throws"
    (let [storage (mem/create-storage)
          schema1 (make-schema :enum-name :status
                               :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                               :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                              :value :active}
                                             {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                              :value :inactive}])
          _ (sp/initialize storage schema1)
          schema2 (make-schema :enum-name :status
                               :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                               :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                              :value :active}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Destructive change: enum values removed"
            (sp/initialize storage schema2))))))


;; === Type change tests ===

(deftest type-change-test
  (testing "safe type widening (int->numeric) is allowed"
    (let [storage (mem/create-storage)
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :fields {:count {:uuid field-uuid :type :int}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :fields {:count {:uuid field-uuid :type :numeric}})
          changes (sp/initialize storage schema2)]
      (is (= [] (:created (:fields changes))))
      (is (= :numeric (:type (get (sp/current-fields storage :user) :count))))))

  (testing "safe type widening (text->jsonb) is allowed"
    (let [storage (mem/create-storage)
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :fields {:data {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :fields {:data {:uuid field-uuid :type :jsonb}})
          changes (sp/initialize storage schema2)]
      (is (= [] (:created (:fields changes))))
      (is (= :jsonb (:type (get (sp/current-fields storage :user) :data))))))

  (testing "unsafe type narrowing (text->int) throws"
    (let [storage (mem/create-storage)
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :fields {:value {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :fields {:value {:uuid field-uuid :type :int}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Destructive change: incompatible type change"
            (sp/initialize storage schema2)))))

  (testing "unrelated type change (bool->uuid) throws"
    (let [storage (mem/create-storage)
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :fields {:flag {:uuid field-uuid :type :bool}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :fields {:flag {:uuid field-uuid :type :uuid}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Destructive change: incompatible type change"
            (sp/initialize storage schema2))))))


;; === Nullable change tests ===

(deftest nullable-change-test
  (testing "nullable->nullable is allowed"
    (let [storage (mem/create-storage)
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :fields {:email {:uuid field-uuid :type :text :nullable? true}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :fields {:email {:uuid field-uuid :type :text :nullable? true}})]
      (sp/initialize storage schema2)
      (is (true? (:nullable? (get (sp/current-fields storage :user) :email))))))

  (testing "non-nullable->nullable is allowed (allowing more)"
    (let [storage (mem/create-storage)
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :fields {:email {:uuid field-uuid :type :text :nullable? false}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :fields {:email {:uuid field-uuid :type :text :nullable? true}})]
      (sp/initialize storage schema2)
      (is (true? (:nullable? (get (sp/current-fields storage :user) :email))))))

  (testing "nullable->non-nullable throws (restricting)"
    (let [storage (mem/create-storage)
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :fields {:email {:uuid field-uuid :type :text :nullable? true}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :fields {:email {:uuid field-uuid :type :text :nullable? false}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"nullable to non-nullable"
            (sp/initialize storage schema2))))))


;; === Close tests ===

(deftest close-test
  (testing "close resets storage state"
    (let [storage (mem/create-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (is (= #{:user} (sp/current-entities storage)))
      (sp/close storage)
      (is (= #{} (sp/current-entities storage)))
      (is (nil? (sp/schema-metadata storage)))))

  (testing "close is idempotent"
    (let [storage (mem/create-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (sp/close storage)
      (sp/close storage)
      (is (= #{} (sp/current-entities storage))))))


;; === Additional edge case tests ===

(deftest current-enum-values-unknown-test
  (testing "current-enum-values returns nil for unknown enum"
    (let [storage (mem/create-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (is (nil? (sp/current-enum-values storage :nonexistent))))))


(deftest entity-with-no-fields-test
  (testing "entity with no fields works correctly"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :empty-entity #uuid "00000000-0000-0000-0000-000000000100"
                                    {})
                     ds/build)]
      (sp/initialize storage schema)
      (is (= #{:empty-entity} (sp/current-entities storage)))
      ;; Entity exists so should return empty map, not nil
      (is (= {} (sp/current-fields storage :empty-entity))))))


(deftest data-migration-with-field-rename-test
  (testing "data is migrated when field is renamed"
    (let [storage (mem/create-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000200"
          field-uuid #uuid "00000000-0000-0000-0000-000000000201"
          schema1 (make-schema :entity-name :record
                               :entity-uuid entity-uuid
                               :fields {:old-field {:uuid field-uuid :type :text}})]
      ;; Initialize with first schema
      (sp/initialize storage schema1)

      ;; Simulate having data by directly modifying the atom
      ;; (This tests the data migration logic that's otherwise not exercised)
      (let [state-atom (:state storage)
            row-id (random-uuid)]
        (swap! state-atom assoc-in [:data :record row-id] {:old-field "value1"})

        ;; Now rename the field
        (let [schema2 (make-schema :entity-name :record
                                   :entity-uuid entity-uuid
                                   :fields {:new-field {:uuid field-uuid :type :text}})
              changes (sp/initialize storage schema2)]
          ;; Verify field was renamed
          (is (= [{:entity :record :old-field :old-field :new-field :new-field}]
                 (:renamed (:fields changes))))

          ;; Verify data was migrated - old-field renamed to new-field
          (let [migrated-data (get-in @state-atom [:data :record row-id])]
            (is (contains? migrated-data :new-field))
            (is (not (contains? migrated-data :old-field)))
            (is (= "value1" (:new-field migrated-data)))))))))


(deftest data-preserved-without-renames-test
  (testing "data is preserved when no renames occur (empty renames branch)"
    (let [storage (mem/create-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000400"
          field-uuid #uuid "00000000-0000-0000-0000-000000000401"
          field2-uuid #uuid "00000000-0000-0000-0000-000000000402"
          schema1 (make-schema :entity-name :record
                               :entity-uuid entity-uuid
                               :fields {:name {:uuid field-uuid :type :text}})]
      ;; Initialize with first schema
      (sp/initialize storage schema1)

      ;; Add some data
      (let [state-atom (:state storage)
            row-id (random-uuid)]
        (swap! state-atom assoc-in [:data :record row-id] {:name "test-value"})

        ;; Add a NEW field (no rename, just addition) - this tests the empty renames branch
        (let [schema2 (make-schema :entity-name :record
                                   :entity-uuid entity-uuid
                                   :fields {:name {:uuid field-uuid :type :text}
                                            :email {:uuid field2-uuid :type :text}})
              changes (sp/initialize storage schema2)]
          ;; New field was added
          (is (= [{:entity :record :field :email}] (:created (:fields changes))))
          ;; No renames
          (is (= [] (:renamed (:fields changes))))

          ;; Data should be preserved unchanged
          (let [preserved-data (get-in @state-atom [:data :record row-id])]
            (is (= {:name "test-value"} preserved-data))))))))


(deftest new-entity-with-existing-data-test
  (testing "adding new entity doesn't affect existing entity data"
    (let [storage (mem/create-storage)
          entity1-uuid #uuid "00000000-0000-0000-0000-000000000500"
          entity2-uuid #uuid "00000000-0000-0000-0000-000000000510"
          field1-uuid #uuid "00000000-0000-0000-0000-000000000501"
          field2-uuid #uuid "00000000-0000-0000-0000-000000000511"
          schema1 (-> (mds/create-builder)
                      (ds/add-entity :user entity1-uuid
                                     {:name {:uuid field1-uuid :type :text}})
                      ds/build)]
      ;; Initialize with first schema
      (sp/initialize storage schema1)

      ;; Add data to existing entity
      (let [state-atom (:state storage)
            row-id (random-uuid)]
        (swap! state-atom assoc-in [:data :user row-id] {:name "existing"})

        ;; Add a completely NEW entity
        (let [schema2 (-> (mds/create-builder)
                          (ds/add-entity :user entity1-uuid
                                         {:name {:uuid field1-uuid :type :text}})
                          (ds/add-entity :post entity2-uuid
                                         {:title {:uuid field2-uuid :type :text}})
                          ds/build)
              changes (sp/initialize storage schema2)]
          ;; New entity was created
          (is (= [:post] (:created (:entities changes))))

          ;; Existing data preserved
          (let [preserved-data (get-in @state-atom [:data :user row-id])]
            (is (= {:name "existing"} preserved-data))))))))


(deftest data-migration-with-entity-rename-test
  (testing "data is preserved when entity is renamed"
    (let [storage (mem/create-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000300"
          field-uuid #uuid "00000000-0000-0000-0000-000000000301"
          schema1 (make-schema :entity-name :old-entity
                               :entity-uuid entity-uuid
                               :fields {:name {:uuid field-uuid :type :text}})]
      ;; Initialize with first schema
      (sp/initialize storage schema1)

      ;; Simulate having data
      (let [state-atom (:state storage)
            row-id (random-uuid)]
        (swap! state-atom assoc-in [:data :old-entity row-id] {:name "test-value"})

        ;; Rename the entity
        (let [schema2 (make-schema :entity-name :new-entity
                                   :entity-uuid entity-uuid
                                   :fields {:name {:uuid field-uuid :type :text}})
              changes (sp/initialize storage schema2)]
          ;; Verify entity was renamed
          (is (= {:old-entity :new-entity} (:renamed (:entities changes))))

          ;; Verify data was migrated to new entity name
          (let [migrated-data (get-in @state-atom [:data :new-entity row-id])]
            (is (= {:name "test-value"} migrated-data)))

          ;; Old entity data should be gone
          (is (nil? (get-in @state-atom [:data :old-entity]))))))))


;; === Multi-entity and multi-field tests for check-type-changes coverage ===

(deftest multi-entity-type-check-test
  (testing "type changes checked across multiple entities with multiple fields"
    (let [storage (mem/create-storage)
          user-uuid #uuid "00000000-0000-0000-0000-000000000600"
          post-uuid #uuid "00000000-0000-0000-0000-000000000610"
          name-uuid #uuid "00000000-0000-0000-0000-000000000601"
          email-uuid #uuid "00000000-0000-0000-0000-000000000602"
          title-uuid #uuid "00000000-0000-0000-0000-000000000611"
          content-uuid #uuid "00000000-0000-0000-0000-000000000612"
          schema1 (-> (mds/create-builder)
                      (ds/add-entity :user user-uuid
                                     {:name {:uuid name-uuid :type :text}
                                      :email {:uuid email-uuid :type :text :nullable? true}})
                      (ds/add-entity :post post-uuid
                                     {:title {:uuid title-uuid :type :text}
                                      :content {:uuid content-uuid :type :text :nullable? true}})
                      ds/build)]
      (sp/initialize storage schema1)
      ;; Re-initialize with same schema - exercises all loops with no changes
      (let [changes (sp/initialize storage schema1)]
        (is (= [] (:created (:entities changes))))
        (is (= {} (:renamed (:entities changes))))))))


(deftest multi-field-safe-type-change-test
  (testing "multiple safe type changes in same entity"
    (let [storage (mem/create-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000700"
          field1-uuid #uuid "00000000-0000-0000-0000-000000000701"
          field2-uuid #uuid "00000000-0000-0000-0000-000000000702"
          field3-uuid #uuid "00000000-0000-0000-0000-000000000703"
          schema1 (-> (mds/create-builder)
                      (ds/add-entity :record entity-uuid
                                     {:count {:uuid field1-uuid :type :int}
                                      :amount {:uuid field2-uuid :type :int}
                                      :data {:uuid field3-uuid :type :text}})
                      ds/build)]
      (sp/initialize storage schema1)
      ;; Safe widening for multiple fields
      (let [schema2 (-> (mds/create-builder)
                        (ds/add-entity :record entity-uuid
                                       {:count {:uuid field1-uuid :type :numeric}
                                        :amount {:uuid field2-uuid :type :numeric}
                                        :data {:uuid field3-uuid :type :jsonb}})
                        ds/build)
            changes (sp/initialize storage schema2)]
        (is (= [] (:created (:fields changes))))
        (is (= :numeric (:type (get (sp/current-fields storage :record) :count))))
        (is (= :numeric (:type (get (sp/current-fields storage :record) :amount))))
        (is (= :jsonb (:type (get (sp/current-fields storage :record) :data)))))))

  (testing "mixed: some fields have type changes, some don't"
    (let [storage (mem/create-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000710"
          field1-uuid #uuid "00000000-0000-0000-0000-000000000711"
          field2-uuid #uuid "00000000-0000-0000-0000-000000000712"
          schema1 (-> (mds/create-builder)
                      (ds/add-entity :record entity-uuid
                                     {:count {:uuid field1-uuid :type :int}
                                      :name {:uuid field2-uuid :type :text}})
                      ds/build)]
      (sp/initialize storage schema1)
      ;; Only count changes type, name stays same
      (let [schema2 (-> (mds/create-builder)
                        (ds/add-entity :record entity-uuid
                                       {:count {:uuid field1-uuid :type :numeric}
                                        :name {:uuid field2-uuid :type :text}})
                        ds/build)
            changes (sp/initialize storage schema2)]
        (is (= [] (:created (:fields changes))))
        (is (= :numeric (:type (get (sp/current-fields storage :record) :count))))
        (is (= :text (:type (get (sp/current-fields storage :record) :name))))))))


(deftest new-field-added-to-existing-entity-check-test
  (testing "new field to existing entity skips type check for new field"
    (let [storage (mem/create-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000720"
          field1-uuid #uuid "00000000-0000-0000-0000-000000000721"
          field2-uuid #uuid "00000000-0000-0000-0000-000000000722"
          schema1 (-> (mds/create-builder)
                      (ds/add-entity :record entity-uuid
                                     {:name {:uuid field1-uuid :type :text}})
                      ds/build)]
      (sp/initialize storage schema1)
      ;; Add new field - old-field-info is nil for new field
      (let [schema2 (-> (mds/create-builder)
                        (ds/add-entity :record entity-uuid
                                       {:name {:uuid field1-uuid :type :text}
                                        :count {:uuid field2-uuid :type :int}})
                        ds/build)
            changes (sp/initialize storage schema2)]
        (is (= [{:entity :record :field :count}] (:created (:fields changes))))
        (is (= :text (:type (get (sp/current-fields storage :record) :name))))
        (is (= :int (:type (get (sp/current-fields storage :record) :count))))))))


(deftest nullable-changes-across-multiple-fields-test
  (testing "nullable changes checked for multiple fields"
    (let [storage (mem/create-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000730"
          field1-uuid #uuid "00000000-0000-0000-0000-000000000731"
          field2-uuid #uuid "00000000-0000-0000-0000-000000000732"
          field3-uuid #uuid "00000000-0000-0000-0000-000000000733"
          schema1 (-> (mds/create-builder)
                      (ds/add-entity :record entity-uuid
                                     {:required1 {:uuid field1-uuid :type :text :nullable? false}
                                      :required2 {:uuid field2-uuid :type :text :nullable? false}
                                      :optional {:uuid field3-uuid :type :text :nullable? true}})
                      ds/build)]
      (sp/initialize storage schema1)
      ;; Make required fields nullable (safe)
      (let [schema2 (-> (mds/create-builder)
                        (ds/add-entity :record entity-uuid
                                       {:required1 {:uuid field1-uuid :type :text :nullable? true}
                                        :required2 {:uuid field2-uuid :type :text :nullable? true}
                                        :optional {:uuid field3-uuid :type :text :nullable? true}})
                        ds/build)
            changes (sp/initialize storage schema2)]
        (is (= [] (:created (:fields changes))))
        (is (true? (:nullable? (get (sp/current-fields storage :record) :required1))))
        (is (true? (:nullable? (get (sp/current-fields storage :record) :required2))))
        (is (true? (:nullable? (get (sp/current-fields storage :record) :optional))))))))


;; === CRUD tests ===

(deftest crud-basic-test
  (testing "create-entity creates record with generated id"
    (let [storage (mem/create-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (let [record (sp/create-entity storage :user {:name "Alice"})]
        (is (uuid? (:id record)))
        (is (= "Alice" (:name record))))))

  (testing "create-entity uses provided id"
    (let [storage (mem/create-storage)
          schema (make-schema)
          id (random-uuid)]
      (sp/initialize storage schema)
      (let [record (sp/create-entity storage :user {:id id :name "Bob"})]
        (is (= id (:id record)))
        (is (= "Bob" (:name record))))))

  (testing "read-entity returns record by id"
    (let [storage (mem/create-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (let [created (sp/create-entity storage :user {:name "Charlie"})
            read-result (sp/read-entity storage :user (:id created))]
        (is (= created read-result)))))

  (testing "read-entity returns nil for unknown id"
    (let [storage (mem/create-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (is (nil? (sp/read-entity storage :user (random-uuid))))))

  (testing "update-entity updates record"
    (let [storage (mem/create-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (let [created (sp/create-entity storage :user {:name "Dave"})
            updated (sp/update-entity storage :user (:id created) {:name "David"})]
        (is (= "David" (:name updated)))
        (is (= (:id created) (:id updated))))))

  (testing "update-entity throws for unknown id"
    (let [storage (mem/create-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Entity not found"
            (sp/update-entity storage :user (random-uuid) {:name "Nobody"})))))

  (testing "delete-entity removes record"
    (let [storage (mem/create-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (let [created (sp/create-entity storage :user {:name "Eve"})]
        (is (true? (sp/delete-entity storage :user (:id created))))
        (is (nil? (sp/read-entity storage :user (:id created)))))))

  (testing "delete-entity returns false for unknown id"
    (let [storage (mem/create-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (is (false? (sp/delete-entity storage :user (random-uuid)))))))


(deftest query-entities-test
  (testing "query-entities returns all records when where is empty"
    (let [storage (mem/create-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (sp/create-entity storage :user {:name "Alice"})
      (sp/create-entity storage :user {:name "Bob"})
      (let [results (sp/query-entities storage :user {})]
        (is (= 2 (count results)))
        (is (= #{"Alice" "Bob"} (set (map :name results)))))))

  (testing "query-entities filters by field"
    (let [storage (mem/create-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (sp/create-entity storage :user {:name "Alice"})
      (sp/create-entity storage :user {:name "Bob"})
      (let [results (sp/query-entities storage :user {:name "Alice"})]
        (is (= 1 (count results)))
        (is (= "Alice" (:name (first results)))))))

  (testing "query-entities returns empty seq when no match"
    (let [storage (mem/create-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (sp/create-entity storage :user {:name "Alice"})
      (let [results (sp/query-entities storage :user {:name "Nobody"})]
        (is (empty? results))))))


;; === GraphConstraints tests ===

(deftest validate-parent-same-schema-test
  (testing "passes when parent has same fn-schema-id"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn-schema #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "20000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}
                                                 :parent-fn-id {:uuid #uuid "20000000-0000-0000-0000-000000000004"
                                                                :type :ref :ref-entity :fn :nullable? true}})
                                 ds/build))
      (let [schema-id (random-uuid)
            parent (sp/create-entity storage :fn {:name "parent" :fn-schema-id schema-id :parent-fn-id nil})
            child (sp/create-entity storage :fn {:name "child" :fn-schema-id schema-id :parent-fn-id (:id parent)})]
        ;; Should not throw
        (sp/validate-parent-same-schema! storage (:id child) (:id parent)))))

  (testing "throws when parent has different fn-schema-id"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn-schema #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "20000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}
                                                 :parent-fn-id {:uuid #uuid "20000000-0000-0000-0000-000000000004"
                                                                :type :ref :ref-entity :fn :nullable? true}})
                                 ds/build))
      (let [schema1-id (random-uuid)
            schema2-id (random-uuid)
            parent (sp/create-entity storage :fn {:name "parent" :fn-schema-id schema1-id :parent-fn-id nil})
            child (sp/create-entity storage :fn {:name "child" :fn-schema-id schema2-id :parent-fn-id (:id parent)})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"different fn-schema-id"
              (sp/validate-parent-same-schema! storage (:id child) (:id parent))))))))


(deftest validate-no-inheritance-cycle-test
  (testing "passes when no cycle"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "20000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn :nullable? true}
                                                 :parent-fn-id {:uuid #uuid "20000000-0000-0000-0000-000000000004"
                                                                :type :ref :ref-entity :fn :nullable? true}})
                                 ds/build))
      (let [a (sp/create-entity storage :fn {:name "a" :parent-fn-id nil})
            b (sp/create-entity storage :fn {:name "b" :parent-fn-id (:id a)})]
        ;; c -> b -> a: no cycle
        (sp/validate-no-inheritance-cycle! storage (random-uuid) (:id b)))))

  (testing "throws when self-reference"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :parent-fn-id {:uuid #uuid "20000000-0000-0000-0000-000000000004"
                                                                :type :ref :ref-entity :fn :nullable? true}})
                                 ds/build))
      (let [a (sp/create-entity storage :fn {:name "a" :parent-fn-id nil})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot set self as parent"
              (sp/validate-no-inheritance-cycle! storage (:id a) (:id a)))))))

  (testing "throws when cycle detected"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :parent-fn-id {:uuid #uuid "20000000-0000-0000-0000-000000000004"
                                                                :type :ref :ref-entity :fn :nullable? true}})
                                 ds/build))
      (let [a (sp/create-entity storage :fn {:name "a" :parent-fn-id nil})
            _ (sp/update-entity storage :fn (:id a) {:parent-fn-id nil})
            b (sp/create-entity storage :fn {:name "b" :parent-fn-id (:id a)})
            c (sp/create-entity storage :fn {:name "c" :parent-fn-id (:id b)})]
        ;; Try to make a -> c, which would create c -> b -> a -> c cycle
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"inheritance cycle"
              (sp/validate-no-inheritance-cycle! storage (:id a) (:id c))))))))


(deftest validate-no-arg-override-test
  (testing "passes when arg-schema not in parent chain"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn-schema #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :arg-schema #uuid "11000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "11000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "11000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}})
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "20000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}
                                                 :parent-fn-id {:uuid #uuid "20000000-0000-0000-0000-000000000004"
                                                                :type :ref :ref-entity :fn :nullable? true}})
                                 (ds/add-entity :arg-value #uuid "30000000-0000-0000-0000-000000000001"
                                                {:owner-fn-id {:uuid #uuid "30000000-0000-0000-0000-000000000002"
                                                               :type :ref :ref-entity :fn}
                                                 :arg-schema-id {:uuid #uuid "30000000-0000-0000-0000-000000000003"
                                                                 :type :ref :ref-entity :arg-schema}
                                                 :value {:uuid #uuid "30000000-0000-0000-0000-000000000004"
                                                         :type :int}})
                                 ds/build))
      (let [schema-id (random-uuid)
            arg-schema-1 (sp/create-entity storage :arg-schema {:name "x" :fn-schema-id schema-id})
            arg-schema-2 (sp/create-entity storage :arg-schema {:name "y" :fn-schema-id schema-id})
            parent-fn (sp/create-entity storage :fn {:name "parent" :fn-schema-id schema-id})
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id parent-fn)
                                                    :arg-schema-id (:id arg-schema-1)
                                                    :value 42})
            child-fn (sp/create-entity storage :fn {:name "child" :fn-schema-id schema-id
                                                    :parent-fn-id (:id parent-fn)})]
        ;; arg-schema-2 is not in parent chain, should pass
        (sp/validate-no-arg-override! storage (:id child-fn) (:id arg-schema-2)))))

  (testing "throws when arg-schema already in parent chain"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn-schema #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :arg-schema #uuid "11000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "11000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "11000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}})
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "20000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}
                                                 :parent-fn-id {:uuid #uuid "20000000-0000-0000-0000-000000000004"
                                                                :type :ref :ref-entity :fn :nullable? true}})
                                 (ds/add-entity :arg-value #uuid "30000000-0000-0000-0000-000000000001"
                                                {:owner-fn-id {:uuid #uuid "30000000-0000-0000-0000-000000000002"
                                                               :type :ref :ref-entity :fn}
                                                 :arg-schema-id {:uuid #uuid "30000000-0000-0000-0000-000000000003"
                                                                 :type :ref :ref-entity :arg-schema}
                                                 :value {:uuid #uuid "30000000-0000-0000-0000-000000000004"
                                                         :type :int}})
                                 ds/build))
      (let [schema-id (random-uuid)
            arg-schema (sp/create-entity storage :arg-schema {:name "x" :fn-schema-id schema-id})
            parent-fn (sp/create-entity storage :fn {:name "parent" :fn-schema-id schema-id})
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id parent-fn)
                                                    :arg-schema-id (:id arg-schema)
                                                    :value 42})
            child-fn (sp/create-entity storage :fn {:name "child" :fn-schema-id schema-id
                                                    :parent-fn-id (:id parent-fn)})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"already defined in parent"
              (sp/validate-no-arg-override! storage (:id child-fn) (:id arg-schema))))))))


(deftest validate-arg-schema-belongs-to-fn-test
  (testing "passes when arg-schema belongs to fn's schema"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn-schema #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :arg-schema #uuid "11000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "11000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "11000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}})
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "20000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}})
                                 ds/build))
      (let [schema-id (random-uuid)
            arg-schema (sp/create-entity storage :arg-schema {:name "x" :fn-schema-id schema-id})
            fn-rec (sp/create-entity storage :fn {:name "my-fn" :fn-schema-id schema-id})]
        ;; Same schema-id, should pass
        (sp/validate-arg-schema-belongs-to-fn! storage (:id fn-rec) (:id arg-schema)))))

  (testing "throws when arg-schema belongs to different schema"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn-schema #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :arg-schema #uuid "11000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "11000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "11000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}})
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :fn-schema-id {:uuid #uuid "20000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn-schema}})
                                 ds/build))
      (let [schema1-id (random-uuid)
            schema2-id (random-uuid)
            arg-schema (sp/create-entity storage :arg-schema {:name "x" :fn-schema-id schema1-id})
            fn-rec (sp/create-entity storage :fn {:name "my-fn" :fn-schema-id schema2-id})]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not belong to fn's schema"
              (sp/validate-arg-schema-belongs-to-fn! storage (:id fn-rec) (:id arg-schema))))))))


(deftest validate-no-dependency-cycle-test
  (testing "passes when no dependency cycle"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :arg-value #uuid "30000000-0000-0000-0000-000000000001"
                                                {:owner-fn-id {:uuid #uuid "30000000-0000-0000-0000-000000000002"
                                                               :type :ref :ref-entity :fn}
                                                 :arg-schema-id {:uuid #uuid "30000000-0000-0000-0000-000000000003"
                                                                 :type :uuid}
                                                 :value {:uuid #uuid "30000000-0000-0000-0000-000000000004"
                                                         :type :uuid}})
                                 ds/build))
      (let [fn-a (sp/create-entity storage :fn {:name "a"})
            fn-b (sp/create-entity storage :fn {:name "b"})]
        ;; a references b, no cycle
        (sp/validate-no-dependency-cycle! storage (:id fn-a) (:id fn-b)))))

  (testing "throws when self-reference creates cycle"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :arg-value #uuid "30000000-0000-0000-0000-000000000001"
                                                {:owner-fn-id {:uuid #uuid "30000000-0000-0000-0000-000000000002"
                                                               :type :ref :ref-entity :fn}
                                                 :arg-schema-id {:uuid #uuid "30000000-0000-0000-0000-000000000003"
                                                                 :type :uuid}
                                                 :value {:uuid #uuid "30000000-0000-0000-0000-000000000004"
                                                         :type :uuid}})
                                 ds/build))
      (let [fn-a (sp/create-entity storage :fn {:name "a"})
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-a)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-a)})]
        ;; a already references itself through arg-value
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dependency cycle"
              (sp/validate-no-dependency-cycle! storage (:id fn-a) (:id fn-a)))))))

  (testing "throws when indirect cycle detected"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}})
                                 (ds/add-entity :arg-value #uuid "30000000-0000-0000-0000-000000000001"
                                                {:owner-fn-id {:uuid #uuid "30000000-0000-0000-0000-000000000002"
                                                               :type :ref :ref-entity :fn}
                                                 :arg-schema-id {:uuid #uuid "30000000-0000-0000-0000-000000000003"
                                                                 :type :uuid}
                                                 :value {:uuid #uuid "30000000-0000-0000-0000-000000000004"
                                                         :type :uuid}})
                                 ds/build))
      (let [fn-a (sp/create-entity storage :fn {:name "a"})
            fn-b (sp/create-entity storage :fn {:name "b"})
            fn-c (sp/create-entity storage :fn {:name "c"})
            ;; b -> c
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-b)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-c)})
            ;; c -> a
            _ (sp/create-entity storage :arg-value {:owner-fn-id (:id fn-c)
                                                    :arg-schema-id (random-uuid)
                                                    :value (:id fn-a)})]
        ;; Try to add a -> b, which would create a -> b -> c -> a cycle
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dependency cycle"
              (sp/validate-no-dependency-cycle! storage (:id fn-a) (:id fn-b))))))))


(deftest migration-with-data-test
  (testing "data is preserved during entity rename"
    (let [storage (mem/create-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema-v1 (-> (mds/create-builder)
                        (ds/add-entity :user entity-uuid
                                       {:name {:uuid field-uuid :type :text}})
                        ds/build)
          schema-v2 (-> (mds/create-builder)
                        (ds/add-entity :person entity-uuid  ; renamed entity
                                       {:name {:uuid field-uuid :type :text}})
                        ds/build)]
      (sp/initialize storage schema-v1)
      (sp/create-entity storage :user {:name "Alice"})
      (sp/create-entity storage :user {:name "Bob"})

      ;; Migrate to schema-v2
      (sp/initialize storage schema-v2)

      ;; Data should be accessible under new entity name
      (let [results (sp/query-entities storage :person {})]
        (is (= 2 (count results)))
        (is (= #{"Alice" "Bob"} (set (map :name results)))))))

  (testing "data is preserved during field rename"
    (let [storage (mem/create-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema-v1 (-> (mds/create-builder)
                        (ds/add-entity :user entity-uuid
                                       {:name {:uuid field-uuid :type :text}})
                        ds/build)
          schema-v2 (-> (mds/create-builder)
                        (ds/add-entity :user entity-uuid
                                       {:full-name {:uuid field-uuid :type :text}})  ; renamed field
                        ds/build)]
      (sp/initialize storage schema-v1)
      (let [alice (sp/create-entity storage :user {:name "Alice"})
            bob (sp/create-entity storage :user {:name "Bob"})]

        ;; Migrate to schema-v2
        (sp/initialize storage schema-v2)

        ;; Data should be accessible under new field name
        (let [alice-new (sp/read-entity storage :user (:id alice))
              bob-new (sp/read-entity storage :user (:id bob))]
          (is (= "Alice" (:full-name alice-new)))
          (is (= "Bob" (:full-name bob-new)))
          ;; Old field name should not exist
          (is (nil? (:name alice-new)))))))

  (testing "data is preserved during both entity and field rename"
    (let [storage (mem/create-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema-v1 (-> (mds/create-builder)
                        (ds/add-entity :user entity-uuid
                                       {:name {:uuid field-uuid :type :text}})
                        ds/build)
          schema-v2 (-> (mds/create-builder)
                        (ds/add-entity :person entity-uuid      ; renamed entity
                                       {:full-name {:uuid field-uuid :type :text}})  ; renamed field
                        ds/build)]
      (sp/initialize storage schema-v1)
      (let [alice (sp/create-entity storage :user {:name "Alice"})]

        ;; Migrate to schema-v2
        (sp/initialize storage schema-v2)

        ;; Data should be accessible under new entity and field names
        (let [alice-new (sp/read-entity storage :person (:id alice))]
          (is (= "Alice" (:full-name alice-new))))))))


(deftest graphconstraints-edge-cases-test
  (testing "validate-parent-same-schema! passes when parent-fn-id is nil"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}})
                                 ds/build))
      ;; Should not throw for nil parent, returns nil
      (is (nil? (sp/validate-parent-same-schema! storage (random-uuid) nil)))))

  (testing "validate-no-inheritance-cycle! passes when parent-fn-id is nil"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}})
                                 ds/build))
      ;; Should not throw for nil parent, returns nil
      (is (nil? (sp/validate-no-inheritance-cycle! storage (random-uuid) nil)))))

  (testing "validate-no-dependency-cycle! passes when value-fn-id is nil"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}})
                                 ds/build))
      ;; Should not throw for nil value, returns nil
      (is (nil? (sp/validate-no-dependency-cycle! storage (random-uuid) nil)))))

  (testing "validate-no-arg-override! passes when no parent chain"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :fn #uuid "20000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "20000000-0000-0000-0000-000000000002" :type :text}
                                                 :parent-fn-id {:uuid #uuid "20000000-0000-0000-0000-000000000003"
                                                                :type :ref :ref-entity :fn :nullable? true}})
                                 ds/build))
      (let [fn-rec (sp/create-entity storage :fn {:name "orphan" :parent-fn-id nil})]
        ;; Should not throw when fn has no parent, returns nil
        (is (nil? (sp/validate-no-arg-override! storage (:id fn-rec) (random-uuid))))))))


(deftest required-field-validation-test
  (testing "create-entity throws when required field is missing"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :user #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002"
                                                        :type :text}
                                                 :email {:uuid #uuid "10000000-0000-0000-0000-000000000003"
                                                         :type :text}})
                                 ds/build))
      ;; Missing :email field
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required field 'email' is missing or nil"
            (sp/create-entity storage :user {:name "Alice"})))))

  (testing "create-entity throws when required field is nil"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :user #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002"
                                                        :type :text}})
                                 ds/build))
      ;; :name is nil
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required field 'name' is missing or nil"
            (sp/create-entity storage :user {:name nil})))))

  (testing "create-entity allows nil for nullable field"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :user #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002"
                                                        :type :text}
                                                 :bio {:uuid #uuid "10000000-0000-0000-0000-000000000003"
                                                       :type :text :nullable? true}})
                                 ds/build))
      ;; :bio is nullable, so nil is allowed
      (let [user (sp/create-entity storage :user {:name "Alice" :bio nil})]
        (is (= "Alice" (:name user)))
        (is (nil? (:bio user))))))

  (testing "create-entity allows missing nullable field"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :user #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002"
                                                        :type :text}
                                                 :bio {:uuid #uuid "10000000-0000-0000-0000-000000000003"
                                                       :type :text :nullable? true}})
                                 ds/build))
      ;; :bio is not provided at all
      (let [user (sp/create-entity storage :user {:name "Alice"})]
        (is (= "Alice" (:name user))))))

  (testing "update-entity throws when setting required field to nil"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :user #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002"
                                                        :type :text}})
                                 ds/build))
      (let [user (sp/create-entity storage :user {:name "Alice"})]
        ;; Try to set :name to nil
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required field 'name' is missing or nil"
              (sp/update-entity storage :user (:id user) {:name nil}))))))

  (testing "update-entity allows setting nullable field to nil"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (-> (mds/create-builder)
                                 (ds/add-entity :user #uuid "10000000-0000-0000-0000-000000000001"
                                                {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002"
                                                        :type :text}
                                                 :bio {:uuid #uuid "10000000-0000-0000-0000-000000000003"
                                                       :type :text :nullable? true}})
                                 ds/build))
      (let [user (sp/create-entity storage :user {:name "Alice" :bio "Developer"})
            ;; Set :bio to nil
            updated (sp/update-entity storage :user (:id user) {:bio nil})]
        (is (= "Alice" (:name updated)))
        (is (nil? (:bio updated)))))))


;; === ExecutionGraph tests ===

(defn- make-graph-schema
  "Creates schema with fn-schema, arg-schema, fn, and arg-value entities."
  []
  (-> (mds/create-builder)
      (ds/add-enum :value-kind #uuid "b79e6e8b-8aff-4188-862b-d8a85ef4fcdf"
                   [{:uuid #uuid "c703ffd9-6401-4c49-9ca3-a280f6aac8ba" :value :null}
                    {:uuid #uuid "cf26384f-d093-461d-9268-b42b8fd6eae6" :value :text}
                    {:uuid #uuid "154d3c4f-8d11-4592-9e24-5c40176cc5a7" :value :int}])
      (ds/add-entity :fn-schema #uuid "dc2df695-6167-4add-9e75-022213c96537"
                     {:name {:uuid #uuid "abe8475e-9130-4647-a2bf-be0cb07099b7" :type :text}
                      :returned-type {:uuid #uuid "5ea6c13d-553c-4d85-8511-38ae88f7f9e5"
                                      :type :enum :enum-name :value-kind}})
      (ds/add-entity :arg-schema #uuid "946c1f9c-30ce-4fab-98ed-dd9a26f6676b"
                     {:fn-schema-id {:uuid #uuid "c100ed37-f3d8-4a93-becc-17ae2b91f64a"
                                     :type :ref :ref-entity :fn-schema}
                      :name {:uuid #uuid "e68c993e-7840-4541-b55f-cf4b08ba3de7" :type :text}
                      :type {:uuid #uuid "be65f37b-4758-49da-9091-37dee0e28ad1"
                             :type :enum :enum-name :value-kind}
                      :required {:uuid #uuid "a1d4e8c2-5f67-4b3a-9c12-8e0f7d6b5a4c" :type :bool}})
      (ds/add-entity :fn #uuid "986e8a2a-39ba-41ae-8449-d06c31515486"
                     {:name {:uuid #uuid "af336498-6d1e-4879-b2a5-b0d6c1994d12" :type :text}
                      :fn-schema-id {:uuid #uuid "3a685253-07f7-4469-be8b-1a585ba3e7d4"
                                     :type :ref :ref-entity :fn-schema}
                      :parent-fn-id {:uuid #uuid "7c8e2f4a-9b31-4d56-a8e7-3f2c1b5d9a0e"
                                     :type :ref :ref-entity :fn :nullable? true}})
      (ds/add-entity :arg-value #uuid "afb02fb7-0174-496b-9b21-a61063de0c04"
                     {:owner-fn-id {:uuid #uuid "d9331598-36b3-4238-83f8-16558d8b3a7e"
                                    :type :ref :ref-entity :fn}
                      :arg-schema-id {:uuid #uuid "834336b1-b55c-4557-b580-a62799deb729"
                                      :type :ref :ref-entity :arg-schema}
                      :value {:uuid #uuid "b6780ba3-d050-4162-aba8-5f68ac17bcb8" :type :jsonb}})
      ds/build))


(deftest resolve-execution-graph-simple-test
  (testing "resolves simple function with no dependencies"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      ;; Create fn-schema
      (let [fn-schema (sp/create-entity storage :fn-schema
                                        {:name "add" :returned-type :int})
            ;; Create arg-schemas
            arg-a (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id fn-schema)
                                     :name "a" :type :int :required true})
            arg-b (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id fn-schema)
                                     :name "b" :type :int :required true})
            ;; Create fn instance
            fn-add (sp/create-entity storage :fn
                                     {:name "add-1-2"
                                      :fn-schema-id (:id fn-schema)
                                      :parent-fn-id nil})
            ;; Create arg-values
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id fn-add)
                                 :arg-schema-id (:id arg-a)
                                 :value 1})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id fn-add)
                                 :arg-schema-id (:id arg-b)
                                 :value 2})
            ;; Resolve execution graph
            graph (sp/resolve-execution-graph storage (:id fn-add))]
        ;; Check structure
        (is (contains? (:fns graph) (:id fn-add)))
        (is (contains? (:fn-schemas graph) (:id fn-schema)))
        (is (contains? (:arg-schemas graph) (:id arg-a)))
        (is (contains? (:arg-schemas graph) (:id arg-b)))
        (is (contains? (:resolved-args graph) (:id fn-add)))
        ;; Check resolved args
        (let [args (get (:resolved-args graph) (:id fn-add))]
          (is (= 1 (:value (get args (:id arg-a)))))
          (is (= 2 (:value (get args (:id arg-b))))))))))


(deftest resolve-execution-graph-with-parent-test
  (testing "resolves function with parent chain - child overrides parent"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [fn-schema (sp/create-entity storage :fn-schema
                                        {:name "greet" :returned-type :text})
            arg-name (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id fn-schema)
                                        :name "name" :type :text :required true})
            arg-greeting (sp/create-entity storage :arg-schema
                                           {:fn-schema-id (:id fn-schema)
                                            :name "greeting" :type :text :required true})
            ;; Parent fn with greeting="Hello"
            parent-fn (sp/create-entity storage :fn
                                        {:name "greet-hello"
                                         :fn-schema-id (:id fn-schema)
                                         :parent-fn-id nil})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id parent-fn)
                                 :arg-schema-id (:id arg-greeting)
                                 :value "Hello"})
            ;; Child fn with name="World" - inherits greeting from parent
            child-fn (sp/create-entity storage :fn
                                       {:name "greet-hello-world"
                                        :fn-schema-id (:id fn-schema)
                                        :parent-fn-id (:id parent-fn)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id child-fn)
                                 :arg-schema-id (:id arg-name)
                                 :value "World"})
            graph (sp/resolve-execution-graph storage (:id child-fn))]
        ;; Both fns should be in graph
        (is (contains? (:fns graph) (:id child-fn)))
        ;; Resolved args should have both - name from child, greeting from parent
        (let [args (get (:resolved-args graph) (:id child-fn))]
          (is (= "World" (:value (get args (:id arg-name)))))
          (is (= "Hello" (:value (get args (:id arg-greeting))))))))))


(deftest resolve-execution-graph-with-fn-refs-test
  (testing "resolves function with references to other functions"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [;; const-int schema
            const-schema (sp/create-entity storage :fn-schema
                                           {:name "const-int" :returned-type :int})
            const-arg (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id const-schema)
                                         :name "value" :type :int :required true})
            ;; add schema
            add-schema (sp/create-entity storage :fn-schema
                                         {:name "add" :returned-type :int})
            add-arg-a (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "a" :type :int :required true})
            add-arg-b (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "b" :type :int :required true})
            ;; const-3 fn
            const-3 (sp/create-entity storage :fn
                                      {:name "const-3"
                                       :fn-schema-id (:id const-schema)
                                       :parent-fn-id nil})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id const-3)
                                 :arg-schema-id (:id const-arg)
                                 :value 3})
            ;; const-5 fn
            const-5 (sp/create-entity storage :fn
                                      {:name "const-5"
                                       :fn-schema-id (:id const-schema)
                                       :parent-fn-id nil})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id const-5)
                                 :arg-schema-id (:id const-arg)
                                 :value 5})
            ;; add-3-5 fn referencing const-3 and const-5
            add-3-5 (sp/create-entity storage :fn
                                      {:name "add-3-5"
                                       :fn-schema-id (:id add-schema)
                                       :parent-fn-id nil})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-3-5)
                                 :arg-schema-id (:id add-arg-a)
                                 :value (:id const-3)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-3-5)
                                 :arg-schema-id (:id add-arg-b)
                                 :value (:id const-5)})
            graph (sp/resolve-execution-graph storage (:id add-3-5))]
        ;; All 3 fns should be in graph
        (is (= 3 (count (:fns graph))))
        (is (contains? (:fns graph) (:id add-3-5)))
        (is (contains? (:fns graph) (:id const-3)))
        (is (contains? (:fns graph) (:id const-5)))
        ;; Both schemas
        (is (= 2 (count (:fn-schemas graph))))
        ;; All arg-schemas
        (is (= 3 (count (:arg-schemas graph))))))))


(deftest resolve-execution-graph-not-found-test
  (testing "throws when function not found"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Function not found"
            (sp/resolve-execution-graph storage (random-uuid)))))))


(deftest resolve-execution-graph-with-optional-args-test
  (testing "handles optional (non-required) arguments that are not provided"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [fn-schema (sp/create-entity storage :fn-schema
                                        {:name "greet" :returned-type :text})
            ;; Required arg
            arg-name (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id fn-schema)
                                        :name "name" :type :text :required true})
            ;; Optional arg (not required)
            arg-suffix (sp/create-entity storage :arg-schema
                                         {:fn-schema-id (:id fn-schema)
                                          :name "suffix" :type :text :required false})
            fn-rec (sp/create-entity storage :fn
                                     {:name "greet-fn"
                                      :fn-schema-id (:id fn-schema)})
            ;; Only provide required arg, not optional
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id fn-rec)
                                 :arg-schema-id (:id arg-name)
                                 :value "World"})
            graph (sp/resolve-execution-graph storage (:id fn-rec))]
        (is (= 1 (count (:fns graph))))
        (let [args (get (:resolved-args graph) (:id fn-rec))]
          ;; Required arg should be present
          (is (= "World" (:value (get args (:id arg-name)))))
          ;; Optional arg should not be present (no arg-value for it)
          (is (nil? (get args (:id arg-suffix)))))))))


(deftest resolve-execution-graph-with-non-fn-uuid-value-test
  (testing "UUID value that doesn't exist as fn is not followed as reference"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [fn-schema (sp/create-entity storage :fn-schema
                                        {:name "process" :returned-type :text})
            arg-ref (sp/create-entity storage :arg-schema
                                      {:fn-schema-id (:id fn-schema)
                                       :name "ref" :type :uuid :required true})
            fn-rec (sp/create-entity storage :fn
                                     {:name "my-process"
                                      :fn-schema-id (:id fn-schema)})
            ;; Store a random UUID that doesn't exist as a fn
            non-existent-fn-id (random-uuid)
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id fn-rec)
                                 :arg-schema-id (:id arg-ref)
                                 :value non-existent-fn-id})
            graph (sp/resolve-execution-graph storage (:id fn-rec))]
        ;; Should only have 1 fn (the non-existent UUID is not resolved)
        (is (= 1 (count (:fns graph))))
        (let [args (get (:resolved-args graph) (:id fn-rec))]
          (is (= non-existent-fn-id (:value (get args (:id arg-ref))))))))))


(deftest query-entities-with-empty-where-test
  (testing "query-entities with empty where returns all records"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :item #uuid "11111111-1111-1111-1111-111111111111"
                                    {:name {:uuid #uuid "22222222-2222-2222-2222-222222222222"
                                            :type :text}})
                     ds/build)]
      (sp/initialize storage schema)
      (sp/create-entity storage :item {:name "item1"})
      (sp/create-entity storage :item {:name "item2"})
      (sp/create-entity storage :item {:name "item3"})
      (let [all-items (sp/query-entities storage :item {})]
        (is (= 3 (count all-items)))
        (is (= #{"item1" "item2" "item3"} (set (map :name all-items))))))))


(deftest resolve-execution-graph-shared-reference-test
  (testing "handles shared fn reference (same fn referenced by multiple args)"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [;; const-int schema
            const-schema (sp/create-entity storage :fn-schema
                                           {:name "const-int" :returned-type :int})
            const-arg (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id const-schema)
                                         :name "value" :type :int :required true})
            ;; add schema - both args reference fns
            add-schema (sp/create-entity storage :fn-schema
                                         {:name "add" :returned-type :int})
            add-arg-a (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "a" :type :int :required true})
            add-arg-b (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "b" :type :int :required true})
            ;; const-5 fn - will be referenced TWICE
            const-5 (sp/create-entity storage :fn
                                      {:name "const-5"
                                       :fn-schema-id (:id const-schema)
                                       :parent-fn-id nil})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id const-5)
                                 :arg-schema-id (:id const-arg)
                                 :value 5})
            ;; add-5-5 fn referencing const-5 for BOTH args
            ;; This creates a shared reference that triggers the "already visited" branch
            add-5-5 (sp/create-entity storage :fn
                                      {:name "add-5-5"
                                       :fn-schema-id (:id add-schema)
                                       :parent-fn-id nil})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-5-5)
                                 :arg-schema-id (:id add-arg-a)
                                 :value (:id const-5)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-5-5)
                                 :arg-schema-id (:id add-arg-b)
                                 :value (:id const-5)}) ; Same fn referenced again!
            graph (sp/resolve-execution-graph storage (:id add-5-5))]
        ;; const-5 should only appear once in the graph despite being referenced twice
        (is (= 2 (count (:fns graph))))
        (is (contains? (:fns graph) (:id add-5-5)))
        (is (contains? (:fns graph) (:id const-5)))
        ;; Both args should reference const-5
        (let [args (get (:resolved-args graph) (:id add-5-5))]
          (is (= (:id const-5) (:value (get args (:id add-arg-a)))))
          (is (= (:id const-5) (:value (get args (:id add-arg-b))))))))))


(deftest resolve-execution-graph-self-reference-test
  (testing "handles fn with self-reference in arg-value (triggers 'already visited' branch)"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [;; recursive fn-schema with two args
            rec-schema (sp/create-entity storage :fn-schema
                                         {:name "recursive" :returned-type :int})
            ;; 'self' arg will reference the fn itself (for recursion)
            arg-self (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id rec-schema)
                                        :name "self" :type :fn :required true}) ; :fn type
            arg-n (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id rec-schema)
                                     :name "n" :type :int :required true})
            ;; Create fn instance that references itself
            rec-fn (sp/create-entity storage :fn
                                     {:name "factorial"
                                      :fn-schema-id (:id rec-schema)
                                      :parent-fn-id nil})
            ;; Self-reference: arg-value points to the fn itself
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id rec-fn)
                                 :arg-schema-id (:id arg-self)
                                 :value (:id rec-fn)}) ; Self-reference!
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id rec-fn)
                                 :arg-schema-id (:id arg-n)
                                 :value 5})
            graph (sp/resolve-execution-graph storage (:id rec-fn))]
        ;; Should only have 1 fn (self-reference doesn't create duplicate)
        (is (= 1 (count (:fns graph))))
        (is (contains? (:fns graph) (:id rec-fn)))
        ;; Self arg should reference the same fn
        (let [args (get (:resolved-args graph) (:id rec-fn))]
          (is (= (:id rec-fn) (:value (get args (:id arg-self)))))
          (is (= 5 (:value (get args (:id arg-n))))))))))
