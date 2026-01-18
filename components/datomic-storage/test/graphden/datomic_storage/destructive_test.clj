(ns graphden.datomic-storage.destructive-test
  "Tests for datomic-storage destructive changes detection."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.datomic-storage.test-setup :as setup]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.test-helpers :as th]))


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


(deftest close-test
  (testing "close is idempotent"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (is (nil? (sp/close storage)))
      (is (nil? (sp/close storage))))))
