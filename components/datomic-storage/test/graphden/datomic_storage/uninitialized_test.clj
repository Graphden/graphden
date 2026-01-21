(ns graphden.datomic-storage.uninitialized-test
  "Tests for datomic-storage operations on uninitialized storage."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.datomic-storage.test-setup :as setup]
    [graphden.storage-protocol.interface :as sp]))


(deftest uninitialized-storage-test
  (testing "current-entities returns empty set when storage not connected"
    (let [storage (setup/create-test-storage)]
      ;; Don't initialize - just close immediately to disconnect
      (sp/close storage)
      ;; Should return empty set, not throw
      (is (= #{} (sp/current-entities storage)))))

  (testing "current-enums returns empty set when storage not connected"
    (let [storage (setup/create-test-storage)]
      (sp/close storage)
      (is (= #{} (sp/current-enums storage)))))

  (testing "constraint validation throws when storage not connected"
    (let [storage (setup/create-test-storage)
          fake-fn-id #uuid "11111111-1111-1111-1111-111111111111"
          fake-ref-fn-id #uuid "33333333-3333-3333-3333-333333333333"
          fake-arg-schema-id #uuid "22222222-2222-2222-2222-222222222222"]
      (sp/close storage)
      ;; All constraint validations should throw :storage-not-initialized
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)storage not initialized"
            (sp/validate-arg-schema-belongs-to-fn! storage fake-fn-id fake-arg-schema-id)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"(?i)storage not initialized"
            (sp/validate-no-dependency-cycle! storage fake-fn-id fake-ref-fn-id)))))

  (testing "CRUD operations throw when storage not initialized"
    (let [storage (setup/create-test-storage)
          fake-id #uuid "11111111-1111-1111-1111-111111111111"]
      ;; Don't initialize, just close to ensure conn is nil
      (sp/close storage)
      ;; All CRUD operations should throw :storage-not-initialized
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/create-entity storage :user {:id fake-id :name "test"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/read-entity storage :user fake-id)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/update-entity storage :user fake-id {:name "updated"})))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/delete-entity storage :user fake-id)))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/query-entities storage :user {:name "test"})))))

  (testing "Batch CRUD operations throw when storage not initialized"
    (let [storage (setup/create-test-storage)
          fake-id #uuid "11111111-1111-1111-1111-111111111111"]
      (sp/close storage)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/create-entities storage :user [{:id fake-id :name "test"}])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/read-entities storage :user [fake-id])))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/delete-entities storage :user [fake-id])))))

  (testing "resolve-execution-graph throws when storage not initialized"
    (let [storage (setup/create-test-storage)
          fake-id #uuid "11111111-1111-1111-1111-111111111111"]
      (sp/close storage)
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"storage not initialized"
            (sp/resolve-execution-graph storage fake-id))))))
