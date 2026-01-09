(ns graphden.storage-protocol.interface-test
  "Tests for storage-protocol.
   Tests the helper functions directly.
   Contract tests for Storage/StorageIntrospection protocols
   will be added when implementations exist."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.storage-protocol.interface :as storage]))


;; === Type widening tests ===

(deftest type-widening-test
  (testing "type-widening map contains expected entries"
    (is (contains? storage/type-widening :int))
    (is (contains? storage/type-widening :bool))
    (is (contains? storage/type-widening :numeric))
    (is (contains? storage/type-widening :text))
    (is (contains? storage/type-widening :uuid))
    (is (contains? storage/type-widening :timestamptz)))

  (testing "int can widen to numeric, text, jsonb"
    (is (contains? (:int storage/type-widening) :numeric))
    (is (contains? (:int storage/type-widening) :text))
    (is (contains? (:int storage/type-widening) :jsonb)))

  (testing "text can only widen to jsonb"
    (is (= #{:jsonb} (:text storage/type-widening)))))


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
    (is (contains? (:sigs storage/GraphConstraints) :validate-parent-same-schema!))
    (is (contains? (:sigs storage/GraphConstraints) :validate-no-arg-override!))
    (is (contains? (:sigs storage/GraphConstraints) :validate-arg-schema-belongs-to-fn!))
    (is (contains? (:sigs storage/GraphConstraints) :validate-no-inheritance-cycle!))
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


;; === validate-required-fields! tests ===

(deftest validate-required-fields!-test
  (testing "valid data with all required fields passes"
    (let [fields {:name {:type :text :nullable? false}
                  :email {:type :text :nullable? false}}
          data {:name "Alice" :email "alice@example.com"}]
      (is (nil? (storage/validate-required-fields! :user fields data)))))

  (testing "missing required field throws"
    (let [fields {:name {:type :text :nullable? false}
                  :email {:type :text :nullable? false}}
          data {:name "Alice"}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Required field 'email' is missing or nil"
            (storage/validate-required-fields! :user fields data)))))

  (testing "nil required field throws"
    (let [fields {:name {:type :text :nullable? false}}
          data {:name nil}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Required field 'name' is missing or nil"
            (storage/validate-required-fields! :user fields data)))))

  (testing "nullable field can be nil"
    (let [fields {:name {:type :text :nullable? false}
                  :bio {:type :text :nullable? true}}
          data {:name "Alice" :bio nil}]
      (is (nil? (storage/validate-required-fields! :user fields data)))))

  (testing "nullable field can be missing"
    (let [fields {:name {:type :text :nullable? false}
                  :bio {:type :text :nullable? true}}
          data {:name "Alice"}]
      (is (nil? (storage/validate-required-fields! :user fields data)))))

  (testing ":id field is ignored (auto-generated)"
    (let [fields {:id {:type :uuid :nullable? false}
                  :name {:type :text :nullable? false}}
          data {:name "Alice"}]  ; no :id provided
      (is (nil? (storage/validate-required-fields! :user fields data)))))

  (testing "exception contains correct data"
    (try
      (storage/validate-required-fields! :user
                                         {:email {:type :text :nullable? false}}
                                         {:email nil})
      (catch clojure.lang.ExceptionInfo e
        (is (= :validation-error/required-field-missing (:type (ex-data e))))
        (is (= :user (:entity (ex-data e))))
        (is (= :email (:field (ex-data e)))))))

  (testing "multiple fields - some nullable, some not, iterating through all"
    ;; This exercises more paths through the doseq
    (let [fields {:id {:type :uuid :nullable? false}    ; skipped (is :id)
                  :name {:type :text :nullable? false}  ; required
                  :bio {:type :text :nullable? true}    ; nullable
                  :email {:type :text :nullable? false} ; required
                  :avatar {:type :text :nullable? true}} ; nullable
          data {:name "Alice" :email "alice@example.com"}] ; bio and avatar missing but nullable
      (is (nil? (storage/validate-required-fields! :user fields data)))))

  (testing "field with nil nullable spec is treated as required"
    ;; When :nullable? is missing from field-spec, (not (:nullable? field-spec)) = (not nil) = true
    (let [fields {:name {:type :text}}  ; no :nullable? key
          data {}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Required field 'name' is missing or nil"
            (storage/validate-required-fields! :user fields data)))))

  (testing "empty fields map passes with any data"
    (is (nil? (storage/validate-required-fields! :user {} {:foo "bar"}))))

  (testing "empty data with only nullable fields passes"
    (let [fields {:bio {:type :text :nullable? true}
                  :avatar {:type :text :nullable? true}}]
      (is (nil? (storage/validate-required-fields! :user fields {}))))))


;; === validate-no-duplicate-ids! tests ===

(deftest validate-no-duplicate-ids!-test
  (testing "unique IDs pass validation"
    (let [data-seq [{:id (random-uuid) :name "Alice"}
                    {:id (random-uuid) :name "Bob"}]]
      (is (nil? (storage/validate-no-duplicate-ids! :user data-seq)))))

  (testing "data without explicit IDs passes (IDs auto-generated)"
    (let [data-seq [{:name "Alice"}
                    {:name "Bob"}]]
      (is (nil? (storage/validate-no-duplicate-ids! :user data-seq)))))

  (testing "empty data-seq passes"
    (is (nil? (storage/validate-no-duplicate-ids! :user []))))

  (testing "single record passes"
    (let [id (random-uuid)]
      (is (nil? (storage/validate-no-duplicate-ids! :user [{:id id :name "Alice"}])))))

  (testing "duplicate IDs throw"
    (let [dup-id (random-uuid)
          data-seq [{:id dup-id :name "Alice"}
                    {:id (random-uuid) :name "Bob"}
                    {:id dup-id :name "Charlie"}]]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Duplicate IDs found in batch"
            (storage/validate-no-duplicate-ids! :user data-seq)))))

  (testing "exception contains correct data"
    (let [dup-id (random-uuid)
          data-seq [{:id dup-id} {:id dup-id}]]
      (try
        (storage/validate-no-duplicate-ids! :user data-seq)
        (catch clojure.lang.ExceptionInfo e
          (is (= :validation-error/duplicate-ids (:type (ex-data e))))
          (is (= :user (:entity (ex-data e))))
          (is (= [dup-id] (:duplicate-ids (ex-data e)))))))))


;; === validate-data-is-map! tests ===

