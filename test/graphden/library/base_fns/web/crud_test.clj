(ns graphden.library.base-fns.web.crud-test
  "Tests for CRUD web handlers.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)"
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.library.base-fns.web.crud :as crud]
    [graphden.storage.protocol.core :as sp]))


;; =============================================================================
;; Helper Functions Tests
;; =============================================================================

(defn- call-impl
  "Helper to call a defbase impl function with delays."
  [def-map arg-map]
  (let [impl (:impl def-map)
        delays (into {} (map (fn [[k v]] [k (delay v)]) arg-map))]
    (impl delays nil)))


(deftest get-query-param-test
  (testing "extracts query parameter from request"
    (let [request {:query-string "foo=bar&baz=qux"}]
      (is (= "bar" (call-impl crud/get-query-param {:request request :param "foo"})))
      (is (= "qux" (call-impl crud/get-query-param {:request request :param "baz"})))))

  (testing "returns default when parameter missing"
    (let [request {:query-string "foo=bar"}]
      (is (= "default" (call-impl crud/get-query-param {:request request :param "missing" :default "default"})))))

  (testing "handles empty query string"
    (let [request {}]
      (is (nil? (call-impl crud/get-query-param {:request request :param "foo"})))))

  (testing "handles parameter without value"
    (let [request {:query-string "flag"}]
      (is (= "" (call-impl crud/get-query-param {:request request :param "flag"}))))))


(deftest parse-form-body-test
  (testing "parses URL-encoded form body"
    (let [request {:body "name=John&email=john%40example.com"
                   :headers {"content-type" "application/x-www-form-urlencoded"}}]
      (is (= {"name" "John" "email" "john@example.com"}
             (call-impl crud/parse-form-body {:request request})))))

  (testing "returns empty map for non-form content type"
    (let [request {:body "name=John"
                   :headers {"content-type" "application/json"}}]
      (is (= {} (call-impl crud/parse-form-body {:request request})))))

  (testing "returns empty map when body is nil"
    (let [request {:headers {"content-type" "application/x-www-form-urlencoded"}}]
      (is (= {} (call-impl crud/parse-form-body {:request request}))))))


(deftest parse-json-body-test
  (testing "parses JSON body"
    (let [request {:body "{\"name\":\"John\",\"age\":30}"
                   :headers {"content-type" "application/json"}}]
      (is (= {:name "John" :age 30}
             (call-impl crud/parse-json-body {:request request})))))

  (testing "returns nil for non-JSON content type"
    (let [request {:body "{\"name\":\"John\"}"
                   :headers {"content-type" "text/plain"}}]
      (is (nil? (call-impl crud/parse-json-body {:request request})))))

  (testing "returns nil when body is nil"
    (let [request {:headers {"content-type" "application/json"}}]
      (is (nil? (call-impl crud/parse-json-body {:request request}))))))


(deftest str-to-uuid-test
  (testing "parses valid UUID string"
    (let [uuid-str "550e8400-e29b-41d4-a716-446655440000"
          result (call-impl crud/str-to-uuid {:s uuid-str})]
      (is (uuid? result))
      (is (= uuid-str (str result)))))

  (testing "returns nil for invalid UUID"
    (is (nil? (call-impl crud/str-to-uuid {:s "not-a-uuid"})))
    (is (nil? (call-impl crud/str-to-uuid {:s ""})))))


;; =============================================================================
;; all-defs Tests
;; =============================================================================

(deftest all-defs-test
  (testing "all-defs contains all expected functions"
    (is (map? crud/all-defs))
    (is (contains? crud/all-defs :list-entities))
    (is (contains? crud/all-defs :get-entity))
    (is (contains? crud/all-defs :create-entity))
    (is (contains? crud/all-defs :update-entity))
    (is (contains? crud/all-defs :delete-entity))
    (is (contains? crud/all-defs :list-all-graph-entities))
    (is (contains? crud/all-defs :all-entities-json-handler))
    (is (contains? crud/all-defs :entity-details-handler))
    (is (contains? crud/all-defs :entity-form-handler))
    (is (contains? crud/all-defs :create-entity-api-handler))
    (is (contains? crud/all-defs :delete-entity-api-handler))
    (is (contains? crud/all-defs :get-query-param))
    (is (contains? crud/all-defs :parse-form-body))
    (is (contains? crud/all-defs :parse-json-body))
    (is (contains? crud/all-defs :str-to-uuid)))

  (testing "impl functions have correct metadata"
    (let [list-entities (:list-entities crud/all-defs)]
      (is (map? list-entities))
      (is (contains? (:args list-entities) :entity-type))
      (is (= :jsonb (:return-type list-entities)))
      (is (fn? (:impl list-entities))))))


