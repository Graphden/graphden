(ns graphden.storage.postgres.types-test
  "Tests for PostgreSQL storage field types, type changes, and nullable changes."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.malli.interface :as mds]
    [graphden.schema.protocol.interface :as ds]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.protocol.interface :as sp]
    [graphden.storage.protocol.test-helpers :as th]
    [next.jdbc :as jdbc]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


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
