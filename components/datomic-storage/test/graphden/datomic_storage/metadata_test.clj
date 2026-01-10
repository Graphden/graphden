(ns graphden.datomic-storage.metadata-test
  "Tests for datomic-storage.metadata - metadata transaction building."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.datomic-storage.metadata :as metadata]
    [graphden.malli-data-schema.interface :as mds]))


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
