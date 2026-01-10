(ns graphden.cache-postgres.interface-test
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.cache-data-schema.interface :as cds]
    [graphden.cache-postgres.interface :as cache-pg]
    [graphden.cache-protocol.interface :as cache]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.postgres-storage.interface :as pg]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.postgres-test-helpers :as pth]))


;; === Testcontainers setup ===

(def ^:dynamic *container* nil)


(use-fixtures :once (pth/create-container-fixture #'*container*))
(use-fixtures :each (pth/create-clean-db-fixture #'*container*))


(defn- create-test-storage
  "Creates a test storage with cache schema initialized."
  []
  (pth/clean-database-fast! *container*)
  (let [storage (pg/create-storage (pth/get-container-config *container*))
        schema (cds/build-schema (mds/create-builder))]
    (sp/initialize storage schema)
    storage))


(defn- get-datasource
  "Gets the datasource (pool) from storage."
  [storage]
  (:pool storage))


;; === Unit tests (no DB required) ===

(deftest create-cache-test
  (testing "creates PostgresCache instance"
    (let [mock-ds (reify javax.sql.DataSource)]
      (is (some? (cache-pg/create-cache mock-ds)))
      (is (cache/cached-storage? (cache-pg/create-cache mock-ds))))))


;; === Integration tests ===

(deftest cache-exists-integration-test
  (testing "returns false for non-existent cache"
    (let [storage (create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (get-datasource storage))
              fn-id (random-uuid)]
          (is (false? (cache/cache-exists? cache fn-id))))
        (finally
          (sp/close storage))))))


(deftest save-and-get-cache-integration-test
  (testing "saves and retrieves execution graph"
    (let [storage (create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (get-datasource storage))
              ;; Create fn-schema first (required by FK constraints)
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "test-schema"
                                           :returned-type :text
                                           :base-fn-name "base-fn"})
              fn-schema-id (:id fn-schema)
              ;; Create arg-schema
              arg-schema (sp/create-entity storage :arg-schema
                                           {:fn-schema-id fn-schema-id
                                            :name "arg1"
                                            :type :text
                                            :required true})
              arg-schema-id (:id arg-schema)
              ;; Create fn
              fn-record (sp/create-entity storage :fn
                                          {:name "test-fn"
                                           :fn-schema-id fn-schema-id})
              fn-id (:id fn-record)
              ;; Build graph
              graph {:fns {fn-id {:id fn-id
                                  :name "test-fn"
                                  :fn-schema-id fn-schema-id
                                  :parent-fn-id nil}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "test-schema"
                                                :base-fn-name "base-fn"
                                                :returned-type :text}}
                     :arg-schemas {arg-schema-id {:id arg-schema-id
                                                  :fn-schema-id fn-schema-id
                                                  :name "arg1"
                                                  :type :text
                                                  :required true}}
                     :resolved-args {fn-id {arg-schema-id "test-value"}}
                     :fn-result-values {}}
              dependencies {:fn-ids {fn-id 1}
                            :fn-schema-ids {fn-schema-id 1}
                            :arg-schema-ids {arg-schema-id 1}}]
          ;; Save cache
          (cache/save-cache! cache fn-id graph dependencies)
          ;; Verify it exists
          (is (true? (cache/cache-exists? cache fn-id)))
          ;; Get cached graph
          (let [cached (cache/get-cached-graph cache fn-id)]
            (is (some? cached))
            (is (sp/execution-graph? cached))
            ;; Verify fns
            (is (= 1 (count (:fns cached))))
            (is (= "test-fn" (get-in cached [:fns fn-id :name])))
            ;; Verify fn-schemas
            (is (= 1 (count (:fn-schemas cached))))
            (is (= "test-schema" (get-in cached [:fn-schemas fn-schema-id :name])))
            (is (= :text (get-in cached [:fn-schemas fn-schema-id :returned-type])))
            ;; Verify arg-schemas
            (is (= 1 (count (:arg-schemas cached))))
            (is (= "arg1" (get-in cached [:arg-schemas arg-schema-id :name])))
            (is (= :text (get-in cached [:arg-schemas arg-schema-id :type])))
            ;; Verify resolved-args
            (is (= "test-value" (get-in cached [:resolved-args fn-id arg-schema-id])))))
        (finally
          (sp/close storage))))))


