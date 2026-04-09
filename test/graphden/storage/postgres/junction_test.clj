(ns graphden.storage.postgres.junction-test
  "Tests for junction table operations (:ref-many fields)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.postgres.ddl :as ddl]
    [graphden.storage.postgres.junction :as junction]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


;; ============================================================================
;; Pure-function helpers (no DB)
;; ============================================================================

(deftest ref-many-fields-test
  (testing "filters fields with :ref-many type"
    (let [fields {:name {:type :text}
                  :parent-ids {:type :ref-many :ref-entity :fn}
                  :tags {:type :ref-many :ref-entity :tag}
                  :age {:type :int}}
          result (junction/ref-many-fields fields)]
      (is (= 2 (count result)))
      (is (= #{:parent-ids :tags} (set (map first result))))))

  (testing "returns empty when no ref-many fields"
    (is (empty? (junction/ref-many-fields {:name {:type :text}})))
    (is (empty? (junction/ref-many-fields {})))))


(deftest has-ref-many-test
  (testing "returns true when at least one ref-many field exists"
    (is (true? (junction/has-ref-many?
                 {:name {:type :text}
                  :tags {:type :ref-many :ref-entity :tag}}))))

  (testing "returns false when no ref-many fields"
    (is (false? (junction/has-ref-many? {:name {:type :text}})))
    (is (false? (junction/has-ref-many? {})))))


(deftest extract-ref-many-data-test
  (testing "splits data into columnar and ref-many parts"
    (let [fields {:name {:type :text}
                  :parent-ids {:type :ref-many :ref-entity :fn}}
          data {:id #uuid "00000000-0000-0000-0000-000000000001"
                :name "test"
                :parent-ids [#uuid "00000000-0000-0000-0000-000000000002"]}
          [columnar ref-many] (junction/extract-ref-many-data data fields)]
      (is (= {:id #uuid "00000000-0000-0000-0000-000000000001"
              :name "test"} columnar))
      (is (= {:parent-ids [#uuid "00000000-0000-0000-0000-000000000002"]}
             ref-many))))

  (testing "returns empty ref-many map when no ref-many fields in data"
    (let [fields {:name {:type :text}
                  :parent-ids {:type :ref-many :ref-entity :fn}}
          data {:name "test"}
          [columnar ref-many] (junction/extract-ref-many-data data fields)]
      (is (= {:name "test"} columnar))
      (is (empty? ref-many))))

  (testing "returns whole data as columnar when no ref-many in fields"
    (let [fields {:name {:type :text}}
          data {:name "test" :extra "value"}
          [columnar ref-many] (junction/extract-ref-many-data data fields)]
      (is (= data columnar))
      (is (empty? ref-many)))))


;; ============================================================================
;; DB-backed integration tests
;; ============================================================================

(deftest single-inheritance-create-read-test
  (testing "create fn with single parent and read it back"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          parent (sp/create-entity storage :fn {:name "parent" :return-type "custom-type"})
          child (sp/create-entity storage :fn {:name "child"
                                               :parent-ids [(:id parent)]
                                               :return-type "custom-type"})]
      (try
        (is (= [(:id parent)] (:parent-ids child)))
        (let [read-back (sp/read-entity storage :fn (:id child))]
          (is (= [(:id parent)] (:parent-ids read-back))))
        (finally (sp/close storage))))))


(deftest multiple-inheritance-create-read-test
  (testing "create fn with multiple parents preserves order"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          p1 (sp/create-entity storage :fn {:name "parent-1" :return-type "custom-type"})
          p2 (sp/create-entity storage :fn {:name "parent-2" :return-type "custom-type"})
          p3 (sp/create-entity storage :fn {:name "parent-3" :return-type "custom-type"})
          child (sp/create-entity storage :fn {:name "child"
                                               :parent-ids [(:id p1) (:id p2) (:id p3)]
                                               :return-type "custom-type"})]
      (try
        ;; Order must be preserved
        (is (= [(:id p1) (:id p2) (:id p3)] (:parent-ids child)))
        (let [read-back (sp/read-entity storage :fn (:id child))]
          (is (= [(:id p1) (:id p2) (:id p3)] (:parent-ids read-back))))
        (finally (sp/close storage))))))


(deftest no-parents-base-fn-test
  (testing "base fn read back has empty parent-ids vector"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          base-fn (sp/create-entity storage :fn {:name "base" :return-type "custom-type"})]
      (try
        ;; read-back populates :parent-ids from junction (empty for base-fn)
        (let [read-back (sp/read-entity storage :fn (:id base-fn))]
          (is (= [] (:parent-ids read-back))))
        (finally (sp/close storage))))))


(deftest update-replaces-junction-rows-test
  (testing "updating parent-ids replaces junction rows"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          p1 (sp/create-entity storage :fn {:name "p1" :return-type "custom-type"})
          p2 (sp/create-entity storage :fn {:name "p2" :return-type "custom-type"})
          p3 (sp/create-entity storage :fn {:name "p3" :return-type "custom-type"})
          child (sp/create-entity storage :fn {:name "child"
                                               :parent-ids [(:id p1) (:id p2)]
                                               :return-type "custom-type"})]
      (try
        (is (= [(:id p1) (:id p2)] (:parent-ids child)))
        ;; Replace with single parent
        (sp/update-entity storage :fn (:id child) {:parent-ids [(:id p3)]})
        (is (= [(:id p3)] (:parent-ids (sp/read-entity storage :fn (:id child)))))
        ;; Replace with empty (no parents)
        (sp/update-entity storage :fn (:id child) {:parent-ids []})
        (is (= [] (:parent-ids (sp/read-entity storage :fn (:id child)))))
        (finally (sp/close storage))))))


(deftest delete-cascades-junction-rows-test
  (testing "deleting fn cascades junction rows"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          parent (sp/create-entity storage :fn {:name "parent" :return-type "custom-type"})
          child (sp/create-entity storage :fn {:name "child"
                                               :parent-ids [(:id parent)]
                                               :return-type "custom-type"})]
      (try
        (sp/delete-entity storage :fn (:id child))
        (is (nil? (sp/read-entity storage :fn (:id child))))
        ;; Parent still exists
        (is (some? (sp/read-entity storage :fn (:id parent))))
        (finally (sp/close storage))))))