;; =============================================================================
;; Storage-dependent function metadata tests
;; =============================================================================

(deftest list-entities-impl-metadata-test
  (testing "list-entities-impl has correct structure"
    (let [impl crud/list-entities-impl]
      (is (map? impl))
      (is (= :text (get-in impl [:args :entity-type])))
      (is (= :jsonb (:return-type impl)))
      (is (fn? (:impl impl))))))


(deftest get-entity-impl-metadata-test
  (testing "get-entity-impl has correct structure"
    (let [impl crud/get-entity-impl]
      (is (map? impl))
      (is (= :text (get-in impl [:args :entity-type])))
      (is (= :uuid (get-in impl [:args :id])))
      (is (= :jsonb (:return-type impl))))))


(deftest create-entity-impl-metadata-test
  (testing "create-entity-impl has correct structure"
    (let [impl crud/create-entity-impl]
      (is (map? impl))
      (is (= :text (get-in impl [:args :entity-type])))
      (is (= :jsonb (get-in impl [:args :data])))
      (is (= :jsonb (:return-type impl))))))


(deftest update-entity-impl-metadata-test
  (testing "update-entity-impl has correct structure"
    (let [impl crud/update-entity-impl]
      (is (map? impl))
      (is (= :text (get-in impl [:args :entity-type])))
      (is (= :uuid (get-in impl [:args :id])))
      (is (= :jsonb (get-in impl [:args :data])))
      (is (= :jsonb (:return-type impl))))))


(deftest delete-entity-impl-metadata-test
  (testing "delete-entity-impl has correct structure"
    (let [impl crud/delete-entity-impl]
      (is (map? impl))
      (is (= :text (get-in impl [:args :entity-type])))
      (is (= :uuid (get-in impl [:args :id])))
      (is (= :bool (:return-type impl))))))


(deftest list-all-graph-entities-impl-metadata-test
  (testing "list-all-graph-entities-impl has correct structure"
    (let [impl crud/list-all-graph-entities-impl]
      (is (map? impl))
      (is (= {} (:args impl)))
      (is (= :jsonb (:return-type impl))))))


;; =============================================================================
;; Handler impl metadata tests
;; =============================================================================

(deftest all-entities-json-handler-impl-test
  (testing "all-entities-json-handler-impl has correct structure"
    (let [impl crud/all-entities-json-handler-impl]
      (is (map? impl))
      (is (= {} (:args impl)))
      (is (= :fn (:return-type impl)))
      (is (fn? (:impl impl))))))


(deftest entity-details-handler-impl-test
  (testing "entity-details-handler-impl has correct structure"
    (let [impl crud/entity-details-handler-impl]
      (is (map? impl))
      (is (= {} (:args impl)))
      (is (= :fn (:return-type impl)))
      (is (fn? (:impl impl))))))


(deftest entity-form-handler-impl-test
  (testing "entity-form-handler-impl has correct structure"
    (let [impl crud/entity-form-handler-impl]
      (is (map? impl))
      (is (= {} (:args impl)))
      (is (= :fn (:return-type impl)))
      (is (fn? (:impl impl))))))


(deftest create-entity-api-handler-impl-test
  (testing "create-entity-api-handler-impl has correct structure"
    (let [impl crud/create-entity-api-handler-impl]
      (is (map? impl))
      (is (= {} (:args impl)))
      (is (= :fn (:return-type impl)))
      (is (fn? (:impl impl))))))


(deftest delete-entity-api-handler-impl-test
  (testing "delete-entity-api-handler-impl has correct structure"
    (let [impl crud/delete-entity-api-handler-impl]
      (is (map? impl))
      (is (= {} (:args impl)))
      (is (= :fn (:return-type impl)))
      (is (fn? (:impl impl))))))


;; =============================================================================
;; Handler error handling tests (without storage)
;; =============================================================================

