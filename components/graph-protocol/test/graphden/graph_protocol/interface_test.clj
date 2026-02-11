(ns graphden.graph-protocol.interface-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.graph-data-schema.interface :as gds]
    [graphden.graph-protocol.interface :as gp]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.memory-storage.interface :as mem]
    [graphden.storage-protocol.interface :as sp]))


(defn- create-test-storage
  "Creates an initialized in-memory storage for testing."
  []
  (let [builder (mds/create-builder)
        schema (gds/build-schema builder)
        storage (mem/create-storage)]
    (sp/initialize-with-cleanup! storage schema)))


(deftest direct-graph-reader-test
  (testing "direct-graph-reader creates a GraphReader"
    (let [storage (create-test-storage)
          reader (gp/direct-graph-reader storage)]
      (is (gp/graph-reader? reader))
      (sp/close storage)))

  (testing "resolve-graph returns execution graph"
    (let [storage (create-test-storage)
          reader (gp/direct-graph-reader storage)
          ;; Create a simple fn-schema and fn
          schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema
                              {:id schema-id
                               :name "test-schema"
                               :returned-type :int})
          fn-data (sp/create-entity storage :fn
                                    {:name "test-fn"
                                     :fn-schema-id schema-id})
          fn-id (:id fn-data)
          ;; Resolve graph
          graph (gp/resolve-graph reader fn-id)]
      (is (sp/execution-graph? graph))
      (is (contains? (sp/get-graph-fns graph) fn-id))
      (is (contains? (sp/get-graph-fn-schemas graph) schema-id))
      (sp/close storage)))

  (testing "get-fn returns fn record"
    (let [storage (create-test-storage)
          reader (gp/direct-graph-reader storage)
          schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema
                              {:id schema-id :name "schema" :returned-type :int})
          fn-data (sp/create-entity storage :fn
                                    {:name "my-fn" :fn-schema-id schema-id})
          fn-record (gp/get-fn reader (:id fn-data))]
      (is (= "my-fn" (:name fn-record)))
      (is (= schema-id (:fn-schema-id fn-record)))
      (sp/close storage)))

  (testing "get-fn-schema returns schema record"
    (let [storage (create-test-storage)
          reader (gp/direct-graph-reader storage)
          schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema
                              {:id schema-id :name "my-schema" :returned-type :text})
          schema-record (gp/get-fn-schema reader schema-id)]
      (is (= "my-schema" (:name schema-record)))
      (is (= :text (:returned-type schema-record)))
      (sp/close storage)))

  (testing "get-arg-schemas-for-fn-schema returns arg-schemas map"
    (let [storage (create-test-storage)
          reader (gp/direct-graph-reader storage)
          schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema
                              {:id schema-id :name "with-args" :returned-type :int})
          arg1 (sp/create-entity storage :arg-schema
                                 {:fn-schema-id schema-id :name "x" :type :int :required true})
          arg2 (sp/create-entity storage :arg-schema
                                 {:fn-schema-id schema-id :name "y" :type :int :required true})
          arg-schemas (gp/get-arg-schemas-for-fn-schema reader schema-id)]
      (is (= 2 (count arg-schemas)))
      (is (contains? arg-schemas (:id arg1)))
      (is (contains? arg-schemas (:id arg2)))
      (sp/close storage)))

  (testing "query-fns returns matching fns"
    (let [storage (create-test-storage)
          reader (gp/direct-graph-reader storage)
          schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema
                              {:id schema-id :name "schema" :returned-type :int})
          _ (sp/create-entity storage :fn {:name "fn-a" :fn-schema-id schema-id})
          _ (sp/create-entity storage :fn {:name "fn-b" :fn-schema-id schema-id})
          all-fns (gp/query-fns reader {})
          named-fn (gp/query-fns reader {:name "fn-a"})]
      (is (= 2 (count all-fns)))
      (is (= 1 (count named-fn)))
      (is (= "fn-a" (:name (first named-fn))))
      (sp/close storage))))


(deftest re-exports-test
  (testing "re-exported functions work"
    (is (fn? gp/->execution-graph))
    (is (fn? gp/execution-graph?))
    (is (fn? gp/traverse-bfs))
    (is (fn? gp/try-parse-uuid)))

  (testing "try-parse-uuid works via re-export"
    (let [uuid (random-uuid)]
      (is (= uuid (gp/try-parse-uuid uuid)))
      (is (= uuid (gp/try-parse-uuid (str uuid))))
      (is (nil? (gp/try-parse-uuid "not-a-uuid"))))))
