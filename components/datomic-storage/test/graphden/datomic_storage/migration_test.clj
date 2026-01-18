(ns graphden.datomic-storage.migration-test
  "Tests for datomic-storage initialization, introspection, and schema migration."
  (:require
    [clojure.test :refer [deftest is testing]]
    [datomic.client.api :as d]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.datomic-storage.interface :as dat]
    [graphden.datomic-storage.introspection :as introspection]
    [graphden.datomic-storage.schema :as schema]
    [graphden.datomic-storage.test-setup :as setup]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.test-helpers :as th]))


;; === First-time initialization tests ===

(deftest first-initialization-test
  (testing "initializing empty storage creates entities"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)
          changes (sp/initialize storage schema)]
      (try
        (is (= [:user] (:created (:entities changes))))
        (is (= {} (:renamed (:entities changes))))
        (is (= #{{:entity :user :field :name}} (set (:created (:fields changes)))))
        (is (= [] (:renamed (:fields changes))))
        (finally
          (sp/close storage)))))

  (testing "initializing with enum creates enum"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :enum-name :status
                                 :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                                 :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                                :value :active}
                                               {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                                :value :inactive}])
          changes (sp/initialize storage schema)]
      (try
        (is (= [:status] (:created (:enums changes))))
        (is (= {} (:renamed (:enums changes))))
        (is (= #{{:enum :status :value :active}
                 {:enum :status :value :inactive}}
               (set (:created (:enum-values changes)))))
        (finally
          (sp/close storage)))))

  (testing "single-field unique constraint adds :db/unique"
    (let [storage (setup/create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000020"
                                    {:email {:uuid #uuid "00000000-0000-0000-0000-000000000021"
                                             :type :text}})
                     (ds/add-constraint :user {:type :unique :fields [:email]})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        (is (contains? (sp/current-entities storage) :user))
        (finally
          (sp/close storage)))))

  (testing "multi-field unique constraint is enforced at application level"
    (let [storage (setup/create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000030"
                                    {:first-name {:uuid #uuid "00000000-0000-0000-0000-000000000031"
                                                  :type :text}
                                     :last-name {:uuid #uuid "00000000-0000-0000-0000-000000000032"
                                                 :type :text}})
                     (ds/add-constraint :user {:type :unique :fields [:first-name :last-name]})
                     ds/build)]
      (try
        ;; Initialize succeeds - multi-field constraints are enforced at create/update time
        (sp/initialize storage schema)
        (is (contains? (sp/current-entities storage) :user))
        ;; Create first user
        (sp/create-entity storage :user {:id (random-uuid)
                                         :first-name "John"
                                         :last-name "Doe"})
        ;; Creating duplicate should fail
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Unique constraint violation"
              (sp/create-entity storage :user {:id (random-uuid)
                                               :first-name "John"
                                               :last-name "Doe"})))
        ;; Different combination should succeed
        (sp/create-entity storage :user {:id (random-uuid)
                                         :first-name "John"
                                         :last-name "Smith"})
        (finally
          (sp/close storage)))))

  (testing "multi-field unique constraint with missing values is skipped"
    ;; In Datomic, nullable fields are omitted rather than set to nil
    (let [storage (setup/create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000040"
                                    {:first-name {:uuid #uuid "00000000-0000-0000-0000-000000000041"
                                                  :type :text
                                                  :nullable? true}
                                     :last-name {:uuid #uuid "00000000-0000-0000-0000-000000000042"
                                                 :type :text
                                                 :nullable? true}})
                     (ds/add-constraint :user {:type :unique :fields [:first-name :last-name]})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        ;; Create with first-name missing - should allow multiple
        (sp/create-entity storage :user {:id (random-uuid)
                                         :last-name "Doe"})
        (sp/create-entity storage :user {:id (random-uuid)
                                         :last-name "Doe"})
        ;; Create with last-name missing - should allow multiple
        (sp/create-entity storage :user {:id (random-uuid)
                                         :first-name "John"})
        (sp/create-entity storage :user {:id (random-uuid)
                                         :first-name "John"})
        ;; Create with both missing - should allow multiple
        (sp/create-entity storage :user {:id (random-uuid)})
        (sp/create-entity storage :user {:id (random-uuid)})
        (is (= 6 (count (sp/query-entities storage :user {}))))
        (finally
          (sp/close storage)))))

  (testing "multi-field unique constraint violation during update"
    (let [storage (setup/create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000050"
                                    {:first-name {:uuid #uuid "00000000-0000-0000-0000-000000000051"
                                                  :type :text}
                                     :last-name {:uuid #uuid "00000000-0000-0000-0000-000000000052"
                                                 :type :text}})
                     (ds/add-constraint :user {:type :unique :fields [:first-name :last-name]})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        (let [user-1 (sp/create-entity storage :user {:id (random-uuid)
                                                      :first-name "John"
                                                      :last-name "Doe"})
              _ (sp/create-entity storage :user {:id (random-uuid)
                                                 :first-name "Jane"
                                                 :last-name "Smith"})]
          ;; Update user-1 to have same first-name and last-name as user-2 - should fail
          (is (thrown-with-msg?
                clojure.lang.ExceptionInfo
                #"Unique constraint violation"
                (sp/update-entity storage :user (:id user-1) {:first-name "Jane" :last-name "Smith"}))))
        (finally
          (sp/close storage))))))


