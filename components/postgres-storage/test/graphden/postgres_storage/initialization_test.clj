(ns graphden.postgres-storage.initialization-test
  "Tests for PostgreSQL storage initialization and introspection."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.postgres-storage.test-setup :as setup]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.test-helpers :as th]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


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

  (testing "initializing with enum creates enum and values"
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
        (is (= #{{:enum :status :value :active}
                 {:enum :status :value :inactive}}
               (set (:created (:enum-values changes)))))
        (finally
          (sp/close storage))))))


;; === Introspection tests ===

(deftest introspection-test
  (testing "current-entities returns entity names"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (try
        (sp/initialize storage schema)
        (is (= #{:user} (sp/current-entities storage)))
        (finally
          (sp/close storage)))))

  (testing "current-fields returns field definitions"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (try
        (sp/initialize storage schema)
        (is (= {:name {:type :text :nullable? false}}
               (sp/current-fields storage :user)))
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
        (is (= #{:status} (sp/current-enums storage)))
        (finally
          (sp/close storage)))))

  (testing "current-enum-values returns enum value names"
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

  (testing "schema-metadata returns full metadata"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :enum-name :status
                                 :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                                 :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                                :value :active}])]
      (try
        (sp/initialize storage schema)
        (let [metadata (sp/schema-metadata storage)]
          (is (map? metadata))
          (is (contains? metadata :entities))
          (is (contains? metadata :fields))
          (is (contains? metadata :enums))
          (is (contains? metadata :enum-values)))
        (finally
          (sp/close storage))))))


;; === Idempotency tests ===

(deftest idempotency-test
  (testing "multiple initializations with same schema are idempotent"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :enum-name :status
                                 :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                                 :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                                :value :active}])]
      (try
        (let [changes1 (sp/initialize storage schema)
              changes2 (sp/initialize storage schema)]
          ;; First init creates everything
          (is (seq (:created (:entities changes1))))
          ;; Second init creates nothing (all exists)
          (is (empty? (:created (:entities changes2))))
          (is (empty? (:created (:fields changes2))))
          (is (empty? (:created (:enums changes2))))
          (is (empty? (:created (:enum-values changes2)))))
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
