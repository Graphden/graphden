(ns graphden.storage.protocol.config-test
  "Tests for storage-protocol.config - declarative configuration validation."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.protocol.config :as cfg]))


;; === Schema definition tests ===

(deftest non-blank-string-schema-test
  (testing "schema is defined"
    (is (some? cfg/non-blank-string))))


(deftest positive-int-schema-test
  (testing "schema is defined"
    (is (some? cfg/positive-int))))


(deftest non-negative-int-schema-test
  (testing "schema is defined"
    (is (some? cfg/non-negative-int))))


(deftest postgres-pool-config-schema-test
  (testing "schema is defined"
    (is (some? cfg/postgres-pool-config))))


;; === validate-config! tests ===

(deftest validate-config!-test
  (testing "passes for valid data"
    (is (nil? (cfg/validate-config! {:name "test"} [:map [:name :string]] "test"))))

  (testing "throws for invalid data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid test configuration"
          (cfg/validate-config! {:name 123} [:map [:name :string]] "test"))))

  (testing "exception contains config-name"
    (try
      (cfg/validate-config! {} [:map [:required :string]] "MyConfig")
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :config-error/invalid-config (:type (ex-data e))))
        (is (= "MyConfig" (:config-name (ex-data e))))
        (is (some? (:errors (ex-data e))))
        (is (= {} (:config (ex-data e))))))))


;; === validate-postgres-config! tests ===

(deftest validate-postgres-config!-test
  (testing "passes for valid minimal config"
    (is (nil? (cfg/validate-postgres-config!
                {:jdbc-url "jdbc:postgresql://localhost:5432/db"
                 :username "user"
                 :password "pass"}))))

  (testing "passes for valid config with all options"
    (is (nil? (cfg/validate-postgres-config!
                {:jdbc-url "jdbc:postgresql://localhost:5432/db"
                 :username "user"
                 :password "pass"
                 :pool-size 20
                 :min-idle 5
                 :connection-timeout 60000
                 :idle-timeout 300000
                 :max-lifetime 1800000}))))

  (testing "throws for missing jdbc-url"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid PostgreSQL pool configuration"
          (cfg/validate-postgres-config!
            {:username "user" :password "pass"}))))

  (testing "throws for invalid jdbc-url prefix"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid PostgreSQL pool configuration"
          (cfg/validate-postgres-config!
            {:jdbc-url "mysql://localhost:3306/db"
             :username "user"
             :password "pass"}))))

  (testing "throws for missing username"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid PostgreSQL pool configuration"
          (cfg/validate-postgres-config!
            {:jdbc-url "jdbc:postgresql://localhost:5432/db"
             :password "pass"}))))

  (testing "throws for blank username"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid PostgreSQL pool configuration"
          (cfg/validate-postgres-config!
            {:jdbc-url "jdbc:postgresql://localhost:5432/db"
             :username "   "
             :password "pass"}))))

  (testing "throws for missing password"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid PostgreSQL pool configuration"
          (cfg/validate-postgres-config!
            {:jdbc-url "jdbc:postgresql://localhost:5432/db"
             :username "user"}))))

  (testing "throws for pool-size > 100"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid PostgreSQL pool configuration"
          (cfg/validate-postgres-config!
            {:jdbc-url "jdbc:postgresql://localhost:5432/db"
             :username "user"
             :password "pass"
             :pool-size 101}))))

  (testing "throws for pool-size <= 0"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid PostgreSQL pool configuration"
          (cfg/validate-postgres-config!
            {:jdbc-url "jdbc:postgresql://localhost:5432/db"
             :username "user"
             :password "pass"
             :pool-size 0}))))

  (testing "throws when min-idle > pool-size"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"min-idle cannot exceed pool-size"
          (cfg/validate-postgres-config!
            {:jdbc-url "jdbc:postgresql://localhost:5432/db"
             :username "user"
             :password "pass"
             :pool-size 5
             :min-idle 10}))))

  (testing "throws when idle-timeout >= max-lifetime"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"idle-timeout must be less than max-lifetime"
          (cfg/validate-postgres-config!
            {:jdbc-url "jdbc:postgresql://localhost:5432/db"
             :username "user"
             :password "pass"
             :idle-timeout 2000000
             :max-lifetime 1800000}))))

  (testing "allows idle-timeout 0 (disabled)"
    (is (nil? (cfg/validate-postgres-config!
                {:jdbc-url "jdbc:postgresql://localhost:5432/db"
                 :username "user"
                 :password "pass"
                 :idle-timeout 0}))))

  (testing "rejects extra keys (closed map)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Invalid PostgreSQL pool configuration"
          (cfg/validate-postgres-config!
            {:jdbc-url "jdbc:postgresql://localhost:5432/db"
             :username "user"
             :password "pass"
             :unknown-key "value"})))))


