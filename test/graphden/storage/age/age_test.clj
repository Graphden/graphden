(ns graphden.storage.age.age-test
  "Tests for Apache AGE graph operations module.

   Covers:
   - Helper functions (graph-entity?, parse-agtype, escape-cypher-string)
   - Entity to node label conversion
   - Cypher query building for each entity type
   - Entity sync operations
   - Dependency extraction and sync"
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.age.age :as age]
    [graphden.storage.age.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(defn- get-pool
  "Gets the pool from an AgeStorage instance."
  [storage]
  (:pool storage))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


;; =============================================================================
;; graph-entity? tests
;; =============================================================================

(deftest graph-entity?-test
  (testing "returns true for graph entities"
    (is (true? (age/graph-entity? :fn)))
    (is (true? (age/graph-entity? :fn-schema)))
    (is (true? (age/graph-entity? :arg-schema)))
    (is (true? (age/graph-entity? :arg-value)))
    (is (true? (age/graph-entity? :fn-usage)))
    (is (true? (age/graph-entity? :fn-arg))))

  (testing "returns false for non-graph entities"
    (is (false? (age/graph-entity? :user)))
    (is (false? (age/graph-entity? :other)))
    (is (false? (age/graph-entity? nil)))))


;; =============================================================================
;; parse-agtype tests
;; =============================================================================

(deftest parse-agtype-test
  (testing "parses vertex agtype"
    (let [result (age/parse-agtype "{\"id\": 1, \"name\": \"test\"}::vertex")]
      (is (map? result))
      (is (= 1 (:id result)))
      (is (= "test" (:name result)))))

  (testing "parses edge agtype"
    (let [result (age/parse-agtype "{\"type\": \"DEPENDS_ON\"}::edge")]
      (is (map? result))
      (is (= "DEPENDS_ON" (:type result)))))

  (testing "parses generic agtype"
    (let [result (age/parse-agtype "[1, 2, 3]::agtype")]
      (is (sequential? result))
      (is (= '(1 2 3) result))))

  (testing "parses quoted string"
    (let [result (age/parse-agtype "\"hello world\"")]
      (is (= "hello world" result))))

  (testing "parses plain JSON"
    (let [result (age/parse-agtype "{\"key\": \"value\"}")]
      (is (= "value" (:key result)))))

  (testing "returns original string for unparseable value"
    (let [result (age/parse-agtype "invalid-json{")]
      (is (= "invalid-json{" result))))

  (testing "returns nil for nil input"
    (is (nil? (age/parse-agtype nil)))))


;; =============================================================================
;; ensure-graph! tests
;; =============================================================================

(deftest ensure-graph!-test
  (testing "creates graph if not exists"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)
              result (age/ensure-graph! ds "test_graph")]
          (is (true? result)))
        (finally
          (sp/close storage)))))

  (testing "succeeds if graph already exists"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)]
          ;; Create once
          (age/ensure-graph! ds "test_graph2")
          ;; Create again - should not fail
          (let [result (age/ensure-graph! ds "test_graph2")]
            (is (true? result))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; execute-cypher! tests
;; =============================================================================

(deftest execute-cypher!-test
  (testing "executes simple cypher query"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)]
          (age/ensure-graph! ds "cypher_test")
          (age/with-age-connection ds
                                   (fn [conn]
                                     ;; Create a node
                                     (age/execute-cypher! conn "cypher_test" "CREATE (n:Test {id: '1', name: 'test'}) RETURN n")
                                     ;; Query it back
                                     (let [results (age/execute-cypher! conn "cypher_test" "MATCH (n:Test) RETURN n")]
                                       (is (= 1 (count results)))))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; sync-entity-to-graph! tests
;; =============================================================================

(deftest sync-fn-schema-to-graph!-test
  (testing "syncs fn-schema entity to graph"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)
              fn-schema {:id #uuid "11111111-1111-1111-1111-111111111111"
                         :name :my-function
                         :returned-type :int
                         :base-fn-name :add
                         :impl-hash "abc123"}]
          (age/ensure-graph! ds "sync_test")
          (age/sync-entity-to-graph! ds "sync_test" :fn-schema fn-schema)
          ;; Verify node exists
          (age/with-age-connection ds
                                   (fn [conn]
                                     (let [results (age/execute-cypher! conn "sync_test"
                                                                        "MATCH (n:FnSchema {id: '11111111-1111-1111-1111-111111111111'}) RETURN n")]
                                       (is (= 1 (count results)))))))
        (finally
          (sp/close storage)))))

  (testing "syncs fn-schema without base-fn-name"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)
              fn-schema {:id #uuid "22222222-2222-2222-2222-222222222222"
                         :name :composed-fn
                         :returned-type :text
                         :base-fn-name nil
                         :impl-hash nil}]
          (age/ensure-graph! ds "sync_test2")
          (age/sync-entity-to-graph! ds "sync_test2" :fn-schema fn-schema)
          (age/with-age-connection ds
                                   (fn [conn]
                                     (let [results (age/execute-cypher! conn "sync_test2"
                                                                        "MATCH (n:FnSchema {id: '22222222-2222-2222-2222-222222222222'}) RETURN n")]
                                       (is (= 1 (count results)))))))
        (finally
          (sp/close storage))))))