(deftest delete-cache-integration-test
  (testing "deletes cache data"
    (let [storage (create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "test-schema"
                                           :returned-type :text
                                           :base-fn-name "base-fn"})
              fn-schema-id (:id fn-schema)
              fn-record (sp/create-entity storage :fn
                                          {:name "test-fn"
                                           :fn-schema-id fn-schema-id})
              fn-id (:id fn-record)
              graph {:fns {fn-id {:id fn-id
                                  :name "test-fn"
                                  :fn-schema-id fn-schema-id
                                  :parent-fn-id nil}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "test-schema"
                                                :base-fn-name "base-fn"
                                                :returned-type :text}}
                     :arg-schemas {}
                     :resolved-args {}
                     :fn-result-values {}}
              dependencies {:fn-ids {} :fn-schema-ids {} :arg-schema-ids {}}]
          ;; Save and verify
          (cache/save-cache! cache fn-id graph dependencies)
          (is (true? (cache/cache-exists? cache fn-id)))
          ;; Delete
          (is (true? (cache/delete-cache! cache fn-id)))
          ;; Verify deleted
          (is (false? (cache/cache-exists? cache fn-id)))
          (is (nil? (cache/get-cached-graph cache fn-id)))
          ;; Delete again returns false
          (is (false? (cache/delete-cache! cache fn-id))))
        (finally
          (sp/close storage))))))


(deftest find-caches-by-deps-integration-test
  (testing "finds caches by dependency"
    (let [storage (create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :text
                                           :base-fn-name "base"})
              fn-schema-id (:id fn-schema)
              arg-schema (sp/create-entity storage :arg-schema
                                           {:fn-schema-id fn-schema-id
                                            :name "arg"
                                            :type :text
                                            :required false})
              arg-schema-id (:id arg-schema)
              ;; Create shared dependency fn
              shared-dep-fn (sp/create-entity storage :fn
                                              {:name "shared-dep"
                                               :fn-schema-id fn-schema-id})
              shared-dep-fn-id (:id shared-dep-fn)
              ;; First cache fn
              fn-record-1 (sp/create-entity storage :fn
                                            {:name "fn-1"
                                             :fn-schema-id fn-schema-id})
              fn-id-1 (:id fn-record-1)
              ;; Second cache fn
              fn-record-2 (sp/create-entity storage :fn
                                            {:name "fn-2"
                                             :fn-schema-id fn-schema-id})
              fn-id-2 (:id fn-record-2)
              ;; Build graphs
              make-graph (fn [fid fname]
                           {:fns {fid {:id fid
                                       :name fname
                                       :fn-schema-id fn-schema-id
                                       :parent-fn-id nil}}
                            :fn-schemas {fn-schema-id {:id fn-schema-id
                                                       :name "schema"
                                                       :base-fn-name "base"
                                                       :returned-type :text}}
                            :arg-schemas {arg-schema-id {:id arg-schema-id
                                                         :fn-schema-id fn-schema-id
                                                         :name "arg"
                                                         :type :text
                                                         :required false}}
                            :resolved-args {}
                            :fn-result-values {}})
              deps {:fn-ids {shared-dep-fn-id 1}
                    :fn-schema-ids {fn-schema-id 1}
                    :arg-schema-ids {arg-schema-id 1}}]
          ;; Save both caches
          (cache/save-cache! cache fn-id-1 (make-graph fn-id-1 "fn-1") deps)
          (cache/save-cache! cache fn-id-2 (make-graph fn-id-2 "fn-2") deps)
          ;; Find caches by fn dependency
          (let [affected (cache/find-caches-by-fn-dep cache shared-dep-fn-id)]
            (is (set? affected))
            (is (= 2 (count affected)))
            (is (contains? affected fn-id-1))
            (is (contains? affected fn-id-2)))
          ;; Find caches by fn-schema dependency
          (let [affected (cache/find-caches-by-fn-schema-dep cache fn-schema-id)]
            (is (= 2 (count affected))))
          ;; Find caches by arg-schema dependency
          (let [affected (cache/find-caches-by-arg-schema-dep cache arg-schema-id)]
            (is (= 2 (count affected)))))
        (finally
          (sp/close storage))))))


