(ns graphden.datomic-storage.edge-cases-test
  "Tests for datomic-storage edge cases: concurrent access, locks, input validation, errors."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.datomic-storage.core :as core]
    [graphden.datomic-storage.interface :as dat]
    [graphden.datomic-storage.test-setup :as setup]
    [graphden.datomic-storage.util :as util]
    [graphden.storage-protocol.contract-tests :as contract]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.test-helpers :as th]))


;; === Concurrent operation tests ===

(deftest concurrent-access-test
  (testing "concurrent reads are thread-safe"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                 :type :text}})
          errors (atom [])]
      (sp/initialize storage schema)
      (try
        (sp/create-entity storage :user {:name "Alice"})
        ;; Launch multiple threads reading concurrently
        (let [futures (doall
                        (for [_ (range 10)]
                          (future
                            (try
                              (dotimes [_ 50]
                                (sp/query-entities storage :user {}))
                              (catch Exception e
                                (swap! errors conj e))))))]
          (doseq [f futures]
            (deref f 5000 :timeout)))
        (is (empty? @errors) (str "Errors during concurrent access: " @errors))
        (finally
          (sp/close storage)))))

  (testing "concurrent writes are thread-safe"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema :fields {:value {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                  :type :int}})
          errors (atom [])]
      (sp/initialize storage schema)
      (try
        ;; Launch multiple threads creating entities concurrently
        (let [futures (doall
                        (for [i (range 5)]
                          (future
                            (try
                              (dotimes [j 10]
                                (sp/create-entity storage :user {:value (+ (* i 10) j)}))
                              (catch Exception e
                                (swap! errors conj e))))))]
          (doseq [f futures]
            (deref f 10000 :timeout)))
        (is (empty? @errors) (str "Errors during concurrent writes: " @errors))
        (is (= 50 (count (sp/query-entities storage :user {}))))
        (finally
          (sp/close storage))))))


;; === GraphConstraints contract tests ===

(deftest graph-constraints-contract-test
  (contract/run-graph-constraints-tests
    setup/create-test-storage
    sp/close))


;; === Lock function tests ===
;; These tests verify the shared lock utilities from storage-protocol

