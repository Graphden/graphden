(ns graphden.storage.postgres.metadata-corruption-test
  "Tests for PostgreSQL storage metadata corruption handling and caching."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.interface :as ds]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.protocol.interface :as sp]
    [graphden.storage.protocol.test-helpers :as th]
    [next.jdbc :as jdbc]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


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


;; === Metadata caching tests ===

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