(deftest cache-with-parent-fn-integration-test
  (testing "handles parent-fn-id correctly"
    (let [storage (create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :int
                                           :base-fn-name "base"})
              fn-schema-id (:id fn-schema)
              parent-fn (sp/create-entity storage :fn
                                          {:name "parent"
                                           :fn-schema-id fn-schema-id})
              parent-fn-id (:id parent-fn)
              child-fn (sp/create-entity storage :fn
                                         {:name "child"
                                          :fn-schema-id fn-schema-id
                                          :parent-fn-id parent-fn-id})
              child-fn-id (:id child-fn)
              graph {:fns {parent-fn-id {:id parent-fn-id
                                         :name "parent"
                                         :fn-schema-id fn-schema-id
                                         :parent-fn-id nil}
                           child-fn-id {:id child-fn-id
                                        :name "child"
                                        :fn-schema-id fn-schema-id
                                        :parent-fn-id parent-fn-id}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "schema"
                                                :base-fn-name "base"
                                                :returned-type :int}}
                     :arg-schemas {}
                     :resolved-args {}
                     :fn-result-values {}}
              dependencies {:fn-ids {parent-fn-id 1 child-fn-id 1}
                            :fn-schema-ids {fn-schema-id 1}
                            :arg-schema-ids {}}]
          (cache/save-cache! cache child-fn-id graph dependencies)
          (let [cached (cache/get-cached-graph cache child-fn-id)]
            (is (= 2 (count (:fns cached))))
            (is (nil? (get-in cached [:fns parent-fn-id :parent-fn-id])))
            (is (= parent-fn-id (get-in cached [:fns child-fn-id :parent-fn-id])))))
        (finally
          (sp/close storage))))))


(deftest cache-overwrites-existing-integration-test
  (testing "save-cache! overwrites existing cache"
    (let [storage (create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :text
                                           :base-fn-name "base"})
              fn-schema-id (:id fn-schema)
              fn-record (sp/create-entity storage :fn
                                          {:name "v1"
                                           :fn-schema-id fn-schema-id})
              fn-id (:id fn-record)
              make-graph (fn [fn-name]
                           {:fns {fn-id {:id fn-id
                                         :name fn-name
                                         :fn-schema-id fn-schema-id
                                         :parent-fn-id nil}}
                            :fn-schemas {fn-schema-id {:id fn-schema-id
                                                       :name "schema"
                                                       :base-fn-name "base"
                                                       :returned-type :text}}
                            :arg-schemas {}
                            :resolved-args {}
                            :fn-result-values {}})
              deps {:fn-ids {} :fn-schema-ids {} :arg-schema-ids {}}]
          ;; Save v1
          (cache/save-cache! cache fn-id (make-graph "v1") deps)
          (is (= "v1" (get-in (cache/get-cached-graph cache fn-id) [:fns fn-id :name])))
          ;; Save v2 (should overwrite)
          (cache/save-cache! cache fn-id (make-graph "v2") deps)
          (is (= "v2" (get-in (cache/get-cached-graph cache fn-id) [:fns fn-id :name]))))
        (finally
          (sp/close storage))))))


(deftest dependency-cleanup-on-overwrite-integration-test
  (testing "old dependencies are cleaned up when cache is overwritten"
    (let [storage (create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :text
                                           :base-fn-name "base"})
              fn-schema-id (:id fn-schema)
              ;; Create fns for dependencies
              old-dep-fn (sp/create-entity storage :fn
                                           {:name "old-dep"
                                            :fn-schema-id fn-schema-id})
              old-dep-fn-id (:id old-dep-fn)
              new-dep-fn (sp/create-entity storage :fn
                                           {:name "new-dep"
                                            :fn-schema-id fn-schema-id})
              new-dep-fn-id (:id new-dep-fn)
              cache-fn (sp/create-entity storage :fn
                                         {:name "cache-fn"
                                          :fn-schema-id fn-schema-id})
              fn-id (:id cache-fn)
              graph {:fns {fn-id {:id fn-id
                                  :name "cache-fn"
                                  :fn-schema-id fn-schema-id
                                  :parent-fn-id nil}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "schema"
                                                :base-fn-name "base"
                                                :returned-type :text}}
                     :arg-schemas {}
                     :resolved-args {}
                     :fn-result-values {}}
              old-deps {:fn-ids {old-dep-fn-id 1}
                        :fn-schema-ids {}
                        :arg-schema-ids {}}
              new-deps {:fn-ids {new-dep-fn-id 1}
                        :fn-schema-ids {}
                        :arg-schema-ids {}}]
          ;; Save with old dependency
          (cache/save-cache! cache fn-id graph old-deps)
          (is (contains? (cache/find-caches-by-fn-dep cache old-dep-fn-id) fn-id))
          (is (empty? (cache/find-caches-by-fn-dep cache new-dep-fn-id)))
          ;; Save with new dependency
          (cache/save-cache! cache fn-id graph new-deps)
          ;; Old dep should be cleaned up
          (is (empty? (cache/find-caches-by-fn-dep cache old-dep-fn-id)))
          ;; New dep should be active
          (is (contains? (cache/find-caches-by-fn-dep cache new-dep-fn-id) fn-id)))
        (finally
          (sp/close storage))))))


