(ns graphden.datomic-storage.execution-graph-test
  "Tests for datomic-storage ExecutionGraphReader protocol."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.datomic-storage.test-setup :as setup]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.storage-protocol.interface :as sp]))


;; === Test helpers ===

(defn- make-graph-schema
  "Creates schema with fn-schema, arg-schema, fn, and arg-value entities."
  []
  (-> (mds/create-builder)
      (ds/add-entity :fn-schema #uuid "00000000-0000-0000-0001-000000000001"
                     {:name {:uuid #uuid "00000000-0000-0000-0001-000000000002"
                             :type :text}
                      :returned-type {:uuid #uuid "00000000-0000-0000-0001-000000000003"
                                      :type :text}})
      (ds/add-entity :arg-schema #uuid "00000000-0000-0000-0002-000000000001"
                     {:fn-schema-id {:uuid #uuid "00000000-0000-0000-0002-000000000002"
                                     :type :uuid}
                      :name {:uuid #uuid "00000000-0000-0000-0002-000000000003"
                             :type :text}
                      :type {:uuid #uuid "00000000-0000-0000-0002-000000000004"
                             :type :text}
                      :required {:uuid #uuid "00000000-0000-0000-0002-000000000005"
                                 :type :bool}})
      (ds/add-entity :fn #uuid "00000000-0000-0000-0003-000000000001"
                     {:name {:uuid #uuid "00000000-0000-0000-0003-000000000002"
                             :type :text}
                      :fn-schema-id {:uuid #uuid "00000000-0000-0000-0003-000000000003"
                                     :type :uuid}
                      :parent-fn-id {:uuid #uuid "00000000-0000-0000-0003-000000000004"
                                     :type :uuid
                                     :nullable? true}})
      (ds/add-entity :arg-value #uuid "00000000-0000-0000-0004-000000000001"
                     {:owner-fn-id {:uuid #uuid "00000000-0000-0000-0004-000000000002"
                                    :type :uuid}
                      :arg-schema-id {:uuid #uuid "00000000-0000-0000-0004-000000000003"
                                      :type :uuid}
                      :value {:uuid #uuid "00000000-0000-0000-0004-000000000004"
                              :type :text}})
      ds/build))


;; === ExecutionGraph tests ===

(deftest resolve-execution-graph-simple-test
  (testing "resolves simple function with no dependencies"
    (let [storage (setup/create-test-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [fn-schema (sp/create-entity storage :fn-schema
                                        {:name "add" :returned-type "int"})
            arg-a (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id fn-schema)
                                     :name "a" :type "int" :required true})
            arg-b (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id fn-schema)
                                     :name "b" :type "int" :required true})
            fn-add (sp/create-entity storage :fn
                                     {:name "add-1-2"
                                      :fn-schema-id (:id fn-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id fn-add)
                                 :arg-schema-id (:id arg-a)
                                 :value "1"})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id fn-add)
                                 :arg-schema-id (:id arg-b)
                                 :value "2"})
            graph (sp/resolve-execution-graph storage (:id fn-add))]
        (try
          (is (contains? (:fns graph) (:id fn-add)))
          (is (contains? (:fn-schemas graph) (:id fn-schema)))
          (is (contains? (:arg-schemas graph) (:id arg-a)))
          (is (contains? (:arg-schemas graph) (:id arg-b)))
          (is (contains? (:resolved-args graph) (:id fn-add)))
          (let [args (get (:resolved-args graph) (:id fn-add))]
            (is (= "1" (:value (get args (:id arg-a)))))
            (is (= "2" (:value (get args (:id arg-b))))))
          (finally
            (sp/close storage)))))))


(deftest resolve-execution-graph-with-parent-test
  (testing "resolves function with parent chain - child overrides parent"
    (let [storage (setup/create-test-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [fn-schema (sp/create-entity storage :fn-schema
                                        {:name "greet" :returned-type "text"})
            arg-name (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id fn-schema)
                                        :name "name" :type "text" :required true})
            arg-greeting (sp/create-entity storage :arg-schema
                                           {:fn-schema-id (:id fn-schema)
                                            :name "greeting" :type "text" :required true})
            parent-fn (sp/create-entity storage :fn
                                        {:name "greet-hello"
                                         :fn-schema-id (:id fn-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id parent-fn)
                                 :arg-schema-id (:id arg-greeting)
                                 :value "Hello"})
            child-fn (sp/create-entity storage :fn
                                       {:name "greet-hello-world"
                                        :fn-schema-id (:id fn-schema)
                                        :parent-fn-id (:id parent-fn)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id child-fn)
                                 :arg-schema-id (:id arg-name)
                                 :value "World"})
            graph (sp/resolve-execution-graph storage (:id child-fn))]
        (try
          (is (contains? (:fns graph) (:id child-fn)))
          (let [args (get (:resolved-args graph) (:id child-fn))]
            (is (= "World" (:value (get args (:id arg-name)))))
            (is (= "Hello" (:value (get args (:id arg-greeting))))))
          (finally
            (sp/close storage)))))))