(deftest sync-fn-to-graph!-test
  (testing "syncs fn entity to graph"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)
              fn-entity {:id #uuid "33333333-3333-3333-3333-333333333333"
                         :name :my-fn-instance
                         :fn-schema-id #uuid "11111111-1111-1111-1111-111111111111"}]
          (age/ensure-graph! ds "fn_test")
          (age/sync-entity-to-graph! ds "fn_test" :fn fn-entity)
          (age/with-age-connection ds
                                   (fn [conn]
                                     (let [results (age/execute-cypher! conn "fn_test"
                                                                        "MATCH (n:Fn {id: '33333333-3333-3333-3333-333333333333'}) RETURN n")]
                                       (is (= 1 (count results)))))))
        (finally
          (sp/close storage))))))


(deftest sync-arg-schema-to-graph!-test
  (testing "syncs arg-schema entity to graph"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)
              arg-schema {:id #uuid "44444444-4444-4444-4444-444444444444"
                          :fn-schema-id #uuid "11111111-1111-1111-1111-111111111111"
                          :name :x
                          :type :int
                          :required true}]
          (age/ensure-graph! ds "arg_schema_test")
          (age/sync-entity-to-graph! ds "arg_schema_test" :arg-schema arg-schema)
          (age/with-age-connection ds
                                   (fn [conn]
                                     (let [results (age/execute-cypher! conn "arg_schema_test"
                                                                        "MATCH (n:ArgSchema {id: '44444444-4444-4444-4444-444444444444'}) RETURN n")]
                                       (is (= 1 (count results)))))))
        (finally
          (sp/close storage))))))


(deftest sync-arg-value-to-graph!-test
  (testing "syncs arg-value entity with literal value"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)
              arg-value {:id #uuid "55555555-5555-5555-5555-555555555555"
                         :arg-schema-id #uuid "44444444-4444-4444-4444-444444444444"
                         :value 42}]
          (age/ensure-graph! ds "arg_value_test")
          (age/sync-entity-to-graph! ds "arg_value_test" :arg-value arg-value)
          (age/with-age-connection ds
                                   (fn [conn]
                                     (let [results (age/execute-cypher! conn "arg_value_test"
                                                                        "MATCH (n:ArgValue {id: '55555555-5555-5555-5555-555555555555'}) RETURN n")]
                                       (is (= 1 (count results)))))))
        (finally
          (sp/close storage)))))

  (testing "syncs arg-value entity with map value"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)
              arg-value {:id #uuid "66666666-6666-6666-6666-666666666666"
                         :arg-schema-id #uuid "44444444-4444-4444-4444-444444444444"
                         :value {:fn-id #uuid "33333333-3333-3333-3333-333333333333"}}]
          (age/ensure-graph! ds "arg_value_test2")
          (age/sync-entity-to-graph! ds "arg_value_test2" :arg-value arg-value)
          (age/with-age-connection ds
                                   (fn [conn]
                                     (let [results (age/execute-cypher! conn "arg_value_test2"
                                                                        "MATCH (n:ArgValue {id: '66666666-6666-6666-6666-666666666666'}) RETURN n")]
                                       (is (= 1 (count results)))))))
        (finally
          (sp/close storage))))))


(deftest sync-fn-usage-to-graph!-test
  (testing "syncs fn-usage entity to graph"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)
              fn-usage {:id #uuid "77777777-7777-7777-7777-777777777777"
                        :fn-id #uuid "33333333-3333-3333-3333-333333333333"
                        :name :my-fn-usage}]
          (age/ensure-graph! ds "fn_usage_test")
          (age/sync-entity-to-graph! ds "fn_usage_test" :fn-usage fn-usage)
          (age/with-age-connection ds
                                   (fn [conn]
                                     (let [results (age/execute-cypher! conn "fn_usage_test"
                                                                        "MATCH (n:FnUsage {id: '77777777-7777-7777-7777-777777777777'}) RETURN n")]
                                       (is (= 1 (count results)))))))
        (finally
          (sp/close storage))))))


