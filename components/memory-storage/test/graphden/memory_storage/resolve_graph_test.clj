(ns graphden.memory-storage.resolve-graph-test
  "Tests for memory storage resolve-execution-graph functionality."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.memory-storage.interface :as mem]
    [graphden.storage-protocol.interface :as sp]))


(defn- make-graph-schema
  "Creates schema with fn-schema, arg-schema, fn, and arg-value entities."
  []
  (-> (mds/create-builder)
      (ds/add-enum :value-kind #uuid "b79e6e8b-8aff-4188-862b-d8a85ef4fcdf"
                   [{:uuid #uuid "c703ffd9-6401-4c49-9ca3-a280f6aac8ba" :value :null}
                    {:uuid #uuid "cf26384f-d093-461d-9268-b42b8fd6eae6" :value :text}
                    {:uuid #uuid "154d3c4f-8d11-4592-9e24-5c40176cc5a7" :value :int}])
      (ds/add-entity :fn-schema #uuid "dc2df695-6167-4add-9e75-022213c96537"
                     {:name {:uuid #uuid "abe8475e-9130-4647-a2bf-be0cb07099b7" :type :text}
                      :returned-type {:uuid #uuid "5ea6c13d-553c-4d85-8511-38ae88f7f9e5"
                                      :type :enum :enum-name :value-kind}})
      (ds/add-entity :arg-schema #uuid "946c1f9c-30ce-4fab-98ed-dd9a26f6676b"
                     {:fn-schema-id {:uuid #uuid "c100ed37-f3d8-4a93-becc-17ae2b91f64a"
                                     :type :ref :ref-entity :fn-schema}
                      :name {:uuid #uuid "e68c993e-7840-4541-b55f-cf4b08ba3de7" :type :text}
                      :type {:uuid #uuid "be65f37b-4758-49da-9091-37dee0e28ad1"
                             :type :enum :enum-name :value-kind}
                      :required {:uuid #uuid "a1d4e8c2-5f67-4b3a-9c12-8e0f7d6b5a4c" :type :bool}})
      (ds/add-entity :fn #uuid "986e8a2a-39ba-41ae-8449-d06c31515486"
                     {:name {:uuid #uuid "af336498-6d1e-4879-b2a5-b0d6c1994d12" :type :text}
                      :fn-schema-id {:uuid #uuid "3a685253-07f7-4469-be8b-1a585ba3e7d4"
                                     :type :ref :ref-entity :fn-schema}})
      (ds/add-entity :arg-value #uuid "afb02fb7-0174-496b-9b21-a61063de0c04"
                     {:owner-fn-id {:uuid #uuid "d9331598-36b3-4238-83f8-16558d8b3a7e"
                                    :type :ref :ref-entity :fn}
                      :arg-schema-id {:uuid #uuid "834336b1-b55c-4557-b580-a62799deb729"
                                      :type :ref :ref-entity :arg-schema}
                      :value {:uuid #uuid "b6780ba3-d050-4162-aba8-5f68ac17bcb8" :type :jsonb}})
      ds/build))


(deftest resolve-execution-graph-simple-test
  (testing "resolves simple function with no dependencies"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      ;; Create fn-schema
      (let [fn-schema (sp/create-entity storage :fn-schema
                                        {:name "add" :returned-type :int})
            ;; Create arg-schemas
            arg-a (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id fn-schema)
                                     :name "a" :type :int :required true})
            arg-b (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id fn-schema)
                                     :name "b" :type :int :required true})
            ;; Create fn instance
            fn-add (sp/create-entity storage :fn
                                     {:name "add-1-2"
                                      :fn-schema-id (:id fn-schema)})
            ;; Create arg-values
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id fn-add)
                                 :arg-schema-id (:id arg-a)
                                 :value 1})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id fn-add)
                                 :arg-schema-id (:id arg-b)
                                 :value 2})
            ;; Resolve execution graph
            graph (sp/resolve-execution-graph storage (:id fn-add))]
        ;; Check structure
        (is (contains? (:fns graph) (:id fn-add)))
        (is (contains? (:fn-schemas graph) (:id fn-schema)))
        (is (contains? (:arg-schemas graph) (:id arg-a)))
        (is (contains? (:arg-schemas graph) (:id arg-b)))
        (is (contains? (:resolved-args graph) (:id fn-add)))
        ;; Check resolved args
        (let [args (get (:resolved-args graph) (:id fn-add))]
          (is (= 1 (:value (get args (:id arg-a)))))
          (is (= 2 (:value (get args (:id arg-b))))))))))


(deftest resolve-execution-graph-with-fn-refs-test
  (testing "resolves function with references to other functions"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [;; const-int schema
            const-schema (sp/create-entity storage :fn-schema
                                           {:name "const-int" :returned-type :int})
            const-arg (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id const-schema)
                                         :name "value" :type :int :required true})
            ;; add schema
            add-schema (sp/create-entity storage :fn-schema
                                         {:name "add" :returned-type :int})
            add-arg-a (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "a" :type :int :required true})
            add-arg-b (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "b" :type :int :required true})
            ;; const-3 fn
            const-3 (sp/create-entity storage :fn
                                      {:name "const-3"
                                       :fn-schema-id (:id const-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id const-3)
                                 :arg-schema-id (:id const-arg)
                                 :value 3})
            ;; const-5 fn
            const-5 (sp/create-entity storage :fn
                                      {:name "const-5"
                                       :fn-schema-id (:id const-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id const-5)
                                 :arg-schema-id (:id const-arg)
                                 :value 5})
            ;; add-3-5 fn referencing const-3 and const-5
            add-3-5 (sp/create-entity storage :fn
                                      {:name "add-3-5"
                                       :fn-schema-id (:id add-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-3-5)
                                 :arg-schema-id (:id add-arg-a)
                                 :value (:id const-3)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-3-5)
                                 :arg-schema-id (:id add-arg-b)
                                 :value (:id const-5)})
            graph (sp/resolve-execution-graph storage (:id add-3-5))]
        ;; All 3 fns should be in graph
        (is (= 3 (count (:fns graph))))
        (is (contains? (:fns graph) (:id add-3-5)))
        (is (contains? (:fns graph) (:id const-3)))
        (is (contains? (:fns graph) (:id const-5)))
        ;; Both schemas
        (is (= 2 (count (:fn-schemas graph))))
        ;; All arg-schemas
        (is (= 3 (count (:arg-schemas graph))))))))