;; === StorageIntrospection tests ===

(deftest introspection-test
  (testing "current-entities returns entity names"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (try
        (sp/initialize storage schema)
        (is (contains? (sp/current-entities storage) :user))
        (finally
          (sp/close storage)))))

  (testing "current-fields returns field definitions"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}
                                          :email {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                  :type :text
                                                  :nullable? true}})]
      (try
        (sp/initialize storage schema)
        (let [fields (sp/current-fields storage :user)]
          (is (= :text (:type (get fields :name))))
          (is (= :text (:type (get fields :email)))))
        (finally
          (sp/close storage)))))

  (testing "current-fields returns nil for unknown entity"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (try
        (sp/initialize storage schema)
        (is (nil? (sp/current-fields storage :unknown)))
        (finally
          (sp/close storage)))))

  (testing "current-enums returns enum names"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :enum-name :status
                                 :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                                 :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                                :value :active}])]
      (try
        (sp/initialize storage schema)
        (is (contains? (sp/current-enums storage) :status))
        (finally
          (sp/close storage)))))

  (testing "current-enum-values returns values"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :enum-name :status
                                 :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                                 :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                                :value :active}
                                               {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                                :value :inactive}])]
      (try
        (sp/initialize storage schema)
        (is (= #{:active :inactive} (sp/current-enum-values storage :status)))
        (finally
          (sp/close storage)))))

  (testing "current-enum-values returns nil for unknown enum"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (try
        (sp/initialize storage schema)
        (is (nil? (sp/current-enum-values storage :unknown)))
        (finally
          (sp/close storage)))))

  (testing "schema-metadata returns uuid mappings"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema (th/make-schema :entity-uuid entity-uuid
                                 :fields {:name {:uuid field-uuid :type :text}})]
      (try
        (sp/initialize storage schema)
        (let [metadata (sp/schema-metadata storage)]
          (is (= :user (get (:entities metadata) entity-uuid)))
          (is (= {:entity :user :field :name :type :text :nullable? false}
                 (get (:fields metadata) field-uuid))))
        (finally
          (sp/close storage))))))


;; === Re-initialization (no changes) tests ===

(deftest no-changes-test
  (testing "re-initializing with same schema reports no changes"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (try
        (sp/initialize storage schema)
        (let [changes (sp/initialize storage schema)]
          (is (= [] (:created (:entities changes))))
          (is (= {} (:renamed (:entities changes))))
          (is (= [] (:created (:fields changes))))
          (is (= [] (:renamed (:fields changes)))))
        (finally
          (sp/close storage))))))


;; === Adding new entities/fields tests ===

(deftest adding-test
  (testing "adding new entity"
    (let [storage (setup/create-test-storage)
          schema1 (th/make-schema)
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
      (try
        (is (= [:post] (:created (:entities changes))))
        (is (= #{{:entity :post :field :title}} (set (:created (:fields changes)))))
        (finally
          (sp/close storage)))))

  (testing "adding new field to existing entity"
    (let [storage (setup/create-test-storage)
          schema1 (th/make-schema)
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                  :type :text}
                                           :email {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                   :type :text}})
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:entities changes))))
        (is (= [{:entity :user :field :email}] (:created (:fields changes))))
        (finally
          (sp/close storage)))))

  (testing "adding new enum"
    (let [storage (setup/create-test-storage)
          schema1 (th/make-schema)
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :enum-name :status
                                  :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                                  :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                                 :value :active}])
          changes (sp/initialize storage schema2)]
      (try
        (is (= [:status] (:created (:enums changes))))
        (is (= [{:enum :status :value :active}] (:created (:enum-values changes))))
        (finally
          (sp/close storage)))))

  (testing "adding new enum value"
    (let [storage (setup/create-test-storage)
          schema1 (th/make-schema :enum-name :status
                                  :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                                  :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                                 :value :active}])
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :enum-name :status
                                  :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                                  :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                                 :value :active}
                                                {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                                 :value :inactive}])
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:enums changes))))
        (is (= [{:enum :status :value :inactive}] (:created (:enum-values changes))))
        (finally
          (sp/close storage))))))


