(ns graphden.storage.protocol.types-test
  "Tests for storage-protocol.
   Tests the helper functions directly.
   Contract tests for Storage/StorageIntrospection protocols
   will be added when implementations exist."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.protocol.core :as storage]))


(deftest types-equivalent?-test
  (testing "uuid and ref are equivalent"
    (is (storage/types-equivalent? :uuid :ref))
    (is (storage/types-equivalent? :ref :uuid)))

  (testing "jsonb and union are equivalent"
    (is (storage/types-equivalent? :jsonb :union))
    (is (storage/types-equivalent? :union :jsonb)))

  (testing "non-equivalent types return nil"
    (is (nil? (storage/types-equivalent? :text :int)))
    (is (nil? (storage/types-equivalent? :uuid :text)))))


(deftest safe-type-change?-test
  (testing "same type is always safe"
    (is (storage/safe-type-change? :int :int))
    (is (storage/safe-type-change? :text :text))
    (is (storage/safe-type-change? :bool :bool))
    (is (storage/safe-type-change? :uuid :uuid))
    (is (storage/safe-type-change? :jsonb :jsonb))
    (is (storage/safe-type-change? :bytes :bytes)))

  (testing "equivalent types are safe"
    (is (storage/safe-type-change? :uuid :ref))
    (is (storage/safe-type-change? :ref :uuid))
    (is (storage/safe-type-change? :jsonb :union))
    (is (storage/safe-type-change? :union :jsonb)))

  (testing "widening is safe"
    (is (storage/safe-type-change? :int :numeric))
    (is (storage/safe-type-change? :int :text))
    (is (storage/safe-type-change? :int :jsonb))
    (is (storage/safe-type-change? :bool :text))
    (is (storage/safe-type-change? :bool :jsonb))
    (is (storage/safe-type-change? :numeric :text))
    (is (storage/safe-type-change? :numeric :jsonb))
    (is (storage/safe-type-change? :text :jsonb))
    (is (storage/safe-type-change? :uuid :text))
    (is (storage/safe-type-change? :timestamptz :text)))

  (testing "narrowing is not safe"
    (is (not (storage/safe-type-change? :text :int)))
    (is (not (storage/safe-type-change? :numeric :int)))
    (is (not (storage/safe-type-change? :jsonb :text)))
    (is (not (storage/safe-type-change? :text :bool)))
    (is (not (storage/safe-type-change? :text :uuid))))

  (testing "unrelated types are not safe"
    (is (not (storage/safe-type-change? :bool :int)))
    (is (not (storage/safe-type-change? :uuid :int)))
    (is (not (storage/safe-type-change? :timestamptz :int)))
    (is (not (storage/safe-type-change? :bytes :text)))))


;; === Protocol existence tests ===
;; These just verify the protocols are defined correctly

;; === Nullable change tests ===

(deftest safe-nullable-change?-test
  (testing "same value is safe"
    (is (storage/safe-nullable-change? true true))
    (is (storage/safe-nullable-change? false false)))

  (testing "false→true is safe (allowing more)"
    (is (storage/safe-nullable-change? false true)))

  (testing "true→false is unsafe (restricting)"
    (is (not (storage/safe-nullable-change? true false)))))


(deftest check-type-change!-test
  (testing "nil old-type doesn't throw (new field)"
    (is (nil? (storage/check-type-change! :user :name nil :text))))

  (testing "safe changes don't throw"
    (is (nil? (storage/check-type-change! :user :name :text :text)))
    (is (nil? (storage/check-type-change! :user :name :int :numeric)))
    (is (nil? (storage/check-type-change! :user :name :uuid :ref))))

  (testing "unsafe change throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"incompatible type change"
          (storage/check-type-change! :user :name :text :int))))

  (testing "exception contains correct data"
    (try
      (storage/check-type-change! :user :email :text :bool)
      (catch clojure.lang.ExceptionInfo e
        (is (= :destructive-change (:type (ex-data e))))
        (is (= :user (:entity (ex-data e))))
        (is (= :email (:field (ex-data e))))
        (is (= :text (:old-type (ex-data e))))
        (is (= :bool (:new-type (ex-data e))))))))