(deftest with-read-lock-test
  (let [rw-lock (java.util.concurrent.locks.ReentrantReadWriteLock.)
        write-lock (java.util.concurrent.locks.ReentrantReadWriteLock/.writeLock rw-lock)]
    (testing "with-read-lock returns value from function"
      (is (= 42 (sp/with-read-lock rw-lock #(+ 40 2)))))

    (testing "with-read-lock releases lock after normal return"
      (sp/with-read-lock rw-lock #(identity :done))
      ;; If lock wasn't released, we couldn't acquire write lock
      (is (true? (java.util.concurrent.locks.Lock/.tryLock write-lock)))
      (java.util.concurrent.locks.Lock/.unlock write-lock))

    (testing "with-read-lock releases lock after exception"
      (try
        (sp/with-read-lock rw-lock #(throw (ex-info "test error" {})))
        (catch Exception _))
      ;; If lock wasn't released, we couldn't acquire write lock
      (is (true? (java.util.concurrent.locks.Lock/.tryLock write-lock)))
      (java.util.concurrent.locks.Lock/.unlock write-lock))

    (testing "multiple readers can run concurrently"
      (let [read-count (atom 0)
            latch (java.util.concurrent.CountDownLatch. 2)]
        ;; Start two readers
        (future
          (sp/with-read-lock rw-lock
                             (fn []
                               (swap! read-count inc)
                               (java.util.concurrent.CountDownLatch/.countDown latch)
                               (Thread/sleep 50))))
        (future
          (sp/with-read-lock rw-lock
                             (fn []
                               (swap! read-count inc)
                               (java.util.concurrent.CountDownLatch/.countDown latch)
                               (Thread/sleep 50))))
        ;; Wait for both to be inside the lock
        (java.util.concurrent.CountDownLatch/.await latch 1 java.util.concurrent.TimeUnit/SECONDS)
        ;; Both readers should be running concurrently
        (is (= 2 @read-count))))))


(deftest with-write-lock-test
  (let [rw-lock (java.util.concurrent.locks.ReentrantReadWriteLock.)
        write-lock (java.util.concurrent.locks.ReentrantReadWriteLock/.writeLock rw-lock)]
    (testing "with-write-lock returns value from function"
      (is (= 42 (sp/with-write-lock rw-lock #(+ 40 2)))))

    (testing "with-write-lock releases lock after normal return"
      (sp/with-write-lock rw-lock #(identity :done))
      ;; If lock wasn't released, we couldn't acquire another write lock
      (is (true? (java.util.concurrent.locks.Lock/.tryLock write-lock)))
      (java.util.concurrent.locks.Lock/.unlock write-lock))

    (testing "with-write-lock releases lock after exception"
      (try
        (sp/with-write-lock rw-lock #(throw (ex-info "test error" {})))
        (catch Exception _))
      ;; If lock wasn't released, we couldn't acquire another write lock
      (is (true? (java.util.concurrent.locks.Lock/.tryLock write-lock)))
      (java.util.concurrent.locks.Lock/.unlock write-lock))))


(deftest with-query-timeout-test
  (testing "with-query-timeout executes function and returns result"
    (is (= 42 (dat/with-query-timeout 60000 #(+ 40 2)))))

  (testing "with-query-timeout binds timeout value"
    (is (= 5000
           (dat/with-query-timeout 5000
                                   #(identity sp/*query-timeout-ms*)))))

  (testing "with-query-timeout restores original value after execution"
    (let [original sp/*query-timeout-ms*]
      (dat/with-query-timeout 99999 #(identity :done))
      (is (= original sp/*query-timeout-ms*))))

  (testing "with-query-timeout restores value after exception"
    (let [original sp/*query-timeout-ms*]
      (try
        (dat/with-query-timeout 99999 #(throw (ex-info "test" {})))
        (catch Exception _))
      (is (= original sp/*query-timeout-ms*)))))


;; === Input validation tests ===

(deftest create-entity-invalid-data-test
  (testing "create-entity throws when data is not a map"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (try
        (sp/initialize storage schema)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"data must be a map"
              (sp/create-entity storage :user "not a map")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"data must be a map"
              (sp/create-entity storage :user [:a :vector])))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"data must be a map"
              (sp/create-entity storage :user 123)))
        (finally
          (sp/close storage))))))


(deftest query-entities-invalid-where-test
  (testing "query-entities throws when where is not a map"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (try
        (sp/initialize storage schema)
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"where clause must be nil or a map"
              (sp/query-entities storage :user "not a map")))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"where clause must be nil or a map"
              (sp/query-entities storage :user [:a :vector])))
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"where clause must be nil or a map"
              (sp/query-entities storage :user 123)))
        (finally
          (sp/close storage))))))


;; === ensure-connection! Tests ===

(deftest ensure-connection-error-test
  (testing "throws when storage is closed"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      ;; Close the storage
      (sp/close storage)
      ;; Now operations should fail with storage-not-initialized error
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"storage not initialized"
            (sp/read-entity storage :user (random-uuid))))))

  (testing "error includes operation name"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (sp/initialize storage schema)
      (sp/close storage)
      (try
        (sp/create-entity storage :user {:name "test"})
        (is false "should have thrown")
        (catch clojure.lang.ExceptionInfo e
          (is (= :storage-not-initialized (:type (ex-data e))))
          (is (some? (:operation (ex-data e)))))))))


;; === validate-db-name! Tests ===

(deftest validate-db-name-test
  (testing "rejects non-string db-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"db-name must be a string"
          (core/create-storage {:db-name 123})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"db-name must be a string"
          (core/create-storage {:db-name :keyword}))))

  (testing "rejects blank db-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"db-name cannot be blank"
          (core/create-storage {:db-name ""})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"db-name cannot be blank"
          (core/create-storage {:db-name "   "}))))

  (testing "rejects db-name with invalid pattern"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must start with a letter"
          (core/create-storage {:db-name "123abc"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must start with a letter"
          (core/create-storage {:db-name "-abc"})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must start with a letter"
          (core/create-storage {:db-name "a b c"}))))

  (testing "error includes db-name value"
    (try
      (core/create-storage {:db-name 42})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :config-error/invalid-db-name (:type (ex-data e))))
        (is (= 42 (:db-name (ex-data e))))))))


;; === validate-client-config! Tests ===

(deftest validate-client-config-test
  (testing "rejects non-map config"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"client-config must be a map"
          (core/create-storage {:db-name "test" :client-config "not-a-map"}))))

  (testing "rejects missing server-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"must include :server-type"
          (core/create-storage {:db-name "test" :client-config {}}))))

  (testing "rejects unknown server-type"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown server-type"
          (core/create-storage {:db-name "test"
                                :client-config {:server-type :unknown-type}}))))

  (testing "rejects datomic-local without :system"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :system"
          (core/create-storage {:db-name "test"
                                :client-config {:server-type :datomic-local
                                                :storage-dir :mem}}))))

  (testing "rejects datomic-local without :storage-dir"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :storage-dir"
          (core/create-storage {:db-name "test"
                                :client-config {:server-type :datomic-local
                                                :system "test"}}))))

  (testing "rejects peer-server without :endpoint"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :endpoint"
          (core/create-storage {:db-name "test"
                                :client-config {:server-type :peer-server}}))))

  (testing "rejects peer-server without :access-key"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :access-key"
          (core/create-storage {:db-name "test"
                                :client-config {:server-type :peer-server
                                                :endpoint "localhost:8998"}}))))

  (testing "rejects peer-server without :secret"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"requires :secret"
          (core/create-storage {:db-name "test"
                                :client-config {:server-type :peer-server
                                                :endpoint "localhost:8998"
                                                :access-key "test-access-key"}}))))

  (testing "error includes valid-types for unknown server-type"
    (try
      (core/create-storage {:db-name "test"
                            :client-config {:server-type :bad-type}})
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (is (= :config-error/invalid-server-type (:type (ex-data e))))
        (is (= :bad-type (:server-type (ex-data e))))
        (is (set? (:valid-types (ex-data e))))
        (is (contains? (:valid-types (ex-data e)) :datomic-local))))))


;; === with-query-timeout Tests ===

(deftest with-query-timeout-custom-test
  (testing "custom timeout can be set"
    (let [original-timeout sp/*query-timeout-ms*]
      (util/with-query-timeout 60000
                               (fn []
                                 (is (= 60000 sp/*query-timeout-ms*))))
      ;; Verify original is restored
      (is (= original-timeout sp/*query-timeout-ms*))))

  (testing "nested timeouts work correctly"
    (let [original-timeout sp/*query-timeout-ms*]
      (util/with-query-timeout 30000
                               (fn []
                                 (is (= 30000 sp/*query-timeout-ms*))
                                 (util/with-query-timeout 10000
                                                          (fn []
                                                            (is (= 10000 sp/*query-timeout-ms*))))
                                 ;; After inner binding ends, outer binding is restored
                                 (is (= 30000 sp/*query-timeout-ms*))))
      (is (= original-timeout sp/*query-timeout-ms*)))))


;; === Error classifier Tests ===

(deftest classify-error-test
  (let [storage (setup/create-test-storage)]
    (testing "classifies Datomic unique conflict"
      (let [exc (ex-info "test" {:db/error :db.error/unique-conflict})]
        (is (= :constraint-violation/unique (sp/classify-error storage exc)))))

    (testing "classifies Datomic not-found"
      (let [exc (ex-info "test" {:db/error :db.error/not-found})]
        (is (= :not-found (sp/classify-error storage exc)))))

    (testing "classifies Datomic datoms-conflict"
      (let [exc (ex-info "test" {:db/error :db.error/datoms-conflict})]
        (is (= :constraint-violation/unique (sp/classify-error storage exc)))))

    (testing "classifies Datomic invalid-entity-id"
      (let [exc (ex-info "test" {:db/error :db.error/invalid-entity-id})]
        (is (= :not-found (sp/classify-error storage exc)))))

    (testing "classifies Datomic cas-failed"
      (let [exc (ex-info "test" {:db/error :db.error/cas-failed})]
        (is (= :concurrent-modification (sp/classify-error storage exc)))))

    (testing "classifies our custom types"
      (let [exc (ex-info "test" {:type :custom-error})]
        (is (= :custom-error (sp/classify-error storage exc)))))

    (testing "returns datomic-error for other db/errors"
      (let [exc (ex-info "test" {:db/error :db.error/some-other-error})]
        (is (= :datomic-error (sp/classify-error storage exc)))))

    (testing "returns datomic-error for non-ExceptionInfo"
      (let [exc (Exception. "plain exception")]
        (is (= :datomic-error (sp/classify-error storage exc)))))))


;; === wrap-error Tests ===

(deftest wrap-error-test
  (let [storage (setup/create-test-storage)]
    (testing "wraps error with context"
      (let [original (ex-info "original" {:db/error :db.error/unique-conflict})
            wrapped (sp/wrap-error storage original :create-entity {:entity :user})]
        (is (instance? clojure.lang.ExceptionInfo wrapped))
        (is (str/includes? (ex-message wrapped) "create-entity"))
        (is (= :constraint-violation/unique (:type (ex-data wrapped))))
        (is (= :create-entity (:operation (ex-data wrapped))))
        (is (= :user (:entity (ex-data wrapped))))
        ;; Note: wrap-datomic-error doesn't preserve :db/error in wrapped data
        (is (= original (ex-cause wrapped)))))))
