(ns graphden.postgres-storage.schema-changes-test
  "Tests for PostgreSQL storage schema changes: adding, renaming, destructive changes."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.postgres-storage.test-setup :as setup]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.test-helpers :as th]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


;; === Adding tests ===

(deftest adding-test
  (testing "adding new entity in second init"
    (let [storage (setup/create-test-storage)
          schema1 (th/make-schema)
          _ (sp/initialize storage schema1)
          schema2 (-> (mds/create-builder)
                      (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :text}})
                      (ds/add-entity :post #uuid "00000000-0000-0000-0000-000000000003"
                                     {:title {:uuid #uuid "00000000-0000-0000-0000-000000000004"
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


;; === Destructive changes tests ===

(deftest destructive-changes-test
  (testing "removing entity throws"
    (let [storage (setup/create-test-storage)
          schema1 (-> (mds/create-builder)
                      (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :text}})
                      (ds/add-entity :post #uuid "00000000-0000-0000-0000-000000000003"
                                     {:title {:uuid #uuid "00000000-0000-0000-0000-000000000004"
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
          schema2 (th/make-schema)]
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


;; === Enum migration tests ===

(deftest enum-migration-test
  (testing "adding enum during migration (not first-time init)"
    (let [storage (setup/create-test-storage)]
      (try
        ;; First initialize WITHOUT enums
        (let [schema1 (th/make-schema :entity-name :user
                                      :entity-uuid #uuid "00000000-0000-0000-0000-000000006001"
                                      :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000006002"
                                                      :type :text}})]
          (sp/initialize storage schema1))
        ;; Now add an enum in the second initialize
        (let [schema2 (-> (mds/create-builder)
                          (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000006010"
                                       [{:uuid #uuid "00000000-0000-0000-0000-000000006011"
                                         :value :active}
                                        {:uuid #uuid "00000000-0000-0000-0000-000000006012"
                                         :value :inactive}])
                          (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000006001"
                                         {:name {:uuid #uuid "00000000-0000-0000-0000-000000006002"
                                                 :type :text}})
                          ds/build)
              changes (sp/initialize storage schema2)]
          ;; Verify enum was created during migration
          (is (= [:status] (:created (:enums changes))))
          (is (= #{{:enum :status :value :active}
                   {:enum :status :value :inactive}}
                 (set (:created (:enum-values changes)))))
          ;; Verify enum exists in database
          (is (contains? (sp/current-enums storage) :status)))
        (finally
          (sp/close storage))))))