(deftest get-cached-graph-nonexistent-integration-test
  (testing "get-cached-graph returns nil for non-existent cache"
    (let [storage (create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (get-datasource storage))
              fn-id (random-uuid)]
          (is (nil? (cache/get-cached-graph cache fn-id))))
        (finally
          (sp/close storage))))))


(deftest find-deps-for-nonexistent-returns-empty-integration-test
  (testing "find-caches-by-*-dep returns empty set for non-existent dep"
    (let [storage (create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (get-datasource storage))
              non-existent-id (random-uuid)]
          (is (= #{} (cache/find-caches-by-fn-dep cache non-existent-id)))
          (is (= #{} (cache/find-caches-by-fn-schema-dep cache non-existent-id)))
          (is (= #{} (cache/find-caches-by-arg-schema-dep cache non-existent-id))))
        (finally
          (sp/close storage))))))


(deftest fn-ref-value-integration-test
  (testing "handles fn-ref values in resolved-args"
    (let [storage (create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :any
                                           :base-fn-name "base"})
              fn-schema-id (:id fn-schema)
              arg-schema (sp/create-entity storage :arg-schema
                                           {:fn-schema-id fn-schema-id
                                            :name "f"
                                            :type :fn
                                            :required true})
              arg-schema-id (:id arg-schema)
              fn-record (sp/create-entity storage :fn
                                          {:name "test-fn"
                                           :fn-schema-id fn-schema-id})
              fn-id (:id fn-record)
              ref-fn (sp/create-entity storage :fn
                                       {:name "ref-fn"
                                        :fn-schema-id fn-schema-id})
              ref-fn-id (:id ref-fn)
              graph {:fns {fn-id {:id fn-id
                                  :name "test-fn"
                                  :fn-schema-id fn-schema-id
                                  :parent-fn-id nil}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "schema"
                                                :base-fn-name "base"
                                                :returned-type :any}}
                     :arg-schemas {arg-schema-id {:id arg-schema-id
                                                  :fn-schema-id fn-schema-id
                                                  :name "f"
                                                  :type :fn
                                                  :required true}}
                     :resolved-args {fn-id {arg-schema-id {:kind :fn-ref :fn-id ref-fn-id}}}
                     :fn-result-values {}}
              deps {:fn-ids {} :fn-schema-ids {} :arg-schema-ids {}}]
          (cache/save-cache! cache fn-id graph deps)
          (let [cached (cache/get-cached-graph cache fn-id)
                resolved-value (get-in cached [:resolved-args fn-id arg-schema-id])]
            (is (map? resolved-value))
            (is (= :fn-ref (:kind resolved-value)))
            (is (= (str ref-fn-id) (str (:fn-id resolved-value))))))
        (finally
          (sp/close storage))))))


(deftest empty-resolved-args-integration-test
  (testing "handles empty resolved-args"
    (let [storage (create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :text
                                           :base-fn-name "base"})
              fn-schema-id (:id fn-schema)
              arg-schema (sp/create-entity storage :arg-schema
                                           {:fn-schema-id fn-schema-id
                                            :name "opt"
                                            :type :text
                                            :required false})
              arg-schema-id (:id arg-schema)
              fn-record (sp/create-entity storage :fn
                                          {:name "test-fn"
                                           :fn-schema-id fn-schema-id})
              fn-id (:id fn-record)
              graph {:fns {fn-id {:id fn-id
                                  :name "test-fn"
                                  :fn-schema-id fn-schema-id
                                  :parent-fn-id nil}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "schema"
                                                :base-fn-name "base"
                                                :returned-type :text}}
                     :arg-schemas {arg-schema-id {:id arg-schema-id
                                                  :fn-schema-id fn-schema-id
                                                  :name "opt"
                                                  :type :text
                                                  :required false}}
                     ;; No resolved-args for this fn - simulating optional arg not set
                     :resolved-args {}
                     :fn-result-values {}}
              deps {:fn-ids {} :fn-schema-ids {} :arg-schema-ids {}}]
          (cache/save-cache! cache fn-id graph deps)
          (let [cached (cache/get-cached-graph cache fn-id)]
            ;; Empty resolved-args should be preserved
            (is (empty? (:resolved-args cached)))))
        (finally
          (sp/close storage))))))


