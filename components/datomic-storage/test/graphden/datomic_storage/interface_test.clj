(ns graphden.datomic-storage.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.datomic-storage.core :as core]
    [graphden.datomic-storage.interface :as dat]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.storage-protocol.interface :as sp]))


;; === Test fixtures ===

(def ^:private test-counter (atom 0))


(defn- unique-db-name
  "Generates a unique database name for each test."
  []
  (str "test-" (swap! test-counter inc) "-" (System/currentTimeMillis)))


(defn- create-test-storage
  "Creates a test storage with a unique database."
  []
  (dat/create-storage {:db-name (unique-db-name)}))


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
    (let [storage (create-test-storage)
          schema (make-schema)
          changes (sp/initialize storage schema)]
      (try
        (is (= [:user] (:created (:entities changes))))
        (is (= {} (:renamed (:entities changes))))
        (is (= #{{:entity :user :field :name}} (set (:created (:fields changes)))))
        (is (= [] (:renamed (:fields changes))))
        (finally
          (sp/close storage)))))

  (testing "initializing with enum creates enum"
    (let [storage (create-test-storage)
          schema (make-schema :enum-name :status
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
          (sp/close storage))))))


;; === StorageIntrospection tests ===

(deftest introspection-test
  (testing "current-entities returns entity names"
    (let [storage (create-test-storage)
          schema (make-schema)]
      (try
        (sp/initialize storage schema)
        (is (contains? (sp/current-entities storage) :user))
        (finally
          (sp/close storage)))))

  (testing "current-fields returns field definitions"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
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
    (let [storage (create-test-storage)
          schema (make-schema)]
      (try
        (sp/initialize storage schema)
        (is (nil? (sp/current-fields storage :unknown)))
        (finally
          (sp/close storage)))))

  (testing "current-enums returns enum names"
    (let [storage (create-test-storage)
          schema (make-schema :enum-name :status
                              :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                              :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                             :value :active}])]
      (try
        (sp/initialize storage schema)
        (is (contains? (sp/current-enums storage) :status))
        (finally
          (sp/close storage)))))

  (testing "current-enum-values returns values"
    (let [storage (create-test-storage)
          schema (make-schema :enum-name :status
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
    (let [storage (create-test-storage)
          schema (make-schema)]
      (try
        (sp/initialize storage schema)
        (is (nil? (sp/current-enum-values storage :unknown)))
        (finally
          (sp/close storage)))))

  (testing "schema-metadata returns uuid mappings"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema (make-schema :entity-uuid entity-uuid
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
    (let [storage (create-test-storage)
          schema (make-schema)]
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
    (let [storage (create-test-storage)
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
      (try
        (is (= [:post] (:created (:entities changes))))
        (is (= #{{:entity :post :field :title}} (set (:created (:fields changes)))))
        (finally
          (sp/close storage)))))

  (testing "adding new field to existing entity"
    (let [storage (create-test-storage)
          schema1 (make-schema)
          _ (sp/initialize storage schema1)
          schema2 (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
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
    (let [storage (create-test-storage)
          schema1 (make-schema)
          _ (sp/initialize storage schema1)
          schema2 (make-schema :enum-name :status
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
    (let [storage (create-test-storage)
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
      (try
        (is (= [] (:created (:enums changes))))
        (is (= [{:enum :status :value :inactive}] (:created (:enum-values changes))))
        (finally
          (sp/close storage))))))


;; === Renaming tests ===

(deftest renaming-test
  (testing "renaming entity (same UUID, different name) is tracked"
    (let [storage (create-test-storage)
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
      (try
        (is (= [] (:created (:entities changes))))
        (is (= {:user :person} (:renamed (:entities changes))))
        (finally
          (sp/close storage)))))

  (testing "renaming field (same UUID, different name) is tracked"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :entity-uuid entity-uuid
                               :fields {:name {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :entity-uuid entity-uuid
                               :fields {:full-name {:uuid field-uuid :type :text}})
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:fields changes))))
        (is (= [{:entity :user :old-field :name :new-field :full-name}]
               (:renamed (:fields changes))))
        (finally
          (sp/close storage)))))

  (testing "renaming enum (same UUID, different name) is tracked"
    (let [storage (create-test-storage)
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
      (try
        (is (= [] (:created (:enums changes))))
        (is (= {:status :state} (:renamed (:enums changes))))
        (finally
          (sp/close storage))))))


;; === Destructive changes tests (should throw) ===

(deftest destructive-changes-test
  (testing "removing entity throws"
    (let [storage (create-test-storage)
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
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change: entities removed"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage)))))

  (testing "removing field throws"
    (let [storage (create-test-storage)
          schema1 (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                               :type :text}
                                        :email {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                               :type :text}})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change: fields removed"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage)))))

  (testing "removing enum throws"
    (let [storage (create-test-storage)
          schema1 (make-schema :enum-name :status
                               :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                               :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                              :value :active}])
          _ (sp/initialize storage schema1)
          schema2 (make-schema)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change: enums removed"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage)))))

  (testing "removing enum value throws"
    (let [storage (create-test-storage)
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
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change: enum values removed"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage))))))


;; === Close tests ===

(deftest close-test
  (testing "close is idempotent"
    (let [storage (create-test-storage)
          schema (make-schema)]
      (sp/initialize storage schema)
      (is (nil? (sp/close storage)))
      (is (nil? (sp/close storage))))))


;; === Edge case tests with mocks ===

(deftest uninitialized-storage-test
  (testing "current-entities returns empty set when storage not connected"
    (let [storage (create-test-storage)]
      ;; Don't initialize - just close immediately to disconnect
      (sp/close storage)
      ;; Should return empty set, not throw
      (is (= #{} (sp/current-entities storage)))))

  (testing "current-enums returns empty set when storage not connected"
    (let [storage (create-test-storage)]
      (sp/close storage)
      (is (= #{} (sp/current-enums storage))))))


(deftest metadata-db-inconsistency-test
  (testing "detects when metadata says field exists but DB attribute is missing"
    (let [storage (create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (make-schema :entity-uuid entity-uuid
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
          (with-redefs [core/read-metadata (constantly fake-metadata)]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Metadata/DB inconsistency"
                  (sp/initialize storage schema2)))))
        (finally
          (sp/close storage))))))


(deftest metadata-schema-missing-test
  (testing "metadata-schema-exists? returns false when metadata schema not installed"
    ;; This exercises the try/catch path in metadata-schema-exists?
    ;; On a fresh database, the metadata attributes don't exist
    (let [storage (create-test-storage)]
      (try
        ;; Don't initialize - directly check if metadata schema exists
        (let [metadata-exists-fn #'core/metadata-schema-exists?
              conn (:conn storage)]
          ;; Should return false without throwing
          (is (false? (metadata-exists-fn conn))))
        (finally
          (sp/close storage))))))