(deftest handlers-without-storage-test
  (testing "all-entities-json-handler returns error without storage"
    (let [handler-fn ((:impl crud/all-entities-json-handler-impl) {} nil)
          response (handler-fn {})]
      (is (= 500 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))))

  (testing "entity-details-handler returns error for invalid request"
    (let [handler-fn ((:impl crud/entity-details-handler-impl) {} nil)
          response (handler-fn {:path-params {}})]
      (is (= 400 (:status response)))))

  (testing "entity-form-handler returns error for invalid type"
    (let [handler-fn ((:impl crud/entity-form-handler-impl) {} nil)
          response (handler-fn {:path-params {:type "invalid"}})]
      (is (= 400 (:status response)))))

  (testing "create-entity-api-handler returns error without storage"
    (let [handler-fn ((:impl crud/create-entity-api-handler-impl) {} nil)
          response (handler-fn {:path-params {:type "fn"} :body "name=test"})]
      (is (= 400 (:status response)))))

  (testing "delete-entity-api-handler returns error without storage"
    (let [handler-fn ((:impl crud/delete-entity-api-handler-impl) {} nil)
          response (handler-fn {:path-params {:type "fn" :id "550e8400-e29b-41d4-a716-446655440000"}})]
      (is (= 400 (:status response))))))


;; =============================================================================
;; Mock storage tests (2-entity schema: fn + arg)
;; =============================================================================