(deftest validate-data-is-map!-test
  (testing "map data passes validation"
    (is (nil? (storage/validate-data-is-map! :user {:name "Alice"}))))

  (testing "empty map passes validation"
    (is (nil? (storage/validate-data-is-map! :user {}))))

  (testing "nil data throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"data must be a map"
          (storage/validate-data-is-map! :user nil))))

  (testing "vector throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"data must be a map"
          (storage/validate-data-is-map! :user [{:name "Alice"}]))))

  (testing "string throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"data must be a map"
          (storage/validate-data-is-map! :user "not a map"))))

  (testing "integer throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"data must be a map"
          (storage/validate-data-is-map! :user 123))))

  (testing "exception contains correct data"
    (try
      (storage/validate-data-is-map! :user [1 2 3])
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-data (:type (ex-data e))))
        (is (= :user (:entity-name (ex-data e))))
        (is (= [1 2 3] (:data (ex-data e))))
        (is (some? (:data-type (ex-data e))))))))


;; === validate-where-clause! tests ===

(deftest validate-where-clause!-test
  (testing "nil where clause passes"
    (is (nil? (storage/validate-where-clause! nil))))

  (testing "empty map passes"
    (is (nil? (storage/validate-where-clause! {}))))

  (testing "map with values passes"
    (is (nil? (storage/validate-where-clause! {:name "Alice" :active true}))))

  (testing "vector throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"where clause must be nil or a map"
          (storage/validate-where-clause! [:name "Alice"]))))

  (testing "string throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"where clause must be nil or a map"
          (storage/validate-where-clause! "name = 'Alice'"))))

  (testing "number throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"where clause must be nil or a map"
          (storage/validate-where-clause! 123))))

  (testing "keyword throws"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"where clause must be nil or a map"
          (storage/validate-where-clause! :name))))

  (testing "exception contains correct data"
    (try
      (storage/validate-where-clause! [:bad :data])
      (catch clojure.lang.ExceptionInfo e
        (is (= :invalid-where-clause (:type (ex-data e))))
        (is (= [:bad :data] (:where (ex-data e))))
        (is (some? (:where-type (ex-data e))))))))


;; === check-graph-iteration-limit! tests ===

