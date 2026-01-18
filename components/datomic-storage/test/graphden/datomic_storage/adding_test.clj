(ns graphden.datomic-storage.adding-test
  "Tests for datomic-storage adding new entities/fields/enums."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.datomic-storage.test-setup :as setup]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.test-helpers :as th]))


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