(deftest complex-value-types-integration-test
  (testing "handles various value types in resolved-args"
    (let [storage (create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :jsonb
                                           :base-fn-name "base"})
              fn-schema-id (:id fn-schema)
              arg-schema (sp/create-entity storage :arg-schema
                                           {:fn-schema-id fn-schema-id
                                            :name "data"
                                            :type :jsonb
                                            :required true})
              arg-schema-id (:id arg-schema)
              fn-record (sp/create-entity storage :fn
                                          {:name "test-fn"
                                           :fn-schema-id fn-schema-id})
              fn-id (:id fn-record)
              complex-value {:nested {:array [1 2 3]
                                      :string "hello"
                                      :number 42.5
                                      :boolean true}}
              graph {:fns {fn-id {:id fn-id
                                  :name "test-fn"
                                  :fn-schema-id fn-schema-id
                                  :parent-fn-id nil}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "schema"
                                                :base-fn-name "base"
                                                :returned-type :jsonb}}
                     :arg-schemas {arg-schema-id {:id arg-schema-id
                                                  :fn-schema-id fn-schema-id
                                                  :name "data"
                                                  :type :jsonb
                                                  :required true}}
                     :resolved-args {fn-id {arg-schema-id complex-value}}
                     :fn-result-values {}}
              deps {:fn-ids {} :fn-schema-ids {} :arg-schema-ids {}}]
          (cache/save-cache! cache fn-id graph deps)
          (let [cached (cache/get-cached-graph cache fn-id)
                resolved-value (get-in cached [:resolved-args fn-id arg-schema-id])]
            (is (= [1 2 3] (get-in resolved-value [:nested :array])))
            (is (= "hello" (get-in resolved-value [:nested :string])))
            (is (= 42.5 (get-in resolved-value [:nested :number])))
            (is (true? (get-in resolved-value [:nested :boolean])))))
        (finally
          (sp/close storage))))))


(deftest primitive-values-integration-test
  (testing "handles primitive values in resolved-args"
    (let [storage (create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :any
                                           :base-fn-name "base"})
              fn-schema-id (:id fn-schema)
              arg-schema-int (sp/create-entity storage :arg-schema
                                               {:fn-schema-id fn-schema-id
                                                :name "num"
                                                :type :int
                                                :required true})
              arg-schema-str (sp/create-entity storage :arg-schema
                                               {:fn-schema-id fn-schema-id
                                                :name "str"
                                                :type :text
                                                :required true})
              arg-schema-bool (sp/create-entity storage :arg-schema
                                                {:fn-schema-id fn-schema-id
                                                 :name "flag"
                                                 :type :bool
                                                 :required true})
              fn-record (sp/create-entity storage :fn
                                          {:name "test-fn"
                                           :fn-schema-id fn-schema-id})
              fn-id (:id fn-record)
              graph {:fns {fn-id {:id fn-id
                                  :name "test-fn"
                                  :fn-schema-id fn-schema-id
                                  :parent-fn-id nil}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "schema"
                                                :base-fn-name "base"
                                                :returned-type :any}}
                     :arg-schemas {(:id arg-schema-int) arg-schema-int
                                   (:id arg-schema-str) arg-schema-str
                                   (:id arg-schema-bool) arg-schema-bool}
                     :resolved-args {fn-id {(:id arg-schema-int) 42
                                            (:id arg-schema-str) "hello"
                                            (:id arg-schema-bool) false}}
                     :fn-result-values {}}
              deps {:fn-ids {} :fn-schema-ids {} :arg-schema-ids {}}]
          (cache/save-cache! cache fn-id graph deps)
          (let [cached (cache/get-cached-graph cache fn-id)]
            (is (= 42 (get-in cached [:resolved-args fn-id (:id arg-schema-int)])))
            (is (= "hello" (get-in cached [:resolved-args fn-id (:id arg-schema-str)])))
            (is (false? (get-in cached [:resolved-args fn-id (:id arg-schema-bool)])))))
        (finally
          (sp/close storage))))))