(deftest check-graph-iteration-limit!-test
  (testing "under limit doesn't throw"
    (is (nil? (storage/check-graph-iteration-limit! 0 (random-uuid))))
    (is (nil? (storage/check-graph-iteration-limit! 100 (random-uuid))))
    (is (nil? (storage/check-graph-iteration-limit! 9999 (random-uuid))))
    (is (nil? (storage/check-graph-iteration-limit! storage/*max-graph-iterations* (random-uuid)))))

  (testing "over limit throws"
    (let [fn-id (random-uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"exceeded maximum iterations"
            (storage/check-graph-iteration-limit! (inc storage/*max-graph-iterations*) fn-id)))))

  (testing "exception contains correct data"
    (let [fn-id (random-uuid)]
      (try
        (storage/check-graph-iteration-limit! 10001 fn-id)
        (catch clojure.lang.ExceptionInfo e
          (is (= :execution-error/graph-too-large (:type (ex-data e))))
          (is (= fn-id (:fn-id (ex-data e))))
          (is (= storage/*max-graph-iterations* (:max-iterations (ex-data e))))
          (is (= 10001 (:iteration-count (ex-data e)))))))))


(deftest with-max-graph-iterations-test
  (testing "executes function and returns result"
    (is (= 42 (storage/with-max-graph-iterations 50000 #(+ 40 2)))))

  (testing "overrides limit within binding"
    (is (= 50000
           (storage/with-max-graph-iterations 50000
                                              #(deref #'storage/*max-graph-iterations*)))))

  (testing "allows higher iterations when limit is increased"
    (let [fn-id (random-uuid)]
      ;; Default limit is 10000, this should throw normally
      (is (thrown? clojure.lang.ExceptionInfo
            (storage/check-graph-iteration-limit! 15000 fn-id)))
      ;; But with increased limit it should not throw
      (is (nil? (storage/with-max-graph-iterations 20000
                                                   #(storage/check-graph-iteration-limit! 15000 fn-id))))))

  (testing "restores original limit after execution"
    (let [original storage/*max-graph-iterations*]
      (storage/with-max-graph-iterations 50000 #(identity :done))
      (is (= original storage/*max-graph-iterations*))))

  (testing "restores original limit after exception"
    (let [original storage/*max-graph-iterations*]
      (try
        (storage/with-max-graph-iterations 50000 #(throw (ex-info "test" {})))
        (catch Exception _))
      (is (= original storage/*max-graph-iterations*)))))


(deftest check-graph-iteration-limit-edge-cases-test
  (testing "exactly at limit doesn't throw"
    (let [fn-id (random-uuid)]
      (storage/with-max-graph-iterations 100
                                         #(is (nil? (storage/check-graph-iteration-limit! 100 fn-id))))))

  (testing "one over limit throws"
    (let [fn-id (random-uuid)]
      (storage/with-max-graph-iterations 100
                                         #(is (thrown-with-msg?
                                                clojure.lang.ExceptionInfo
                                                #"exceeded maximum iterations"
                                                (storage/check-graph-iteration-limit! 101 fn-id))))))

  (testing "at 79% of limit doesn't warn (below threshold)"
    ;; Testing that 79% doesn't trigger warning path
    ;; We can't easily test log output, but we verify no exception
    (let [fn-id (random-uuid)]
      (storage/with-max-graph-iterations 100
                                         #(is (nil? (storage/check-graph-iteration-limit! 79 fn-id))))))

  (testing "at 80% of limit still passes (warning threshold)"
    ;; 80% of limit triggers warning but doesn't throw
    (let [fn-id (random-uuid)]
      (storage/with-max-graph-iterations 100
                                         #(is (nil? (storage/check-graph-iteration-limit! 80 fn-id))))))

  (testing "at 99% of limit still passes"
    (let [fn-id (random-uuid)]
      (storage/with-max-graph-iterations 100
                                         #(is (nil? (storage/check-graph-iteration-limit! 99 fn-id))))))

  (testing "works with very small limits"
    (let [fn-id (random-uuid)]
      (storage/with-max-graph-iterations 1
                                         #(do
                                            (is (nil? (storage/check-graph-iteration-limit! 0 fn-id)))
                                            (is (nil? (storage/check-graph-iteration-limit! 1 fn-id)))
                                            (is (thrown? clojure.lang.ExceptionInfo
                                                  (storage/check-graph-iteration-limit! 2 fn-id)))))))

  (testing "works with large limits"
    (let [fn-id (random-uuid)]
      (storage/with-max-graph-iterations 1000000
                                         #(is (nil? (storage/check-graph-iteration-limit! 999999 fn-id)))))))


;; === try-parse-uuid tests ===

(deftest try-parse-uuid-test
  (testing "returns UUID for UUID input"
    (let [u (random-uuid)]
      (is (= u (storage/try-parse-uuid u)))))

  (testing "returns UUID for valid UUID string"
    (let [u (random-uuid)
          s (str u)]
      (is (= u (storage/try-parse-uuid s)))))

  (testing "returns nil for invalid UUID string"
    (is (nil? (storage/try-parse-uuid "not-a-uuid")))
    (is (nil? (storage/try-parse-uuid "12345")))
    (is (nil? (storage/try-parse-uuid ""))))

  (testing "returns nil for non-string, non-UUID values"
    (is (nil? (storage/try-parse-uuid 12345)))
    (is (nil? (storage/try-parse-uuid nil)))
    (is (nil? (storage/try-parse-uuid :keyword)))
    (is (nil? (storage/try-parse-uuid [1 2 3])))))


;; === Mock ConstraintHelpers for testing shared implementations ===

(defrecord MockConstraintHelpers
  [fn-schema-map arg-schema-fn-schema-map parent-map arg-schema-ids-in-chain-map dependency-chain-map]

  storage/ConstraintHelpers

  (get-fn-schema-id-for-fn
    [_this fn-id]
    (get fn-schema-map fn-id))


  (get-fn-schema-id-for-arg-schema
    [_this arg-schema-id]
    (get arg-schema-fn-schema-map arg-schema-id))


  (get-parent-fn-id
    [_this fn-id]
    (get parent-map fn-id))


  (collect-parent-chain
    [this fn-id]
    (storage/collect-parent-chain-impl this fn-id))


  (collect-arg-schema-ids-in-chain
    [_this fn-id]
    (get arg-schema-ids-in-chain-map fn-id #{}))


  (collect-dependency-chain
    [_this fn-id]
    (get dependency-chain-map fn-id #{fn-id})))


;; === collect-parent-chain-impl tests ===

(deftest collect-parent-chain-impl-test
  (testing "returns empty set for fn with no parent"
    (let [helpers (->MockConstraintHelpers {} {} {} {} {})]
      (is (= #{} (storage/collect-parent-chain-impl helpers (random-uuid))))))

  (testing "returns single ancestor for fn with one parent"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          helpers (->MockConstraintHelpers {} {} {fn-b fn-a} {} {})]
      (is (= #{fn-a} (storage/collect-parent-chain-impl helpers fn-b)))))

  (testing "returns all ancestors for deep chain"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          fn-c (random-uuid)
          fn-d (random-uuid)
          helpers (->MockConstraintHelpers {} {} {fn-b fn-a, fn-c fn-b, fn-d fn-c} {} {})]
      (is (= #{fn-a fn-b fn-c} (storage/collect-parent-chain-impl helpers fn-d)))))

  (testing "handles cycle in parent chain (stops when revisiting)"
    ;; This shouldn't happen in valid data, but the impl should handle it gracefully
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          helpers (->MockConstraintHelpers {} {} {fn-a fn-b, fn-b fn-a} {} {})]
      ;; Should return both without infinite loop
      (is (= #{fn-a fn-b} (storage/collect-parent-chain-impl helpers fn-a))))))


;; === validate-parent-same-schema-impl tests ===

(deftest validate-parent-same-schema-impl-test
  (testing "nil parent-fn-id doesn't throw"
    (let [helpers (->MockConstraintHelpers {} {} {} {} {})]
      (is (nil? (storage/validate-parent-same-schema-impl helpers (random-uuid) nil)))))

  (testing "same schema doesn't throw"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          schema-id (random-uuid)
          helpers (->MockConstraintHelpers {fn-a schema-id, fn-b schema-id} {} {} {} {})]
      (is (nil? (storage/validate-parent-same-schema-impl helpers fn-a fn-b)))))

  (testing "different schema throws"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          schema-a (random-uuid)
          schema-b (random-uuid)
          helpers (->MockConstraintHelpers {fn-a schema-a, fn-b schema-b} {} {} {} {})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Parent fn has different fn-schema-id"
            (storage/validate-parent-same-schema-impl helpers fn-a fn-b)))))

  (testing "exception contains correct data"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          schema-a (random-uuid)
          schema-b (random-uuid)
          helpers (->MockConstraintHelpers {fn-a schema-a, fn-b schema-b} {} {} {} {})]
      (try
        (storage/validate-parent-same-schema-impl helpers fn-a fn-b)
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/parent-schema-mismatch (:type (ex-data e))))
          (is (= fn-a (:fn-id (ex-data e))))
          (is (= fn-b (:parent-fn-id (ex-data e))))))))

  (testing "missing fn returns nil (fn not found)"
    (let [helpers (->MockConstraintHelpers {} {} {} {} {})]
      (is (nil? (storage/validate-parent-same-schema-impl helpers (random-uuid) (random-uuid))))))

  (testing "fn-schema-id nil but parent-schema-id present returns nil"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          schema-b (random-uuid)
          ;; fn-a has no schema, fn-b has schema
          helpers (->MockConstraintHelpers {fn-b schema-b} {} {} {} {})]
      (is (nil? (storage/validate-parent-same-schema-impl helpers fn-a fn-b)))))

  (testing "fn-schema-id present but parent-schema-id nil returns nil"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          schema-a (random-uuid)
          ;; fn-a has schema, fn-b has no schema
          helpers (->MockConstraintHelpers {fn-a schema-a} {} {} {} {})]
      (is (nil? (storage/validate-parent-same-schema-impl helpers fn-a fn-b))))))


;; === validate-no-arg-override-impl tests ===

(deftest validate-no-arg-override-impl-test
  (testing "arg not in parent chain doesn't throw"
    (let [fn-id (random-uuid)
          arg-schema-id (random-uuid)
          helpers (->MockConstraintHelpers {} {} {} {fn-id #{}} {})]
      (is (nil? (storage/validate-no-arg-override-impl helpers fn-id arg-schema-id)))))

  (testing "arg in parent chain throws"
    (let [fn-id (random-uuid)
          arg-schema-id (random-uuid)
          helpers (->MockConstraintHelpers {} {} {} {fn-id #{arg-schema-id}} {})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Argument already defined in parent chain"
            (storage/validate-no-arg-override-impl helpers fn-id arg-schema-id)))))

  (testing "exception contains correct data"
    (let [fn-id (random-uuid)
          arg-schema-id (random-uuid)
          helpers (->MockConstraintHelpers {} {} {} {fn-id #{arg-schema-id}} {})]
      (try
        (storage/validate-no-arg-override-impl helpers fn-id arg-schema-id)
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/arg-already-defined (:type (ex-data e))))
          (is (= fn-id (:fn-id (ex-data e))))
          (is (= arg-schema-id (:arg-schema-id (ex-data e)))))))))


;; === validate-arg-schema-belongs-to-fn-impl tests ===

(deftest validate-arg-schema-belongs-to-fn-impl-test
  (testing "arg-schema belongs to fn-schema doesn't throw"
    (let [fn-id (random-uuid)
          arg-schema-id (random-uuid)
          schema-id (random-uuid)
          helpers (->MockConstraintHelpers {fn-id schema-id} {arg-schema-id schema-id} {} {} {})]
      (is (nil? (storage/validate-arg-schema-belongs-to-fn-impl helpers fn-id arg-schema-id)))))

  (testing "arg-schema from different fn-schema throws"
    (let [fn-id (random-uuid)
          arg-schema-id (random-uuid)
          schema-a (random-uuid)
          schema-b (random-uuid)
          helpers (->MockConstraintHelpers {fn-id schema-a} {arg-schema-id schema-b} {} {} {})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Arg-schema does not belong to fn's schema"
            (storage/validate-arg-schema-belongs-to-fn-impl helpers fn-id arg-schema-id)))))

  (testing "exception contains correct data"
    (let [fn-id (random-uuid)
          arg-schema-id (random-uuid)
          schema-a (random-uuid)
          schema-b (random-uuid)
          helpers (->MockConstraintHelpers {fn-id schema-a} {arg-schema-id schema-b} {} {} {})]
      (try
        (storage/validate-arg-schema-belongs-to-fn-impl helpers fn-id arg-schema-id)
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/arg-schema-mismatch (:type (ex-data e))))
          (is (= fn-id (:fn-id (ex-data e))))
          (is (= arg-schema-id (:arg-schema-id (ex-data e))))
          (is (= schema-a (:fn-schema-id (ex-data e))))
          (is (= schema-b (:arg-fn-schema-id (ex-data e))))))))

  (testing "missing fn-schema returns nil"
    (let [helpers (->MockConstraintHelpers {} {} {} {} {})]
      (is (nil? (storage/validate-arg-schema-belongs-to-fn-impl helpers (random-uuid) (random-uuid))))))

  (testing "fn-schema-id present but arg-fn-schema-id nil returns nil"
    (let [fn-id (random-uuid)
          arg-schema-id (random-uuid)
          schema-id (random-uuid)
          ;; fn has schema-id, but arg-schema has no fn-schema-id
          helpers (->MockConstraintHelpers {fn-id schema-id} {} {} {} {})]
      (is (nil? (storage/validate-arg-schema-belongs-to-fn-impl helpers fn-id arg-schema-id))))))


;; === validate-no-inheritance-cycle-impl tests ===

(deftest validate-no-inheritance-cycle-impl-test
  (testing "nil parent-fn-id doesn't throw"
    (let [helpers (->MockConstraintHelpers {} {} {} {} {})]
      (is (nil? (storage/validate-no-inheritance-cycle-impl helpers (random-uuid) nil)))))

  (testing "self-reference throws"
    (let [fn-id (random-uuid)
          helpers (->MockConstraintHelpers {} {} {} {} {})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Cannot set self as parent"
            (storage/validate-no-inheritance-cycle-impl helpers fn-id fn-id)))))

  (testing "non-cyclic parent chain doesn't throw"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          fn-c (random-uuid)
          ;; Chain: fn-c -> fn-b -> fn-a (no cycle)
          helpers (->MockConstraintHelpers {} {} {fn-b fn-a} {} {})]
      (is (nil? (storage/validate-no-inheritance-cycle-impl helpers fn-c fn-b)))))

  (testing "cycle through parent chain throws"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          fn-c (random-uuid)
          ;; Current chain: fn-c -> fn-b -> fn-a
          ;; Trying to set fn-a -> fn-c (would create cycle)
          helpers (->MockConstraintHelpers {} {} {fn-c fn-b, fn-b fn-a} {} {})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Setting parent would create inheritance cycle"
            (storage/validate-no-inheritance-cycle-impl helpers fn-a fn-c)))))

  (testing "exception contains correct data for self-reference"
    (let [fn-id (random-uuid)
          helpers (->MockConstraintHelpers {} {} {} {} {})]
      (try
        (storage/validate-no-inheritance-cycle-impl helpers fn-id fn-id)
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/inheritance-cycle (:type (ex-data e))))
          (is (= fn-id (:fn-id (ex-data e))))
          (is (= fn-id (:parent-fn-id (ex-data e)))))))))


;; === type-mappings tests ===

(deftest type-mappings-test
  (testing "type-mappings contains all base types"
    (is (contains? storage/type-mappings :uuid))
    (is (contains? storage/type-mappings :text))
    (is (contains? storage/type-mappings :int))
    (is (contains? storage/type-mappings :bool))
    (is (contains? storage/type-mappings :numeric))
    (is (contains? storage/type-mappings :timestamptz))
    (is (contains? storage/type-mappings :jsonb))
    (is (contains? storage/type-mappings :bytes)))

  (testing "type-mappings contains special types"
    (is (contains? storage/type-mappings :ref))
    (is (contains? storage/type-mappings :enum))
    (is (contains? storage/type-mappings :union)))

  (testing "each type has all backend mappings"
    (doseq [t (keys storage/type-mappings)]
      (is (contains? (get storage/type-mappings t) :postgres) (str "Missing :postgres for " t))
      (is (contains? (get storage/type-mappings t) :datomic) (str "Missing :datomic for " t))
      (is (contains? (get storage/type-mappings t) :memory) (str "Missing :memory for " t))))

  (testing "postgres mappings are strings or :custom"
    (doseq [[t mapping] storage/type-mappings]
      (let [pg-type (:postgres mapping)]
        (is (or (string? pg-type) (= :custom pg-type))
            (str "Postgres mapping for " t " should be string or :custom")))))

  (testing "datomic mappings are keywords"
    (doseq [[t mapping] storage/type-mappings]
      (is (keyword? (:datomic mapping))
          (str "Datomic mapping for " t " should be keyword"))))

  (testing "memory mappings are :any"
    (doseq [[t mapping] storage/type-mappings]
      (is (= :any (:memory mapping))
          (str "Memory mapping for " t " should be :any"))))

  (testing "can look up specific mappings"
    (is (= "UUID" (get-in storage/type-mappings [:uuid :postgres])))
    (is (= :db.type/long (get-in storage/type-mappings [:int :datomic])))
    (is (= "BIGINT" (get-in storage/type-mappings [:int :postgres])))
    (is (= :db.type/ref (get-in storage/type-mappings [:ref :datomic])))))


;; === StorageBatchCRUD protocol tests ===

(deftest storage-batch-crud-protocol-test
  (testing "StorageBatchCRUD protocol is defined"
    (is (some? storage/StorageBatchCRUD))
    (is (contains? (:sigs storage/StorageBatchCRUD) :create-entities))
    (is (contains? (:sigs storage/StorageBatchCRUD) :read-entities))
    (is (contains? (:sigs storage/StorageBatchCRUD) :delete-entities))))


;; === merge-arg-values-for-chain tests ===

(deftest merge-arg-values-for-chain-test
  (testing "returns nil for empty chain"
    (is (nil? (storage/merge-arg-values-for-chain [] []))))

  (testing "returns nil for nil chain via (seq nil)"
    (is (nil? (storage/merge-arg-values-for-chain [] nil))))

  (testing "child arg-value overrides parent"
    (let [parent-fn-id (random-uuid)
          child-fn-id (random-uuid)
          arg-schema-id (random-uuid)
          arg-values [{:id (random-uuid)
                       :owner-fn-id parent-fn-id
                       :arg-schema-id arg-schema-id
                       :value "parent-value"}
                      {:id (random-uuid)
                       :owner-fn-id child-fn-id
                       :arg-schema-id arg-schema-id
                       :value "child-value"}]
          ;; Chain: child -> parent (child first = lower position = wins)
          chain [child-fn-id parent-fn-id]
          result (storage/merge-arg-values-for-chain arg-values chain)]
      (is (= "child-value" (:value (get result arg-schema-id))))))

  (testing "uses Long/MAX_VALUE fallback for unknown owner"
    ;; This tests the edge case where an arg-value has an owner not in the chain
    (let [known-fn-id (random-uuid)
          unknown-fn-id (random-uuid)
          arg-schema-id (random-uuid)
          arg-values [{:id (random-uuid)
                       :owner-fn-id known-fn-id
                       :arg-schema-id arg-schema-id
                       :value "known"}
                      {:id (random-uuid)
                       :owner-fn-id unknown-fn-id
                       :arg-schema-id arg-schema-id
                       :value "unknown"}]
          chain [known-fn-id]
          result (storage/merge-arg-values-for-chain arg-values chain)]
      ;; Known owner should win (has lower position than MAX_VALUE)
      (is (= "known" (:value (get result arg-schema-id))))))

  (testing "handles multiple arg-schemas correctly"
    (let [fn-id (random-uuid)
          arg-schema-1 (random-uuid)
          arg-schema-2 (random-uuid)
          arg-values [{:owner-fn-id fn-id :arg-schema-id arg-schema-1 :value 1}
                      {:owner-fn-id fn-id :arg-schema-id arg-schema-2 :value 2}]
          chain [fn-id]
          result (storage/merge-arg-values-for-chain arg-values chain)]
      (is (= 1 (:value (get result arg-schema-1))))
      (is (= 2 (:value (get result arg-schema-2)))))))


;; === extract-uuid-refs-from-arg-values tests ===

(deftest extract-uuid-refs-from-arg-values-test
  (testing "extracts UUID values"
    (let [uuid1 (random-uuid)
          uuid2 (random-uuid)
          k1 (random-uuid)
          k2 (random-uuid)
          arg-values-map {k1 {:value uuid1}
                          k2 {:value uuid2}}
          result (storage/extract-uuid-refs-from-arg-values arg-values-map)]
      (is (= #{uuid1 uuid2} result))))

  (testing "parses UUID strings"
    (let [uuid1 (random-uuid)
          k1 (random-uuid)
          arg-values-map {k1 {:value (str uuid1)}}
          result (storage/extract-uuid-refs-from-arg-values arg-values-map)]
      (is (= #{uuid1} result))))

  (testing "ignores non-UUID values"
    (let [k1 (random-uuid)
          k2 (random-uuid)
          k3 (random-uuid)
          arg-values-map {k1 {:value "not-a-uuid"}
                          k2 {:value 123}
                          k3 {:value nil}}
          result (storage/extract-uuid-refs-from-arg-values arg-values-map)]
      (is (= #{} result))))

  (testing "handles empty map"
    (is (= #{} (storage/extract-uuid-refs-from-arg-values {}))))

  (testing "handles mixed values"
    (let [uuid1 (random-uuid)
          k1 (random-uuid)
          k2 (random-uuid)
          k3 (random-uuid)
          arg-values-map {k1 {:value uuid1}
                          k2 {:value "not-a-uuid"}
                          k3 {:value 42}}
          result (storage/extract-uuid-refs-from-arg-values arg-values-map)]
      (is (= #{uuid1} result)))))


;; === needs-special-encoding? tests ===

(deftest needs-special-encoding?-test
  (testing "returns true for JSONB type"
    (is (true? (storage/needs-special-encoding? :jsonb))))

  (testing "returns true for union type"
    (is (true? (storage/needs-special-encoding? :union))))

  (testing "returns true for enum type"
    (is (true? (storage/needs-special-encoding? :enum))))

  (testing "returns false for basic types"
    (is (false? (storage/needs-special-encoding? :text)))
    (is (false? (storage/needs-special-encoding? :int)))
    (is (false? (storage/needs-special-encoding? :bool)))
    (is (false? (storage/needs-special-encoding? :uuid)))
    (is (false? (storage/needs-special-encoding? :ref)))
    (is (false? (storage/needs-special-encoding? :numeric)))
    (is (false? (storage/needs-special-encoding? :timestamptz)))
    (is (false? (storage/needs-special-encoding? :bytes)))))


;; === default-query-timeout-ms tests ===

(deftest default-query-timeout-ms-test
  (testing "default timeout is 30 seconds"
    (is (= 30000 storage/default-query-timeout-ms)))

  (testing "timeout is a positive number"
    (is (pos? storage/default-query-timeout-ms))))


;; === storage-error-types tests ===

(deftest storage-error-types-test
  (testing "contains all expected error types"
    (is (contains? storage/storage-error-types :unique-violation))
    (is (contains? storage/storage-error-types :foreign-key-violation))
    (is (contains? storage/storage-error-types :not-null-violation))
    (is (contains? storage/storage-error-types :check-constraint-violation))
    (is (contains? storage/storage-error-types :table-not-found))
    (is (contains? storage/storage-error-types :connection-error))
    (is (contains? storage/storage-error-types :query-timeout))
    (is (contains? storage/storage-error-types :parse-error))
    (is (contains? storage/storage-error-types :unknown-sql-error)))

  (testing "is a set"
    (is (set? storage/storage-error-types))))


;; === StorageErrorClassifier protocol tests ===

(deftest storage-error-classifier-protocol-test
  (testing "StorageErrorClassifier protocol is defined"
    (is (some? storage/StorageErrorClassifier))
    (is (contains? (:sigs storage/StorageErrorClassifier) :classify-error))
    (is (contains? (:sigs storage/StorageErrorClassifier) :wrap-error))))


;; === Storage Implementation Helpers tests ===

(deftest create-rw-lock-test
  (testing "creates ReentrantReadWriteLock"
    (let [lock (storage/create-rw-lock)]
      (is (instance? java.util.concurrent.locks.ReentrantReadWriteLock lock)))))


(deftest standard-crud-validations!-test
  (testing "passes for valid data"
    (is (nil? (storage/standard-crud-validations! :user {:name "test"} nil)))
    (is (nil? (storage/standard-crud-validations!
                :user
                {:name "test"}
                {:name {:required true}}))))

  (testing "throws for non-map data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data must be a map"
          (storage/standard-crud-validations! :user "not a map" nil))))

  (testing "throws for missing required fields"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Required field.*missing"
          (storage/standard-crud-validations!
            :user
            {:other "field"}
            {:name {:required true}})))))


(deftest standard-batch-validations!-test
  (testing "passes for unique IDs"
    (let [id1 (random-uuid)
          id2 (random-uuid)]
      (is (nil? (storage/standard-batch-validations! :user [{:id id1} {:id id2}])))))

  (testing "throws for duplicate IDs"
    (let [dup-id (random-uuid)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Duplicate IDs"
            (storage/standard-batch-validations! :user [{:id dup-id} {:id dup-id}]))))))


(deftest storage-checklist-test
  (testing "storage-checklist contains required protocols"
    (is (contains? storage/storage-checklist :required-protocols))
    (is (some #{:Storage} (:required-protocols storage/storage-checklist)))
    (is (some #{:StorageCRUD} (:required-protocols storage/storage-checklist))))

  (testing "storage-checklist contains optional protocols"
    (is (contains? storage/storage-checklist :optional-protocols))
    (is (some #{:GraphConstraints} (:optional-protocols storage/storage-checklist))))

  (testing "storage-checklist contains recommended protocols"
    (is (contains? storage/storage-checklist :recommended-protocols))
    (is (some #{:StorageValueCodec} (:recommended-protocols storage/storage-checklist)))))


;; === validate-no-dependency-cycle-impl tests ===

;; === Error helpers tests ===

(deftest make-error-context-test
  (testing "creates error context with required fields"
    (let [ctx (storage/make-error-context :test-error :create "Test message" {:entity :user})]
      (is (= :test-error (:type ctx)))
      (is (= :create (:operation ctx)))
      (is (= "Test message" (:message ctx)))
      (is (= :user (:entity ctx)))))

  (testing "merges additional context"
    (let [ctx (storage/make-error-context :error-type :read "msg" {:id 123 :extra "data"})]
      (is (= :error-type (:type ctx)))
      (is (= :read (:operation ctx)))
      (is (= 123 (:id ctx)))
      (is (= "data" (:extra ctx))))))


(deftest make-storage-error-test
  (testing "creates storage error without cause"
    (let [err (storage/make-storage-error :test-error :create "Test message" {:entity :user})]
      (is (instance? clojure.lang.ExceptionInfo err))
      (is (= "Test message" (ex-message err)))
      (is (= :test-error (:type (ex-data err))))
      (is (= :create (:operation (ex-data err))))
      (is (= :user (:entity (ex-data err))))
      (is (nil? (ex-cause err)))))

  (testing "creates storage error with cause"
    (let [cause (ex-info "Original error" {:original true})
          err (storage/make-storage-error :wrapped-error :update "Wrapped" {:id 42} cause)]
      (is (instance? clojure.lang.ExceptionInfo err))
      (is (= "Wrapped" (ex-message err)))
      (is (= :wrapped-error (:type (ex-data err))))
      (is (= :update (:operation (ex-data err))))
      (is (= 42 (:id (ex-data err))))
      (is (= cause (ex-cause err)))
      (is (= "Original error" (ex-message (ex-cause err)))))))


(deftest validate-no-dependency-cycle-impl-test
  (testing "nil value-fn-id doesn't throw"
    (let [helpers (->MockConstraintHelpers {} {} {} {} {})]
      (is (nil? (storage/validate-no-dependency-cycle-impl helpers (random-uuid) nil)))))

  (testing "no cycle in dependencies doesn't throw"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          ;; fn-b depends on nothing special, fn-a not in its chain
          helpers (->MockConstraintHelpers {} {} {} {} {fn-b #{fn-b}})]
      (is (nil? (storage/validate-no-dependency-cycle-impl helpers fn-a fn-b)))))

  (testing "cycle in dependencies throws"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          ;; fn-b already depends on fn-a
          helpers (->MockConstraintHelpers {} {} {} {} {fn-b #{fn-a fn-b}})]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Reference would create dependency cycle"
            (storage/validate-no-dependency-cycle-impl helpers fn-a fn-b)))))

  (testing "exception contains correct data"
    (let [fn-a (random-uuid)
          fn-b (random-uuid)
          helpers (->MockConstraintHelpers {} {} {} {} {fn-b #{fn-a fn-b}})]
      (try
        (storage/validate-no-dependency-cycle-impl helpers fn-a fn-b)
        (catch clojure.lang.ExceptionInfo e
          (is (= :constraint-violation/dependency-cycle (:type (ex-data e))))
          (is (= fn-a (:owner-fn-id (ex-data e))))
          (is (= fn-b (:value-fn-id (ex-data e)))))))))


;; === ExecutionGraphResult validation tests ===

(deftest execution-graph-validation-test
  (let [fn-id (random-uuid)
        fn-schema-id (random-uuid)
        arg-schema-id (random-uuid)
        valid-fns {fn-id {:id fn-id :fn-schema-id fn-schema-id}}
        valid-fn-schemas {fn-schema-id {:id fn-schema-id :name "test-fn"}}
        valid-arg-schemas {arg-schema-id {:id arg-schema-id :fn-schema-id fn-schema-id}}]

    (testing "creates valid result with all required fields"
      (let [result (storage/->execution-graph
                     {:fns valid-fns
                      :fn-schemas valid-fn-schemas
                      :arg-schemas valid-arg-schemas
                      :resolved-args {}})]
        (is (storage/execution-graph? result))
        (is (= valid-fns (:fns result)))
        (is (= valid-fn-schemas (:fn-schemas result)))))

    (testing "throws when :fns is not a map"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :fns map"
            (storage/->execution-graph
              {:fns "not-a-map"
               :fn-schemas valid-fn-schemas
               :arg-schemas valid-arg-schemas
               :resolved-args {}}))))

    (testing "throws when :fns is empty"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":fns must contain at least"
            (storage/->execution-graph
              {:fns {}
               :fn-schemas valid-fn-schemas
               :arg-schemas valid-arg-schemas
               :resolved-args {}}))))

    (testing "throws when :fn-schemas is not a map"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :fn-schemas map"
            (storage/->execution-graph
              {:fns valid-fns
               :fn-schemas []
               :arg-schemas valid-arg-schemas
               :resolved-args {}}))))

    (testing "throws when :fn-schemas is empty"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #":fn-schemas must contain at least"
            (storage/->execution-graph
              {:fns valid-fns
               :fn-schemas {}
               :arg-schemas valid-arg-schemas
               :resolved-args {}}))))

    (testing "throws when :arg-schemas is not a map"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :arg-schemas map"
            (storage/->execution-graph
              {:fns valid-fns
               :fn-schemas valid-fn-schemas
               :arg-schemas "invalid"
               :resolved-args {}}))))

    (testing "throws when :resolved-args is not a map"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :resolved-args map"
            (storage/->execution-graph
              {:fns valid-fns
               :fn-schemas valid-fn-schemas
               :arg-schemas valid-arg-schemas
               :resolved-args []}))))))


(deftest execution-graph?-test
  (testing "returns true for ExecutionGraphResult"
    (let [result (storage/->execution-graph
                   {:fns {(random-uuid) {:id (random-uuid)}}
                    :fn-schemas {(random-uuid) {:id (random-uuid)}}
                    :arg-schemas {}
                    :resolved-args {}})]
      (is (true? (storage/execution-graph? result)))))

  (testing "returns false for other types"
    (is (false? (storage/execution-graph? {})))
    (is (false? (storage/execution-graph? nil)))
    (is (false? (storage/execution-graph? "string")))))


;; === redact-sensitive tests ===

(deftest redact-sensitive-map-test
  (testing "redacts known sensitive keys"
    (is (= {:password "[REDACTED]"}
           (storage/redact-sensitive-map {:password "secret123"})))
    (is (= {:api-key "[REDACTED]"}
           (storage/redact-sensitive-map {:api-key "abc123"})))
    (is (= {:secret "[REDACTED]"}
           (storage/redact-sensitive-map {:secret "hidden"}))))

  (testing "preserves non-sensitive keys"
    (is (= {:username "john" :email "john@test.com"}
           (storage/redact-sensitive-map {:username "john" :email "john@test.com"}))))

  (testing "handles mixed keys"
    (is (= {:name "test" :password "[REDACTED]"}
           (storage/redact-sensitive-map {:name "test" :password "secret"}))))

  (testing "handles string keys for sensitive fields"
    (is (= {"password" "[REDACTED]"}
           (storage/redact-sensitive-map {"password" "secret123"})))
    (is (= {"api_key" "[REDACTED]"}
           (storage/redact-sensitive-map {"api_key" "abc123"})))))


(deftest redact-sensitive-deep-test
  (testing "redacts nested maps"
    (is (= {:config {:db {:password "[REDACTED]"}} :name "test"}
           (storage/redact-sensitive-deep
             {:config {:db {:password "secret"}} :name "test"}))))

  (testing "redacts in vectors"
    (is (= [{:password "[REDACTED]"} {:password "[REDACTED]"}]
           (storage/redact-sensitive-deep
             [{:password "p1"} {:password "p2"}]))))

  (testing "redacts in sets"
    (let [result (storage/redact-sensitive-deep
                   #{{:password "secret1"} {:password "secret2"}})]
      (is (set? result))
      (is (every? #(= "[REDACTED]" (:password %)) result))))

  (testing "redacts in sequences"
    (let [result (storage/redact-sensitive-deep
                   (list {:password "p1"} {:password "p2"}))]
      (is (seq? result))
      (is (every? #(= "[REDACTED]" (:password %)) result))))

  (testing "preserves non-sensitive data"
    (is (= {:user {:name "john"}}
           (storage/redact-sensitive-deep {:user {:name "john"}}))))

  (testing "handles nil and other types"
    (is (nil? (storage/redact-sensitive-deep nil)))
    (is (= "string" (storage/redact-sensitive-deep "string")))
    (is (= 42 (storage/redact-sensitive-deep 42)))))


;; === NULL Handling Contract Tests ===
;;
;; These tests document the expected NULL semantics for storage implementations.
;; All storage backends (postgres, datomic, memory) MUST follow SQL NULL semantics:
;;
;; 1. Unique constraints: NULL values are NOT considered equal for uniqueness
;;    - Multiple rows can have NULL in a unique-constrained column
;;    - Example: unique(:email) allows multiple rows with email=NULL
;;
;; 2. Equality comparisons: NULL = NULL returns UNKNOWN (not TRUE)
;;    - NULL != any_value returns UNKNOWN
;;    - WHERE field = NULL never matches (use WHERE field IS NULL)
;;
;; 3. Query behavior: Queries with where {:field nil} should match NULL values

(deftest null-handling-contract-uniqueness-test
  (testing "NULL semantics contract: multiple NULLs allowed in unique field"
    ;; This documents the contract that storage implementations must follow.
    ;; The actual enforcement happens at the storage level.
    ;; Memory storage: must explicitly skip NULL values in uniqueness checks
    ;; Postgres: native SQL NULL semantics
    ;; Datomic: doesn't store nil values, so uniqueness on absent field is automatic
    (let [field-specs {:email {:type :text :nullable? true}
                       :name {:type :text :nullable? false}}
          ;; Two records with nil email - should both be valid
          record1 {:name "Alice" :email nil}
          record2 {:name "Bob" :email nil}]
      ;; Both pass required field validation (email is nullable)
      (is (nil? (storage/validate-required-fields! :user field-specs record1)))
      (is (nil? (storage/validate-required-fields! :user field-specs record2)))))

  (testing "NULL semantics contract: NULL in required field rejected"
    (let [field-specs {:email {:type :text :nullable? false}}
          record {:email nil}]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Required field 'email' is missing or nil"
            (storage/validate-required-fields! :user field-specs record)))))

  (testing "NULL semantics: missing field in nullable spec is valid"
    (let [field-specs {:email {:type :text :nullable? true}
                       :name {:type :text :nullable? false}}
          record {:name "Alice"}]  ; email not provided at all
      (is (nil? (storage/validate-required-fields! :user field-specs record))))))


(deftest null-handling-contract-nullable-changes-test
  (testing "nullable to non-nullable is destructive (may break existing data)"
    (is (not (storage/safe-nullable-change? true false))))

  (testing "non-nullable to nullable is safe"
    (is (true? (storage/safe-nullable-change? false true))))

  (testing "same nullable value is always safe"
    (is (true? (storage/safe-nullable-change? true true)))
    (is (true? (storage/safe-nullable-change? false false))))

  (testing "check-nullable-change! throws for unsafe changes"
    (is (thrown-with-msg?
          clojure.lang.ExceptionInfo
          #"nullable to non-nullable"
          (storage/check-nullable-change! :user :email true false))))

  (testing "check-nullable-change! passes for safe changes"
    (is (nil? (storage/check-nullable-change! :user :email false true)))
    (is (nil? (storage/check-nullable-change! :user :email nil false)))))


(deftest null-handling-type-category-test
  (testing "canonical field types are defined for all backends"
    ;; Contract: all field types must have defined behavior for NULL handling
    (doseq [t storage/canonical-field-types]
      (is (contains? storage/type-category t)
          (str "Type " t " must have a category defined")))))


(deftest null-handling-in-batch-operations-test
  (testing "duplicate ID check ignores nil IDs"
    ;; Records without explicit :id get auto-generated UUIDs
    ;; So nil IDs should not trigger duplicate detection
    (let [data-seq [{:name "Alice"}  ; no :id
                    {:name "Bob"}]]  ; no :id
      (is (nil? (storage/validate-no-duplicate-ids! :user data-seq)))))

  (testing "nil IDs in explicit field are handled"
    ;; When :id is explicitly nil, it's treated as "auto-generate"
    (let [data-seq [{:id nil :name "Alice"}
                    {:id nil :name "Bob"}]]
      ;; Should not throw - nil is not counted as a duplicate
      (is (nil? (storage/validate-no-duplicate-ids! :user data-seq))))))


;; === GraphDataLoader protocol tests ===

(deftest graph-data-loader-protocol-test
  (testing "GraphDataLoader protocol is defined"
    (is (some? storage/GraphDataLoader))
    (is (contains? (:sigs storage/GraphDataLoader) :load-fn-record))
    (is (contains? (:sigs storage/GraphDataLoader) :load-fn-schema-record))
    (is (contains? (:sigs storage/GraphDataLoader) :load-arg-schemas-for-fn-schema))
    (is (contains? (:sigs storage/GraphDataLoader) :load-parent-chain))
    (is (contains? (:sigs storage/GraphDataLoader) :load-arg-values-for-fns))
    (is (contains? (:sigs storage/GraphDataLoader) :classify-uuid-refs))))


;; === ExecutionGraphReader protocol tests ===

(deftest execution-graph-reader-protocol-test
  (testing "ExecutionGraphReader protocol is defined"
    (is (some? storage/ExecutionGraphReader))
    (is (contains? (:sigs storage/ExecutionGraphReader) :graph-get-fn))
    (is (contains? (:sigs storage/ExecutionGraphReader) :graph-get-fn-schema))
    (is (contains? (:sigs storage/ExecutionGraphReader) :graph-get-arg-schemas))
    (is (contains? (:sigs storage/ExecutionGraphReader) :graph-get-resolved-args))
    (is (contains? (:sigs storage/ExecutionGraphReader) :graph-get-fn-result-value)))

  (testing "ExecutionGraphResult implements ExecutionGraphReader"
    (let [fn-id (random-uuid)
          fn-schema-id (random-uuid)
          arg-schema-id (random-uuid)
          frv-id (random-uuid)
          graph (storage/->execution-graph
                  {:fns {fn-id {:id fn-id :fn-schema-id fn-schema-id}}
                   :fn-schemas {fn-schema-id {:id fn-schema-id :name "test-fn"}}
                   :arg-schemas {arg-schema-id {:id arg-schema-id :fn-schema-id fn-schema-id}}
                   :resolved-args {fn-id {arg-schema-id {:value 42}}}
                   :fn-result-values {frv-id {:id frv-id :value "result"}}})]
      ;; Test protocol methods
      (is (= {:id fn-id :fn-schema-id fn-schema-id}
             (storage/graph-get-fn graph fn-id)))
      (is (= {:id fn-schema-id :name "test-fn"}
             (storage/graph-get-fn-schema graph fn-schema-id)))
      ;; graph-get-arg-schemas returns a map of {arg-schema-id -> arg-schema-record}
      (is (= {arg-schema-id {:id arg-schema-id :fn-schema-id fn-schema-id}}
             (storage/graph-get-arg-schemas graph fn-schema-id)))
      (is (= {arg-schema-id {:value 42}}
             (storage/graph-get-resolved-args graph fn-id)))
      (is (= {:id frv-id :value "result"}
             (storage/graph-get-fn-result-value graph frv-id)))))

  (testing "ExecutionGraphReader returns nil/empty for missing keys"
    (let [graph (storage/->execution-graph
                  {:fns {(random-uuid) {:id (random-uuid)}}
                   :fn-schemas {(random-uuid) {:id (random-uuid)}}
                   :arg-schemas {}
                   :resolved-args {}})]
      (is (nil? (storage/graph-get-fn graph (random-uuid))))
      (is (nil? (storage/graph-get-fn-schema graph (random-uuid))))
      ;; Returns empty map for missing fn-schema-id (no matching arg-schemas)
      (is (= {} (storage/graph-get-arg-schemas graph (random-uuid))))
      (is (nil? (storage/graph-get-resolved-args graph (random-uuid))))
      (is (nil? (storage/graph-get-fn-result-value graph (random-uuid)))))))