(deftest resolve-execution-graph-with-fn-refs-test
  (testing "resolves function with references to other functions"
    (let [storage (setup/create-test-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [const-schema (sp/create-entity storage :fn-schema
                                           {:name "const-int" :returned-type "int"})
            const-arg (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id const-schema)
                                         :name "value" :type "int" :required true})
            add-schema (sp/create-entity storage :fn-schema
                                         {:name "add" :returned-type "int"})
            add-arg-a (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "a" :type "int" :required true})
            add-arg-b (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "b" :type "int" :required true})
            const-3 (sp/create-entity storage :fn
                                      {:name "const-3"
                                       :fn-schema-id (:id const-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id const-3)
                                 :arg-schema-id (:id const-arg)
                                 :value "3"})
            const-5 (sp/create-entity storage :fn
                                      {:name "const-5"
                                       :fn-schema-id (:id const-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id const-5)
                                 :arg-schema-id (:id const-arg)
                                 :value "5"})
            add-3-5 (sp/create-entity storage :fn
                                      {:name "add-3-5"
                                       :fn-schema-id (:id add-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-3-5)
                                 :arg-schema-id (:id add-arg-a)
                                 :value (str (:id const-3))})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-3-5)
                                 :arg-schema-id (:id add-arg-b)
                                 :value (str (:id const-5))})
            graph (sp/resolve-execution-graph storage (:id add-3-5))]
        (try
          (is (= 3 (count (:fns graph))))
          (is (contains? (:fns graph) (:id add-3-5)))
          (is (contains? (:fns graph) (:id const-3)))
          (is (contains? (:fns graph) (:id const-5)))
          (is (= 2 (count (:fn-schemas graph))))
          (is (= 3 (count (:arg-schemas graph))))
          (finally
            (sp/close storage)))))))


(deftest resolve-execution-graph-not-found-test
  (testing "throws when function not found"
    (let [storage (setup/create-test-storage)]
      (sp/initialize storage (make-graph-schema))
      (try
        (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Function not found"
              (sp/resolve-execution-graph storage (random-uuid))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-self-reference-test
  (testing "handles fn with self-reference in arg-value (triggers 'already visited' branch)"
    (let [storage (setup/create-test-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [;; recursive fn-schema with two args
            rec-schema (sp/create-entity storage :fn-schema
                                         {:name "recursive" :returned-type "int"})
            ;; 'self' arg will reference the fn itself (for recursion)
            arg-self (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id rec-schema)
                                        :name "self" :type "fn" :required true})
            arg-n (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id rec-schema)
                                     :name "n" :type "int" :required true})
            ;; Create fn instance that references itself
            rec-fn (sp/create-entity storage :fn
                                     {:name "factorial"
                                      :fn-schema-id (:id rec-schema)})
            ;; Self-reference: arg-value points to the fn itself
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id rec-fn)
                                 :arg-schema-id (:id arg-self)
                                 :value (str (:id rec-fn))})  ; Self-reference!
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id rec-fn)
                                 :arg-schema-id (:id arg-n)
                                 :value "5"})
            graph (sp/resolve-execution-graph storage (:id rec-fn))]
        (try
          ;; Should only have 1 fn (self-reference doesn't create duplicate)
          (is (= 1 (count (:fns graph))))
          (is (contains? (:fns graph) (:id rec-fn)))
          ;; Self arg should reference the same fn
          (let [args (get (:resolved-args graph) (:id rec-fn))]
            (is (= (str (:id rec-fn)) (str (:value (get args (:id arg-self))))))
            (is (= "5" (str (:value (get args (:id arg-n)))))))
          (finally
            (sp/close storage)))))))


