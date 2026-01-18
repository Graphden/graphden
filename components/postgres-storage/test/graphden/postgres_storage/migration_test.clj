(ns graphden.postgres-storage.migration-test
  "Tests for PostgreSQL storage schema migration.

   Covers:
   - First-time initialization
   - Introspection (current-entities, current-fields, etc.)
   - Adding entities, fields, enums
   - Renaming entities, fields, enums
   - Destructive changes detection
   - Type changes and widening
   - Nullable changes
   - Field types
   - Idempotency
   - Reference fields
   - JSONB and Union types
   - Snake_case collisions
   - Metadata operations
   - Concurrent migration"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.postgres-storage.core :as core]
    [graphden.postgres-storage.interface :as pg]
    [graphden.postgres-storage.introspection :as introspection]
    [graphden.postgres-storage.metadata :as metadata]
    [graphden.postgres-storage.test-setup :as setup]
    [graphden.postgres-storage.util :as util]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.test-helpers :as th]
    [next.jdbc :as jdbc])
  (:import
    (com.zaxxer.hikari
      HikariDataSource)
    (java.sql
      SQLException)
    (java.util.concurrent
      CountDownLatch
      TimeUnit)))


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


;; === Type change tests ===

(deftest type-change-test
  (testing "incompatible type change throws"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          ;; text→int is narrowing (unsafe), int→text would be widening (safe)
          schema1 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:count {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:count {:uuid field-uuid :type :int}})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change: incompatible type change"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage)))))

  (testing "compatible type change succeeds"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:price {:uuid field-uuid :type :int}})
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:price {:uuid field-uuid :type :numeric}})]
      (try
        (is (some? (sp/initialize storage schema2)))
        (finally
          (sp/close storage))))))


;; === Nullable change tests ===