(deftest resolve-execution-graph-not-found-test
  (testing "throws when function not found"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Function not found"
            (sp/resolve-execution-graph storage (random-uuid)))))))


(deftest resolve-execution-graph-with-optional-args-test
  (testing "handles optional (non-required) arguments that are not provided"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [fn-schema (sp/create-entity storage :fn-schema
                                        {:name "greet" :returned-type :text})
            ;; Required arg
            arg-name (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id fn-schema)
                                        :name "name" :type :text :required true})
            ;; Optional arg (not required)
            arg-suffix (sp/create-entity storage :arg-schema
                                         {:fn-schema-id (:id fn-schema)
                                          :name "suffix" :type :text :required false})
            fn-rec (sp/create-entity storage :fn
                                     {:name "greet-fn"
                                      :fn-schema-id (:id fn-schema)})
            ;; Only provide required arg, not optional
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id fn-rec)
                                 :arg-schema-id (:id arg-name)
                                 :value "World"})
            graph (sp/resolve-execution-graph storage (:id fn-rec))]
        (is (= 1 (count (:fns graph))))
        (let [args (get (:resolved-args graph) (:id fn-rec))]
          ;; Required arg should be present
          (is (= "World" (:value (get args (:id arg-name)))))
          ;; Optional arg should not be present (no arg-value for it)
          (is (nil? (get args (:id arg-suffix)))))))))


(deftest resolve-execution-graph-with-non-fn-uuid-value-test
  (testing "UUID value that doesn't exist as fn is not followed as reference"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [fn-schema (sp/create-entity storage :fn-schema
                                        {:name "process" :returned-type :text})
            arg-ref (sp/create-entity storage :arg-schema
                                      {:fn-schema-id (:id fn-schema)
                                       :name "ref" :type :uuid :required true})
            fn-rec (sp/create-entity storage :fn
                                     {:name "my-process"
                                      :fn-schema-id (:id fn-schema)})
            ;; Store a random UUID that doesn't exist as a fn
            non-existent-fn-id (random-uuid)
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id fn-rec)
                                 :arg-schema-id (:id arg-ref)
                                 :value non-existent-fn-id})
            graph (sp/resolve-execution-graph storage (:id fn-rec))]
        ;; Should only have 1 fn (the non-existent UUID is not resolved)
        (is (= 1 (count (:fns graph))))
        (let [args (get (:resolved-args graph) (:id fn-rec))]
          (is (= non-existent-fn-id (:value (get args (:id arg-ref))))))))))


