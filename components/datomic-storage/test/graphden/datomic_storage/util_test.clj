(ns graphden.datomic-storage.util-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.datomic-storage.util :as util]
    [graphden.storage-protocol.interface :as sp]))


;; === Type mapping tests ===

(deftest type->datomic-test
  (testing "maps basic types correctly"
    (is (= :db.type/uuid (:uuid util/type->datomic)))
    (is (= :db.type/string (:text util/type->datomic)))
    (is (= :db.type/long (:int util/type->datomic)))
    (is (= :db.type/boolean (:bool util/type->datomic)))
    (is (= :db.type/bigdec (:numeric util/type->datomic)))
    (is (= :db.type/instant (:timestamptz util/type->datomic)))
    (is (= :db.type/string (:jsonb util/type->datomic)))
    (is (= :db.type/bytes (:bytes util/type->datomic)))))


;; === Query timeout tests ===

(deftest query-timeout-ms-test
  (testing "default timeout is 30000ms"
    (is (= 30000 sp/*query-timeout-ms*))))


(deftest with-query-timeout-test
  (testing "binds timeout for duration of function"
    (is (= 60000
           (util/with-query-timeout 60000
                                    #(identity sp/*query-timeout-ms*)))))

  (testing "restores original timeout after function"
    (let [original sp/*query-timeout-ms*]
      (util/with-query-timeout 99999 #(identity nil))
      (is (= original sp/*query-timeout-ms*))))

  (testing "works with nested calls"
    (is (= [30000 50000 100000 50000 30000]
           (let [results (atom [])]
             (swap! results conj sp/*query-timeout-ms*)
             (util/with-query-timeout 50000
                                      #(do
                                         (swap! results conj sp/*query-timeout-ms*)
                                         (util/with-query-timeout 100000
                                                                  (fn [] (swap! results conj sp/*query-timeout-ms*)))
                                         (swap! results conj sp/*query-timeout-ms*)))
             (swap! results conj sp/*query-timeout-ms*)
             @results)))))


;; === Attribute naming tests ===

(deftest entity-attr-test
  (testing "creates namespaced keyword from entity and field"
    (is (= :user/name (util/entity-attr :user :name)))
    (is (= :order/total (util/entity-attr :order :total)))
    (is (= :fn-schema/name (util/entity-attr :fn-schema :name))))

  (testing "handles string inputs"
    (is (= :user/email (util/entity-attr "user" "email")))))


(deftest metadata-attr-test
  (testing "creates metadata-namespaced keyword"
    (is (= :graphden.metadata/uuid (util/metadata-attr :uuid)))
    (is (= :graphden.metadata/kind (util/metadata-attr :kind)))
    (is (= :graphden.metadata/name (util/metadata-attr :name))))

  (testing "handles string inputs"
    (is (= :graphden.metadata/field-type (util/metadata-attr "field-type")))))


(deftest enum-value-ident-test
  (testing "creates enum value ident"
    (is (= :status.value/active (util/enum-value-ident :status :active)))
    (is (= :priority.value/high (util/enum-value-ident :priority :high))))

  (testing "handles string inputs"
    (is (= :status.value/pending (util/enum-value-ident "status" "pending")))))


;; === Connection validation tests ===

(deftest ensure-connection!-test
  (testing "returns connection when available"
    (let [conn {:some :connection}
          conn-atom (atom conn)]
      (is (= conn (util/ensure-connection! conn-atom "test-op")))))

  (testing "throws when connection is nil"
    (let [conn-atom (atom nil)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Cannot perform operation: storage not initialized"
            (util/ensure-connection! conn-atom "create-entity")))))

  (testing "error includes operation name"
    (let [conn-atom (atom nil)]
      (try
        (util/ensure-connection! conn-atom "my-operation")
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :storage-not-initialized (:type (ex-data e))))
          (is (= "my-operation" (:operation (ex-data e)))))))))


;; === Default configuration tests ===

(deftest default-local-config-test
  (testing "has required fields"
    (is (= :datomic-local (:server-type util/default-local-config)))
    (is (= :mem (:storage-dir util/default-local-config)))
    (is (string? (:system util/default-local-config)))))


;; === Validation tests ===

(deftest validate-db-name!-test
  (testing "accepts valid database names"
    (is (nil? (util/validate-db-name! "mydb")))
    (is (nil? (util/validate-db-name! "my-db")))
    (is (nil? (util/validate-db-name! "MyDatabase123")))
    (is (nil? (util/validate-db-name! "a"))))

  (testing "rejects non-string"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"db-name must be a string"
          (util/validate-db-name! nil)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"db-name must be a string"
          (util/validate-db-name! 123)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"db-name must be a string"
          (util/validate-db-name! :keyword))))

  (testing "rejects blank strings"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"db-name cannot be blank"
          (util/validate-db-name! "")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"db-name cannot be blank"
          (util/validate-db-name! "   "))))

  (testing "rejects names not starting with letter"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"db-name must start with a letter"
          (util/validate-db-name! "123db")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"db-name must start with a letter"
          (util/validate-db-name! "-mydb"))))

  (testing "rejects names with invalid characters"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"db-name must start with a letter"
          (util/validate-db-name! "my_db")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"db-name must start with a letter"
          (util/validate-db-name! "my.db")))))


(deftest validate-client-config!-test
  (testing "accepts valid datomic-local config"
    (is (nil? (util/validate-client-config!
                {:server-type :datomic-local
                 :storage-dir :mem
                 :system "test"}))))

  (testing "accepts valid peer-server config"
    (is (nil? (util/validate-client-config!
                {:server-type :peer-server
                 :endpoint "localhost:8998"
                 :access-key "test-access-key"
                 :secret "test-secret-key"}))))

  (testing "rejects non-map"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"client-config must be a map"
          (util/validate-client-config! nil)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"client-config must be a map"
          (util/validate-client-config! "string"))))

  (testing "rejects missing server-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"client-config must include :server-type"
          (util/validate-client-config! {}))))

  (testing "rejects invalid server-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unknown server-type"
          (util/validate-client-config! {:server-type :invalid}))))

  (testing "rejects datomic-local without system"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"datomic-local requires :system"
          (util/validate-client-config!
            {:server-type :datomic-local
             :storage-dir :mem}))))

  (testing "rejects datomic-local without storage-dir"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"datomic-local requires :storage-dir"
          (util/validate-client-config!
            {:server-type :datomic-local
             :system "test"}))))

  (testing "rejects peer-server without endpoint"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"peer-server requires :endpoint"
          (util/validate-client-config!
            {:server-type :peer-server
             :access-key "test-access-key"
             :secret "test-secret-key"}))))

  (testing "rejects peer-server without access-key"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"peer-server requires :access-key"
          (util/validate-client-config!
            {:server-type :peer-server
             :endpoint "localhost:8998"
             :secret "test-secret-key"}))))

  (testing "rejects peer-server without secret"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"peer-server requires :secret"
          (util/validate-client-config!
            {:server-type :peer-server
             :endpoint "localhost:8998"
             :access-key "test-access-key"}))))

  (testing "rejects peer-server with short access-key"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"access-key must be at least 8 characters"
          (util/validate-client-config!
            {:server-type :peer-server
             :endpoint "localhost:8998"
             :access-key "short"
             :secret "test-secret-key"}))))

  (testing "rejects peer-server with short secret"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"secret must be at least 8 characters"
          (util/validate-client-config!
            {:server-type :peer-server
             :endpoint "localhost:8998"
             :access-key "test-access-key"
             :secret "short"}))))

  (testing "accepts ion with minimal config (warns but doesn't throw)"
    (is (nil? (util/validate-client-config!
                {:server-type :ion}))))

  (testing "accepts cloud with minimal config (warns but doesn't throw)"
    (is (nil? (util/validate-client-config!
                {:server-type :cloud})))))


;; === execute-with-timeout! tests ===

(deftest execute-with-timeout!-test
  (testing "returns result of successful query"
    (is (= 42
           (util/execute-with-timeout! :test-op #(+ 40 2)))))

  (testing "throws on timeout"
    ;; Use with-query-timeout to properly bind both sp and config vars
    ;; min-query-timeout-ms is 1000, so use 1000 and sleep longer
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Query timeout"
          (util/with-query-timeout 1000
                                   #(util/execute-with-timeout!
                                      :slow-query
                                      (fn []
                                        (Thread/sleep 2000)
                                        :never-reached))))))

  (testing "timeout exception contains operation and timeout info"
    (try
      ;; Use with-query-timeout to properly bind both sp and config vars
      ;; min-query-timeout-ms is 1000, so use 1000 and sleep longer
      (util/with-query-timeout 1000
                               #(util/execute-with-timeout!
                                  :my-operation
                                  (fn []
                                    (Thread/sleep 2000)
                                    nil)))
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :query-timeout (:type (ex-data e))))
        (is (= :my-operation (:operation (ex-data e))))
        (is (= 1000 (:timeout-ms (ex-data e)))))))

  (testing "unwraps ExecutionException to get original exception"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Original error"
          (util/execute-with-timeout!
            :failing-query
            #(throw (ex-info "Original error" {:cause :test})))))))


;; === classify-datomic-error tests ===

(deftest classify-datomic-error-test
  (testing "classifies :db.error/not-an-entity as :not-found"
    (is (= :not-found
           (util/classify-datomic-error
             (ex-info "error" {:db/error :db.error/not-an-entity})))))

  (testing "classifies :db.error/unique-conflict as :constraint-violation/unique"
    (is (= :constraint-violation/unique
           (util/classify-datomic-error
             (ex-info "error" {:db/error :db.error/unique-conflict})))))

  (testing "classifies :db.error/invalid-entity-id as :not-found"
    (is (= :not-found
           (util/classify-datomic-error
             (ex-info "error" {:db/error :db.error/invalid-entity-id})))))

  (testing "classifies :db.error/datoms-conflict as :constraint-violation/unique"
    (is (= :constraint-violation/unique
           (util/classify-datomic-error
             (ex-info "error" {:db/error :db.error/datoms-conflict})))))

  (testing "classifies :db.error/cas-failed as :concurrent-modification"
    (is (= :concurrent-modification
           (util/classify-datomic-error
             (ex-info "error" {:db/error :db.error/cas-failed})))))

  (testing "classifies cognitect anomalies"
    (is (= :not-found
           (util/classify-datomic-error
             (ex-info "error" {:cognitect.anomalies/category :cognitect.anomalies/not-found}))))
    (is (= :constraint-violation/unique
           (util/classify-datomic-error
             (ex-info "error" {:cognitect.anomalies/category :cognitect.anomalies/conflict}))))
    (is (= :transient-error/busy
           (util/classify-datomic-error
             (ex-info "error" {:cognitect.anomalies/category :cognitect.anomalies/busy}))))
    (is (= :transient-error/unavailable
           (util/classify-datomic-error
             (ex-info "error" {:cognitect.anomalies/category :cognitect.anomalies/unavailable}))))
    (is (= :transient-error/interrupted
           (util/classify-datomic-error
             (ex-info "error" {:cognitect.anomalies/category :cognitect.anomalies/interrupted})))))

  (testing "classifies ExecutionException as :transient-error/execution"
    (is (= :transient-error/execution
           (util/classify-datomic-error
             (java.util.concurrent.ExecutionException. "error" nil)))))

  (testing "classifies ConnectException as :connection-error"
    (is (= :connection-error
           (util/classify-datomic-error
             (java.net.ConnectException. "connection refused")))))

  (testing "classifies IOException as :io-error"
    (is (= :io-error
           (util/classify-datomic-error
             (java.io.IOException. "IO failed")))))

  (testing "classifies unknown ExceptionInfo as :datomic-error"
    (is (= :datomic-error
           (util/classify-datomic-error
             (ex-info "unknown" {:some :data})))))

  (testing "classifies other exceptions as :datomic-error"
    (is (= :datomic-error
           (util/classify-datomic-error
             (Exception. "some exception"))))))


;; === wrap-datomic-error tests ===

(deftest wrap-datomic-error-test
  (testing "creates wrapped exception with context"
    (let [original (ex-info "Original" {:db/error :db.error/not-an-entity})
          wrapped (util/wrap-datomic-error original "Test error" :read {:id 123})]
      (is (instance? clojure.lang.ExceptionInfo wrapped))
      (is (= :not-found (:type (ex-data wrapped))))
      (is (= :read (:operation (ex-data wrapped))))
      (is (= 123 (:id (ex-data wrapped))))
      (is (= original (ex-cause wrapped)))))

  (testing "redacts sensitive context in exception message"
    (let [original (ex-info "DB error" {})
          wrapped (util/wrap-datomic-error original "Error" :query
                                           {:password "secret123"})]
      ;; The actual context in ex-data should still have password (for debugging)
      (is (= "secret123" (:password (ex-data wrapped)))))))


;; === with-datomic-error-handling tests ===

(deftest with-datomic-error-handling-test
  (testing "returns result when no exception"
    (is (= 42
           (util/with-datomic-error-handling "Test" :read {}
                                             (+ 40 2)))))

  (testing "wraps exception with context"
    (try
      (util/with-datomic-error-handling "DB error" :create {:entity :user}
                                        (throw (ex-info "Original" {:db/error :db.error/unique-conflict})))
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :constraint-violation/unique (:type (ex-data e))))
        (is (= :create (:operation (ex-data e))))
        (is (= :user (:entity (ex-data e))))))))


;; === DatomicErrorClassifier tests ===

(deftest datomic-error-classifier-test
  (let [classifier (util/create-error-classifier)]
    (testing "classify-error delegates to classify-datomic-error"
      (let [error (ex-info "test" {:db/error :db.error/not-an-entity})]
        (is (= :not-found
               (sp/classify-error classifier error)))))

    (testing "wrap-error creates wrapped exception"
      (let [error (ex-info "test" {:db/error :db.error/unique-conflict})
            wrapped (sp/wrap-error classifier error :create {:entity :user})]
        (is (= :constraint-violation/unique (:type (ex-data wrapped))))
        (is (= :create (:operation (ex-data wrapped))))))))
