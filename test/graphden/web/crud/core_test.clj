(ns graphden.web.crud.core-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.interface :as sp]
    [graphden.web.crud.core :as crud]))


;; =============================================================================
;; Helper Functions Tests
;; =============================================================================

(defn- call-impl
  "Helper to call a defbase impl function with delays."
  [def-map arg-map]
  (let [impl (:impl def-map)
        delays (into {} (map (fn [[k v]] [k (delay v)]) arg-map))]
    (impl delays nil)))


(deftest get-path-param-test
  (testing "extracts path parameter from request"
    (let [request {:path-params {:id "123" :type "fn"}}]
      (is (= "123" (call-impl crud/get-path-param {:request request :param "id"})))
      (is (= "fn" (call-impl crud/get-path-param {:request request :param "type"})))))

  (testing "returns nil for missing parameter"
    (let [request {:path-params {:id "123"}}]
      (is (nil? (call-impl crud/get-path-param {:request request :param "missing"}))))))


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
    (is (contains? crud/all-defs :get-path-param))
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
;; Mock storage tests
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
          _ (swap! (:data storage) assoc :fn-schema
                   [{:id #uuid "00000000-0000-0000-0000-000000000001" :name :test}])
          ctx {:storage storage}
          result (call-impl-with-ctx crud/list-entities-impl {:entity-type "fn-schema"} ctx)]
      (is (vector? result))
      (is (= 1 (count result)))))

  (testing "list-entities-impl throws without storage"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Storage not available"
          (call-impl-with-ctx crud/list-entities-impl {:entity-type "fn"} {}))))

  (testing "get-entity-impl reads entity"
    (let [storage (create-mock-storage)
          id #uuid "00000000-0000-0000-0000-000000000001"
          _ (swap! (:data storage) assoc :fn [{:id id :name :test-fn}])
          ctx {:storage storage}
          result (call-impl-with-ctx crud/get-entity-impl {:entity-type "fn" :id id} ctx)]
      (is (map? result))
      (is (= :test-fn (:name result)))))

  (testing "create-entity-impl creates entity"
    (let [storage (create-mock-storage)
          ctx {:storage storage}
          result (call-impl-with-ctx crud/create-entity-impl
                                     {:entity-type "fn"
                                      :data {:name :new-fn}}
                                     ctx)]
      (is (uuid? (:id result)))
      (is (= :new-fn (:name result)))))

  (testing "update-entity-impl updates entity"
    (let [storage (create-mock-storage)
          id #uuid "00000000-0000-0000-0000-000000000001"
          _ (swap! (:data storage) assoc :fn [{:id id :name :old-name}])
          ctx {:storage storage}
          result (call-impl-with-ctx crud/update-entity-impl
                                     {:entity-type "fn" :id id :data {:name :new-name}}
                                     ctx)]
      (is (= :new-name (:name result)))))

  (testing "delete-entity-impl deletes entity"
    (let [storage (create-mock-storage)
          id #uuid "00000000-0000-0000-0000-000000000001"
          _ (swap! (:data storage) assoc :fn [{:id id :name :to-delete}])
          ctx {:storage storage}
          result (call-impl-with-ctx crud/delete-entity-impl
                                     {:entity-type "fn" :id id}
                                     ctx)]
      (is (true? result))
      (is (empty? (get @(:data storage) :fn))))))


(deftest list-all-graph-entities-impl-test
  (testing "returns all entity types"
    (let [storage (create-mock-storage)
          _ (swap! (:data storage) assoc
                   :fn-schema [{:id (random-uuid) :name :s1}]
                   :fn [{:id (random-uuid) :name :f1}]
                   :arg-schema [{:id (random-uuid) :name :a1}]
                   :arg-value [{:id (random-uuid) :value 42}]
                   :call-site [{:id (random-uuid) :name :c1}])
          ctx {:storage storage}
          result (call-impl-with-ctx crud/list-all-graph-entities-impl {} ctx)]
      (is (= 1 (count (:fn-schemas result))))
      (is (= 1 (count (:fns result))))
      (is (= 1 (count (:arg-schemas result))))
      (is (= 1 (count (:arg-values result))))
      (is (= 1 (count (:call-sites result)))))))


(deftest all-entities-json-handler-with-storage-test
  (testing "returns JSON with entities"
    (let [storage (create-mock-storage)
          _ (swap! (:data storage) assoc :fn-schema [{:id (random-uuid) :name :test}])
          ctx {:storage storage}
          handler-fn ((:impl crud/all-entities-json-handler-impl) {} ctx)
          response (handler-fn {})]
      (is (= 200 (:status response)))
      (is (= "application/json" (get-in response [:headers "Content-Type"]))))))


(deftest entity-details-handler-with-storage-test
  (testing "returns HTML for valid entity"
    (let [storage (create-mock-storage)
          id #uuid "00000000-0000-0000-0000-000000000001"
          _ (swap! (:data storage) assoc :fn-schema [{:id id :name :test-fn :returned-type :int}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-details-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn-schema" :id (str id)}})]
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
          _ (swap! (:data storage) assoc :fn-schema [{:id (random-uuid) :name :test}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-form-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn"}})]
      (is (= 200 (:status response)))))

  (testing "returns form for call-site entity type"
    (let [storage (create-mock-storage)
          _ (swap! (:data storage) assoc :fn [{:id (random-uuid) :name :test}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-form-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "call-site"}})]
      (is (= 200 (:status response)))))

  (testing "returns form for arg-value entity type"
    (let [storage (create-mock-storage)
          _ (swap! (:data storage) assoc
                   :fn [{:id (random-uuid) :name :test}]
                   :arg-schema [{:id (random-uuid) :name :arg}])
          ctx {:storage storage}
          handler-fn ((:impl crud/entity-form-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "arg-value"}})]
      (is (= 200 (:status response))))))


(deftest create-entity-api-handler-with-storage-test
  (testing "creates fn entity"
    (let [storage (create-mock-storage)
          schema-id (random-uuid)
          _ (swap! (:data storage) assoc :fn-schema [{:id schema-id :name :test}])
          ctx {:storage storage}
          handler-fn ((:impl crud/create-entity-api-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn"}
                                :body (str "name=new-fn&fn-schema-id=" schema-id)})]
      (is (= 200 (:status response)))))

  (testing "creates call-site entity"
    (let [storage (create-mock-storage)
          fn-id (random-uuid)
          _ (swap! (:data storage) assoc :fn [{:id fn-id :name :test}])
          ctx {:storage storage}
          handler-fn ((:impl crud/create-entity-api-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "call-site"}
                                :body (str "fn-id=" fn-id)})]
      (is (= 200 (:status response))))))


(deftest delete-entity-api-handler-with-storage-test
  (testing "deletes entity"
    (let [storage (create-mock-storage)
          id (random-uuid)
          _ (swap! (:data storage) assoc :fn [{:id id :name :to-delete}])
          ctx {:storage storage}
          handler-fn ((:impl crud/delete-entity-api-handler-impl) {} ctx)
          response (handler-fn {:path-params {:type "fn" :id (str id)}})]
      (is (= 200 (:status response))))))
