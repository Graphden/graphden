(ns graphden.datomic-storage.migration-edge-cases-test
  "Tests for datomic-storage migration edge cases and private functions."
  (:require
    [clojure.test :refer [deftest is testing]]
    [datomic.client.api :as d]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.datomic-storage.interface :as dat]
    [graphden.datomic-storage.introspection :as introspection]
    [graphden.datomic-storage.schema :as schema]
    [graphden.datomic-storage.test-setup :as setup]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.test-helpers :as th]))


(deftest metadata-db-inconsistency-test
  (testing "detects when metadata says field exists but DB attribute is missing"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})]
      (try
        ;; First initialize normally
        (sp/initialize storage schema1)
        ;; Now mock read-metadata to return metadata claiming a non-existent field
        (let [fake-metadata {:entities {entity-uuid :user}
                             :fields {field-uuid {:entity :user
                                                  :field :name
                                                  :type :text
                                                  :nullable? false}
                                      ;; This field doesn't exist in DB!
                                      #uuid "00000000-0000-0000-0000-000000000099"
                                      {:entity :user
                                       :field :ghost-field
                                       :type :text
                                       :nullable? false}}
                             :enums {}
                             :enum-values {}}
              ;; Schema that references the ghost field by UUID
              schema2 (-> (mds/create-builder)
                          (ds/add-entity :user entity-uuid
                                         {:name {:uuid field-uuid :type :text}
                                          :ghost-field {:uuid #uuid "00000000-0000-0000-0000-000000000099"
                                                        :type :text}})
                          ds/build)]
          (with-redefs [introspection/read-metadata (constantly fake-metadata)]
            (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                  #"Metadata/DB inconsistency"
                  (sp/initialize storage schema2)))))
        (finally
          (sp/close storage))))))


(deftest metadata-schema-missing-test
  (testing "metadata-schema-exists? returns false when metadata schema not installed"
    ;; This test verifies that querying for non-existent metadata returns false
    ;; We initialize with a minimal schema that doesn't include graphden.metadata attributes
    ;; then directly query to confirm metadata-schema-exists? returns false on fresh db
    (let [storage (setup/create-test-storage)
          metadata-exists-fn #'introspection/metadata-schema-exists?]
      (try
        ;; First, create the client and database without initializing schema
        ;; This gives us a fresh db without metadata schema
        (let [client (d/client {:server-type :dev-local
                                :storage-dir :mem
                                :system "graphden-test"})
              temp-db-name (str "test-fresh-" (random-uuid))]
          (try
            (d/create-database client {:db-name temp-db-name})
            (let [conn (d/connect client {:db-name temp-db-name})
                  db (d/db conn)]
              ;; Fresh database - no metadata schema exists
              ;; Function returns nil (falsy) when schema doesn't exist
              (is (not (metadata-exists-fn db))))
            (finally
              ;; Cleanup
              (d/delete-database client {:db-name temp-db-name}))))
        (finally
          (sp/close storage))))))


;; === Private function unit tests ===

(deftest single-field-unique-constraint?-test
  (let [single-field-unique-constraint? schema/single-field-unique-constraint?]
    (testing "returns true for single-field unique constraint"
      (is (true? (single-field-unique-constraint? {:type :unique :fields [:email]}))))

    (testing "returns false for multi-field unique constraint"
      (is (false? (single-field-unique-constraint? {:type :unique :fields [:first-name :last-name]}))))

    (testing "returns false for non-unique constraint type"
      (is (false? (single-field-unique-constraint? {:type :other :fields [:field]}))))))


(deftest initialize-error-handling-test
  (testing "initialize re-throws non-'already exists' exceptions from create-database"
    (let [storage (dat/create-storage {:db-name (setup/unique-db-name)})
          schema (th/make-schema)
          ;; Store original create-database
          original-create-db d/create-database
          call-count (atom 0)]
      (try
        ;; Mock create-database to throw a different error
        (with-redefs [d/create-database
                      (fn [& args]
                        (swap! call-count inc)
                        (if (= 1 @call-count)
                          ;; First call - throw a non-"already exists" error
                          (throw (ex-info "Connection refused" {:error :connection-refused}))
                          ;; Subsequent calls - use original
                          (apply original-create-db args)))]
          (is (thrown-with-msg? clojure.lang.ExceptionInfo
                                #"Connection refused"
                (sp/initialize storage schema))))
        (finally
          (sp/close storage))))))


(deftest current-attrs-edge-cases-test
  (testing "filters out idents without namespace"
    (let [current-attrs-fn #'introspection/current-attrs
          ;; Mock query results with some idents without namespace
          fake-results [[:db/ident :db.type/string]
                        ['no-namespace-symbol :db.type/string] ; symbol without namespace
                        [:user/name :db.type/string]]]
      (with-redefs [d/q (constantly fake-results)]
        (let [result (current-attrs-fn :fake-db)]
          ;; Should have filtered out the non-namespaced one and db/* ones
          (is (= {:user/name :db.type/string} result))))))

  (testing "filters out idents from db and fressian namespaces"
    (let [current-attrs-fn #'introspection/current-attrs
          fake-results [[:db/ident :db.type/ref]
                        [:fressian/tag :db.type/string]
                        [:graphden.metadata/uuid :db.type/uuid]
                        [:myapp/field :db.type/string]]]
      (with-redefs [d/q (constantly fake-results)]
        (let [result (current-attrs-fn :fake-db)]
          (is (= {:myapp/field :db.type/string} result)))))))


(deftest current-enum-values-db-edge-cases-test
  (testing "filters out idents without namespace"
    (let [current-enum-values-db-fn #'introspection/current-enum-values-db
          ;; Include idents without namespace - they should be filtered
          fake-results [['no-namespace] [:status.value/active] [:other/thing]]]
      (with-redefs [d/q (constantly fake-results)]
        (let [result (current-enum-values-db-fn :fake-db)]
          ;; Only :status.value/active has .value in namespace
          (is (= [:status.value/active] result)))))))


(deftest read-metadata-empty-test
  (testing "read-metadata returns nil when no metadata entities exist"
    (let [read-metadata-fn #'introspection/read-metadata
          ;; Mock all queries to return empty - but first query is metadata-schema-exists?
          ;; which checks for :graphden.metadata/uuid attribute
          query-results (atom 0)]
      (with-redefs [d/q (fn [& _]
                          (swap! query-results inc)
                          (case @query-results
                            1 [[123]] ; metadata-schema-exists? returns truthy
                            2 []      ; entities
                            3 []      ; fields
                            4 []      ; fields-enum-names
                            5 []      ; fields-ref-entities
                            6 []      ; enums
                            7 []))]   ; enum-values
        (let [result (read-metadata-fn :fake-db)]
          (is (nil? result))))))

  (testing "read-metadata returns data when entities exist but other types are empty"
    (let [read-metadata-fn #'introspection/read-metadata
          entity-uuid #uuid "11111111-1111-1111-1111-111111111111"
          query-results (atom 0)]
      (with-redefs [d/q (fn [& _]
                          (swap! query-results inc)
                          (case @query-results
                            1 [[123]]                   ; metadata-schema-exists?
                            2 [[entity-uuid :user]]     ; entities
                            3 []                        ; fields
                            4 []                        ; fields-enum-names
                            5 []                        ; fields-ref-entities
                            6 []                        ; enums
                            7 []))]                     ; enum-values
        (let [result (read-metadata-fn :fake-db)]
          (is (some? result))
          (is (= {entity-uuid :user} (:entities result)))
          (is (= {} (:fields result))))))))