;; === Renaming tests ===

(deftest renaming-test
  (testing "renaming entity (same UUID, different name) is tracked"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (th/make-schema :entity-name :user
                                  :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :entity-name :person
                                  :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:entities changes))))
        (is (= {:user :person} (:renamed (:entities changes))))
        (finally
          (sp/close storage)))))

  (testing "renaming field (same UUID, different name) is tracked"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:full-name {:uuid field-uuid :type :text}})
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:fields changes))))
        (is (= [{:entity :user :old-field :name :new-field :full-name}]
               (:renamed (:fields changes))))
        (finally
          (sp/close storage)))))

  (testing "renaming enum (same UUID, different name) is tracked"
    (let [storage (setup/create-test-storage)
          enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
          value-uuid #uuid "00000000-0000-0000-0000-000000000011"
          schema1 (th/make-schema :enum-name :status
                                  :enum-uuid enum-uuid
                                  :enum-values [{:uuid value-uuid :value :active}])
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :enum-name :state
                                  :enum-uuid enum-uuid
                                  :enum-values [{:uuid value-uuid :value :active}])
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:enums changes))))
        (is (= {:status :state} (:renamed (:enums changes))))
        (finally
          (sp/close storage))))))


;; === Destructive changes tests (should throw) ===

(deftest destructive-changes-test
  (testing "removing entity throws"
    (let [storage (setup/create-test-storage)
          schema1 (-> (mds/create-builder)
                      (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :text}})
                      (ds/add-entity :post #uuid "00000000-0000-0000-0000-000000000020"
                                     {:title {:uuid #uuid "00000000-0000-0000-0000-000000000021"
                                              :type :text}})
                      ds/build)
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change: entities removed"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage)))))

  (testing "removing field throws"
    (let [storage (setup/create-test-storage)
          schema1 (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                  :type :text}
                                           :email {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                   :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                  :type :text}})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change: fields removed"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage)))))

  (testing "removing enum throws"
    (let [storage (setup/create-test-storage)
          schema1 (th/make-schema :enum-name :status
                                  :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                                  :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                                 :value :active}])
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change: enums removed"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage)))))

  (testing "removing enum value throws"
    (let [storage (setup/create-test-storage)
          schema1 (th/make-schema :enum-name :status
                                  :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                                  :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                                 :value :active}
                                                {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                                 :value :inactive}])
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :enum-name :status
                                  :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                                  :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                                 :value :active}])]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change: enum values removed"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage))))))


;; === Close tests ===

(deftest close-test
  (testing "close is idempotent"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (is (nil? (sp/close storage)))
      (is (nil? (sp/close storage))))))


;; === Edge case tests with mocks ===

