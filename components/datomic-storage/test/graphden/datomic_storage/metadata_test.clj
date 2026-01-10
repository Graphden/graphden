(ns graphden.datomic-storage.metadata-test
  "Tests for datomic-storage.metadata - metadata transaction building and persistence."
  (:require
    [clojure.test :refer [deftest is testing]]
    [datomic.client.api :as d]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.datomic-storage.interface :as dat]
    [graphden.datomic-storage.introspection :as introspection]
    [graphden.datomic-storage.metadata :as metadata]
    [graphden.datomic-storage.schema :as schema]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.storage-protocol.interface :as sp]))


;; === build-all-metadata-tx-data tests ===

(deftest build-all-metadata-tx-data-test
  (testing "returns empty seq for empty schema"
    (let [schema (-> (mds/create-builder) ds/build)
          tx-data (metadata/build-all-metadata-tx-data schema)]
      (is (empty? tx-data))))

  (testing "builds entity metadata"
    (let [entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          schema (-> (mds/create-builder)
                     (ds/add-entity :user entity-uuid {})
                     ds/build)
          tx-data (metadata/build-all-metadata-tx-data schema)]
      (is (= 1 (count tx-data)))
      (let [entity-tx (first tx-data)]
        (is (= entity-uuid (:graphden.metadata/uuid entity-tx)))
        (is (= :entity (:graphden.metadata/kind entity-tx)))
        (is (= :user (:graphden.metadata/name entity-tx))))))

  (testing "builds field metadata"
    (let [entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema (-> (mds/create-builder)
                     (ds/add-entity :user entity-uuid
                                    {:name {:uuid field-uuid :type :text :nullable? false}})
                     ds/build)
          tx-data (metadata/build-all-metadata-tx-data schema)
          field-tx (first (filter #(= :field (:graphden.metadata/kind %)) tx-data))]
      (is (some? field-tx))
      (is (= field-uuid (:graphden.metadata/uuid field-tx)))
      (is (= :name (:graphden.metadata/name field-tx)))
      (is (= entity-uuid (:graphden.metadata/parent-uuid field-tx)))
      (is (= :text (:graphden.metadata/field-type field-tx)))
      (is (false? (:graphden.metadata/field-nullable field-tx)))))

  (testing "builds field metadata with nullable=true"
    (let [entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema (-> (mds/create-builder)
                     (ds/add-entity :user entity-uuid
                                    {:bio {:uuid field-uuid :type :text :nullable? true}})
                     ds/build)
          tx-data (metadata/build-all-metadata-tx-data schema)
          field-tx (first (filter #(= :field (:graphden.metadata/kind %)) tx-data))]
      (is (true? (:graphden.metadata/field-nullable field-tx)))))

  (testing "builds enum metadata"
    (let [enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
          schema (-> (mds/create-builder)
                     (ds/add-enum :status enum-uuid
                                  [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                    :value :active}])
                     ds/build)
          tx-data (metadata/build-all-metadata-tx-data schema)
          enum-tx (first (filter #(= :enum (:graphden.metadata/kind %)) tx-data))]
      (is (some? enum-tx))
      (is (= enum-uuid (:graphden.metadata/uuid enum-tx)))
      (is (= :status (:graphden.metadata/name enum-tx)))))

  (testing "builds enum value metadata"
    (let [enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
          value-uuid #uuid "00000000-0000-0000-0000-000000000011"
          schema (-> (mds/create-builder)
                     (ds/add-enum :status enum-uuid
                                  [{:uuid value-uuid :value :active}])
                     ds/build)
          tx-data (metadata/build-all-metadata-tx-data schema)
          value-tx (first (filter #(= :enum-value (:graphden.metadata/kind %)) tx-data))]
      (is (some? value-tx))
      (is (= value-uuid (:graphden.metadata/uuid value-tx)))
      (is (= :active (:graphden.metadata/name value-tx)))
      (is (= enum-uuid (:graphden.metadata/parent-uuid value-tx)))))

  (testing "builds field metadata with enum reference"
    (let [entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
          schema (-> (mds/create-builder)
                     (ds/add-enum :status enum-uuid
                                  [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                    :value :active}])
                     (ds/add-entity :user entity-uuid
                                    {:status {:uuid field-uuid
                                              :type :enum
                                              :enum-name :status}})
                     ds/build)
          tx-data (metadata/build-all-metadata-tx-data schema)
          field-tx (first (filter #(and (= :field (:graphden.metadata/kind %))
                                        (= :status (:graphden.metadata/name %)))
                                  tx-data))]
      (is (= :enum (:graphden.metadata/field-type field-tx)))
      (is (= :status (:graphden.metadata/field-enum-name field-tx)))))

  (testing "builds field metadata with ref reference"
    (let [user-uuid #uuid "00000000-0000-0000-0000-000000000001"
          order-uuid #uuid "00000000-0000-0000-0000-000000000002"
          ref-field-uuid #uuid "00000000-0000-0000-0000-000000000003"
          schema (-> (mds/create-builder)
                     (ds/add-entity :user user-uuid {})
                     (ds/add-entity :order order-uuid
                                    {:user-id {:uuid ref-field-uuid
                                               :type :ref
                                               :ref-entity :user}})
                     ds/build)
          tx-data (metadata/build-all-metadata-tx-data schema)
          field-tx (first (filter #(and (= :field (:graphden.metadata/kind %))
                                        (= :user-id (:graphden.metadata/name %)))
                                  tx-data))]
      (is (= :ref (:graphden.metadata/field-type field-tx)))
      (is (= :user (:graphden.metadata/field-ref-entity field-tx)))))

  (testing "builds complete schema with multiple entities and enums"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000000010"
                                  [{:uuid #uuid "00000000-0000-0000-0000-000000000011" :value :active}
                                   {:uuid #uuid "00000000-0000-0000-0000-000000000012" :value :inactive}])
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                    {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002" :type :text}
                                     :status {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                              :type :enum :enum-name :status}})
                     (ds/add-entity :order #uuid "00000000-0000-0000-0000-000000000004"
                                    {:total {:uuid #uuid "00000000-0000-0000-0000-000000000005" :type :numeric}})
                     ds/build)
          tx-data (metadata/build-all-metadata-tx-data schema)
          entities (filter #(= :entity (:graphden.metadata/kind %)) tx-data)
          fields (filter #(= :field (:graphden.metadata/kind %)) tx-data)
          enums (filter #(= :enum (:graphden.metadata/kind %)) tx-data)
          enum-values (filter #(= :enum-value (:graphden.metadata/kind %)) tx-data)]
      (is (= 2 (count entities)))    ; user, order
      (is (= 3 (count fields)))      ; name, status, total
      (is (= 1 (count enums)))       ; status
      (is (= 2 (count enum-values))) ; active, inactive
      (is (= 8 (count tx-data))))))  ; total


;; === Integration tests for save-metadata! ===

(def ^:private test-counter (atom 0))


(defn- unique-db-name
  "Generates a unique database name for each test."
  []
  (str "metadata-test-" (swap! test-counter inc) "-" (System/currentTimeMillis)))


(defn- create-test-client-and-conn
  "Creates a datomic-local client and connection for testing."
  []
  (let [db-name (unique-db-name)
        client (d/client {:server-type :datomic-local
                          :storage-dir :mem
                          :system "test"})
        _ (d/create-database client {:db-name db-name})
        conn (d/connect client {:db-name db-name})]
    {:client client :conn conn :db-name db-name}))


(defn- cleanup-test-db
  "Cleans up test database."
  [{:keys [client db-name]}]
  (d/delete-database client {:db-name db-name}))


(deftest save-metadata!-integration-test
  (testing "save-metadata! saves metadata to empty database"
    (let [{:keys [conn] :as ctx} (create-test-client-and-conn)]
      (try
        ;; First, install metadata schema
        (d/transact conn {:tx-data (schema/build-metadata-schema)})

        (let [test-schema (-> (mds/create-builder)
                              (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                             {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                     :type :text}})
                              ds/build)]
          ;; Save metadata
          (metadata/save-metadata! conn test-schema)

          ;; Verify metadata was saved
          (let [db (d/db conn)
                entities (d/q '[:find ?uuid ?name
                                :where
                                [?e :graphden.metadata/uuid ?uuid]
                                [?e :graphden.metadata/kind :entity]
                                [?e :graphden.metadata/name ?name]]
                              db)]
            (is (= 1 (count entities)))
            (is (= #{[#uuid "00000000-0000-0000-0000-000000000001" :user]} (set entities)))))
        (finally
          (cleanup-test-db ctx)))))

  (testing "save-metadata! replaces old metadata"
    (let [{:keys [conn] :as ctx} (create-test-client-and-conn)]
      (try
        ;; Install metadata schema
        (d/transact conn {:tx-data (schema/build-metadata-schema)})

        ;; Save first schema
        (let [schema1 (-> (mds/create-builder)
                          (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001" {})
                          ds/build)]
          (metadata/save-metadata! conn schema1))

        ;; Save second schema (replaces first)
        (let [schema2 (-> (mds/create-builder)
                          (ds/add-entity :product #uuid "00000000-0000-0000-0000-000000000010" {})
                          (ds/add-entity :order #uuid "00000000-0000-0000-0000-000000000020" {})
                          ds/build)]
          (metadata/save-metadata! conn schema2))

        ;; Verify only new metadata exists
        (let [db (d/db conn)
              entity-names (->> (d/q '[:find ?name
                                       :where
                                       [?e :graphden.metadata/kind :entity]
                                       [?e :graphden.metadata/name ?name]]
                                     db)
                                (map first)
                                set)]
          (is (= #{:product :order} entity-names))
          (is (not (contains? entity-names :user))))
        (finally
          (cleanup-test-db ctx)))))

  (testing "save-metadata! with empty schema clears metadata"
    (let [{:keys [conn] :as ctx} (create-test-client-and-conn)]
      (try
        ;; Install metadata schema
        (d/transact conn {:tx-data (schema/build-metadata-schema)})

        ;; Save initial schema
        (let [schema1 (-> (mds/create-builder)
                          (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001" {})
                          ds/build)]
          (metadata/save-metadata! conn schema1))

        ;; Save empty schema
        (let [empty-schema (-> (mds/create-builder) ds/build)]
          (metadata/save-metadata! conn empty-schema))

        ;; Verify no metadata exists
        (let [db (d/db conn)
              all-metadata (d/q '[:find ?e
                                  :where [?e :graphden.metadata/uuid _]]
                                db)]
          (is (empty? all-metadata)))
        (finally
          (cleanup-test-db ctx)))))

  (testing "save-metadata! is idempotent"
    (let [{:keys [conn] :as ctx} (create-test-client-and-conn)]
      (try
        ;; Install metadata schema
        (d/transact conn {:tx-data (schema/build-metadata-schema)})

        (let [test-schema (-> (mds/create-builder)
                              (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                             {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                     :type :text}})
                              ds/build)]
          ;; Save multiple times
          (metadata/save-metadata! conn test-schema)
          (metadata/save-metadata! conn test-schema)
          (metadata/save-metadata! conn test-schema)

          ;; Verify correct count
          (let [db (d/db conn)
                entities (d/q '[:find ?e
                                :where [?e :graphden.metadata/kind :entity]]
                              db)]
            (is (= 1 (count entities)))))
        (finally
          (cleanup-test-db ctx))))))


(deftest fetch-existing-metadata-test
  (testing "fetch-existing-metadata returns nil when no metadata schema"
    (let [{:keys [conn] :as ctx} (create-test-client-and-conn)]
      (try
        ;; No metadata schema installed
        (let [db (d/db conn)
              result (#'metadata/fetch-existing-metadata db)]
          (is (nil? result)))
        (finally
          (cleanup-test-db ctx)))))

  (testing "fetch-existing-metadata returns empty when schema exists but no data"
    (let [{:keys [conn] :as ctx} (create-test-client-and-conn)]
      (try
        ;; Install metadata schema but don't add data
        (d/transact conn {:tx-data (schema/build-metadata-schema)})

        (let [db (d/db conn)
              result (#'metadata/fetch-existing-metadata db)]
          (is (some? result))
          (is (empty? (:entity-ids result)))
          (is (nil? (:full-data result))))
        (finally
          (cleanup-test-db ctx)))))

  (testing "fetch-existing-metadata returns data when metadata exists"
    (let [{:keys [conn] :as ctx} (create-test-client-and-conn)]
      (try
        ;; Install metadata schema and add data
        (d/transact conn {:tx-data (schema/build-metadata-schema)})
        (d/transact conn {:tx-data [{:graphden.metadata/uuid #uuid "00000000-0000-0000-0000-000000000001"
                                     :graphden.metadata/kind :entity
                                     :graphden.metadata/name :test-entity}]})

        (let [db (d/db conn)
              result (#'metadata/fetch-existing-metadata db)]
          (is (some? result))
          (is (= 1 (count (:entity-ids result))))
          (is (= 1 (count (:full-data result))))
          (is (= :test-entity (:graphden.metadata/name (first (:full-data result))))))
        (finally
          (cleanup-test-db ctx))))))


(deftest retract-metadata!-test
  (testing "retract-metadata! does nothing with empty entity-ids"
    (let [{:keys [conn] :as ctx} (create-test-client-and-conn)]
      (try
        ;; Should not throw
        (is (nil? (#'metadata/retract-metadata! conn [])))
        (is (nil? (#'metadata/retract-metadata! conn nil)))
        (finally
          (cleanup-test-db ctx)))))

  (testing "retract-metadata! removes entities"
    (let [{:keys [conn] :as ctx} (create-test-client-and-conn)]
      (try
        ;; Install metadata schema and add data
        (d/transact conn {:tx-data (schema/build-metadata-schema)})
        (d/transact conn {:tx-data [{:graphden.metadata/uuid #uuid "00000000-0000-0000-0000-000000000001"
                                     :graphden.metadata/kind :entity
                                     :graphden.metadata/name :test-entity}]})

        ;; Get entity id
        (let [db (d/db conn)
              entity-ids (d/q '[:find ?e :where [?e :graphden.metadata/uuid _]] db)]
          (is (= 1 (count entity-ids)))

          ;; Retract
          (#'metadata/retract-metadata! conn entity-ids)

          ;; Verify removed
          (let [db-after (d/db conn)
                remaining (d/q '[:find ?e :where [?e :graphden.metadata/uuid _]] db-after)]
            (is (empty? remaining))))
        (finally
          (cleanup-test-db ctx))))))


(deftest assert-metadata!-test
  (testing "assert-metadata! does nothing with empty tx-data"
    (let [{:keys [conn] :as ctx} (create-test-client-and-conn)]
      (try
        ;; Should not throw and return nil
        (is (nil? (#'metadata/assert-metadata! conn [] nil)))
        (is (nil? (#'metadata/assert-metadata! conn nil nil)))
        (finally
          (cleanup-test-db ctx)))))

  (testing "assert-metadata! creates new entities"
    (let [{:keys [conn] :as ctx} (create-test-client-and-conn)]
      (try
        ;; Install metadata schema
        (d/transact conn {:tx-data (schema/build-metadata-schema)})

        ;; Assert new metadata
        (let [tx-data [{:graphden.metadata/uuid #uuid "00000000-0000-0000-0000-000000000001"
                        :graphden.metadata/kind :entity
                        :graphden.metadata/name :new-entity}]]
          (#'metadata/assert-metadata! conn tx-data nil))

        ;; Verify created
        (let [db (d/db conn)
              entity-names (->> (d/q '[:find ?name
                                       :where
                                       [?e :graphden.metadata/kind :entity]
                                       [?e :graphden.metadata/name ?name]]
                                     db)
                                (map first)
                                set)]
          (is (= #{:new-entity} entity-names)))
        (finally
          (cleanup-test-db ctx))))))