(deftest resolve-execution-graph-shared-reference-test
  (testing "handles shared fn reference (same fn referenced by multiple args)"
    (let [storage (setup/create-test-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [;; const-int schema
            const-schema (sp/create-entity storage :fn-schema
                                           {:name "const-shared" :returned-type "int"})
            const-arg (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id const-schema)
                                         :name "value" :type "int" :required true})
            ;; add schema - both args reference fns
            add-schema (sp/create-entity storage :fn-schema
                                         {:name "add-shared" :returned-type "int"})
            add-arg-a (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "a" :type "int" :required true})
            add-arg-b (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "b" :type "int" :required true})
            ;; const-5 fn - will be referenced TWICE
            const-5 (sp/create-entity storage :fn
                                      {:name "const-5-shared"
                                       :fn-schema-id (:id const-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id const-5)
                                 :arg-schema-id (:id const-arg)
                                 :value "5"})
            ;; add-5-5 fn referencing const-5 for BOTH args
            add-5-5 (sp/create-entity storage :fn
                                      {:name "add-5-5-shared"
                                       :fn-schema-id (:id add-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-5-5)
                                 :arg-schema-id (:id add-arg-a)
                                 :value (str (:id const-5))})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-5-5)
                                 :arg-schema-id (:id add-arg-b)
                                 :value (str (:id const-5))})  ; Same fn referenced again!
            graph (sp/resolve-execution-graph storage (:id add-5-5))]
        (try
          ;; const-5 should only appear once in the graph despite being referenced twice
          (is (= 2 (count (:fns graph))))
          (is (contains? (:fns graph) (:id add-5-5)))
          (is (contains? (:fns graph) (:id const-5)))
          ;; Both args should reference const-5
          (let [args (get (:resolved-args graph) (:id add-5-5))]
            (is (= (str (:id const-5)) (str (:value (get args (:id add-arg-a))))))
            (is (= (str (:id const-5)) (str (:value (get args (:id add-arg-b)))))))
          (finally
            (sp/close storage)))))))


(deftest collect-dependency-chain-with-cycle-test
  (testing "collect-dependency-chain handles mutual dependency (A -> B -> A)"
    ;; This tests the 'already visited' branch at line 833
    (let [storage (setup/create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          fn-a-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          fn-b-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          arg-schema-a-id #uuid "dddddddd-dddd-dddd-dddd-dddddddddddd"
          arg-schema-b-id #uuid "eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "test" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-a-id :fn-schema-id fn-schema-id
                                                   :name "ref-a" :type "ref" :required false})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-b-id :fn-schema-id fn-schema-id
                                                   :name "ref-b" :type "ref" :required false})
          _ (sp/create-entity storage :fn {:id fn-a-id :name "fn-a" :fn-schema-id fn-schema-id})
          _ (sp/create-entity storage :fn {:id fn-b-id :name "fn-b" :fn-schema-id fn-schema-id})
          ;; Create mutual dependency: a -> b
          _ (sp/create-entity storage :arg-value {:owner-fn-id fn-a-id
                                                  :arg-schema-id arg-schema-a-id
                                                  :value (str fn-b-id)})
          ;; b -> a (creating cycle)
          _ (sp/create-entity storage :arg-value {:owner-fn-id fn-b-id
                                                  :arg-schema-id arg-schema-b-id
                                                  :value (str fn-a-id)})]
      (try
        ;; Test validate-no-dependency-cycle! which uses collect-dependency-chain
        ;; When checking if we can add a dependency from X to fn-a,
        ;; it will traverse fn-a's dependencies: fn-a -> fn-b -> fn-a (cycle, revisit)
        (let [new-fn-id #uuid "ffffffff-ffff-ffff-ffff-ffffffffffff"
              _ (sp/create-entity storage :fn {:id new-fn-id :name "fn-new" :fn-schema-id fn-schema-id})]
          ;; This should not throw because new-fn is not in fn-a's dependency chain
          ;; But it will exercise the "already visited" branch when traversing the cycle
          (is (nil? (sp/validate-no-dependency-cycle! storage new-fn-id fn-a-id))))
        (finally
          (sp/close storage))))))


(deftest resolve-execution-graph-with-deleted-ref-test
  (testing "handles fn deleted during graph traversal (covers line 956)"
    ;; This tests when a referenced fn doesn't exist
    (let [storage (setup/create-test-storage)
          schema (make-graph-schema)
          _ (sp/initialize storage schema)
          fn-schema-id #uuid "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
          fn-id #uuid "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
          arg-schema-id #uuid "cccccccc-cccc-cccc-cccc-cccccccccccc"
          non-existent-fn-id #uuid "99999999-9999-9999-9999-999999999999"
          _ (sp/create-entity storage :fn-schema {:id fn-schema-id :name "test" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema {:id arg-schema-id :fn-schema-id fn-schema-id
                                                   :name "ref" :type "ref" :required false})
          _ (sp/create-entity storage :fn {:id fn-id :name "test-fn" :fn-schema-id fn-schema-id})
          ;; Create arg-value referencing non-existent fn
          _ (sp/create-entity storage :arg-value {:owner-fn-id fn-id
                                                  :arg-schema-id arg-schema-id
                                                  :value (str non-existent-fn-id)})]
      (try
        ;; resolve-execution-graph should skip the non-existent fn
        (let [graph (sp/resolve-execution-graph storage fn-id)]
          (is (= 1 (count (:fns graph))))
          (is (contains? (:fns graph) fn-id)))
        (finally
          (sp/close storage))))))