(deftest uninitialized-storage-test
  (testing "current-entities returns empty set when storage not connected"
    (let [storage (setup/create-test-storage)]
      ;; Don't initialize - just close immediately to disconnect
      (sp/close storage)
      ;; Should return empty set, not throw
      (is (= #{} (sp/current-entities storage)))))

  (testing "current-enums returns empty set when storage not connected"
    (let [storage (setup/create-test-storage)]
      (sp/close storage)
      (is (= #{} (sp/current-enums storage)))))

  (testing "constraint validation throws when storage not connected"
    (let [storage (setup/create-test-storage)
          fake-fn-id #uuid "11111111-1111-1111-1111-111111111111"
          fake-parent-fn-id #uuid "33333333-3333-3333-3333-333333333333"
          fake-arg-schema-id #uuid "22222222-2222-2222-2222-222222222222"]
      (sp/close storage)
      ;; All constraint validations should throw :storage-not-initialized
      ;; Note: Use different IDs for fn-id and parent-fn-id to avoid triggering
      ;; self-reference checks (which happen before storage connection check)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)storage not initialized"
            (sp/validate-parent-same-schema! storage fake-fn-id fake-parent-fn-id)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)storage not initialized"
            (sp/validate-no-arg-override! storage fake-fn-id fake-arg-schema-id)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)storage not initialized"
            (sp/validate-arg-schema-belongs-to-fn! storage fake-fn-id fake-arg-schema-id)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)storage not initialized"
            (sp/validate-no-inheritance-cycle! storage fake-fn-id fake-parent-fn-id)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)storage not initialized"
            (sp/validate-no-dependency-cycle! storage fake-fn-id fake-parent-fn-id)))))

  (testing "CRUD operations throw when storage not initialized"
    (let [storage (setup/create-test-storage)
          fake-id #uuid "11111111-1111-1111-1111-111111111111"]
      ;; Don't initialize, just close to ensure conn is nil
      (sp/close storage)
      ;; All CRUD operations should throw :storage-not-initialized
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/create-entity storage :user {:id fake-id :name "test"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/read-entity storage :user fake-id)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/update-entity storage :user fake-id {:name "updated"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/delete-entity storage :user fake-id)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/query-entities storage :user {:name "test"})))))

  (testing "Batch CRUD operations throw when storage not initialized"
    (let [storage (setup/create-test-storage)
          fake-id #uuid "11111111-1111-1111-1111-111111111111"]
      (sp/close storage)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/create-entities storage :user [{:id fake-id :name "test"}])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/read-entities storage :user [fake-id])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/delete-entities storage :user [fake-id])))))

  (testing "resolve-execution-graph throws when storage not initialized"
    (let [storage (setup/create-test-storage)
          fake-id #uuid "11111111-1111-1111-1111-111111111111"]
      (sp/close storage)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/resolve-execution-graph storage fake-id))))))


(deftest metadata-db-inconsistency-test
  (testing "detects when metadata says field exists but DB attribute is missing"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})]
      (try
        ;; First initialize normally
        (sp/initialize storage schema1)
        ;; Now mock read-metadata to return metadata claiming a non-existent field
        (let [fake-metadata {:entities {entity-uuid :user}
                             :fields {field-uuid {:entity :user
                                                  :field :name
                                                  :type :text
                                                  :nullable? false}
                                      ;; This field doesn't exist in DB!
                                      #uuid "00000000-0000-0000-0000-000000000099"
                                      {:entity :user
                                       :field :ghost-field
                                       :type :text
                                       :nullable? false}}
                             :enums {}
                             :enum-values {}}
              ;; Schema that references the ghost field by UUID
              schema2 (-> (mds/create-builder)
                          (ds/add-entity :user entity-uuid
                                         {:name {:uuid field-uuid :type :text}
                                          :ghost-field {:uuid #uuid "00000000-0000-0000-0000-000000000099"
                                                        :type :text}})
                          ds/build)]
          (with-redefs [introspection/read-metadata (constantly fake-metadata)]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Metadata/DB inconsistency"
                  (sp/initialize storage schema2)))))
        (finally
          (sp/close storage))))))


(deftest metadata-schema-missing-test
  (testing "metadata-schema-exists? returns false when metadata schema not installed"
    ;; This test verifies that querying for non-existent metadata returns false
    ;; We initialize with a minimal schema that doesn't include graphden.metadata attributes
    ;; then directly query to confirm metadata-schema-exists? returns false on fresh db
    (let [storage (setup/create-test-storage)
          metadata-exists-fn #'introspection/metadata-schema-exists?]
      (try
        ;; First, create the client and database without initializing schema
        ;; This gives us a fresh db without metadata schema
        (let [client (d/client {:server-type :dev-local
                                :storage-dir :mem
                                :system "graphden-test"})
              temp-db-name (str "test-fresh-" (random-uuid))]
          (try
            (d/create-database client {:db-name temp-db-name})
            (let [conn (d/connect client {:db-name temp-db-name})
                  db (d/db conn)]
              ;; Fresh database - no metadata schema exists
              ;; Function returns nil (falsy) when schema doesn't exist
              (is (not (metadata-exists-fn db))))
            (finally
              ;; Cleanup
              (d/delete-database client {:db-name temp-db-name}))))
        (finally
          (sp/close storage))))))


;; === Private function unit tests ===

(deftest single-field-unique-constraint?-test
  (let [single-field-unique-constraint? schema/single-field-unique-constraint?]
    (testing "returns true for single-field unique constraint"
      (is (true? (single-field-unique-constraint? {:type :unique :fields [:email]}))))

    (testing "returns false for multi-field unique constraint"
      (is (false? (single-field-unique-constraint? {:type :unique :fields [:first-name :last-name]}))))

    (testing "returns false for non-unique constraint type"
      (is (false? (single-field-unique-constraint? {:type :other :fields [:field]}))))))


(deftest initialize-error-handling-test
  (testing "initialize re-throws non-'already exists' exceptions from create-database"
    (let [storage (dat/create-storage {:db-name (setup/unique-db-name)})
          schema (th/make-schema)
          ;; Store original create-database
          original-create-db d/create-database
          call-count (atom 0)]
      (try
        ;; Mock create-database to throw a different error
        (with-redefs [d/create-database
                      (fn [& args]
                        (swap! call-count inc)
                        (if (= 1 @call-count)
                          ;; First call - throw a non-"already exists" error
                          (throw (ex-info "Connection refused" {:error :connection-refused}))
                          ;; Subsequent calls - use original
                          (apply original-create-db args)))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Connection refused"
                (sp/initialize storage schema))))
        (finally
          (sp/close storage))))))