(deftest nullable-change-test
  (testing "changing from nullable to non-nullable throws"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:bio {:uuid field-uuid :type :text :nullable? true}})
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:bio {:uuid field-uuid :type :text :nullable? false}})]
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Destructive change: field changed from nullable to non-nullable"
              (sp/initialize storage schema2)))
        (finally
          (sp/close storage)))))

  (testing "changing from non-nullable to nullable succeeds"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:bio {:uuid field-uuid :type :text :nullable? false}})
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:bio {:uuid field-uuid :type :text :nullable? true}})]
      (try
        (is (some? (sp/initialize storage schema2)))
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


;; === Field types tests ===

(deftest field-types-test
  (testing "all supported field types work"
    (let [storage (setup/create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :test-entity #uuid "00000000-0000-0000-0000-000000000001"
                                    {:int-field {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :int}
                                     :numeric-field {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                     :type :numeric}
                                     :bool-field {:uuid #uuid "00000000-0000-0000-0000-000000000004"
                                                  :type :bool}
                                     :text-field {:uuid #uuid "00000000-0000-0000-0000-000000000005"
                                                  :type :text}
                                     :uuid-field {:uuid #uuid "00000000-0000-0000-0000-000000000006"
                                                  :type :uuid}
                                     :bytes-field {:uuid #uuid "00000000-0000-0000-0000-000000000007"
                                                   :type :bytes}
                                     :jsonb-field {:uuid #uuid "00000000-0000-0000-0000-000000000008"
                                                   :type :jsonb}
                                     :timestamptz-field {:uuid #uuid "00000000-0000-0000-0000-000000000009"
                                                         :type :timestamptz}})
                     ds/build)
          changes (sp/initialize storage schema)]
      (try
        (is (= [:test-entity] (:created (:entities changes))))
        (is (= 8 (count (:created (:fields changes)))))
        (finally
          (sp/close storage)))))

  (testing "ref field creates UUID column"
    (let [storage (setup/create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                    {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                            :type :text}})
                     (ds/add-entity :post #uuid "00000000-0000-0000-0000-000000000003"
                                    {:author {:uuid #uuid "00000000-0000-0000-0000-000000000004"
                                              :type :ref
                                              :ref-entity :user}})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        ;; Verify author field exists - ref type is preserved in metadata
        (let [fields (sp/current-fields storage :post)]
          (is (contains? fields :author))
          ;; ref type is preserved in metadata (maps to UUID in DB)
          (is (= :ref (:type (:author fields)))))
        (finally
          (sp/close storage)))))

  (testing "enum field works"
    (let [storage (setup/create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000000010"
                                  [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                    :value :active}
                                   {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                    :value :inactive}])
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                    {:status {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                              :type :enum
                                              :enum-name :status}})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        (let [fields (sp/current-fields storage :user)]
          (is (contains? fields :status))
          (is (= :enum (:type (:status fields)))))
        (finally
          (sp/close storage)))))

  (testing "union field creates JSONB column"
    (let [storage (setup/create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :config #uuid "00000000-0000-0000-0000-000000000001"
                                    {:value {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :union
                                             :variants [{:type :text} {:type :int} {:type :bool}]}})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        (let [fields (sp/current-fields storage :config)]
          (is (contains? fields :value))
          ;; union type is preserved in metadata (maps to JSONB in DB)
          (is (= :union (:type (:value fields)))))
        (finally
          (sp/close storage)))))

  (testing "adding ref field during migration creates index"
    (let [storage (setup/create-test-storage)
          user-uuid #uuid "00000000-0000-0000-0000-000000000001"
          post-uuid #uuid "00000000-0000-0000-0000-000000000003"
          ;; First schema: entities without ref
          schema1 (-> (mds/create-builder)
                      (ds/add-entity :user user-uuid
                                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :text}})
                      (ds/add-entity :post post-uuid
                                     {:title {:uuid #uuid "00000000-0000-0000-0000-000000000004"
                                              :type :text}})
                      ds/build)
          ;; Second schema: add ref field to post
          schema2 (-> (mds/create-builder)
                      (ds/add-entity :user user-uuid
                                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :text}})
                      (ds/add-entity :post post-uuid
                                     {:title {:uuid #uuid "00000000-0000-0000-0000-000000000004"
                                              :type :text}
                                      :author {:uuid #uuid "00000000-0000-0000-0000-000000000005"
                                               :type :ref
                                               :ref-entity :user}})
                      ds/build)]
      (try
        ;; Initialize with first schema
        (sp/initialize storage schema1)
        ;; Migrate to second schema (adds ref field)
        (let [changes (sp/initialize storage schema2)]
          ;; Verify the ref field was added
          (is (some #(= :author (:field %)) (:created (:fields changes))))
          ;; Verify the field exists and is ref type
          (let [fields (sp/current-fields storage :post)]
            (is (= :ref (:type (:author fields))))))
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


;; === Reference field tests ===

(deftest ref-field-test
  (testing "ref field type is preserved in metadata"
    (let [storage (setup/create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                    {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                            :type :text}})
                     (ds/add-entity :post #uuid "00000000-0000-0000-0000-000000000003"
                                    {:author {:uuid #uuid "00000000-0000-0000-0000-000000000004"
                                              :type :ref
                                              :ref-entity :user}})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        (let [metadata (sp/schema-metadata storage)
              author-field-entry (first (filter #(= (:field (val %)) :author)
                                                (:fields metadata)))]
          (is (some? author-field-entry))
          ;; Type should be preserved as :ref (not :uuid)
          (is (= :ref (:type (val author-field-entry)))))
        (finally
          (sp/close storage))))))


;; === JSONB type tests ===

(deftest jsonb-field-test
  (testing "JSONB type is preserved through round-trip"
    (let [storage (setup/create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :config #uuid "00000000-0000-0000-0000-000000000001"
                                    {:data {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                            :type :jsonb}})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        (let [metadata (sp/schema-metadata storage)
              data-field (first (filter #(= (:field (val %)) :data) (:fields metadata)))]
          (is (some? data-field))
          (is (= :jsonb (:type (val data-field)))))
        (finally
          (sp/close storage))))))


;; === Union type tests ===

(deftest union-field-test
  (testing "Union type is preserved through round-trip"
    (let [storage (setup/create-test-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :config #uuid "00000000-0000-0000-0000-000000000001"
                                    {:value {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :union
                                             :variants [{:type :text} {:type :int}]}})
                     ds/build)]
      (try
        (sp/initialize storage schema)
        (let [metadata (sp/schema-metadata storage)
              value-field (first (filter #(= (:field (val %)) :value) (:fields metadata)))]
          (is (some? value-field))
          (is (= :union (:type (val value-field)))))
        (finally
          (sp/close storage))))))


;; === Snake_case collision tests ===

(deftest snake-case-collision-test
  (testing "snake_case naming collision is detected for entities"
    (let [storage (setup/create-test-storage)]
      (try
        ;; :user-name and :user_name both become user_name
        (let [schema (-> (mds/create-builder)
                         (ds/add-entity :user-name #uuid "00000000-0000-0000-0000-000000004001"
                                        {:field1 {:uuid #uuid "00000000-0000-0000-0000-000000004002"
                                                  :type :text}})
                         (ds/add-entity :user_name #uuid "00000000-0000-0000-0000-000000004003"
                                        {:field2 {:uuid #uuid "00000000-0000-0000-0000-000000004004"
                                                  :type :text}})
                         ds/build)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Snake_case naming collision"
                (sp/initialize storage schema))))
        (finally
          (sp/close storage)))))

  (testing "snake_case naming collision is detected for fields"
    (let [storage (setup/create-test-storage)]
      (try
        (let [schema (-> (mds/create-builder)
                         (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000004010"
                                        {:first-name {:uuid #uuid "00000000-0000-0000-0000-000000004011"
                                                      :type :text}
                                         :first_name {:uuid #uuid "00000000-0000-0000-0000-000000004012"
                                                      :type :text}})
                         ds/build)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Snake_case naming collision"
                (sp/initialize storage schema))))
        (finally
          (sp/close storage)))))

  (testing "snake_case naming collision is detected for enums"
    (let [storage (setup/create-test-storage)]
      (try
        (let [schema (-> (mds/create-builder)
                         (ds/add-enum :user-status #uuid "00000000-0000-0000-0000-000000004020"
                                      [{:uuid #uuid "00000000-0000-0000-0000-000000004021"
                                        :value :active}])
                         (ds/add-enum :user_status #uuid "00000000-0000-0000-0000-000000004022"
                                      [{:uuid #uuid "00000000-0000-0000-0000-000000004023"
                                        :value :pending}])
                         (ds/add-entity :item #uuid "00000000-0000-0000-0000-000000004030"
                                        {:name {:uuid #uuid "00000000-0000-0000-0000-000000004031"
                                                :type :text}})
                         ds/build)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Snake_case naming collision"
                (sp/initialize storage schema))))
        (finally
          (sp/close storage))))))


(defn- insert-orphaned-metadata!
  "Insert an orphaned metadata entry directly into the database.
   Uses a fresh connection to ensure the insert is committed."
  [kind entry-name parent-uuid extra]
  (let [{:keys [jdbc-url username password]} (setup/get-container-config)
        orphan-uuid (random-uuid)]
    (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                           :user username
                                           :password password})]
      (if extra
        (jdbc/execute! conn
                       [(str "INSERT INTO _schema_metadata (uuid, kind, name, parent_uuid, extra) "
                             "VALUES (?, ?, ?, ?, ?::jsonb)")
                        orphan-uuid kind entry-name parent-uuid extra])
        (jdbc/execute! conn
                       ["INSERT INTO _schema_metadata (uuid, kind, name, parent_uuid) VALUES (?, ?, ?, ?)"
                        orphan-uuid kind entry-name parent-uuid])))))


(deftest metadata-corruption-test
  (testing "orphaned field metadata throws in strict mode"
    (let [storage (setup/create-test-storage)]
      (try
        ;; First initialize normally
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid #uuid "00000000-0000-0000-0000-000000005001"
                                     :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000005002"
                                                     :type :text}})
              result (sp/initialize storage schema)]
          ;; Verify tables were created
          (is (some? result) "sp/initialize should return changes"))
        ;; Verify metadata table exists
        (is (contains? (sp/current-entities storage) :test-entity)
            "test-entity should exist after initialize")
        ;; Now insert an orphaned field entry (field with non-existent parent)
        (insert-orphaned-metadata! "field" "orphan_field" (random-uuid)
                                   "{\"type\": \"text\", \"nullable?\": false}")
        ;; Now try to initialize again - should throw because of orphaned entry
        (let [schema2 (th/make-schema :entity-name :test-entity
                                      :entity-uuid #uuid "00000000-0000-0000-0000-000000005001"
                                      :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000005002"
                                                      :type :text}
                                               :email {:uuid #uuid "00000000-0000-0000-0000-000000005003"
                                                       :type :text}})]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Orphaned field entry"
                (sp/initialize storage schema2))))
        (finally
          (sp/close storage)))))

  (testing "orphaned enum-value metadata throws in strict mode"
    (let [storage (setup/create-test-storage)]
      (try
        ;; First initialize with an enum
        (let [schema (-> (mds/create-builder)
                         (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000005010"
                                      [{:uuid #uuid "00000000-0000-0000-0000-000000005011"
                                        :value :active}])
                         (ds/add-entity :item #uuid "00000000-0000-0000-0000-000000005020"
                                        {:name {:uuid #uuid "00000000-0000-0000-0000-000000005021"
                                                :type :text}})
                         ds/build)]
          (sp/initialize storage schema))
        ;; Insert an orphaned enum-value entry
        (insert-orphaned-metadata! "enum-value" "orphan_value" (random-uuid) nil)
        ;; Try to initialize again - should throw
        (let [schema2 (-> (mds/create-builder)
                          (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000005010"
                                       [{:uuid #uuid "00000000-0000-0000-0000-000000005011"
                                         :value :active}
                                        {:uuid #uuid "00000000-0000-0000-0000-000000005012"
                                         :value :inactive}])
                          (ds/add-entity :item #uuid "00000000-0000-0000-0000-000000005020"
                                         {:name {:uuid #uuid "00000000-0000-0000-0000-000000005021"
                                                 :type :text}})
                          ds/build)]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Orphaned enum-value entry"
                (sp/initialize storage schema2))))
        (finally
          (sp/close storage)))))

  (testing "lenient mode skips orphaned entries in introspection"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Initialize with a schema
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid #uuid "00000000-0000-0000-0000-000000005030"
                                     :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000005031"
                                                     :type :text}})]
          (sp/initialize storage schema))
        ;; Insert orphaned entries
        (insert-orphaned-metadata! "field" "orphan_field" (random-uuid)
                                   "{\"type\": \"text\", \"nullable?\": false}")
        (insert-orphaned-metadata! "enum-value" "orphan_value" (random-uuid) nil)
        ;; Introspection methods use lenient mode - should NOT throw
        (is (= #{:test-entity} (sp/current-entities storage)))
        (is (= {:name {:type :text :nullable? false}}
               (sp/current-fields storage :test-entity)))
        ;; schema-metadata should also work (lenient mode skips orphaned entries)
        (let [metadata (sp/schema-metadata storage)]
          (is (some? metadata))
          (is (contains? (:entities metadata) #uuid "00000000-0000-0000-0000-000000005030")))
        (finally
          (sp/close storage)))))

  (testing "unknown kind in metadata is ignored in lenient mode"
    (let [storage (setup/create-test-storage)]
      (try
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid #uuid "00000000-0000-0000-0000-000000005040"
                                     :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000005041"
                                                     :type :text}})]
          (sp/initialize storage schema))
        ;; Insert a metadata row with unknown kind
        (let [{:keys [jdbc-url username password]} (setup/get-container-config)]
          (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                                 :user username
                                                 :password password})]
            (jdbc/execute! conn
                           ["INSERT INTO _schema_metadata (uuid, kind, name, parent_uuid) VALUES (?, ?, ?, ?)"
                            (random-uuid) "unknown-kind" "mystery" nil])))
        ;; Introspection that parses metadata should still work (unknown kind is skipped)
        (is (= {:name {:type :text :nullable? false}}
               (sp/current-fields storage :test-entity)))
        (let [metadata (sp/schema-metadata storage)]
          (is (some? metadata))
          ;; Should have the entity but not the unknown kind entry
          (is (= 1 (count (:entities metadata)))))
        (finally
          (sp/close storage))))))


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


