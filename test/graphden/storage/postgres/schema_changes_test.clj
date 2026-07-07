(ns graphden.storage.postgres.schema-changes-test
  "Tests for PostgreSQL storage schema changes: adding, renaming, destructive changes."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.test-helpers :as th]))


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


;; === Type widening ===

(deftest widen-scalar-to-jsonb-test
  (testing "widening a scalar column to :jsonb succeeds + preserves data"
    (let [storage (setup/create-test-storage)
          cuuid #uuid "00000000-0000-0000-0000-0000000000c0"
          nuuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (th/make-schema :fields {:name {:uuid nuuid :type :text}
                                           :count {:uuid cuuid :type :int}})
          _ (sp/initialize storage schema1)
          _ (sp/create-entity storage :user {:name "alice" :count 7})
          ;; Widen :count :int -> :jsonb (blessed as a safe widening).
          schema2 (th/make-schema :fields {:name {:uuid nuuid :type :text}
                                           :count {:uuid cuuid :type :jsonb}})]
      (try
        ;; Before the fix this emitted `ALTER ... USING count::jsonb`, which
        ;; PostgreSQL cannot cast from bigint -> the whole migration aborts.
        ;; `to_jsonb(count)` makes the widening actually work.
        (sp/initialize storage schema2)
        (let [rows (sp/query-entities storage :user {})]
          (is (= 1 (count rows)) "row survived the widening")
          (is (= 7 (:count (first rows))) "int value preserved through the jsonb widening"))
        (finally
          (sp/close storage))))))


(deftest drop-not-null-on-nullable-flip-test
  (testing "flipping a field NOT NULL → nullable drops the DB NOT NULL constraint"
    (let [storage (setup/create-test-storage)
          nuuid #uuid "00000000-0000-0000-0000-000000000002"
          euuid #uuid "00000000-0000-0000-0000-0000000000e0"
          ;; email starts NOT NULL (no :nullable? key → defaults to false).
          schema1 (th/make-schema :fields {:name  {:uuid nuuid :type :text}
                                           :email {:uuid euuid :type :text}})
          _ (sp/initialize storage schema1)
          ;; Flip email to nullable.
          schema2 (th/make-schema :fields {:name  {:uuid nuuid :type :text}
                                           :email {:uuid euuid :type :text :nullable? true}})]
      (try
        (sp/initialize storage schema2)
        ;; Before the fix the column stayed NOT NULL, so this nil write — which
        ;; the migrated schema now permits — failed at the DB. `DROP NOT NULL`
        ;; reconciles the column to the schema.
        (is (some? (sp/create-entity storage :user {:name "alice" :email nil}))
            "a nil write the schema now permits succeeds")
        (finally
          (sp/close storage))))))
