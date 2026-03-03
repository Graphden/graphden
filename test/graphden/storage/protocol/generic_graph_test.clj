(ns graphden.storage.protocol.generic-graph-test
  "Tests for generic ExecutionGraph resolution via StorageCRUD."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.malli.interface :as mds]
    [graphden.schema.protocol.interface :as ds]
    [graphden.storage.postgres.interface :as pg]
    [graphden.storage.protocol.generic-graph :as gg]
    [graphden.storage.protocol.interface :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]))


;; Container for PostgreSQL tests
(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))
(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(defn- create-test-storage
  "Creates a PostgreSQL storage from the current test container.
   Cleans the database before creating storage to ensure test isolation."
  []
  (th/clean-database-fast! *container*)
  (pg/create-storage (th/get-container-config *container*)))


(defn- make-graph-schema
  "Creates schema with fn-schema, arg-schema, fn, arg-value, fn-arg, and fn-usage."
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
                                 :type :bool}
                      :first-class {:uuid #uuid "00000000-0000-0000-0002-000000000006"
                                    :type :bool}})
      (ds/add-entity :fn #uuid "00000000-0000-0000-0003-000000000001"
                     {:name {:uuid #uuid "00000000-0000-0000-0003-000000000002"
                             :type :text}
                      :fn-schema-id {:uuid #uuid "00000000-0000-0000-0003-000000000003"
                                     :type :uuid}
                      :owner-fn-id {:uuid #uuid "00000000-0000-0000-0003-000000000004"
                                    :type :uuid
                                    :nullable? true}})
      (ds/add-entity :arg-value #uuid "00000000-0000-0000-0004-000000000001"
                     {:arg-schema-id {:uuid #uuid "00000000-0000-0000-0004-000000000003"
                                      :type :uuid}
                      :value {:uuid #uuid "00000000-0000-0000-0004-000000000004"
                              :type :text}})
      (ds/add-entity :fn-arg #uuid "00000000-0000-0000-0005-000000000001"
                     {:fn-id {:uuid #uuid "00000000-0000-0000-0005-000000000002"
                              :type :uuid}
                      :arg-schema-id {:uuid #uuid "00000000-0000-0000-0005-000000000003"
                                      :type :uuid}
                      :arg-value-id {:uuid #uuid "00000000-0000-0000-0005-000000000004"
                                     :type :uuid}})
      ds/build))


(defn- create-arg-value-with-binding!
  "Creates arg-value and fn-arg binding. Returns the arg-value."
  [storage fn-id arg-schema-id value]
  (let [av (sp/create-entity storage :arg-value
                             {:arg-schema-id arg-schema-id
                              :value value})]
    (sp/create-entity storage :fn-arg
                      {:fn-id fn-id
                       :arg-schema-id arg-schema-id
                       :arg-value-id (:id av)})
    av))


(deftest resolve-simple-fn-test
  (testing "resolves a simple function with no dependencies"
    (let [storage (create-test-storage)
          _ (sp/initialize storage (make-graph-schema))
          fn-schema-id (random-uuid)
          arg-schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema
                              {:id fn-schema-id :name "add" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema
                              {:id arg-schema-id :fn-schema-id fn-schema-id
                               :name "x" :type "int" :required true :first-class false})
          fn-rec (sp/create-entity storage :fn
                                   {:name "my-add" :fn-schema-id fn-schema-id})
          _ (create-arg-value-with-binding! storage (:id fn-rec) arg-schema-id "42")
          graph (gg/resolve-execution-graph storage (:id fn-rec))]
      (is (sp/execution-graph? graph))
      (is (= 1 (count (:fns graph))))
      (is (contains? (:fns graph) (:id fn-rec)))
      (is (= 1 (count (:fn-schemas graph))))
      (is (contains? (:fn-schemas graph) fn-schema-id))
      (is (= 1 (count (:arg-schemas graph))))
      (is (some? (get-in graph [:resolved-args (:id fn-rec)]))))))


(deftest resolve-fn-chain-test
  (testing "resolves a chain of dependent functions"
    (let [storage (create-test-storage)
          _ (sp/initialize storage (make-graph-schema))
          fn-schema-id (random-uuid)
          arg-schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema
                              {:id fn-schema-id :name "identity" :returned-type "int"})
          _ (sp/create-entity storage :arg-schema
                              {:id arg-schema-id :fn-schema-id fn-schema-id
                               :name "x" :type "int" :required true :first-class false})
          fn-a (sp/create-entity storage :fn {:name "fn-a" :fn-schema-id fn-schema-id})
          fn-b (sp/create-entity storage :fn {:name "fn-b" :fn-schema-id fn-schema-id})
          fn-c (sp/create-entity storage :fn {:name "fn-c" :fn-schema-id fn-schema-id})
          ;; fn-a depends on fn-b (arg-value = fn-b's id)
          _ (create-arg-value-with-binding! storage (:id fn-a) arg-schema-id (str (:id fn-b)))
          ;; fn-b depends on fn-c
          _ (create-arg-value-with-binding! storage (:id fn-b) arg-schema-id (str (:id fn-c)))
          ;; fn-c has a literal value
          _ (create-arg-value-with-binding! storage (:id fn-c) arg-schema-id "99")
          graph (gg/resolve-execution-graph storage (:id fn-a))]
      (is (sp/execution-graph? graph))
      (is (= 3 (count (:fns graph))))
      (is (contains? (:fns graph) (:id fn-a)))
      (is (contains? (:fns graph) (:id fn-b)))
      (is (contains? (:fns graph) (:id fn-c)))
      ;; All share one fn-schema
      (is (= 1 (count (:fn-schemas graph)))))))


(deftest resolve-fn-no-args-test
  (testing "resolves function with no arg-values"
    (let [storage (create-test-storage)
          _ (sp/initialize storage (make-graph-schema))
          fn-schema-id (random-uuid)
          _ (sp/create-entity storage :fn-schema
                              {:id fn-schema-id :name "noop" :returned-type "int"})
          fn-rec (sp/create-entity storage :fn {:name "my-noop" :fn-schema-id fn-schema-id})
          graph (gg/resolve-execution-graph storage (:id fn-rec))]
      (is (sp/execution-graph? graph))
      (is (= 1 (count (:fns graph))))
      (is (= {} (get-in graph [:resolved-args (:id fn-rec)]))))))


(deftest resolve-nonexistent-fn-test
  (testing "throws when fn does not exist"
    (let [storage (create-test-storage)
          _ (sp/initialize storage (make-graph-schema))]
      (is (thrown? clojure.lang.ExceptionInfo
            (gg/resolve-execution-graph storage (random-uuid)))))))