(deftest check-nullable-change!-test
  (testing "nil old-nullable? doesn't throw (new field)"
    (is (nil? (storage/check-nullable-change! :user :name nil false))))

  (testing "safe changes don't throw"
    (is (nil? (storage/check-nullable-change! :user :name true true)))
    (is (nil? (storage/check-nullable-change! :user :name false false)))
    (is (nil? (storage/check-nullable-change! :user :name false true))))

  (testing "unsafe change throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"nullable to non-nullable"
          (storage/check-nullable-change! :user :name true false))))

  (testing "exception contains correct data"
    (try
      (storage/check-nullable-change! :user :email true false)
      (catch clojure.lang.ExceptionInfo e
        (is (= :destructive-change (:type (ex-data e))))
        (is (= :user (:entity (ex-data e))))
        (is (= :email (:field (ex-data e))))
        (is (true? (:old-nullable? (ex-data e))))
        (is (false? (:new-nullable? (ex-data e)))))))

  (testing "corrupted metadata throws specific error"
    ;; When old-nullable? is not a boolean, it indicates corrupted metadata
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Corrupted metadata"
          (storage/check-nullable-change! :user :name 0 false)))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Corrupted metadata"
          (storage/check-nullable-change! :user :name "true" false)))
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"Corrupted metadata"
          (storage/check-nullable-change! :user :name :yes false))))

  (testing "corrupted metadata exception has correct data"
    (try
      (storage/check-nullable-change! :user :name 42 true)
      (catch clojure.lang.ExceptionInfo e
        (is (= :metadata-error/corrupted (:type (ex-data e))))
        (is (= :user (:entity (ex-data e))))
        (is (= :name (:field (ex-data e))))
        (is (= 42 (:old-nullable? (ex-data e))))
        (is (= :boolean (:expected-type (ex-data e))))))))


