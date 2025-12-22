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