(deftest multiple-fns-in-graph-integration-test
  (testing "handles graphs with multiple fns"
    (let [storage (create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :int
                                           :base-fn-name "base"})
              fn-schema-id (:id fn-schema)
              parent-fn (sp/create-entity storage :fn
                                          {:name "parent"
                                           :fn-schema-id fn-schema-id})
              parent-fn-id (:id parent-fn)
              child-fn (sp/create-entity storage :fn
                                         {:name "child"
                                          :fn-schema-id fn-schema-id
                                          :parent-fn-id parent-fn-id})
              child-fn-id (:id child-fn)
              grandchild-fn (sp/create-entity storage :fn
                                              {:name "grandchild"
                                               :fn-schema-id fn-schema-id
                                               :parent-fn-id child-fn-id})
              grandchild-fn-id (:id grandchild-fn)
              graph {:fns {parent-fn-id {:id parent-fn-id
                                         :name "parent"
                                         :fn-schema-id fn-schema-id
                                         :parent-fn-id nil}
                           child-fn-id {:id child-fn-id
                                        :name "child"
                                        :fn-schema-id fn-schema-id
                                        :parent-fn-id parent-fn-id}
                           grandchild-fn-id {:id grandchild-fn-id
                                             :name "grandchild"
                                             :fn-schema-id fn-schema-id
                                             :parent-fn-id child-fn-id}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "schema"
                                                :base-fn-name "base"
                                                :returned-type :int}}
                     :arg-schemas {}
                     :resolved-args {}
                     :fn-result-values {}}
              deps {:fn-ids {parent-fn-id 1 child-fn-id 1 grandchild-fn-id 1}
                    :fn-schema-ids {fn-schema-id 1}
                    :arg-schema-ids {}}]
          (cache/save-cache! cache grandchild-fn-id graph deps)
          (let [cached (cache/get-cached-graph cache grandchild-fn-id)]
            (is (= 3 (count (:fns cached))))
            (is (some? (get-in cached [:fns parent-fn-id])))
            (is (some? (get-in cached [:fns child-fn-id])))
            (is (some? (get-in cached [:fns grandchild-fn-id])))))
        (finally
          (sp/close storage))))))


(deftest multiple-arg-schemas-integration-test
  (testing "handles multiple arg-schemas"
    (let [storage (create-test-storage)]
      (try
        (let [cache (cache-pg/create-cache (get-datasource storage))
              fn-schema (sp/create-entity storage :fn-schema
                                          {:name "schema"
                                           :returned-type :int
                                           :base-fn-name "add"})
              fn-schema-id (:id fn-schema)
              arg1 (sp/create-entity storage :arg-schema
                                     {:fn-schema-id fn-schema-id
                                      :name "a"
                                      :type :int
                                      :required true})
              arg2 (sp/create-entity storage :arg-schema
                                     {:fn-schema-id fn-schema-id
                                      :name "b"
                                      :type :int
                                      :required true})
              arg3 (sp/create-entity storage :arg-schema
                                     {:fn-schema-id fn-schema-id
                                      :name "c"
                                      :type :int
                                      :required false})
              fn-record (sp/create-entity storage :fn
                                          {:name "test"
                                           :fn-schema-id fn-schema-id})
              fn-id (:id fn-record)
              graph {:fns {fn-id {:id fn-id
                                  :name "test"
                                  :fn-schema-id fn-schema-id
                                  :parent-fn-id nil}}
                     :fn-schemas {fn-schema-id {:id fn-schema-id
                                                :name "schema"
                                                :base-fn-name "add"
                                                :returned-type :int}}
                     :arg-schemas {(:id arg1) (assoc arg1 :id (:id arg1))
                                   (:id arg2) (assoc arg2 :id (:id arg2))
                                   (:id arg3) (assoc arg3 :id (:id arg3))}
                     :resolved-args {}
                     :fn-result-values {}}
              deps {:fn-ids {}
                    :fn-schema-ids {fn-schema-id 1}
                    :arg-schema-ids {(:id arg1) 1 (:id arg2) 1 (:id arg3) 1}}]
          (cache/save-cache! cache fn-id graph deps)
          (let [cached (cache/get-cached-graph cache fn-id)]
            (is (= 3 (count (:arg-schemas cached))))
            (is (= #{"a" "b" "c"} (set (map :name (vals (:arg-schemas cached))))))))
        (finally
          (sp/close storage))))))