(defrecord MockStorage
  [data]

  sp/StorageCRUD

  (query-entities
    [_ entity-type _where]
    (get @data entity-type []))


  (read-entity
    [_ entity-type id]
    (first (filter #(= (:id %) id) (get @data entity-type []))))


  (create-entity
    [_ entity-type entity-data]
    (let [id (java.util.UUID/randomUUID)
          entity (assoc entity-data :id id)]
      (swap! data update entity-type (fnil conj []) entity)
      entity))


  (update-entity
    [_ entity-type id entity-data]
    (let [entities (get @data entity-type [])
          updated (mapv #(if (= (:id %) id) (merge % entity-data) %) entities)]
      (swap! data assoc entity-type updated)
      (first (filter #(= (:id %) id) updated))))


  (delete-entity
    [_ entity-type id]
    (let [entities (get @data entity-type [])
          remaining (filterv #(not= (:id %) id) entities)]
      (swap! data assoc entity-type remaining)
      nil)))


(defn- create-mock-storage
  []
  (->MockStorage (atom {})))


(defn- call-impl-with-ctx
  "Helper to call impl function with context."
  [def-map arg-map ctx]
  (let [impl (:impl def-map)
        delays (into {} (map (fn [[k v]] [k (delay v)]) arg-map))]
    (impl delays ctx)))


(deftest storage-crud-operations-test
  (testing "list-entities-impl queries storage"
    (let [storage (create-mock-storage)
          ;; In 2-entity schema, :fn has parent-id=nil for base fns
          _ (swap! (:data storage) assoc :fn
                   [{:id #uuid "00000000-0000-0000-0000-000000000001"
                     :name "test-fn"
                     :parent-id nil}])
          ctx {:storage storage}
          result (call-impl-with-ctx crud/list-entities-impl {:entity-type "fn"} ctx)]
      (is (vector? result))
      (is (= 1 (count result)))))

  (testing "list-entities-impl throws without storage"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Storage not available"
          (call-impl-with-ctx crud/list-entities-impl {:entity-type "fn"} {}))))

  (testing "get-entity-impl reads entity"
    (let [storage (create-mock-storage)
          id #uuid "00000000-0000-0000-0000-000000000001"
          _ (swap! (:data storage) assoc :fn [{:id id :name "test-fn" :parent-id nil}])
          ctx {:storage storage}
          result (call-impl-with-ctx crud/get-entity-impl {:entity-type "fn" :id id} ctx)]
      (is (map? result))
      (is (= "test-fn" (:name result)))))

  (testing "create-entity-impl creates entity"
    (let [storage (create-mock-storage)
          ctx {:storage storage}
          result (call-impl-with-ctx crud/create-entity-impl
                                     {:entity-type "fn"
                                      :data {:name "new-fn" :parent-id nil}}
                                     ctx)]
      (is (uuid? (:id result)))
      (is (= "new-fn" (:name result)))))

  (testing "update-entity-impl updates entity"
    (let [storage (create-mock-storage)
          id #uuid "00000000-0000-0000-0000-000000000001"
          _ (swap! (:data storage) assoc :fn [{:id id :name "old-name" :parent-id nil}])
          ctx {:storage storage}
          result (call-impl-with-ctx crud/update-entity-impl
                                     {:entity-type "fn" :id id :data {:name "new-name"}}
                                     ctx)]
      (is (= "new-name" (:name result)))))

  (testing "delete-entity-impl deletes entity"
    (let [storage (create-mock-storage)
          id #uuid "00000000-0000-0000-0000-000000000001"
          _ (swap! (:data storage) assoc :fn [{:id id :name "to-delete" :parent-id nil}])
          ctx {:storage storage}
          result (call-impl-with-ctx crud/delete-entity-impl
                                     {:entity-type "fn" :id id}
                                     ctx)]
      (is (true? result))
      (is (empty? (get @(:data storage) :fn))))))


(deftest list-all-graph-entities-impl-test
  (testing "returns all entity types (2-entity schema: fn + arg)"
    (let [storage (create-mock-storage)
          _ (swap! (:data storage) assoc
                   :fn [{:id (random-uuid) :name "base-fn" :parent-id nil}
                        {:id (random-uuid) :name "composed-fn" :parent-id (random-uuid)}]
                   :arg [{:id (random-uuid) :fn-id (random-uuid) :name "x" :type "int"}])
          ctx {:storage storage}
          result (call-impl-with-ctx crud/list-all-graph-entities-impl {} ctx)]
      (is (= 2 (count (:fns result))))
      (is (= 1 (count (:args result)))))))


(deftest all-entities-json-handler-with-storage-test
  (testing "returns JSON with entities"
    (let [storage (create-mock-storage)
          _ (swap! (:data storage) assoc :fn [{:id (random-uuid) :name "test" :parent-id nil}])
          ctx {:storage storage}
          handler-fn ((:impl crud/all-entities-json-handler-impl) {} ctx)
          response (handler-fn {})]
      (is (= 200 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"]))))))


(deftest entity-details-handler-with-storage-test
  (testing "returns HTML for valid fn entity"
    (let [storage (create-mock-storage)
          id #uuid "00000000-0000-0000-0000-000000000001"
          _ (swap! (:data storage) assoc :fn [{:id id :name "test-fn" :parent-id nil :return-type "int"}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-details-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn" :id (str id)}})]
      (is (= 200 (:status response)))
      (is (str/includes? (get-in response [:headers "Content-Type"]) "text/html"))))

  (testing "returns 404 for missing entity"
    (let [storage (create-mock-storage)
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-details-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn" :id "00000000-0000-0000-0000-000000000099"}})]
      (is (= 404 (:status response))))))


(deftest entity-form-handler-with-storage-test
  (testing "returns form for fn entity type"
    (let [storage (create-mock-storage)
          _ (swap! (:data storage) assoc :fn [{:id (random-uuid) :name "test" :parent-id nil}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-form-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn"}})]
      (is (= 200 (:status response)))))

  (testing "returns form for arg entity type"
    (let [storage (create-mock-storage)
          fn-id (random-uuid)
          _ (swap! (:data storage) assoc
                   :fn [{:id fn-id :name "test-fn" :parent-id nil}]
                   :arg [{:id (random-uuid) :fn-id fn-id :name "x" :type "int"}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-form-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "arg"}})]
      (is (= 200 (:status response))))))


(deftest create-entity-api-handler-with-storage-test
  (testing "creates fn entity"
    (let [storage (create-mock-storage)
          ctx {:storage storage}
          handler-fn ((:impl crud/create-entity-api-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn"}
                                :body "name=new-fn"})]
      (is (= 200 (:status response)))))

  (testing "creates arg entity"
    (let [storage (create-mock-storage)
          fn-id (random-uuid)
          _ (swap! (:data storage) assoc :fn [{:id fn-id :name "test-fn" :parent-id nil}])
          ctx {:storage storage}
          handler-fn ((:impl crud/create-entity-api-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "arg"}
                                :body (str "fn-id=" fn-id "&name=x&type=int")})]
      (is (= 200 (:status response))))))


(deftest delete-entity-api-handler-with-storage-test
  (testing "deletes entity"
    (let [storage (create-mock-storage)
          id (random-uuid)
          _ (swap! (:data storage) assoc :fn [{:id id :name "to-delete" :parent-id nil}])
          ctx {:storage storage}
          handler-fn ((:impl crud/delete-entity-api-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn" :id (str id)}})]
      (is (= 200 (:status response))))))


;; =============================================================================
;; Additional coverage tests for entity details rendering
;; =============================================================================

(deftest entity-details-handler-fn-entity-test
  (testing "returns HTML for fn entity (base fn)"
    (let [storage (create-mock-storage)
          fn-id (random-uuid)
          _ (swap! (:data storage) assoc
                   :fn [{:id fn-id :name "test-fn" :parent-id nil :return-type "int"}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-details-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn" :id (str fn-id)}})]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "test-fn"))))

  (testing "returns HTML for fn entity (composed fn)"
    (let [storage (create-mock-storage)
          base-fn-id (random-uuid)
          composed-fn-id (random-uuid)
          _ (swap! (:data storage) assoc
                   :fn [{:id base-fn-id :name "base-fn" :parent-id nil :return-type "int"}
                        {:id composed-fn-id :name "composed-fn" :parent-id base-fn-id}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-details-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn" :id (str composed-fn-id)}})]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "composed-fn")))))


(deftest entity-details-handler-arg-entity-test
  (testing "returns HTML for arg entity with literal value"
    (let [storage (create-mock-storage)
          fn-id (random-uuid)
          arg-id (random-uuid)
          _ (swap! (:data storage) assoc
                   :fn [{:id fn-id :name "test-fn" :parent-id nil}]
                   :arg [{:id arg-id
                          :fn-id fn-id
                          :name "x"
                          :type "int"
                          :value 42}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-details-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "arg" :id (str arg-id)}})]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "42"))))

  (testing "returns HTML for arg entity with ref-id reference"
    (let [storage (create-mock-storage)
          fn-id (random-uuid)
          ref-fn-id (random-uuid)
          arg-id (random-uuid)
          _ (swap! (:data storage) assoc
                   :fn [{:id fn-id :name "test-fn" :parent-id nil}
                        {:id ref-fn-id :name "ref-fn" :parent-id nil}]
                   :arg [{:id arg-id
                          :fn-id fn-id
                          :name "x"
                          :type "int"
                          :ref-id ref-fn-id}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-details-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "arg" :id (str arg-id)}})]
      (is (= 200 (:status response)))
      ;; Shows ref-id as a separate field
      (is (str/includes? (:body response) "Ref ID"))
      (is (str/includes? (:body response) (str ref-fn-id))))))


(deftest entity-details-handler-invalid-uuid-test
  (testing "returns 500 for invalid UUID format"
    (let [storage (create-mock-storage)
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-details-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn" :id "not-a-uuid"}})]
      (is (= 500 (:status response)))
      (is (str/includes? (:body response) "Error")))))


;; =============================================================================
;; Form handler edit mode tests
;; =============================================================================

(deftest entity-form-handler-edit-fn-test
  (testing "returns edit form for existing fn entity"
    (let [storage (create-mock-storage)
          fn-id (random-uuid)
          _ (swap! (:data storage) assoc
                   :fn [{:id fn-id :name "existing-fn" :parent-id nil}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-form-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn" :id (str fn-id)}})]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "Edit")))))


(deftest entity-form-handler-edit-arg-test
  (testing "returns edit form for existing arg entity"
    (let [storage (create-mock-storage)
          fn-id (random-uuid)
          arg-id (random-uuid)
          _ (swap! (:data storage) assoc
                   :fn [{:id fn-id :name "test-fn" :parent-id nil}]
                   :arg [{:id arg-id
                          :fn-id fn-id
                          :name "x"
                          :type "int"
                          :value 42}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-form-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "arg" :id (str arg-id)}})]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "Edit")))))


(deftest entity-form-handler-unknown-type-test
  (testing "returns error for unknown entity type"
    (let [storage (create-mock-storage)
          _ (swap! (:data storage) assoc :fn [{:id (random-uuid) :name "test" :parent-id nil}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-form-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "unknown-type"}})]
      ;; Unknown types return 400 since entity-type-from-string returns nil
      (is (= 400 (:status response)))
      (is (str/includes? (:body response) "Invalid entity type")))))


;; =============================================================================
;; Create entity API handler additional tests
;; =============================================================================

(deftest create-entity-api-handler-arg-test
  (testing "creates arg entity with value"
    (let [storage (create-mock-storage)
          fn-id (random-uuid)
          _ (swap! (:data storage) assoc
                   :fn [{:id fn-id :name "test-fn" :parent-id nil}])
          ctx {:storage storage}
          handler-fn ((:impl crud/create-entity-api-handler-impl) {} ctx)
          body (str "fn-id=" fn-id "&name=x&type=int&value=%7B%22x%22%3A1%7D")
          response (handler-fn {:path-params {:type "arg"} :body body})]
      (is (= 200 (:status response))))))


(deftest create-entity-api-handler-invalid-body-test
  (testing "returns 400 for invalid request body"
    (let [storage (create-mock-storage)
          ctx {:storage storage}
          handler-fn ((:impl crud/create-entity-api-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn"} :body nil})]
      (is (= 400 (:status response))))))


(deftest create-entity-api-handler-unknown-type-test
  (testing "returns 400 for unknown entity type"
    (let [storage (create-mock-storage)
          ctx {:storage storage}
          handler-fn ((:impl crud/create-entity-api-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "unknown"} :body "name=test"})]
      (is (= 400 (:status response))))))


;; =============================================================================
;; List entities with where clause test
;; =============================================================================

(deftest list-entities-impl-with-where-test
  (testing "list-entities-impl accepts where clause"
    (let [storage (create-mock-storage)
          _ (swap! (:data storage) assoc :fn
                   [{:id #uuid "00000000-0000-0000-0000-000000000001" :name "fn1" :parent-id nil}
                    {:id #uuid "00000000-0000-0000-0000-000000000002" :name "fn2" :parent-id nil}])
          ctx {:storage storage}
          ;; Note: MockStorage doesn't actually filter by where, but we test the code path
          result (call-impl-with-ctx crud/list-entities-impl
                                     {:entity-type "fn" :where {:name "fn1"}}
                                     ctx)]
      (is (vector? result))
      (is (= 2 (count result))))))


;; =============================================================================
;; Exception handling tests
;; =============================================================================

(defrecord ExceptionMockStorage
  []

  sp/StorageCRUD

  (query-entities [_ _ _] (throw (ex-info "Query failed" {:type :storage-error})))


  (read-entity [_ _ _] (throw (ex-info "Read failed" {:type :storage-error})))


  (create-entity [_ _ _] (throw (ex-info "Create failed" {:type :storage-error})))


  (update-entity [_ _ _ _] (throw (ex-info "Update failed" {:type :storage-error})))


  (delete-entity [_ _ _] (throw (ex-info "Delete failed" {:type :storage-error}))))


(deftest all-entities-json-handler-exception-test
  (testing "returns 500 with error JSON when storage throws"
    (let [storage (->ExceptionMockStorage)
          ctx {:storage storage}
          handler-fn ((:impl crud/all-entities-json-handler-impl) {} ctx)
          response (handler-fn {})]
      (is (= 500 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"])))
      (is (str/includes? (:body response) "Query failed")))))


(deftest entity-form-handler-exception-test
  (testing "returns 500 with error HTML when storage throws"
    (let [storage (->ExceptionMockStorage)
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-form-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn"}})]
      (is (= 500 (:status response)))
      (is (str/includes? (:body response) "Error")))))


(deftest create-entity-api-handler-exception-test
  (testing "returns 500 with error HTML when storage throws"
    (let [storage (->ExceptionMockStorage)
          ctx {:storage storage}
          handler-fn ((:impl crud/create-entity-api-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn"}
                                :body "name=test"})]
      (is (= 500 (:status response)))
      (is (str/includes? (:body response) "Error")))))


(deftest delete-entity-api-handler-exception-test
  (testing "returns 500 with error HTML when storage throws"
    (let [storage (->ExceptionMockStorage)
          ctx {:storage storage}
          handler-fn ((:impl crud/delete-entity-api-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn" :id "00000000-0000-0000-0000-000000000001"}})]
      (is (= 500 (:status response)))
      (is (str/includes? (:body response) "Error")))))


;; =============================================================================
;; Additional arg entity tests for is-fn flag and source-id
;; =============================================================================

(deftest entity-details-handler-arg-with-is-fn-test
  (testing "renders arg with is-fn flag set to true"
    (let [storage (create-mock-storage)
          fn-id (random-uuid)
          arg-id (random-uuid)
          _ (swap! (:data storage) assoc
                   :fn [{:id fn-id :name "test-fn" :parent-id nil}]
                   :arg [{:id arg-id
                          :fn-id fn-id
                          :name "callback"
                          :type "fn"
                          :required true
                          :is-fn true}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-details-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "arg" :id (str arg-id)}})]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "Is Fn"))
      (is (str/includes? (:body response) "Yes")))))


(deftest entity-details-handler-arg-inherited-test
  (testing "renders arg with source-id (inherited arg)"
    (let [storage (create-mock-storage)
          fn-id (random-uuid)
          source-arg-id (random-uuid)
          arg-id (random-uuid)
          _ (swap! (:data storage) assoc
                   :fn [{:id fn-id :name "test-fn" :parent-id nil}]
                   :arg [{:id source-arg-id
                          :fn-id fn-id
                          :name "x"
                          :type "int"
                          :required true
                          :is-fn false}
                         {:id arg-id
                          :fn-id fn-id
                          :name "x"
                          :type "int"
                          :source-id source-arg-id
                          :required false
                          :is-fn false}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-details-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "arg" :id (str arg-id)}})]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "Inherited"))
      (is (str/includes? (:body response) "Source ID")))))


;; =============================================================================
;; Form handler with entity having values pre-selected
;; =============================================================================

(deftest entity-form-handler-fn-with-parent-test
  (testing "renders fn form with parent-id pre-selected"
    (let [storage (create-mock-storage)
          base-fn-id (random-uuid)
          composed-fn-id (random-uuid)
          _ (swap! (:data storage) assoc
                   :fn [{:id base-fn-id :name "base-fn" :parent-id nil}
                        {:id composed-fn-id :name "composed-fn" :parent-id base-fn-id}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-form-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn" :id (str composed-fn-id)}})]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "Edit"))
      (is (str/includes? (:body response) "selected")))))


(deftest entity-form-handler-arg-with-source-test
  (testing "renders arg form with source-id pre-selected"
    (let [storage (create-mock-storage)
          fn-id (random-uuid)
          source-arg-id (random-uuid)
          arg-id (random-uuid)
          _ (swap! (:data storage) assoc
                   :fn [{:id fn-id :name "test-fn" :parent-id nil}]
                   :arg [{:id source-arg-id
                          :fn-id fn-id
                          :name "base-x"
                          :type "int"}
                         {:id arg-id
                          :fn-id fn-id
                          :name "x"
                          :type "int"
                          :source-id source-arg-id}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-form-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "arg" :id (str arg-id)}})]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "Source Arg")))))


;; =============================================================================
;; Create API handler with parent-id for fn
;; =============================================================================

(deftest create-entity-api-handler-fn-with-parent-test
  (testing "creates fn entity with parent-id"
    (let [storage (create-mock-storage)
          base-fn-id (random-uuid)
          _ (swap! (:data storage) assoc
                   :fn [{:id base-fn-id :name "base-fn" :parent-id nil}])
          ctx {:storage storage}
          handler-fn ((:impl crud/create-entity-api-handler-impl) {} ctx)
          body (str "name=composed-fn&parent-id=" base-fn-id)
          response (handler-fn {:path-params {:type "fn"} :body body})]
      (is (= 200 (:status response)))
      ;; Verify the entity was created with parent-id
      (let [fns (get @(:data storage) :fn)]
        (is (= 2 (count fns)))
        (is (some #(= base-fn-id (:parent-id %)) fns))))))


(deftest create-entity-api-handler-arg-with-source-test
  (testing "creates arg entity with source-id"
    (let [storage (create-mock-storage)
          fn-id (random-uuid)
          source-arg-id (random-uuid)
          _ (swap! (:data storage) assoc
                   :fn [{:id fn-id :name "test-fn" :parent-id nil}]
                   :arg [{:id source-arg-id :fn-id fn-id :name "base-x" :type "int"}])
          ctx {:storage storage}
          handler-fn ((:impl crud/create-entity-api-handler-impl) {} ctx)
          body (str "fn-id=" fn-id "&name=x&type=int&source-id=" source-arg-id)
          response (handler-fn {:path-params {:type "arg"} :body body})]
      (is (= 200 (:status response))))))


;; =============================================================================
;; Additional edge cases for impl functions
;; =============================================================================

(deftest get-entity-impl-missing-storage-test
  (testing "get-entity-impl throws without storage"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Storage not available"
          (call-impl-with-ctx crud/get-entity-impl
                              {:entity-type "fn" :id (random-uuid)}
                              {})))))


(deftest create-entity-impl-missing-storage-test
  (testing "create-entity-impl throws without storage"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Storage not available"
          (call-impl-with-ctx crud/create-entity-impl
                              {:entity-type "fn" :data {:name "test"}}
                              {})))))


(deftest update-entity-impl-missing-storage-test
  (testing "update-entity-impl throws without storage"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Storage not available"
          (call-impl-with-ctx crud/update-entity-impl
                              {:entity-type "fn" :id (random-uuid) :data {:name "new"}}
                              {})))))


(deftest delete-entity-impl-missing-storage-test
  (testing "delete-entity-impl throws without storage"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Storage not available"
          (call-impl-with-ctx crud/delete-entity-impl
                              {:entity-type "fn" :id (random-uuid)}
                              {})))))


(deftest list-all-graph-entities-impl-missing-storage-test
  (testing "list-all-graph-entities-impl throws without storage"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Storage not available"
          (call-impl-with-ctx crud/list-all-graph-entities-impl {} {})))))


;; =============================================================================
;; get-path-param tests (not exported in all-defs but still defined)
;; =============================================================================

(deftest get-path-param-test
  (testing "extracts path parameter from request"
    (let [request {:path-params {:id "123" :type "fn"}}]
      (is (= "123" (call-impl crud/get-path-param {:request request :param "id"})))
      (is (= "fn" (call-impl crud/get-path-param {:request request :param "type"})))))

  (testing "returns nil when parameter not found"
    (let [request {:path-params {:id "123"}}]
      (is (nil? (call-impl crud/get-path-param {:request request :param "missing"})))))

  (testing "handles request without path-params"
    (let [request {}]
      (is (nil? (call-impl crud/get-path-param {:request request :param "id"}))))))


;; =============================================================================
;; Additional edge case tests for complete coverage
;; =============================================================================

(deftest delete-entity-api-handler-invalid-uuid-test
  (testing "returns 500 for invalid UUID format in delete"
    (let [storage (create-mock-storage)
          ctx {:storage storage}
          handler-fn ((:impl crud/delete-entity-api-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn" :id "not-a-uuid"}})]
      (is (= 500 (:status response)))
      (is (str/includes? (:body response) "Error")))))


(deftest delete-entity-api-handler-invalid-type-test
  (testing "returns 400 for invalid entity type in delete"
    (let [storage (create-mock-storage)
          ctx {:storage storage}
          handler-fn ((:impl crud/delete-entity-api-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "invalid-type" :id "00000000-0000-0000-0000-000000000001"}})]
      (is (= 400 (:status response))))))


(deftest entity-details-handler-arg-nil-value-test
  (testing "handles arg with nil value"
    (let [storage (create-mock-storage)
          fn-id (random-uuid)
          arg-id (random-uuid)
          _ (swap! (:data storage) assoc
                   :fn [{:id fn-id :name "test-fn" :parent-id nil}]
                   :arg [{:id arg-id
                          :fn-id fn-id
                          :name "x"
                          :type "int"
                          :value nil}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-details-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "arg" :id (str arg-id)}})]
      (is (= 200 (:status response))))))


(deftest all-entities-json-handler-includes-args-test
  (testing "all-entities-json-handler includes args in response"
    (let [storage (create-mock-storage)
          fn-id (random-uuid)
          _ (swap! (:data storage) assoc
                   :fn [{:id fn-id :name "test" :parent-id nil}]
                   :arg [{:id (random-uuid) :fn-id fn-id :name "x" :type "int"}])
          ctx {:storage storage}
          handler-fn ((:impl crud/all-entities-json-handler-impl) {} ctx)
          response (handler-fn {})]
      (is (= 200 (:status response)))
      (is (str/includes? (:body response) "args")))))
