(ns graphden.datomic-storage.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [datomic.client.api :as d]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.datomic-storage.core :as core]
    [graphden.datomic-storage.interface :as dat]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.storage-protocol.contract-tests :as contract]
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
          (sp/close storage)))))

  (testing "single-field unique constraint adds :db/unique"
    (let [storage (create-test-storage)
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

  (testing "multi-field unique constraint is skipped (Datomic limitation)"
    (let [storage (create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000030"
                                    {:first-name {:uuid #uuid "00000000-0000-0000-0000-000000000031"
                                                  :type :text}
                                     :last-name {:uuid #uuid "00000000-0000-0000-0000-000000000032"
                                                 :type :text}})
                     (ds/add-constraint :user {:type :unique :fields [:first-name :last-name]})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        ;; Multi-field constraints are silently skipped in Datomic
        (is (contains? (sp/current-entities storage) :user))
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
      (is (= #{} (sp/current-enums storage)))))

  (testing "constraint validation throws when storage not connected"
    (let [storage (create-test-storage)
          fake-fn-id #uuid "11111111-1111-1111-1111-111111111111"
          fake-arg-schema-id #uuid "22222222-2222-2222-2222-222222222222"]
      (sp/close storage)
      ;; All constraint validations should throw :storage-not-initialized
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/validate-parent-same-schema! storage fake-fn-id fake-fn-id)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/validate-no-arg-override! storage fake-fn-id fake-arg-schema-id)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/validate-arg-schema-belongs-to-fn! storage fake-fn-id fake-arg-schema-id)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/validate-no-inheritance-cycle! storage fake-fn-id fake-fn-id)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/validate-no-dependency-cycle! storage fake-fn-id fake-fn-id))))))


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
    ;; This test verifies that querying for non-existent metadata returns false
    ;; We initialize with a minimal schema that doesn't include graphden.metadata attributes
    ;; then directly query to confirm metadata-schema-exists? returns false on fresh db
    (let [storage (create-test-storage)
          metadata-exists-fn #'core/metadata-schema-exists?]
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
  (let [single-field-unique-constraint? #'core/single-field-unique-constraint?]
    (testing "returns true for single-field unique constraint"
      (is (true? (single-field-unique-constraint? {:type :unique :fields [:email]}))))

    (testing "returns false for multi-field unique constraint"
      (is (false? (single-field-unique-constraint? {:type :unique :fields [:first-name :last-name]}))))

    (testing "returns false for non-unique constraint type"
      (is (false? (single-field-unique-constraint? {:type :other :fields [:field]}))))))


(deftest initialize-error-handling-test
  (testing "initialize re-throws non-'already exists' exceptions from create-database"
    (let [storage (dat/create-storage {:db-name (unique-db-name)})
          schema (make-schema)
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
    (let [current-attrs-fn #'core/current-attrs
          ;; Mock query results with some idents without namespace
          fake-results [[:db/ident :db.type/string]
                        ['no-namespace-symbol :db.type/string] ; symbol without namespace
                        [:user/name :db.type/string]]]
      (with-redefs [d/q (constantly fake-results)]
        (let [result (current-attrs-fn :fake-db)]
          ;; Should have filtered out the non-namespaced one and db/* ones
          (is (= {:user/name :db.type/string} result))))))

  (testing "filters out idents from db and fressian namespaces"
    (let [current-attrs-fn #'core/current-attrs
          fake-results [[:db/ident :db.type/ref]
                        [:fressian/tag :db.type/string]
                        [:graphden.metadata/uuid :db.type/uuid]
                        [:myapp/field :db.type/string]]]
      (with-redefs [d/q (constantly fake-results)]
        (let [result (current-attrs-fn :fake-db)]
          (is (= {:myapp/field :db.type/string} result)))))))


(deftest current-enum-values-db-edge-cases-test
  (testing "filters out idents without namespace"
    (let [current-enum-values-db-fn #'core/current-enum-values-db
          ;; Include idents without namespace - they should be filtered
          fake-results [['no-namespace] [:status.value/active] [:other/thing]]]
      (with-redefs [d/q (constantly fake-results)]
        (let [result (current-enum-values-db-fn :fake-db)]
          ;; Only :status.value/active has .value in namespace
          (is (= [:status.value/active] result)))))))


(deftest read-metadata-empty-test
  (testing "read-metadata returns nil when no metadata entities exist"
    (let [read-metadata-fn #'core/read-metadata
          ;; Mock all queries to return empty - but first query is metadata-schema-exists?
          ;; which checks for :graphden.metadata/uuid attribute
          query-results (atom 0)]
      (with-redefs [d/q (fn [& _]
                          (swap! query-results inc)
                          (case @query-results
                            1 [[123]] ; metadata-schema-exists? returns truthy
                            2 []      ; entities
                            3 []      ; fields
                            4 []      ; enums
                            5 []))]   ; enum-values
        (let [result (read-metadata-fn :fake-db)]
          (is (nil? result))))))

  (testing "read-metadata returns data when entities exist but other types are empty"
    (let [read-metadata-fn #'core/read-metadata
          entity-uuid #uuid "11111111-1111-1111-1111-111111111111"
          query-results (atom 0)]
      (with-redefs [d/q (fn [& _]
                          (swap! query-results inc)
                          (case @query-results
                            1 [[123]]                   ; metadata-schema-exists?
                            2 [[entity-uuid :user]]     ; entities
                            3 []                        ; fields
                            4 []                        ; enums
                            5 []))]                     ; enum-values
        (let [result (read-metadata-fn :fake-db)]
          (is (some? result))
          (is (= {entity-uuid :user} (:entities result)))
          (is (= {} (:fields result))))))))


;; === StorageCRUD tests ===

(deftest crud-create-entity-test
  (testing "create-entity with provided id"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          id #uuid "11111111-1111-1111-1111-111111111111"
          result (sp/create-entity storage :user {:id id :name "Alice"})]
      (try
        (is (= id (:id result)))
        (is (= "Alice" (:name result)))
        (finally
          (sp/close storage)))))

  (testing "create-entity generates id if not provided"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          result (sp/create-entity storage :user {:name "Bob"})]
      (try
        (is (uuid? (:id result)))
        (is (= "Bob" (:name result)))
        (finally
          (sp/close storage))))))


(deftest crud-read-entity-test
  (testing "read-entity returns entity by id"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          id #uuid "11111111-1111-1111-1111-111111111111"
          _ (sp/create-entity storage :user {:id id :name "Alice"})
          result (sp/read-entity storage :user id)]
      (try
        (is (= id (:id result)))
        (is (= "Alice" (:name result)))
        (finally
          (sp/close storage)))))

  (testing "read-entity returns nil for non-existent id"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          result (sp/read-entity storage :user #uuid "11111111-1111-1111-1111-111111111111")]
      (try
        (is (nil? result))
        (finally
          (sp/close storage))))))


(deftest crud-update-entity-test
  (testing "update-entity updates existing entity"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          id #uuid "11111111-1111-1111-1111-111111111111"
          _ (sp/create-entity storage :user {:id id :name "Alice"})
          result (sp/update-entity storage :user id {:name "Alice Updated"})]
      (try
        (is (= id (:id result)))
        (is (= "Alice Updated" (:name result)))
        ;; Verify persistence
        (is (= "Alice Updated" (:name (sp/read-entity storage :user id))))
        (finally
          (sp/close storage)))))

  (testing "update-entity throws for non-existent entity"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Entity not found"
              (sp/update-entity storage :user
                                #uuid "11111111-1111-1111-1111-111111111111"
                                {:name "Test"})))
        (finally
          (sp/close storage))))))


(deftest crud-delete-entity-test
  (testing "delete-entity returns true for existing entity"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          id #uuid "11111111-1111-1111-1111-111111111111"
          _ (sp/create-entity storage :user {:id id :name "Alice"})
          result (sp/delete-entity storage :user id)]
      (try
        (is (true? result))
        (is (nil? (sp/read-entity storage :user id)))
        (finally
          (sp/close storage)))))

  (testing "delete-entity returns false for non-existent entity"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          result (sp/delete-entity storage :user #uuid "11111111-1111-1111-1111-111111111111")]
      (try
        (is (false? result))
        (finally
          (sp/close storage))))))


(deftest crud-query-entities-test
  (testing "query-entities with empty where returns all"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          _ (sp/create-entity storage :user {:id #uuid "11111111-1111-1111-1111-111111111111" :name "Alice"})
          _ (sp/create-entity storage :user {:id #uuid "22222222-2222-2222-2222-222222222222" :name "Bob"})
          result (sp/query-entities storage :user {})]
      (try
        (is (= 2 (count result)))
        (is (= #{"Alice" "Bob"} (set (map :name result))))
        (finally
          (sp/close storage)))))

  (testing "query-entities with where filters results"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          _ (sp/create-entity storage :user {:id #uuid "11111111-1111-1111-1111-111111111111" :name "Alice"})
          _ (sp/create-entity storage :user {:id #uuid "22222222-2222-2222-2222-222222222222" :name "Bob"})
          result (sp/query-entities storage :user {:name "Alice"})]
      (try
        (is (= 1 (count result)))
        (is (= "Alice" (:name (first result))))
        (finally
          (sp/close storage))))))


;; === StorageBatchCRUD tests ===

(deftest batch-create-entities-test
  (testing "create-entities creates multiple entities in single operation"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          data [{:name "Alice"}
                {:name "Bob"}
                {:name "Charlie"}]
          results (sp/create-entities storage :user data)]
      (try
        (is (= 3 (count results)))
        (is (= #{"Alice" "Bob" "Charlie"} (set (map :name results))))
        (is (every? uuid? (map :id results)))
        ;; Verify persistence
        (is (= 3 (count (sp/query-entities storage :user {}))))
        (finally
          (sp/close storage)))))

  (testing "create-entities with provided ids"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id2 #uuid "22222222-2222-2222-2222-222222222222"
          data [{:id id1 :name "Alice"}
                {:id id2 :name "Bob"}]
          results (sp/create-entities storage :user data)]
      (try
        (is (= #{id1 id2} (set (map :id results))))
        (finally
          (sp/close storage)))))

  (testing "create-entities with empty sequence returns empty"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          results (sp/create-entities storage :user [])]
      (try
        (is (empty? results))
        (finally
          (sp/close storage))))))


(deftest batch-read-entities-test
  (testing "read-entities returns map of found entities"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id2 #uuid "22222222-2222-2222-2222-222222222222"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          _ (sp/create-entity storage :user {:id id2 :name "Bob"})
          results (sp/read-entities storage :user [id1 id2])]
      (try
        (is (= 2 (count results)))
        (is (= "Alice" (:name (get results id1))))
        (is (= "Bob" (:name (get results id2))))
        (finally
          (sp/close storage)))))

  (testing "read-entities excludes non-existent ids"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id-nonexistent #uuid "99999999-9999-9999-9999-999999999999"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          results (sp/read-entities storage :user [id1 id-nonexistent])]
      (try
        (is (= 1 (count results)))
        (is (= "Alice" (:name (get results id1))))
        (is (nil? (get results id-nonexistent)))
        (finally
          (sp/close storage)))))

  (testing "read-entities with empty ids returns empty map"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          results (sp/read-entities storage :user [])]
      (try
        (is (= {} results))
        (finally
          (sp/close storage))))))


(deftest batch-delete-entities-test
  (testing "delete-entities deletes multiple entities and returns count"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id2 #uuid "22222222-2222-2222-2222-222222222222"
          id3 #uuid "33333333-3333-3333-3333-333333333333"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          _ (sp/create-entity storage :user {:id id2 :name "Bob"})
          _ (sp/create-entity storage :user {:id id3 :name "Charlie"})
          deleted-count (sp/delete-entities storage :user [id1 id2])]
      (try
        (is (= 2 deleted-count))
        ;; Verify entities are gone
        (is (nil? (sp/read-entity storage :user id1)))
        (is (nil? (sp/read-entity storage :user id2)))
        ;; Charlie should still exist
        (is (= "Charlie" (:name (sp/read-entity storage :user id3))))
        (finally
          (sp/close storage)))))

  (testing "delete-entities with non-existent ids returns count of actually deleted"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          id1 #uuid "11111111-1111-1111-1111-111111111111"
          id-nonexistent #uuid "99999999-9999-9999-9999-999999999999"
          _ (sp/create-entity storage :user {:id id1 :name "Alice"})
          deleted-count (sp/delete-entities storage :user [id1 id-nonexistent])]
      (try
        (is (= 1 deleted-count))
        (finally
          (sp/close storage)))))

  (testing "delete-entities with empty ids returns 0"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          _ (sp/initialize storage schema)
          deleted-count (sp/delete-entities storage :user [])]
      (try
        (is (zero? deleted-count))
        (finally
          (sp/close storage))))))


;; === Required field validation tests ===

(deftest crud-required-field-validation-test
  (testing "create-entity throws when required field is missing"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}
                                       :email {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                               :type :text}})]
      (sp/initialize storage schema)
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required field 'email' is missing or nil"
              (sp/create-entity storage :user {:name "Alice"})))
        (finally
          (sp/close storage)))))

  (testing "create-entity throws when required field is nil"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})]
      (sp/initialize storage schema)
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required field 'name' is missing or nil"
              (sp/create-entity storage :user {:name nil})))
        (finally
          (sp/close storage)))))

  (testing "create-entity allows missing nullable field"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}
                                       :bio {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                             :type :text :nullable? true}})]
      (sp/initialize storage schema)
      (try
        ;; Note: Datomic doesn't allow nil values, so we just omit the field
        (let [user (sp/create-entity storage :user {:name "Alice"})]
          (is (= "Alice" (:name user)))
          (is (nil? (:bio user))))
        (finally
          (sp/close storage)))))

  (testing "update-entity throws when setting required field to nil"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})]
      (sp/initialize storage schema)
      (try
        (let [user (sp/create-entity storage :user {:name "Alice"})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Required field 'name' is missing or nil"
                (sp/update-entity storage :user (:id user) {:name nil}))))
        (finally
          (sp/close storage)))))

  (testing "update-entity preserves nullable field when not updating it"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}
                                       :bio {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                             :type :text :nullable? true}})]
      (sp/initialize storage schema)
      (try
        ;; Note: Datomic doesn't allow nil values, but we can update without including the field
        (let [user (sp/create-entity storage :user {:name "Alice" :bio "Hello"})
              updated (sp/update-entity storage :user (:id user) {:name "Alice Updated"})]
          (is (= "Alice Updated" (:name updated)))
          (is (= "Hello" (:bio updated))))
        (finally
          (sp/close storage))))))


;; === GraphConstraints tests ===

(defn- make-graph-schema
  "Creates schema with fn-schema, arg-schema, fn, and arg-value entities."
  []
  (-> (mds/create-builder)
      (ds/add-entity :fn-schema #uuid "00000000-0000-0000-0001-000000000001"
                     {:name {:uuid #uuid "00000000-0000-0000-0001-000000000002"
                             :type :text}
                      :returned-type {:uuid #uuid "00000000-0000-0000-0001-000000000003"
                                      :type :text}})
      (ds/add-entity :arg-schema #uuid "00000000-0000-0000-0002-000000000001"
                     {:fn-schema-id {:uuid #uuid "00000000-0000-0000-0002-000000000002"
                                     :type :uuid}
                      :name {:uuid #uuid "00000000-0000-0000-0002-000000000003"
                             :type :text}
                      :type {:uuid #uuid "00000000-0000-0000-0002-000000000004"
                             :type :text}
                      :required {:uuid #uuid "00000000-0000-0000-0002-000000000005"
                                 :type :bool}})
      (ds/add-entity :fn #uuid "00000000-0000-0000-0003-000000000001"
                     {:name {:uuid #uuid "00000000-0000-0000-0003-000000000002"
                             :type :text}
                      :fn-schema-id {:uuid #uuid "00000000-0000-0000-0003-000000000003"
                                     :type :uuid}
                      :parent-fn-id {:uuid #uuid "00000000-0000-0000-0003-000000000004"
                                     :type :uuid
                                     :nullable? true}})
      (ds/add-entity :arg-value #uuid "00000000-0000-0000-0004-000000000001"
                     {:owner-fn-id {:uuid #uuid "00000000-0000-0000-0004-000000000002"
                                    :type :uuid}
                      :arg-schema-id {:uuid #uuid "00000000-0000-0000-0004-000000000003"
                                      :type :uuid}
                      :value {:uuid #uuid "00000000-0000-0000-0004-000000000004"
                              :type :text}})
      ds/build))


(deftest validate-parent-same-schema-test
  (testing "allows nil parent"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn {:id fn-id :name "my-sum" :fn-schema-id fn-schema-id})]
      (try
        (is (nil? (sp/validate-parent-same-schema! storage fn-id nil)))
        (finally
          (sp/close storage)))))

  (testing "allows parent with same schema"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          parent-fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          child-fn-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn {:id parent-fn-id :name "base-sum" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id child-fn-id :name "my-sum" :fn-schema-id fn-schema-id
                                           :parent-fn-id parent-fn-id})]
      (try
        (is (nil? (sp/validate-parent-same-schema! storage child-fn-id parent-fn-id)))
        (finally
          (sp/close storage)))))

  (testing "throws on different schema"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          schema1-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          schema2-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-bbbbbbbbbbbb"
          parent-fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          child-fn-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id schema1-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn-schema {:id schema2-id :name "sub" :returned-type "int"})
          _ (sp/create-entity storage :fn {:id parent-fn-id :name "base-sum" :fn-schema-id schema1-id})
          _ (sp/create-entity storage :fn {:id child-fn-id :name "my-sub" :fn-schema-id schema2-id})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Parent fn has different fn-schema-id"
              (sp/validate-parent-same-schema! storage child-fn-id parent-fn-id)))
        (finally
          (sp/close storage))))))


(deftest validate-no-inheritance-cycle-test
  (testing "allows nil parent"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"]
      (try
        (is (nil? (sp/validate-no-inheritance-cycle! storage fn-id nil)))
        (finally
          (sp/close storage)))))

  (testing "throws on self-reference"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot set self as parent"
              (sp/validate-no-inheritance-cycle! storage fn-id fn-id)))
        (finally
          (sp/close storage)))))

  (testing "throws on cycle A→B→A"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          fn-a #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          fn-b #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn {:id fn-a :name "fn-a" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id fn-b :name "fn-b" :fn-schema-id fn-schema-id
                                           :parent-fn-id fn-a})]
      (try
        ;; Try to make A's parent B (would create A→B→A cycle)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Setting parent would create inheritance cycle"
              (sp/validate-no-inheritance-cycle! storage fn-a fn-b)))
        (finally
          (sp/close storage))))))


(deftest validate-arg-schema-belongs-to-fn-test
  (testing "allows matching schema"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          arg-schema-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id fn-schema-id
                                                   :name "x" :type "int" :required true})
          _ (sp/create-entity storage :fn {:id fn-id :name "my-sum" :fn-schema-id fn-schema-id})]
      (try
        (is (nil? (sp/validate-arg-schema-belongs-to-fn! storage fn-id arg-schema-id)))
        (finally
          (sp/close storage)))))

  (testing "throws on mismatched schema"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          schema1-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          schema2-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-bbbbbbbbbbbb"
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          arg-schema-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id schema1-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn-schema {:id schema2-id :name "sub" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id schema2-id
                                                   :name "x" :type "int" :required true})
          _ (sp/create-entity storage :fn {:id fn-id :name "my-sum" :fn-schema-id schema1-id})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Arg-schema does not belong to fn's schema"
              (sp/validate-arg-schema-belongs-to-fn! storage fn-id arg-schema-id)))
        (finally
          (sp/close storage))))))


(deftest validate-no-arg-override-test
  (testing "allows arg when not in parent chain"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          arg-schema-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id fn-schema-id
                                                   :name "x" :type "int" :required true})
          _ (sp/create-entity storage :fn {:id fn-id :name "my-sum" :fn-schema-id fn-schema-id})]
      (try
        (is (nil? (sp/validate-no-arg-override! storage fn-id arg-schema-id)))
        (finally
          (sp/close storage)))))

  (testing "throws when arg already defined in parent"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          arg-schema-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          parent-fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          child-fn-id #uuid "dddddddd-dddd-dddd-dddd-dddddddddddd"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id fn-schema-id
                                                   :name "x" :type "int" :required true})
          _ (sp/create-entity storage :fn {:id parent-fn-id :name "base-sum" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id child-fn-id :name "my-sum" :fn-schema-id fn-schema-id
                                           :parent-fn-id parent-fn-id})
          _ (sp/create-entity storage :arg-value {:id #uuid "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
                                                  :owner-fn-id parent-fn-id
                                                  :arg-schema-id arg-schema-id
                                                  :value "42"})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Argument already defined in parent chain"
              (sp/validate-no-arg-override! storage child-fn-id arg-schema-id)))
        (finally
          (sp/close storage))))))


(deftest validate-no-dependency-cycle-test
  (testing "allows non-cyclic reference"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          owner-fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          value-fn-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "sum" :returned-type "int"})
          _ (sp/create-entity storage :fn {:id owner-fn-id :name "owner" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id value-fn-id :name "value" :fn-schema-id fn-schema-id})]
      (try
        (is (nil? (sp/validate-no-dependency-cycle! storage owner-fn-id value-fn-id)))
        (finally
          (sp/close storage)))))

  (testing "allows nil value-fn-id"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          owner-fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"]
      (try
        (is (nil? (sp/validate-no-dependency-cycle! storage owner-fn-id nil)))
        (finally
          (sp/close storage)))))

  (testing "throws when dependency cycle detected"
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          fn-a-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          fn-b-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          fn-c-id #uuid "dddddddd-dddd-dddd-dddd-dddddddddddd"
          arg-schema-id #uuid "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "test" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id fn-schema-id
                                                   :name "x" :type "int" :required true})
          _ (sp/create-entity storage :fn {:id fn-a-id :name "fn-a" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id fn-b-id :name "fn-b" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id fn-c-id :name "fn-c" :fn-schema-id fn-schema-id})
          ;; Create b -> c reference (b depends on c)
          _ (sp/create-entity storage :arg-value {:owner-fn-id fn-b-id
                                                  :arg-schema-id arg-schema-id
                                                  :value (str fn-c-id)})
          ;; Create c -> a reference (c depends on a)
          _ (sp/create-entity storage :arg-value {:id #uuid "ffffffff-ffff-ffff-ffff-ffffffffffff"
                                                  :owner-fn-id fn-c-id
                                                  :arg-schema-id arg-schema-id
                                                  :value (str fn-a-id)})]
      (try
        ;; Try to validate a -> b, which would create cycle: a -> b -> c -> a
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"dependency cycle"
              (sp/validate-no-dependency-cycle! storage fn-a-id fn-b-id)))
        (finally
          (sp/close storage))))))


;; === ExecutionGraph tests ===

(deftest resolve-execution-graph-simple-test
  (testing "resolves simple function with no dependencies"
    (let [storage (create-test-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [fn-schema (sp/create-entity storage :fn-schema
                                        {:name "add" :returned-type "int"})
            arg-a (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id fn-schema)
                                     :name "a" :type "int" :required true})
            arg-b (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id fn-schema)
                                     :name "b" :type "int" :required true})
            fn-add (sp/create-entity storage :fn
                                     {:name "add-1-2"
                                      :fn-schema-id (:id fn-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id fn-add)
                                 :arg-schema-id (:id arg-a)
                                 :value "1"})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id fn-add)
                                 :arg-schema-id (:id arg-b)
                                 :value "2"})
            graph (sp/resolve-execution-graph storage (:id fn-add))]
        (try
          (is (contains? (:fns graph) (:id fn-add)))
          (is (contains? (:fn-schemas graph) (:id fn-schema)))
          (is (contains? (:arg-schemas graph) (:id arg-a)))
          (is (contains? (:arg-schemas graph) (:id arg-b)))
          (is (contains? (:resolved-args graph) (:id fn-add)))
          (let [args (get (:resolved-args graph) (:id fn-add))]
            (is (= "1" (:value (get args (:id arg-a)))))
            (is (= "2" (:value (get args (:id arg-b))))))
          (finally
            (sp/close storage)))))))


(deftest resolve-execution-graph-with-parent-test
  (testing "resolves function with parent chain - child overrides parent"
    (let [storage (create-test-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [fn-schema (sp/create-entity storage :fn-schema
                                        {:name "greet" :returned-type "text"})
            arg-name (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id fn-schema)
                                        :name "name" :type "text" :required true})
            arg-greeting (sp/create-entity storage :arg-schema
                                           {:fn-schema-id (:id fn-schema)
                                            :name "greeting" :type "text" :required true})
            parent-fn (sp/create-entity storage :fn
                                        {:name "greet-hello"
                                         :fn-schema-id (:id fn-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id parent-fn)
                                 :arg-schema-id (:id arg-greeting)
                                 :value "Hello"})
            child-fn (sp/create-entity storage :fn
                                       {:name "greet-hello-world"
                                        :fn-schema-id (:id fn-schema)
                                        :parent-fn-id (:id parent-fn)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id child-fn)
                                 :arg-schema-id (:id arg-name)
                                 :value "World"})
            graph (sp/resolve-execution-graph storage (:id child-fn))]
        (try
          (is (contains? (:fns graph) (:id child-fn)))
          (let [args (get (:resolved-args graph) (:id child-fn))]
            (is (= "World" (:value (get args (:id arg-name)))))
            (is (= "Hello" (:value (get args (:id arg-greeting))))))
          (finally
            (sp/close storage)))))))


(deftest resolve-execution-graph-with-fn-refs-test
  (testing "resolves function with references to other functions"
    (let [storage (create-test-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [const-schema (sp/create-entity storage :fn-schema
                                           {:name "const-int" :returned-type "int"})
            const-arg (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id const-schema)
                                         :name "value" :type "int" :required true})
            add-schema (sp/create-entity storage :fn-schema
                                         {:name "add" :returned-type "int"})
            add-arg-a (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "a" :type "int" :required true})
            add-arg-b (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "b" :type "int" :required true})
            const-3 (sp/create-entity storage :fn
                                      {:name "const-3"
                                       :fn-schema-id (:id const-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id const-3)
                                 :arg-schema-id (:id const-arg)
                                 :value "3"})
            const-5 (sp/create-entity storage :fn
                                      {:name "const-5"
                                       :fn-schema-id (:id const-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id const-5)
                                 :arg-schema-id (:id const-arg)
                                 :value "5"})
            add-3-5 (sp/create-entity storage :fn
                                      {:name "add-3-5"
                                       :fn-schema-id (:id add-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-3-5)
                                 :arg-schema-id (:id add-arg-a)
                                 :value (str (:id const-3))})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-3-5)
                                 :arg-schema-id (:id add-arg-b)
                                 :value (str (:id const-5))})
            graph (sp/resolve-execution-graph storage (:id add-3-5))]
        (try
          (is (= 3 (count (:fns graph))))
          (is (contains? (:fns graph) (:id add-3-5)))
          (is (contains? (:fns graph) (:id const-3)))
          (is (contains? (:fns graph) (:id const-5)))
          (is (= 2 (count (:fn-schemas graph))))
          (is (= 3 (count (:arg-schemas graph))))
          (finally
            (sp/close storage)))))))


(deftest resolve-execution-graph-not-found-test
  (testing "throws when function not found"
    (let [storage (create-test-storage)]
      (sp/initialize storage (make-graph-schema))
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Function not found"
              (sp/resolve-execution-graph storage (random-uuid))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-self-reference-test
  (testing "handles fn with self-reference in arg-value (triggers 'already visited' branch)"
    (let [storage (create-test-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [;; recursive fn-schema with two args
            rec-schema (sp/create-entity storage :fn-schema
                                         {:name "recursive" :returned-type "int"})
            ;; 'self' arg will reference the fn itself (for recursion)
            arg-self (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id rec-schema)
                                        :name "self" :type "fn" :required true})
            arg-n (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id rec-schema)
                                     :name "n" :type "int" :required true})
            ;; Create fn instance that references itself
            rec-fn (sp/create-entity storage :fn
                                     {:name "factorial"
                                      :fn-schema-id (:id rec-schema)})
            ;; Self-reference: arg-value points to the fn itself
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id rec-fn)
                                 :arg-schema-id (:id arg-self)
                                 :value (str (:id rec-fn))})  ; Self-reference!
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id rec-fn)
                                 :arg-schema-id (:id arg-n)
                                 :value "5"})
            graph (sp/resolve-execution-graph storage (:id rec-fn))]
        (try
          ;; Should only have 1 fn (self-reference doesn't create duplicate)
          (is (= 1 (count (:fns graph))))
          (is (contains? (:fns graph) (:id rec-fn)))
          ;; Self arg should reference the same fn
          (let [args (get (:resolved-args graph) (:id rec-fn))]
            (is (= (str (:id rec-fn)) (str (:value (get args (:id arg-self))))))
            (is (= "5" (str (:value (get args (:id arg-n)))))))
          (finally
            (sp/close storage)))))))


(deftest resolve-execution-graph-shared-reference-test
  (testing "handles shared fn reference (same fn referenced by multiple args)"
    (let [storage (create-test-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [;; const-int schema
            const-schema (sp/create-entity storage :fn-schema
                                           {:name "const-shared" :returned-type "int"})
            const-arg (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id const-schema)
                                         :name "value" :type "int" :required true})
            ;; add schema - both args reference fns
            add-schema (sp/create-entity storage :fn-schema
                                         {:name "add-shared" :returned-type "int"})
            add-arg-a (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "a" :type "int" :required true})
            add-arg-b (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "b" :type "int" :required true})
            ;; const-5 fn - will be referenced TWICE
            const-5 (sp/create-entity storage :fn
                                      {:name "const-5-shared"
                                       :fn-schema-id (:id const-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id const-5)
                                 :arg-schema-id (:id const-arg)
                                 :value "5"})
            ;; add-5-5 fn referencing const-5 for BOTH args
            add-5-5 (sp/create-entity storage :fn
                                      {:name "add-5-5-shared"
                                       :fn-schema-id (:id add-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-5-5)
                                 :arg-schema-id (:id add-arg-a)
                                 :value (str (:id const-5))})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-5-5)
                                 :arg-schema-id (:id add-arg-b)
                                 :value (str (:id const-5))})  ; Same fn referenced again!
            graph (sp/resolve-execution-graph storage (:id add-5-5))]
        (try
          ;; const-5 should only appear once in the graph despite being referenced twice
          (is (= 2 (count (:fns graph))))
          (is (contains? (:fns graph) (:id add-5-5)))
          (is (contains? (:fns graph) (:id const-5)))
          ;; Both args should reference const-5
          (let [args (get (:resolved-args graph) (:id add-5-5))]
            (is (= (str (:id const-5)) (str (:value (get args (:id add-arg-a))))))
            (is (= (str (:id const-5)) (str (:value (get args (:id add-arg-b)))))))
          (finally
            (sp/close storage)))))))


(deftest collect-dependency-chain-with-cycle-test
  (testing "collect-dependency-chain handles mutual dependency (A -> B -> A)"
    ;; This tests the 'already visited' branch at line 833
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          fn-a-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          fn-b-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          arg-schema-a-id #uuid "dddddddd-dddd-dddd-dddd-dddddddddddd"
          arg-schema-b-id #uuid "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "test" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-a-id :fn-schema-id fn-schema-id
                                                   :name "ref-a" :type "ref" :required false})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-b-id :fn-schema-id fn-schema-id
                                                   :name "ref-b" :type "ref" :required false})
          _ (sp/create-entity storage :fn {:id fn-a-id :name "fn-a" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id fn-b-id :name "fn-b" :fn-schema-id fn-schema-id})
          ;; Create mutual dependency: a -> b
          _ (sp/create-entity storage :arg-value {:owner-fn-id fn-a-id
                                                  :arg-schema-id arg-schema-a-id
                                                  :value (str fn-b-id)})
          ;; b -> a (creating cycle)
          _ (sp/create-entity storage :arg-value {:owner-fn-id fn-b-id
                                                  :arg-schema-id arg-schema-b-id
                                                  :value (str fn-a-id)})]
      (try
        ;; Test validate-no-dependency-cycle! which uses collect-dependency-chain
        ;; When checking if we can add a dependency from X to fn-a,
        ;; it will traverse fn-a's dependencies: fn-a -> fn-b -> fn-a (cycle, revisit)
        (let [new-fn-id #uuid "ffffffff-ffff-ffff-ffff-ffffffffffff"
              _ (sp/create-entity storage :fn {:id new-fn-id :name "fn-new" :fn-schema-id fn-schema-id})]
          ;; This should not throw because new-fn is not in fn-a's dependency chain
          ;; But it will exercise the "already visited" branch when traversing the cycle
          (is (nil? (sp/validate-no-dependency-cycle! storage new-fn-id fn-a-id))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-with-deleted-ref-test
  (testing "handles fn deleted during graph traversal (covers line 956)"
    ;; This tests when a referenced fn doesn't exist
    (let [storage (create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          arg-schema-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          non-existent-fn-id #uuid "99999999-9999-9999-9999-999999999999"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "test" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id fn-schema-id
                                                   :name "ref" :type "ref" :required false})
          _ (sp/create-entity storage :fn {:id fn-id :name "test-fn" :fn-schema-id fn-schema-id})
          ;; Create arg-value referencing non-existent fn
          _ (sp/create-entity storage :arg-value {:owner-fn-id fn-id
                                                  :arg-schema-id arg-schema-id
                                                  :value (str non-existent-fn-id)})]
      (try
        ;; resolve-execution-graph should skip the non-existent fn
        (let [graph (sp/resolve-execution-graph storage fn-id)]
          (is (= 1 (count (:fns graph))))
          (is (contains? (:fns graph) fn-id)))
        (finally
          (sp/close storage))))))


;; === Concurrent operation tests ===

(deftest concurrent-access-test
  (testing "concurrent reads are thread-safe"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :text}})
          errors (atom [])]
      (sp/initialize storage schema)
      (try
        (sp/create-entity storage :user {:name "Alice"})
        ;; Launch multiple threads reading concurrently
        (let [futures (doall
                        (for [_ (range 10)]
                          (future
                            (try
                              (dotimes [_ 50]
                                (sp/query-entities storage :user {}))
                              (catch Exception e
                                (swap! errors conj e))))))]
          (doseq [f futures]
            (deref f 5000 :timeout)))
        (is (empty? @errors) (str "Errors during concurrent access: " @errors))
        (finally
          (sp/close storage)))))

  (testing "concurrent writes are thread-safe"
    (let [storage (create-test-storage)
          schema (make-schema :fields {:value {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                               :type :int}})
          errors (atom [])]
      (sp/initialize storage schema)
      (try
        ;; Launch multiple threads creating entities concurrently
        (let [futures (doall
                        (for [i (range 5)]
                          (future
                            (try
                              (dotimes [j 10]
                                (sp/create-entity storage :user {:value (+ (* i 10) j)}))
                              (catch Exception e
                                (swap! errors conj e))))))]
          (doseq [f futures]
            (deref f 10000 :timeout)))
        (is (empty? @errors) (str "Errors during concurrent writes: " @errors))
        (is (= 50 (count (sp/query-entities storage :user {}))))
        (finally
          (sp/close storage))))))


;; === GraphConstraints contract tests ===

(deftest graph-constraints-contract-test
  (contract/run-graph-constraints-tests
    create-test-storage
    sp/close))