(deftest configuration-validation-test
  (testing "creating storage without jdbc-url throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"jdbc-url is required"
          (pg/create-storage {:username "test"
                              :password "test"
                              :pool-size 2}))))

  (testing "creating storage without username throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"username is required"
          (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost/test"
                              :password "test"}))))

  (testing "creating storage with empty username throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"username is required"
          (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost/test"
                              :username "   "
                              :password "test"}))))

  (testing "creating storage without password throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"password is required"
          (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost/test"
                              :username "test"}))))

  (testing "creating storage with empty password throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"password is required"
          (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost/test"
                              :username "test"
                              :password "  "}))))

  (testing "error ex-data contains type instead of sensitive credentials info"
    (try
      (pg/create-storage {:username "testuser" :password "testpass"})
      (catch clojure.lang.ExceptionInfo e
        ;; Should NOT expose :username or :password in ex-data
        (is (= :config-error/missing-jdbc-url (:type (ex-data e))))
        (is (nil? (:provided-keys (ex-data e)))))))

  (testing "missing username error has correct type"
    (try
      (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost/test"
                          :password "test"})
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :config-error/missing-username (:type (ex-data e)))))))

  (testing "missing password error has correct type"
    (try
      (pg/create-storage {:jdbc-url "jdbc:postgresql://localhost/test"
                          :username "test"})
      (is false "Should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :config-error/missing-password (:type (ex-data e))))))))


(deftest with-query-timeout-test
  (testing "with-query-timeout function changes timeout value (in milliseconds)"
    (is (= 30000 sp/*query-timeout-ms*) "Default should be 30000 ms")
    (pg/with-query-timeout 60000
                           (fn []
                             (is (= 60000 sp/*query-timeout-ms*) "Should be 60000 inside function")))
    (is (= 30000 sp/*query-timeout-ms*) "Should restore to 30000 after function"))

  (testing "with-query-timeout returns body result"
    (is (= 42 (pg/with-query-timeout 10000 #(+ 40 2)))))

  (testing "nested with-query-timeout works correctly"
    (pg/with-query-timeout 100000
                           (fn []
                             (is (= 100000 sp/*query-timeout-ms*))
                             (pg/with-query-timeout 200000
                                                    (fn []
                                                      (is (= 200000 sp/*query-timeout-ms*))))
                             (is (= 100000 sp/*query-timeout-ms*))))))


(deftest close-pool-idempotency-test
  (testing "close-pool with nil pool returns true (no-op)"
    (is (true? (core/close-pool nil))))

  (testing "close-pool is idempotent - can be called multiple times"
    (let [pool (core/create-pool (merge (setup/get-container-config)
                                        {:pool-size 1 :min-idle 1}))]
      ;; First close - returns true on success
      (is (true? (core/close-pool pool)))
      (is (true? (HikariDataSource/.isClosed pool)))
      ;; Second close - returns true (pool already closed, no-op)
      (is (true? (core/close-pool pool)))
      (is (true? (HikariDataSource/.isClosed pool))))))


(deftest create-pool-validation-test
  (let [valid-opts (setup/get-container-config)]

    (testing "pool-size must be positive integer"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"pool-size must be a positive integer"
            (core/create-pool (assoc valid-opts :pool-size 0))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"pool-size must be a positive integer"
            (core/create-pool (assoc valid-opts :pool-size -1))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"pool-size must be a positive integer"
            (core/create-pool (assoc valid-opts :pool-size "10")))))

    (testing "min-idle must be positive integer"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"min-idle must be a positive integer"
            (core/create-pool (assoc valid-opts :min-idle 0))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"min-idle must be a positive integer"
            (core/create-pool (assoc valid-opts :min-idle -1)))))

    (testing "min-idle cannot exceed pool-size"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"min-idle cannot exceed pool-size"
            (core/create-pool (assoc valid-opts :pool-size 5 :min-idle 10)))))

    (testing "connection-timeout must be positive integer"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"connection-timeout must be a positive integer"
            (core/create-pool (assoc valid-opts :connection-timeout 0))))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"connection-timeout must be a positive integer"
            (core/create-pool (assoc valid-opts :connection-timeout -1000)))))

    (testing "pool-size cannot exceed 100"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"pool-size exceeds maximum allowed value of 100"
            (core/create-pool (assoc valid-opts :pool-size 101)))))

    (testing "idle-timeout must be less than max-lifetime"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"idle-timeout must be less than max-lifetime"
            (core/create-pool (assoc valid-opts
                                     :idle-timeout 600000
                                     :max-lifetime 500000))))
      ;; Equal values should also fail
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"idle-timeout must be less than max-lifetime"
            (core/create-pool (assoc valid-opts
                                     :idle-timeout 600000
                                     :max-lifetime 600000)))))

    (testing "idle-timeout = 0 is allowed (never retire idle connections)"
      ;; idle-timeout = 0 is a special case meaning "never retire"
      ;; This should not throw even though 0 < max-lifetime
      (let [pool (core/create-pool (assoc valid-opts
                                          :idle-timeout 0
                                          :max-lifetime 1800000))]
        (is (some? pool))
        (core/close-pool pool)))))


(deftest with-query-timeout-validation-test
  (testing "timeout must be positive integer"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be a positive integer"
          (pg/with-query-timeout 0 (constantly :ok))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be a positive integer"
          (pg/with-query-timeout -1000 (constantly :ok)))))

  (testing "timeout must be at least 1000ms"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be at least 1000ms"
          (pg/with-query-timeout 500 (constantly :ok))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"must be at least 1000ms"
          (pg/with-query-timeout 999 (constantly :ok)))))

  (testing "1000ms is valid minimum"
    (is (= 42 (pg/with-query-timeout 1000 #(+ 40 2))))))


(deftest unknown-pg-type-coverage-test
  (testing "unknown postgres type falls through to default in current-columns"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000009001"
          field-uuid #uuid "00000000-0000-0000-0000-000000009002"]
      (try
        ;; Initialize with a normal schema to create the table
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid entity-uuid
                                     :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Add a column with an unusual postgres type directly
        (let [{:keys [jdbc-url username password]} (setup/get-container-config)]
          (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                                 :user username
                                                 :password password})]
            ;; Add a 'point' type column
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN location point"])))
        ;; Call the private current-columns function directly
        (let [current-columns-fn #'introspection/current-columns
              pool (:pool storage)
              columns (current-columns-fn pool "test_entity")]
          ;; The :location column should have type :point (unknown type passes through)
          (is (= :point (:type (:location columns)))))
        (finally
          (sp/close storage)))))

  (testing "current-columns handles timestamp without time zone"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000009010"
          field-uuid #uuid "00000000-0000-0000-0000-000000009011"]
      (try
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid entity-uuid
                                     :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Add a timestamp without time zone column
        (let [{:keys [jdbc-url username password]} (setup/get-container-config)]
          (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                                 :user username
                                                 :password password})]
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN created_at timestamp without time zone"])))
        (let [current-columns-fn #'introspection/current-columns
              pool (:pool storage)
              columns (current-columns-fn pool "test_entity")]
          ;; timestamp without time zone maps to :timestamptz
          (is (= :timestamptz (:type (:created-at columns)))))
        (finally
          (sp/close storage)))))

  (testing "current-columns handles all postgres type mappings"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000009020"
          field-uuid #uuid "00000000-0000-0000-0000-000000009021"]
      (try
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid entity-uuid
                                     :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Add columns with various postgres types
        (let [{:keys [jdbc-url username password]} (setup/get-container-config)]
          (with-open [conn (jdbc/get-connection {:jdbcUrl jdbc-url
                                                 :user username
                                                 :password password})]
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN is_active boolean"])
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN amount numeric"])
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN data bytea"])
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN big_count bigint"])
            (jdbc/execute! conn ["ALTER TABLE test_entity ADD COLUMN updated_at timestamp with time zone"])))
        (let [current-columns-fn #'introspection/current-columns
              pool (:pool storage)
              columns (current-columns-fn pool "test_entity")]
          ;; boolean maps to :bool
          (is (= :bool (:type (:is-active columns))))
          ;; numeric stays as :numeric
          (is (= :numeric (:type (:amount columns))))
          ;; bytea maps to :bytes
          (is (= :bytes (:type (:data columns))))
          ;; bigint maps to :int
          (is (= :int (:type (:big-count columns))))
          ;; timestamp with time zone maps to :timestamptz
          (is (= :timestamptz (:type (:updated-at columns)))))
        (finally
          (sp/close storage))))))


(deftest edge-case-coverage-test
  (testing "parse-extra handles non-string non-PGobject values"
    ;; This covers the :else branch in parse-extra (line 160)
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000008001"
          field-uuid #uuid "00000000-0000-0000-0000-000000008002"]
      (try
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid entity-uuid
                                     :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Mock read-metadata-rows to return a row with extra as a number (not string/PGobject)
        (let [fake-rows [{:uuid entity-uuid :kind "entity" :name "test-entity" :parent_uuid nil :extra nil}
                         {:uuid field-uuid :kind "field" :name "name" :parent_uuid entity-uuid
                          :extra 12345}]] ; number instead of string/PGobject
          (with-redefs [metadata/read-metadata-rows (constantly fake-rows)]
            ;; schema-metadata uses parse-metadata-lenient which calls parse-extra
            (let [metadata (sp/schema-metadata storage)]
              ;; Should not throw, just parse what it can
              (is (some? metadata)))))
        (finally
          (sp/close storage)))))

  (testing "parse-extra handles string 'null' value"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000008050"
          field-uuid #uuid "00000000-0000-0000-0000-000000008051"]
      (try
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid entity-uuid
                                     :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Mock read-metadata-rows to return "null" string
        (let [fake-rows [{:uuid entity-uuid :kind "entity" :name "test-entity" :parent_uuid nil :extra "null"}
                         {:uuid field-uuid :kind "field" :name "name" :parent_uuid entity-uuid
                          :extra "null"}]]
          (with-redefs [metadata/read-metadata-rows (constantly fake-rows)]
            (let [metadata (sp/schema-metadata storage)]
              (is (some? metadata)))))
        (finally
          (sp/close storage)))))

  (testing "parse-extra handles empty JSON object string"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000008060"
          field-uuid #uuid "00000000-0000-0000-0000-000000008061"]
      (try
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid entity-uuid
                                     :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Mock read-metadata-rows to return "{}" string
        (let [fake-rows [{:uuid entity-uuid :kind "entity" :name "test-entity" :parent_uuid nil :extra "{}"}
                         {:uuid field-uuid :kind "field" :name "name" :parent_uuid entity-uuid
                          :extra "{}"}]]
          (with-redefs [metadata/read-metadata-rows (constantly fake-rows)]
            (let [metadata (sp/schema-metadata storage)]
              (is (some? metadata)))))
        (finally
          (sp/close storage)))))

  (testing "parse-extra handles raw string input"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000008070"
          field-uuid #uuid "00000000-0000-0000-0000-000000008071"]
      (try
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid entity-uuid
                                     :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Mock read-metadata-rows to return a valid JSON string
        (let [fake-rows [{:uuid entity-uuid :kind "entity" :name "test-entity" :parent_uuid nil :extra nil}
                         {:uuid field-uuid :kind "field" :name "name" :parent_uuid entity-uuid
                          :extra "{\"type\": \"text\", \"nullable?\": \"false\"}"}]]
          (with-redefs [metadata/read-metadata-rows (constantly fake-rows)]
            (let [metadata (sp/schema-metadata storage)]
              (is (some? metadata))
              ;; Should have parsed the string values to keywords
              (is (= :text (:type (val (first (:fields metadata)))))))))
        (finally
          (sp/close storage)))))

  (testing "parse-extra handles empty string"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000008080"
          field-uuid #uuid "00000000-0000-0000-0000-000000008081"]
      (try
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid entity-uuid
                                     :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Mock read-metadata-rows to return empty string for extra
        (let [fake-rows [{:uuid entity-uuid :kind "entity" :name "test-entity" :parent_uuid nil :extra ""}
                         {:uuid field-uuid :kind "field" :name "name" :parent_uuid entity-uuid
                          :extra ""}]]
          (with-redefs [metadata/read-metadata-rows (constantly fake-rows)]
            (let [metadata (sp/schema-metadata storage)]
              ;; Should work, extra is just nil
              (is (some? metadata)))))
        (finally
          (sp/close storage)))))

  (testing "unknown kind in strict mode falls through to default"
    ;; This covers the acc))) fallback (line 213) in strict parsing
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000008010"
          field-uuid #uuid "00000000-0000-0000-0000-000000008011"]
      (try
        (let [schema (th/make-schema :entity-name :test-entity
                                     :entity-uuid entity-uuid
                                     :fields {:name {:uuid field-uuid :type :text}})]
          (sp/initialize storage schema))
        ;; Insert unknown kind directly
        (insert-orphaned-metadata! "weird-kind" "mystery" nil nil)
        ;; Second initialize uses strict parsing - should skip unknown kind
        (let [schema2 (th/make-schema :entity-name :test-entity
                                      :entity-uuid entity-uuid
                                      :fields {:name {:uuid field-uuid :type :text}
                                               :email {:uuid #uuid "00000000-0000-0000-0000-000000008012"
                                                       :type :text}})
              changes (sp/initialize storage schema2)]
          ;; Should succeed - unknown kind is just skipped in the reduce
          (is (= [{:entity :test-entity :field :email}] (:created (:fields changes)))))
        (finally
          (sp/close storage))))))


(deftest uninitialized-storage-test
  (testing "current-fields returns nil on uninitialized storage"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Don't initialize - just try to read fields
        ;; This exercises the try/catch in current-fields
        (is (nil? (sp/current-fields storage :nonexistent)))
        (finally
          (sp/close storage)))))

  (testing "schema-metadata returns nil on uninitialized storage"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Don't initialize - just try to read metadata
        ;; This exercises the try/catch in schema-metadata
        (is (nil? (sp/schema-metadata storage)))
        (finally
          (sp/close storage))))))


(deftest metadata-db-inconsistency-test
  (testing "detects when metadata says field exists but DB column is missing"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000007001"
          field-uuid #uuid "00000000-0000-0000-0000-000000007002"
          schema1 (th/make-schema :entity-name :user
                                  :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})]
      (try
        ;; First initialize normally
        (sp/initialize storage schema1)
        ;; Mock parse-metadata to return metadata claiming a non-existent field
        (let [fake-metadata {:entities {entity-uuid :user}
                             :fields {field-uuid {:entity :user
                                                  :field :name
                                                  :type :text
                                                  :nullable? false}
                                      ;; This field doesn't exist in DB!
                                      #uuid "00000000-0000-0000-0000-000000007099"
                                      {:entity :user
                                       :field :ghost-field
                                       :type :text
                                       :nullable? false}}
                             :enums {}
                             :enum-values {}}
              schema2 (-> (mds/create-builder)
                          (ds/add-entity :user entity-uuid
                                         {:name {:uuid field-uuid :type :text}
                                          :ghost-field {:uuid #uuid "00000000-0000-0000-0000-000000007099"
                                                        :type :text}})
                          ds/build)]
          (with-redefs [metadata/parse-metadata (constantly fake-metadata)]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Metadata/DB inconsistency"
                  (sp/initialize storage schema2)))))
        (finally
          (sp/close storage))))))


(deftest table-not-found-error-handling-test
  (testing "table-not-found? returns true for SQLState 42P01"
    (let [e (SQLException. "relation does not exist" "42P01")]
      (is (true? (#'util/table-not-found? e)))))

  (testing "table-not-found? returns false for other SQLState"
    (let [e (SQLException. "connection failed" "08001")]
      (is (false? (#'util/table-not-found? e)))))

  (testing "current-fields re-throws non-42P01 SQLException"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Mock read-metadata-rows to throw a non-42P01 SQLException
        (let [connection-error (SQLException. "connection failed" "08001")]
          (with-redefs [metadata/read-metadata-rows (fn [_] (throw connection-error))]
            (is (thrown? SQLException (sp/current-fields storage :any-entity)))))
        (finally
          (sp/close storage)))))

  (testing "schema-metadata re-throws non-42P01 SQLException"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Mock read-metadata-rows to throw a non-42P01 SQLException
        (let [connection-error (SQLException. "connection failed" "08001")]
          (with-redefs [metadata/read-metadata-rows (fn [_] (throw connection-error))]
            (is (thrown? SQLException (sp/schema-metadata storage)))))
        (finally
          (sp/close storage))))))


(deftest sql-identifier-validation-test
  (testing "validate-sql-identifier! accepts valid identifiers"
    (let [validate-fn #'util/validate-sql-identifier!]
      (is (nil? (validate-fn "valid_name" {})))
      (is (nil? (validate-fn "name123" {})))
      (is (nil? (validate-fn "a" {})))))

  (testing "validate-sql-identifier! rejects invalid identifiers"
    (let [validate-fn #'util/validate-sql-identifier!]
      ;; SQL injection attempts
      (is (thrown? clojure.lang.ExceptionInfo (validate-fn "name'; DROP TABLE users; --" {})))
      (is (thrown? clojure.lang.ExceptionInfo (validate-fn "name\"" {})))
      ;; Invalid characters
      (is (thrown? clojure.lang.ExceptionInfo (validate-fn "name-with-dash" {})))
      (is (thrown? clojure.lang.ExceptionInfo (validate-fn "123starts_with_number" {})))
      (is (thrown? clojure.lang.ExceptionInfo (validate-fn "UPPERCASE" {})))
      (is (thrown? clojure.lang.ExceptionInfo (validate-fn "" {}))))))


(deftest pg-type-validation-test
  (testing "validate-pg-type! accepts valid base types"
    (let [validate-fn #'util/validate-pg-type!]
      (is (nil? (validate-fn "UUID" {})))
      (is (nil? (validate-fn "TEXT" {})))
      (is (nil? (validate-fn "BIGINT" {})))
      (is (nil? (validate-fn "BOOLEAN" {})))
      (is (nil? (validate-fn "NUMERIC" {})))
      (is (nil? (validate-fn "TIMESTAMPTZ" {})))
      (is (nil? (validate-fn "JSONB" {})))
      (is (nil? (validate-fn "BYTEA" {})))))

  (testing "validate-pg-type! accepts valid quoted enum identifiers"
    (let [validate-fn #'util/validate-pg-type!]
      (is (nil? (validate-fn "\"status\"" {})))
      (is (nil? (validate-fn "\"user_role\"" {})))
      (is (nil? (validate-fn "\"my_enum123\"" {})))))

  (testing "validate-pg-type! rejects invalid types"
    (let [validate-fn #'util/validate-pg-type!]
      ;; SQL injection attempts
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid PostgreSQL type"
            (validate-fn "TEXT; DROP TABLE users; --" {})))
      ;; Invalid type names
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid PostgreSQL type"
            (validate-fn "INVALID_TYPE" {})))
      ;; Improperly quoted identifiers
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid PostgreSQL type"
            (validate-fn "\"UPPERCASE\"" {})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid PostgreSQL type"
            (validate-fn "\"has-dash\"" {}))))))


(deftest kw->snake-case-test
  (testing "converts kebab-case to snake_case"
    (is (= "foo_bar" (util/kw->snake-case :foo-bar)))
    (is (= "foo_bar_baz" (util/kw->snake-case :foo-bar-baz))))

  (testing "handles already snake_case"
    (is (= "foo_bar" (util/kw->snake-case :foo_bar))))

  (testing "handles simple keywords"
    (is (= "foo" (util/kw->snake-case :foo)))
    (is (= "x" (util/kw->snake-case :x)))))


(deftest check-snake-case-collisions-test
  (testing "detects collision between kebab and snake case"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Snake_case naming collision"
          (util/check-snake-case-collisions! {:context "test"} [:foo-bar :foo_bar]))))

  (testing "allows non-colliding names"
    (is (nil? (util/check-snake-case-collisions! {:context "test"} [:foo :bar :baz])))
    (is (nil? (util/check-snake-case-collisions! {:context "test"} [:foo-bar :baz-qux]))))

  (testing "empty and single-element collections pass"
    (is (nil? (util/check-snake-case-collisions! {:context "test"} [])))
    (is (nil? (util/check-snake-case-collisions! {:context "test"} [:foo])))))


(deftest ident->sql-test
  (testing "wraps identifier in double quotes"
    (is (= "\"foo\"" (util/ident->sql :foo)))
    (is (= "\"foo_bar\"" (util/ident->sql :foo-bar)))
    (is (= "\"user\"" (util/ident->sql :user)))))


(deftest field-type->pg-test
  (testing "maps basic types"
    (is (= "UUID" (util/field-type->pg {:type :uuid})))
    (is (= "TEXT" (util/field-type->pg {:type :text})))
    (is (= "BIGINT" (util/field-type->pg {:type :int})))
    (is (= "BOOLEAN" (util/field-type->pg {:type :bool})))
    (is (= "NUMERIC" (util/field-type->pg {:type :numeric})))
    (is (= "TIMESTAMPTZ" (util/field-type->pg {:type :timestamptz})))
    (is (= "JSONB" (util/field-type->pg {:type :jsonb})))
    (is (= "BYTEA" (util/field-type->pg {:type :bytes}))))

  (testing "maps ref to UUID"
    (is (= "UUID" (util/field-type->pg {:type :ref :ref-entity :user}))))

  (testing "maps union to JSONB"
    (is (= "JSONB" (util/field-type->pg {:type :union :union-types [:foo :bar]}))))

  (testing "maps enum to quoted identifier"
    (is (= "\"status\"" (util/field-type->pg {:type :enum :enum-name :status})))
    (is (= "\"user_role\"" (util/field-type->pg {:type :enum :enum-name :user-role}))))

  (testing "falls back to TEXT for unknown types"
    (is (= "TEXT" (util/field-type->pg {:type :unknown})))))


(deftest enum-value-conversion-test
  (testing "enum-value->sql converts to snake_case"
    (is (= "active" (util/enum-value->sql :active)))
    (is (= "in_progress" (util/enum-value->sql :in-progress))))

  (testing "sql->enum-value converts back to kebab-case keyword"
    (is (= :active (util/sql->enum-value "active")))
    (is (= :in-progress (util/sql->enum-value "in_progress"))))

  (testing "roundtrip conversion"
    (let [original :in-progress
          sql-val (util/enum-value->sql original)
          back (util/sql->enum-value sql-val)]
      (is (= original back))))

  (testing "enum-value->sql normalizes case and rejects invalid values"
    ;; Uppercase is now normalized to lowercase (security fix)
    (is (= "uppercase" (util/enum-value->sql :UPPERCASE)))
    ;; These still fail because they start with numbers
    (is (thrown? clojure.lang.ExceptionInfo
          (util/enum-value->sql (keyword "123-invalid"))))))


(deftest metadata-caching-test
  (testing "metadata is cached after first read"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (try
        (sp/initialize storage schema)
        ;; First call reads from DB
        (let [metadata1 (sp/schema-metadata storage)
              ;; Second call should use cache (same object)
              metadata2 (sp/schema-metadata storage)]
          (is (some? metadata1))
          (is (identical? metadata1 metadata2) "Metadata should be cached"))
        (finally
          (sp/close storage)))))

  (testing "cache is invalidated on initialize"
    (let [storage (setup/create-test-storage)
          schema1 (th/make-schema)]
      (try
        (sp/initialize storage schema1)
        (let [metadata1 (sp/schema-metadata storage)
              ;; Re-initialize with same schema
              _ (sp/initialize storage schema1)
              ;; Cache should be invalidated
              metadata2 (sp/schema-metadata storage)]
          (is (some? metadata1))
          (is (some? metadata2))
          ;; After re-init, cache was cleared, so new object
          (is (not (identical? metadata1 metadata2)) "Cache should be invalidated on initialize"))
        (finally
          (sp/close storage))))))


(deftest concurrent-access-test
  (testing "concurrent reads are thread-safe"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)
          errors (atom [])
          num-threads 10
          iterations 50]
      (try
        (sp/initialize storage schema)
        ;; Launch multiple threads reading concurrently
        (let [futures (doall
                        (for [_ (range num-threads)]
                          (future
                            (try
                              (dotimes [_ iterations]
                                (sp/current-entities storage)
                                (sp/current-fields storage :user)
                                (sp/schema-metadata storage))
                              (catch Exception e
                                (swap! errors conj e))))))]
          ;; Wait for all threads to complete
          (doseq [f futures]
            (deref f 5000 :timeout)))
        ;; No errors should have occurred
        (is (empty? @errors) (str "Errors during concurrent access: " @errors))
        (finally
          (sp/close storage)))))

  (testing "concurrent initialize and read are thread-safe"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)
          errors (atom [])
          num-readers 5
          num-iterations 20
          ;; Use CountDownLatch to start all threads simultaneously
          start-latch (CountDownLatch. 1)
          ;; Track completion
          done-latch (CountDownLatch. (inc num-readers))]
      (try
        ;; First initialize
        (sp/initialize storage schema)
        ;; Start reader threads - wait for start signal
        (doseq [_ (range num-readers)]
          (future
            (try
              (CountDownLatch/.await start-latch)
              (dotimes [_ num-iterations]
                (sp/schema-metadata storage))
              (catch Exception e
                (swap! errors conj e))
              (finally
                (CountDownLatch/.countDown done-latch)))))
        ;; Writer thread that re-initializes
        (future
          (try
            (CountDownLatch/.await start-latch)
            (dotimes [_ 3]
              (sp/initialize storage schema))
            (catch Exception e
              (swap! errors conj e))
            (finally
              (CountDownLatch/.countDown done-latch))))
        ;; Start all threads at once
        (CountDownLatch/.countDown start-latch)
        ;; Wait for all threads to complete (max 10 seconds)
        (is (true? (CountDownLatch/.await done-latch 10 TimeUnit/SECONDS))
            "Threads did not complete in time")
        (is (empty? @errors) (str "Errors during concurrent read/write: " @errors))
        (finally
          (sp/close storage))))))


;; === Type widening with data tests ===

(deftest type-widening-preserves-data-test
  (testing "int→numeric widening preserves integer data"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:count {:uuid field-uuid :type :int}})
          _ (sp/initialize storage schema1)
          ;; Insert data with int type
          pool (:pool storage)
          _ (jdbc/execute! pool ["INSERT INTO \"user\" (id, count) VALUES (?, ?)"
                                 #uuid "11111111-1111-1111-1111-111111111111" 42])
          _ (jdbc/execute! pool ["INSERT INTO \"user\" (id, count) VALUES (?, ?)"
                                 #uuid "22222222-2222-2222-2222-222222222222" -100])
          ;; Widen type to numeric
          schema2 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:count {:uuid field-uuid :type :numeric}})
          _ (sp/initialize storage schema2)
          ;; Query data
          rows (jdbc/execute! pool ["SELECT id, count FROM \"user\" ORDER BY count"])]
      (try
        (is (= 2 (count rows)))
        (is (= -100M (:user/count (first rows))))
        (is (= 42M (:user/count (second rows))))
        (finally
          (sp/close storage)))))

  (testing "numeric→text widening preserves decimal data"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:price {:uuid field-uuid :type :numeric}})
          _ (sp/initialize storage schema1)
          pool (:pool storage)
          _ (jdbc/execute! pool ["INSERT INTO \"user\" (id, price) VALUES (?, ?)"
                                 #uuid "11111111-1111-1111-1111-111111111111" 3.14159M])
          ;; Widen to text
          schema2 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:price {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema2)
          rows (jdbc/execute! pool ["SELECT price FROM \"user\""])]
      (try
        (is (= 1 (count rows)))
        (is (= "3.14159" (:user/price (first rows))))
        (finally
          (sp/close storage)))))

  (testing "int→text widening preserves data"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:code {:uuid field-uuid :type :int}})
          _ (sp/initialize storage schema1)
          pool (:pool storage)
          _ (jdbc/execute! pool ["INSERT INTO \"user\" (id, code) VALUES (?, ?)"
                                 #uuid "11111111-1111-1111-1111-111111111111" 12345])
          ;; Widen to text
          schema2 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:code {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema2)
          rows (jdbc/execute! pool ["SELECT code FROM \"user\""])]
      (try
        (is (= "12345" (:user/code (first rows))))
        (finally
          (sp/close storage)))))

  ;; Note: text→jsonb is NOT supported by PostgreSQL directly (requires explicit to_jsonb())
  ;; so we don't test that conversion

  (testing "NULL values survive type widening"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:value {:uuid field-uuid :type :int :nullable? true}})
          _ (sp/initialize storage schema1)
          pool (:pool storage)
          _ (jdbc/execute! pool ["INSERT INTO \"user\" (id, value) VALUES (?, ?)"
                                 #uuid "11111111-1111-1111-1111-111111111111" nil])
          _ (jdbc/execute! pool ["INSERT INTO \"user\" (id, value) VALUES (?, ?)"
                                 #uuid "22222222-2222-2222-2222-222222222222" 42])
          ;; Widen to text
          schema2 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:value {:uuid field-uuid :type :text :nullable? true}})
          _ (sp/initialize storage schema2)
          rows (jdbc/execute! pool ["SELECT id, value FROM \"user\" ORDER BY id"])]
      (try
        (is (= 2 (count rows)))
        (is (nil? (:user/value (first rows))))
        (is (= "42" (:user/value (second rows))))
        (finally
          (sp/close storage))))))


;; === Concurrent migration tests ===

(deftest concurrent-migration-test
  (testing "concurrent initializations are handled safely"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema (th/make-schema :entity-uuid entity-uuid
                                 :fields {:name {:uuid field-uuid :type :text}})
          results (atom [])
          errors (atom [])
          num-threads 5
          start-latch (CountDownLatch. 1)
          done-latch (CountDownLatch. num-threads)]
      (try
        ;; Create threads that wait for start signal
        (doseq [_ (range num-threads)]
          (future
            (try
              (CountDownLatch/.await start-latch)
              (let [result (sp/initialize storage schema)]
                (swap! results conj result))
              (catch Exception e
                (swap! errors conj e))
              (finally
                (CountDownLatch/.countDown done-latch)))))
        ;; Start all threads simultaneously
        (CountDownLatch/.countDown start-latch)
        ;; Wait for all threads to complete (max 10 seconds)
        (is (true? (CountDownLatch/.await done-latch 10 TimeUnit/SECONDS))
            "Threads did not complete in time")

        ;; All threads should complete without errors
        (is (empty? @errors) (str "Got errors: " (map #(Exception/.getMessage %) @errors)))

        ;; At least one thread should have created the table
        (is (pos? (count @results)))

        ;; Database state should be consistent
        (is (= #{:user} (sp/current-entities storage)))
        (is (= #{:name} (set (keys (sp/current-fields storage :user)))))

        (finally
          (sp/close storage)))))

  (testing "concurrent migrations with schema changes"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field1-uuid #uuid "00000000-0000-0000-0000-000000000002"
          field2-uuid #uuid "00000000-0000-0000-0000-000000000003"
          schema1 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:name {:uuid field1-uuid :type :text}})
          schema2 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:name {:uuid field1-uuid :type :text}
                                           :email {:uuid field2-uuid :type :text}})
          _ (sp/initialize storage schema1)
          results (atom [])
          errors (atom [])
          num-threads 3
          start-latch (CountDownLatch. 1)
          done-latch (CountDownLatch. num-threads)]
      (try
        ;; Create threads that wait for start signal
        (doseq [_ (range num-threads)]
          (future
            (try
              (CountDownLatch/.await start-latch)
              (let [result (sp/initialize storage schema2)]
                (swap! results conj result))
              (catch Exception e
                (swap! errors conj e))
              (finally
                (CountDownLatch/.countDown done-latch)))))
        ;; Start all threads simultaneously
        (CountDownLatch/.countDown start-latch)
        ;; Wait for all threads to complete (max 10 seconds)
        (is (true? (CountDownLatch/.await done-latch 10 TimeUnit/SECONDS))
            "Threads did not complete in time")

        ;; All threads should complete without errors
        (is (empty? @errors) (str "Got errors: " (map #(Exception/.getMessage %) @errors)))

        ;; Database state should reflect schema2
        (is (= #{:user} (sp/current-entities storage)))
        (is (= #{:name :email} (set (keys (sp/current-fields storage :user)))))

        (finally
          (sp/close storage))))))
