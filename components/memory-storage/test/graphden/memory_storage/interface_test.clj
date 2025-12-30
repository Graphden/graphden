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