;; === Query timeout tests ===

(deftest query-timeout-dynamic-var-test
  (testing "default value is set"
    (is (pos-int? cfg/*query-timeout-ms*))))


(deftest min-query-timeout-ms-test
  (testing "minimum is 1000ms"
    (is (= 1000 cfg/min-query-timeout-ms))))


(deftest validate-query-timeout!-test
  (testing "passes for valid timeout"
    (is (nil? (cfg/validate-query-timeout! 5000))))

  (testing "passes for minimum timeout"
    (is (nil? (cfg/validate-query-timeout! 1000))))

  (testing "throws for non-positive integer"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Query timeout must be a positive integer"
          (cfg/validate-query-timeout! 0)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Query timeout must be a positive integer"
          (cfg/validate-query-timeout! -100))))

  (testing "throws for below minimum"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Query timeout must be at least"
          (cfg/validate-query-timeout! 500)))))


(deftest with-query-timeout-test
  (testing "executes function with custom timeout"
    (let [captured (atom nil)]
      (cfg/with-query-timeout 5000
                              #(reset! captured cfg/*query-timeout-ms*))
      (is (= 5000 @captured))))

  (testing "returns function result"
    (is (= 42 (cfg/with-query-timeout 5000 #(+ 40 2)))))

  (testing "validates timeout"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Query timeout must be at least"
          (cfg/with-query-timeout 100 #(identity 1))))))


(deftest get-query-timeout-seconds-test
  (testing "converts milliseconds to seconds"
    (cfg/with-query-timeout 5000
                            #(is (= 5 (cfg/get-query-timeout-seconds)))))

  (testing "throws for timeout below minimum"
    ;; Use binding directly to simulate improper usage
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Query timeout must be at least"
          (binding [cfg/*query-timeout-ms* 100]
            (cfg/get-query-timeout-seconds))))))


(deftest execute-with-timeout!-test
  (testing "returns result for fast operation"
    (is (= 42 (cfg/with-query-timeout 5000
                                      #(cfg/execute-with-timeout! :test-op (fn [] 42))))))

  (testing "throws timeout for slow operation"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Query timeout after"
          (cfg/with-query-timeout 1000
                                  #(cfg/execute-with-timeout! :slow-op (fn [] (Thread/sleep 2000)))))))

  (testing "unwraps ExecutionException"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"inner error"
          (cfg/with-query-timeout 5000
                                  #(cfg/execute-with-timeout! :error-op
                                                              (fn [] (throw (ex-info "inner error" {:cause :test})))))))))


;; === Regex limits tests ===

(deftest regex-limits-dynamic-vars-test
  (testing "default values are set"
    (is (pos-int? cfg/*max-regex-length*))
    (is (pos-int? cfg/*max-regex-input-length*))
    (is (pos-int? cfg/*regex-compile-timeout-ms*))))


(deftest with-regex-limits-test
  (testing "binds custom limits"
    (cfg/with-regex-limits {:max-pattern-length 50
                            :max-input-length 5000
                            :compile-timeout-ms 200}
                           #(do
                              (is (= 50 cfg/*max-regex-length*))
                              (is (= 5000 cfg/*max-regex-input-length*))
                              (is (= 200 cfg/*regex-compile-timeout-ms*)))))

  (testing "returns function result"
    (is (= :result (cfg/with-regex-limits {} #(identity :result)))))

  (testing "uses defaults for unspecified options"
    (let [original-pattern cfg/*max-regex-length*]
      (cfg/with-regex-limits {:max-input-length 999}
                             #(do
                                (is (= original-pattern cfg/*max-regex-length*))
                                (is (= 999 cfg/*max-regex-input-length*)))))))


;; === Lazy sequence limits tests ===

(deftest lazy-seq-limits-dynamic-vars-test
  (testing "default values are set"
    (is (pos-int? cfg/*max-lazy-seq-size*))
    (is (pos-int? cfg/*max-nested-collection-depth*))))