(deftest build-metadata-from-schema-test
  (testing "empty schema returns empty metadata"
    (let [schema (-> (mds/create-builder) ds/build)
          metadata (storage/build-metadata-from-schema schema)]
      (is (= {} (:entities metadata)))
      (is (= {} (:fields metadata)))
      (is (= {} (:enums metadata)))
      (is (= {} (:enum-values metadata)))))

  (testing "converts schema to metadata format with enums"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000000010"
                                  [{:uuid #uuid "00000000-0000-0000-0000-000000000011" :value :active}
                                   {:uuid #uuid "00000000-0000-0000-0000-000000000012" :value :inactive}])
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                    {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002" :type :text}})
                     ds/build)
          metadata (storage/build-metadata-from-schema schema)]
      ;; Check entities
      (is (= :user (get (:entities metadata) #uuid "00000000-0000-0000-0000-000000000001")))
      ;; Check fields
      (is (= {:entity :user :field :name}
             (get (:fields metadata) #uuid "00000000-0000-0000-0000-000000000002")))
      ;; Check enums
      (is (= :status (get (:enums metadata) #uuid "00000000-0000-0000-0000-000000000010")))
      ;; Check enum values (this hits the nested for)
      (is (= {:enum :status :value :active}
             (get (:enum-values metadata) #uuid "00000000-0000-0000-0000-000000000011")))
      (is (= {:enum :status :value :inactive}
             (get (:enum-values metadata) #uuid "00000000-0000-0000-0000-000000000012"))))))


(deftest build-first-init-changes-test
  (testing "empty schema returns empty changes"
    (let [schema (-> (mds/create-builder) ds/build)
          changes (storage/build-first-init-changes schema)]
      (is (= [] (:created (:entities changes))))
      (is (= [] (:created (:fields changes))))
      (is (= [] (:created (:enums changes))))
      (is (= [] (:created (:enum-values changes))))))

  (testing "builds changes for schema with enums"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000000010"
                                  [{:uuid #uuid "00000000-0000-0000-0000-000000000011" :value :active}])
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                    {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002" :type :text}})
                     ds/build)
          changes (storage/build-first-init-changes schema)]
      ;; Check entities created
      (is (= [:user] (:created (:entities changes))))
      ;; Check fields created (hits nested for)
      (is (= [{:entity :user :field :name}] (:created (:fields changes))))
      ;; Check enums created
      (is (= [:status] (:created (:enums changes))))
      ;; Check enum values created (hits nested for)
      (is (= [{:enum :status :value :active}] (:created (:enum-values changes)))))))


(deftest check-all-removals!-test
  (testing "empty old metadata and empty schema doesn't throw"
    (let [empty-metadata {:entities {} :fields {} :enums {} :enum-values {}}
          schema (-> (mds/create-builder) ds/build)]
      (is (nil? (storage/check-all-removals! empty-metadata schema)))))

  (testing "matching metadata and schema doesn't throw"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000000010"
                                  [{:uuid #uuid "00000000-0000-0000-0000-000000000011" :value :active}])
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                    {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002" :type :text}})
                     ds/build)
          metadata (storage/build-metadata-from-schema schema)]
      (is (nil? (storage/check-all-removals! metadata schema))))))


(deftest protocols-defined-test
  (testing "Storage protocol is defined"
    (is (some? storage/Storage))
    (is (contains? (:sigs storage/Storage) :initialize))
    (is (contains? (:sigs storage/Storage) :close)))

  (testing "StorageIntrospection protocol is defined"
    (is (some? storage/StorageIntrospection))
    (is (contains? (:sigs storage/StorageIntrospection) :current-entities))
    (is (contains? (:sigs storage/StorageIntrospection) :current-fields))
    (is (contains? (:sigs storage/StorageIntrospection) :current-enums))
    (is (contains? (:sigs storage/StorageIntrospection) :current-enum-values))
    (is (contains? (:sigs storage/StorageIntrospection) :schema-metadata)))

  (testing "StorageCRUD protocol is defined"
    (is (some? storage/StorageCRUD))
    (is (contains? (:sigs storage/StorageCRUD) :create-entity))
    (is (contains? (:sigs storage/StorageCRUD) :read-entity))
    (is (contains? (:sigs storage/StorageCRUD) :update-entity))
    (is (contains? (:sigs storage/StorageCRUD) :delete-entity))
    (is (contains? (:sigs storage/StorageCRUD) :query-entities)))

  (testing "GraphConstraints protocol is defined"
    (is (some? storage/GraphConstraints))
    ;; In 2-entity schema, arg.fn-id is FK to fn - no separate arg-schema validation needed
    (is (contains? (:sigs storage/GraphConstraints) :validate-no-dependency-cycle!)))

  (testing "StorageValueCodec protocol is defined"
    (is (some? storage/StorageValueCodec))
    (is (contains? (:sigs storage/StorageValueCodec) :encode-value))
    (is (contains? (:sigs storage/StorageValueCodec) :decode-value))
    (is (contains? (:sigs storage/StorageValueCodec) :encode-row))
    (is (contains? (:sigs storage/StorageValueCodec) :decode-row))))


;; === check-removed! tests ===

(deftest check-removed!-test
  (testing "no removals doesn't throw"
    (is (nil? (storage/check-removed! "entities"
                                      #{#uuid "00000000-0000-0000-0000-000000000001"}
                                      #{#uuid "00000000-0000-0000-0000-000000000001"}
                                      identity))))

  (testing "new items added doesn't throw"
    (is (nil? (storage/check-removed! "entities"
                                      #{#uuid "00000000-0000-0000-0000-000000000001"}
                                      #{#uuid "00000000-0000-0000-0000-000000000001"
                                        #uuid "00000000-0000-0000-0000-000000000002"}
                                      identity))))

  (testing "removed items throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"entities removed"
          (storage/check-removed! "entities"
                                  #{#uuid "00000000-0000-0000-0000-000000000001"
                                    #uuid "00000000-0000-0000-0000-000000000002"}
                                  #{#uuid "00000000-0000-0000-0000-000000000001"}
                                  (fn [uuid] {:name (str "entity-" uuid)})))))

  (testing "exception contains removed items"
    (try
      (storage/check-removed! "fields"
                              #{#uuid "00000000-0000-0000-0000-000000000001"}
                              #{}
                              (fn [_] :removed-field))
      (catch clojure.lang.ExceptionInfo e
        (is (= :destructive-change (:type (ex-data e))))
        (is (= [:removed-field] (:removed (ex-data e))))))))


;; === compute-*-changes tests ===

(deftest compute-entity-changes-test
  (testing "empty metadata returns all as created"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001" {})
                     ds/build)
          changes (storage/compute-entity-changes {:entities {}} schema)]
      (is (= [:user] (:created changes)))
      (is (= {} (:renamed changes)))))

  (testing "existing entity with same name returns empty"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001" {})
                     ds/build)
          old-metadata {:entities {#uuid "00000000-0000-0000-0000-000000000001" :user}}
          changes (storage/compute-entity-changes old-metadata schema)]
      (is (= [] (:created changes)))
      (is (= {} (:renamed changes)))))

  (testing "renamed entity detected"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :customer #uuid "00000000-0000-0000-0000-000000000001" {})
                     ds/build)
          old-metadata {:entities {#uuid "00000000-0000-0000-0000-000000000001" :user}}
          changes (storage/compute-entity-changes old-metadata schema)]
      (is (= [] (:created changes)))
      (is (= {:user :customer} (:renamed changes))))))


(deftest compute-field-changes-test
  (testing "empty metadata returns all as created"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                    {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002" :type :text}})
                     ds/build)
          changes (storage/compute-field-changes {:fields {}} schema)]
      (is (= [{:entity :user :field :name}] (:created changes)))
      (is (= [] (:renamed changes)))))

  (testing "existing field with same name returns empty"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                    {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002" :type :text}})
                     ds/build)
          old-metadata {:fields {#uuid "00000000-0000-0000-0000-000000000002" {:entity :user :field :name}}}
          changes (storage/compute-field-changes old-metadata schema)]
      (is (= [] (:created changes)))
      (is (= [] (:renamed changes)))))

  (testing "renamed field detected"
    (let [schema (-> (mds/create-builder)
                     (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                    {:full-name {:uuid #uuid "00000000-0000-0000-0000-000000000002" :type :text}})
                     ds/build)
          old-metadata {:fields {#uuid "00000000-0000-0000-0000-000000000002" {:entity :user :field :name}}}
          changes (storage/compute-field-changes old-metadata schema)]
      (is (= [] (:created changes)))
      (is (= [{:entity :user :old-field :name :new-field :full-name}] (:renamed changes))))))


(deftest compute-enum-changes-test
  (testing "empty metadata returns all as created"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000000001"
                                  [{:uuid #uuid "00000000-0000-0000-0000-000000000002" :value :active}])
                     ds/build)
          changes (storage/compute-enum-changes {:enums {}} schema)]
      (is (= [:status] (:created changes)))
      (is (= {} (:renamed changes)))))

  (testing "existing enum with same name returns empty"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000000001"
                                  [{:uuid #uuid "00000000-0000-0000-0000-000000000002" :value :active}])
                     ds/build)
          old-metadata {:enums {#uuid "00000000-0000-0000-0000-000000000001" :status}}
          changes (storage/compute-enum-changes old-metadata schema)]
      (is (= [] (:created changes)))
      (is (= {} (:renamed changes)))))

  (testing "renamed enum detected"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :user-status #uuid "00000000-0000-0000-0000-000000000001"
                                  [{:uuid #uuid "00000000-0000-0000-0000-000000000002" :value :active}])
                     ds/build)
          old-metadata {:enums {#uuid "00000000-0000-0000-0000-000000000001" :status}}
          changes (storage/compute-enum-changes old-metadata schema)]
      (is (= [] (:created changes)))
      (is (= {:status :user-status} (:renamed changes))))))


(deftest compute-enum-value-changes-test
  (testing "empty metadata returns all as created"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000000001"
                                  [{:uuid #uuid "00000000-0000-0000-0000-000000000002" :value :active}
                                   {:uuid #uuid "00000000-0000-0000-0000-000000000003" :value :inactive}])
                     ds/build)
          changes (storage/compute-enum-value-changes {:enum-values {}} schema)]
      (is (= 2 (count (:created changes))))
      (is (some #(= {:enum :status :value :active} %) (:created changes)))
      (is (some #(= {:enum :status :value :inactive} %) (:created changes)))))

  (testing "existing value returns only new ones"
    (let [schema (-> (mds/create-builder)
                     (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000000001"
                                  [{:uuid #uuid "00000000-0000-0000-0000-000000000002" :value :active}
                                   {:uuid #uuid "00000000-0000-0000-0000-000000000003" :value :inactive}])
                     ds/build)
          old-metadata {:enum-values {#uuid "00000000-0000-0000-0000-000000000002" {:enum :status :value :active}}}
          changes (storage/compute-enum-value-changes old-metadata schema)]
      (is (= [{:enum :status :value :inactive}] (:created changes))))))
