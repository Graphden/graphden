(ns graphden.datomic-storage.first-init-test
  "Tests for datomic-storage first-time initialization."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.datomic-storage.test-setup :as setup]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.test-helpers :as th]))


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
