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
        (is (false? (:new-nullable? (ex-data e))))))))


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
    (is (contains? (:sigs storage/GraphConstraints) :validate-no-dependency-cycle!))))


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


;; === check-graph-iteration-limit! tests ===

(deftest check-graph-iteration-limit!-test
  (testing "under limit doesn't throw"
    (is (nil? (storage/check-graph-iteration-limit! 0 (random-uuid))))
    (is (nil? (storage/check-graph-iteration-limit! 100 (random-uuid))))
    (is (nil? (storage/check-graph-iteration-limit! 9999 (random-uuid))))
    (is (nil? (storage/check-graph-iteration-limit! storage/max-graph-iterations (random-uuid)))))

  (testing "over limit throws"
    (let [fn-id (random-uuid)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"exceeded maximum iterations"
            (storage/check-graph-iteration-limit! (inc storage/max-graph-iterations) fn-id)))))

  (testing "exception contains correct data"
    (let [fn-id (random-uuid)]
      (try
        (storage/check-graph-iteration-limit! 10001 fn-id)
        (catch clojure.lang.ExceptionInfo e
          (is (= :execution-error/graph-too-large (:type (ex-data e))))
          (is (= fn-id (:fn-id (ex-data e))))
          (is (= storage/max-graph-iterations (:max-iterations (ex-data e))))
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


;; === validate-no-dependency-cycle-impl tests ===

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