(deftest sync-fn-arg-edge-to-graph!-test
  (testing "syncs fn-arg edge to graph"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)
              fn-id #uuid "33333333-3333-3333-3333-333333333333"
              av-id #uuid "55555555-5555-5555-5555-555555555555"
              as-id #uuid "44444444-4444-4444-4444-444444444444"]
          (age/ensure-graph! ds "fn_arg_test")
          ;; First create the nodes
          (age/sync-entity-to-graph! ds "fn_arg_test" :fn
                                     {:id fn-id :name :test-fn :fn-schema-id #uuid "11111111-1111-1111-1111-111111111111"})
          (age/sync-entity-to-graph! ds "fn_arg_test" :arg-value
                                     {:id av-id :arg-schema-id as-id :value 42})
          ;; Then create the edge
          (age/sync-entity-to-graph! ds "fn_arg_test" :fn-arg
                                     {:fn-id fn-id :arg-value-id av-id :arg-schema-id as-id})
          (age/with-age-connection ds
                                   (fn [conn]
                                     (let [results (age/execute-cypher! conn "fn_arg_test"
                                                                        "MATCH (f:Fn)-[r:HAS_ARG]->(av:ArgValue) RETURN r")]
                                       (is (= 1 (count results)))))))
        (finally
          (sp/close storage))))))