(deftest current-attrs-edge-cases-test
  (testing "filters out idents without namespace"
    (let [current-attrs-fn #'introspection/current-attrs
          ;; Mock query results with some idents without namespace
          fake-results [[:db/ident :db.type/string]
                        ['no-namespace-symbol :db.type/string] ; symbol without namespace
                        [:user/name :db.type/string]]]
      (with-redefs [d/q (constantly fake-results)]
        (let [result (current-attrs-fn :fake-db)]
          ;; Should have filtered out the non-namespaced one and db/* ones
          (is (= {:user/name :db.type/string} result))))))

  (testing "filters out idents from db and fressian namespaces"
    (let [current-attrs-fn #'introspection/current-attrs
          fake-results [[:db/ident :db.type/ref]
                        [:fressian/tag :db.type/string]
                        [:graphden.metadata/uuid :db.type/uuid]
                        [:myapp/field :db.type/string]]]
      (with-redefs [d/q (constantly fake-results)]
        (let [result (current-attrs-fn :fake-db)]
          (is (= {:myapp/field :db.type/string} result)))))))


(deftest current-enum-values-db-edge-cases-test
  (testing "filters out idents without namespace"
    (let [current-enum-values-db-fn #'introspection/current-enum-values-db
          ;; Include idents without namespace - they should be filtered
          fake-results [['no-namespace] [:status.value/active] [:other/thing]]]
      (with-redefs [d/q (constantly fake-results)]
        (let [result (current-enum-values-db-fn :fake-db)]
          ;; Only :status.value/active has .value in namespace
          (is (= [:status.value/active] result)))))))


(deftest read-metadata-empty-test
  (testing "read-metadata returns nil when no metadata entities exist"
    (let [read-metadata-fn #'introspection/read-metadata
          ;; Mock all queries to return empty - but first query is metadata-schema-exists?
          ;; which checks for :graphden.metadata/uuid attribute
          query-results (atom 0)]
      (with-redefs [d/q (fn [& _]
                          (swap! query-results inc)
                          (case @query-results
                            1 [[123]] ; metadata-schema-exists? returns truthy
                            2 []      ; entities
                            3 []      ; fields
                            4 []      ; fields-enum-names
                            5 []      ; fields-ref-entities
                            6 []      ; enums
                            7 []))]   ; enum-values
        (let [result (read-metadata-fn :fake-db)]
          (is (nil? result))))))

  (testing "read-metadata returns data when entities exist but other types are empty"
    (let [read-metadata-fn #'introspection/read-metadata
          entity-uuid #uuid "11111111-1111-1111-1111-111111111111"
          query-results (atom 0)]
      (with-redefs [d/q (fn [& _]
                          (swap! query-results inc)
                          (case @query-results
                            1 [[123]]                   ; metadata-schema-exists?
                            2 [[entity-uuid :user]]     ; entities
                            3 []                        ; fields
                            4 []                        ; fields-enum-names
                            5 []                        ; fields-ref-entities
                            6 []                        ; enums
                            7 []))]                     ; enum-values
        (let [result (read-metadata-fn :fake-db)]
          (is (some? result))
          (is (= {entity-uuid :user} (:entities result)))
          (is (= {} (:fields result))))))))