(deftest query-entities-populates-junction-test
  (testing "query-entities populates :ref-many fields for all results"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          p1 (sp/create-entity storage :fn {:name "p1" :return-type "custom-type"})
          p2 (sp/create-entity storage :fn {:name "p2" :return-type "custom-type"})
          c1 (sp/create-entity storage :fn {:name "c1"
                                            :parent-ids [(:id p1)]
                                            :return-type "custom-type"})
          c2 (sp/create-entity storage :fn {:name "c2"
                                            :parent-ids [(:id p1) (:id p2)]
                                            :return-type "custom-type"})]
      (try
        (let [all-fns (sp/query-entities storage :fn {})
              by-id (into {} (map (juxt :id identity)) all-fns)]
          (is (= [] (:parent-ids (get by-id (:id p1)))))
          (is (= [] (:parent-ids (get by-id (:id p2)))))
          (is (= [(:id p1)] (:parent-ids (get by-id (:id c1)))))
          (is (= [(:id p1) (:id p2)] (:parent-ids (get by-id (:id c2))))))
        (finally (sp/close storage))))))


(deftest batch-create-with-junction-test
  (testing "batch create-entities writes junction rows for all records"
    (let [storage (setup/create-test-storage)
          schema (setup/make-graph-schema)
          _ (sp/initialize storage schema)
          parent (sp/create-entity storage :fn {:name "parent" :return-type "custom-type"})
          children (sp/create-entities storage :fn
                                       [{:name "c1" :parent-ids [(:id parent)] :return-type "custom-type"}
                                        {:name "c2" :parent-ids [(:id parent)] :return-type "custom-type"}
                                        {:name "c3" :return-type "custom-type"}])]
      (try
        (is (= 3 (count children)))
        (let [c1 (first (filter #(= "c1" (:name %)) children))
              c2 (first (filter #(= "c2" (:name %)) children))
              c3 (first (filter #(= "c3" (:name %)) children))]
          (is (= [(:id parent)] (:parent-ids (sp/read-entity storage :fn (:id c1)))))
          (is (= [(:id parent)] (:parent-ids (sp/read-entity storage :fn (:id c2)))))
          (is (= [] (:parent-ids (sp/read-entity storage :fn (:id c3))))))
        (finally (sp/close storage))))))


(deftest junction-table-name-test
  (testing "generates correct junction table name"
    (is (= "fn_parent_ids" (ddl/junction-table-name :fn :parent-ids)))
    (is (= "user_tags" (ddl/junction-table-name :user :tags)))))