(deftest sync-unknown-entity-returns-nil-test
  (testing "sync with unknown entity type returns nil"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)]
          (age/ensure-graph! ds "unknown_test")
          ;; Should not throw, just return nil
          (is (nil? (age/sync-entity-to-graph! ds "unknown_test" :unknown-entity {:id #uuid "11111111-1111-1111-1111-111111111111"}))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; delete-entity-from-graph! tests
;; =============================================================================

(deftest delete-entity-from-graph!-test
  (testing "deletes existing entity"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)
              fn-id #uuid "88888888-8888-8888-8888-888888888888"]
          (age/ensure-graph! ds "delete_test")
          ;; Create entity
          (age/sync-entity-to-graph! ds "delete_test" :fn
                                     {:id fn-id :name :to-delete :fn-schema-id #uuid "11111111-1111-1111-1111-111111111111"})
          ;; Verify exists
          (age/with-age-connection ds
                                   (fn [conn]
                                     (is (= 1 (count (age/execute-cypher! conn "delete_test"
                                                                          "MATCH (n:Fn {id: '88888888-8888-8888-8888-888888888888'}) RETURN n"))))))
          ;; Delete
          (age/delete-entity-from-graph! ds "delete_test" :fn fn-id)
          ;; Verify gone
          (age/with-age-connection ds
                                   (fn [conn]
                                     (is (empty? (age/execute-cypher! conn "delete_test"
                                                                      "MATCH (n:Fn {id: '88888888-8888-8888-8888-888888888888'}) RETURN n"))))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; sync-dependencies! tests
;; =============================================================================

(deftest sync-dependencies!-test
  (testing "creates DEPENDS_ON edges for fn references"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)
              fn1-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
              fn2-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
              arg-values [{:value {:fn-id fn2-id}}]]
          (age/ensure-graph! ds "deps_test")
          ;; Create both Fn nodes first
          (age/sync-entity-to-graph! ds "deps_test" :fn
                                     {:id fn1-id :name :fn1 :fn-schema-id #uuid "11111111-1111-1111-1111-111111111111"})
          (age/sync-entity-to-graph! ds "deps_test" :fn
                                     {:id fn2-id :name :fn2 :fn-schema-id #uuid "11111111-1111-1111-1111-111111111111"})
          ;; Sync dependencies
          (age/sync-dependencies! ds "deps_test" fn1-id arg-values)
          ;; Verify DEPENDS_ON edge exists
          (age/with-age-connection ds
                                   (fn [conn]
                                     (let [results (age/execute-cypher! conn "deps_test"
                                                                        "MATCH (f1:Fn)-[r:DEPENDS_ON]->(f2:Fn) RETURN r")]
                                       (is (= 1 (count results)))))))
        (finally
          (sp/close storage)))))

  (testing "handles UUID value as fn reference"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)
              fn1-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
              fn2-id #uuid "dddddddd-dddd-dddd-dddd-dddddddddddd"
              arg-values [{:value fn2-id}]]  ; Direct UUID reference
          (age/ensure-graph! ds "deps_test2")
          ;; Create both Fn nodes first
          (age/sync-entity-to-graph! ds "deps_test2" :fn
                                     {:id fn1-id :name :fn1 :fn-schema-id #uuid "11111111-1111-1111-1111-111111111111"})
          (age/sync-entity-to-graph! ds "deps_test2" :fn
                                     {:id fn2-id :name :fn2 :fn-schema-id #uuid "11111111-1111-1111-1111-111111111111"})
          ;; Sync dependencies
          (age/sync-dependencies! ds "deps_test2" fn1-id arg-values)
          ;; Verify DEPENDS_ON edge exists
          (age/with-age-connection ds
                                   (fn [conn]
                                     (let [results (age/execute-cypher! conn "deps_test2"
                                                                        "MATCH (f1:Fn)-[r:DEPENDS_ON]->(f2:Fn) RETURN r")]
                                       (is (= 1 (count results)))))))
        (finally
          (sp/close storage)))))

  (testing "ignores non-reference values"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)
              fn1-id #uuid "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
              arg-values [{:value 42}
                          {:value "string"}
                          {:value {:other-key "value"}}]]
          (age/ensure-graph! ds "deps_test3")
          (age/sync-entity-to-graph! ds "deps_test3" :fn
                                     {:id fn1-id :name :fn1 :fn-schema-id #uuid "11111111-1111-1111-1111-111111111111"})
          ;; Should not throw
          (age/sync-dependencies! ds "deps_test3" fn1-id arg-values)
          ;; No DEPENDS_ON edges should exist
          (age/with-age-connection ds
                                   (fn [conn]
                                     (let [results (age/execute-cypher! conn "deps_test3"
                                                                        "MATCH (f:Fn)-[:DEPENDS_ON]->() RETURN f")]
                                       (is (empty? results))))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; execute-cypher-multi! tests
;; =============================================================================

(deftest execute-cypher-multi!-test
  (testing "executes query with multiple return columns"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)]
          (age/ensure-graph! ds "multi_test")
          (age/with-age-connection ds
                                   (fn [conn]
                                     ;; Create two nodes
                                     (age/execute-cypher! conn "multi_test"
                                                          "CREATE (a:Test {id: 'a'}), (b:Test {id: 'b'})")
                                     ;; Query with multiple columns
                                     (let [results (age/execute-cypher-multi! conn "multi_test"
                                                                              "MATCH (a:Test), (b:Test) WHERE a.id = 'a' AND b.id = 'b' RETURN a, b"
                                                                              [["a" "agtype"] ["b" "agtype"]])]
                                       (is (= 1 (count results)))
                                       (is (some? (:a (first results))))
                                       (is (some? (:b (first results))))))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; Special character escaping tests
;; =============================================================================

(deftest escape-special-characters-test
  (testing "syncs entity with special characters in name"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)
              fn-schema {:id #uuid "99999999-9999-9999-9999-999999999999"
                         :name :my-fn's-name  ; Contains apostrophe
                         :returned-type :text
                         :base-fn-name nil
                         :impl-hash nil}]
          (age/ensure-graph! ds "escape_test")
          (age/sync-entity-to-graph! ds "escape_test" :fn-schema fn-schema)
          (age/with-age-connection ds
                                   (fn [conn]
                                     (let [results (age/execute-cypher! conn "escape_test"
                                                                        "MATCH (n:FnSchema {id: '99999999-9999-9999-9999-999999999999'}) RETURN n")]
                                       (is (= 1 (count results)))))))
        (finally
          (sp/close storage)))))

  (testing "syncs arg-value with JSON containing special characters"
    (let [storage (setup/create-test-storage)]
      (try
        (let [ds (get-pool storage)
              arg-value {:id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaab"
                         :arg-schema-id #uuid "44444444-4444-4444-4444-444444444444"
                         :value {:text "He said \"hello\""  ; Contains quotes
                                 :path "C:\\Users\\test"}}]  ; Contains backslashes
          (age/ensure-graph! ds "escape_test2")
          (age/sync-entity-to-graph! ds "escape_test2" :arg-value arg-value)
          (age/with-age-connection ds
                                   (fn [conn]
                                     (let [results (age/execute-cypher! conn "escape_test2"
                                                                        "MATCH (n:ArgValue {id: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaab'}) RETURN n")]
                                       (is (= 1 (count results)))))))
        (finally
          (sp/close storage))))))
