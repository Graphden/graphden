(ns graphden.storage-protocol.crud-validation-test
  "Tests for CRUD validation helpers."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage-protocol.interface :as storage]))


;; === Storage Implementation Helpers tests ===

(deftest create-rw-lock-test
  (testing "creates ReentrantReadWriteLock"
    (let [lock (storage/create-rw-lock)]
      (is (instance? java.util.concurrent.locks.ReentrantReadWriteLock lock)))))


(deftest standard-crud-validations!-test
  (testing "passes for valid data"
    (is (nil? (storage/standard-crud-validations! :user {:name "test"} nil)))
    (is (nil? (storage/standard-crud-validations!
                :user
                {:name "test"}
                {:name {:required true}}))))

  (testing "throws for non-map data"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"data must be a map"
          (storage/standard-crud-validations! :user "not a map" nil))))

  (testing "throws for missing required fields"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Required field.*missing"
          (storage/standard-crud-validations!
            :user
            {:other "field"}
            {:name {:required true}})))))


(deftest standard-batch-validations!-test
  (testing "passes for unique IDs"
    (let [id1 (random-uuid)
          id2 (random-uuid)]
      (is (nil? (storage/standard-batch-validations! :user [{:id id1} {:id id2}])))))

  (testing "throws for duplicate IDs"
    (let [dup-id (random-uuid)]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Duplicate IDs"
            (storage/standard-batch-validations! :user [{:id dup-id} {:id dup-id}]))))))