(deftest query-entities-with-empty-where-test
  (testing "query-entities with empty where returns all records"
    (let [storage (mem/create-storage)
          schema (-> (mds/create-builder)
                     (ds/add-entity :item #uuid "11111111-1111-1111-1111-111111111111"
                                    {:name {:uuid #uuid "22222222-2222-2222-2222-222222222222"
                                            :type :text}})
                     ds/build)]
      (sp/initialize storage schema)
      (sp/create-entity storage :item {:name "item1"})
      (sp/create-entity storage :item {:name "item2"})
      (sp/create-entity storage :item {:name "item3"})
      (let [all-items (sp/query-entities storage :item {})]
        (is (= 3 (count all-items)))
        (is (= #{"item1" "item2" "item3"} (set (map :name all-items))))))))


(deftest resolve-execution-graph-shared-reference-test
  (testing "handles shared fn reference (same fn referenced by multiple args)"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [;; const-int schema
            const-schema (sp/create-entity storage :fn-schema
                                           {:name "const-int" :returned-type :int})
            const-arg (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id const-schema)
                                         :name "value" :type :int :required true})
            ;; add schema - both args reference fns
            add-schema (sp/create-entity storage :fn-schema
                                         {:name "add" :returned-type :int})
            add-arg-a (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "a" :type :int :required true})
            add-arg-b (sp/create-entity storage :arg-schema
                                        {:fn-schema-id (:id add-schema)
                                         :name "b" :type :int :required true})
            ;; const-5 fn - will be referenced TWICE
            const-5 (sp/create-entity storage :fn
                                      {:name "const-5"
                                       :fn-schema-id (:id const-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id const-5)
                                 :arg-schema-id (:id const-arg)
                                 :value 5})
            ;; add-5-5 fn referencing const-5 for BOTH args
            ;; This creates a shared reference that triggers the "already visited" branch
            add-5-5 (sp/create-entity storage :fn
                                      {:name "add-5-5"
                                       :fn-schema-id (:id add-schema)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-5-5)
                                 :arg-schema-id (:id add-arg-a)
                                 :value (:id const-5)})
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id add-5-5)
                                 :arg-schema-id (:id add-arg-b)
                                 :value (:id const-5)}) ; Same fn referenced again!
            graph (sp/resolve-execution-graph storage (:id add-5-5))]
        ;; const-5 should only appear once in the graph despite being referenced twice
        (is (= 2 (count (:fns graph))))
        (is (contains? (:fns graph) (:id add-5-5)))
        (is (contains? (:fns graph) (:id const-5)))
        ;; Both args should reference const-5
        (let [args (get (:resolved-args graph) (:id add-5-5))]
          (is (= (:id const-5) (:value (get args (:id add-arg-a)))))
          (is (= (:id const-5) (:value (get args (:id add-arg-b))))))))))


(deftest resolve-execution-graph-self-reference-test
  (testing "handles fn with self-reference in arg-value (triggers 'already visited' branch)"
    (let [storage (mem/create-storage)]
      (sp/initialize storage (make-graph-schema))
      (let [;; recursive fn-schema with two args
            rec-schema (sp/create-entity storage :fn-schema
                                         {:name "recursive" :returned-type :int})
            ;; 'self' arg will reference the fn itself (for recursion)
            arg-self (sp/create-entity storage :arg-schema
                                       {:fn-schema-id (:id rec-schema)
                                        :name "self" :type :fn :required true}) ; :fn type
            arg-n (sp/create-entity storage :arg-schema
                                    {:fn-schema-id (:id rec-schema)
                                     :name "n" :type :int :required true})
            ;; Create fn instance that references itself
            rec-fn (sp/create-entity storage :fn
                                     {:name "factorial"
                                      :fn-schema-id (:id rec-schema)})
            ;; Self-reference: arg-value points to the fn itself
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id rec-fn)
                                 :arg-schema-id (:id arg-self)
                                 :value (:id rec-fn)}) ; Self-reference!
            _ (sp/create-entity storage :arg-value
                                {:owner-fn-id (:id rec-fn)
                                 :arg-schema-id (:id arg-n)
                                 :value 5})
            graph (sp/resolve-execution-graph storage (:id rec-fn))]
        ;; Should only have 1 fn (self-reference doesn't create duplicate)
        (is (= 1 (count (:fns graph))))
        (is (contains? (:fns graph) (:id rec-fn)))
        ;; Self arg should reference the same fn
        (let [args (get (:resolved-args graph) (:id rec-fn))]
          (is (= (:id rec-fn) (:value (get args (:id arg-self)))))
          (is (= 5 (:value (get args (:id arg-n))))))))))
