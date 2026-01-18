(ns graphden.datomic-storage.introspection-test
  "Tests for datomic-storage StorageIntrospection protocol."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.datomic-storage.test-setup :as setup]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.test-helpers :as th]))


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
