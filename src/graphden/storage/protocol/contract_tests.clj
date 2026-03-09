(ns graphden.storage.protocol.contract-tests
  "Contract tests for GraphConstraints protocol.
   These tests verify that any storage implementation correctly enforces
   graph integrity constraints. Each storage module should run these tests
   with their specific storage factory function.

   ## 2-Entity Schema

   Uses simplified schema:
   - fn: parent-id=nil for base-fn, parent-id set for composed fn
   - arg: fn-id (owner), source-id (parent's arg), value/ref-id (data), is-fn (HOF)

   Usage in storage implementation tests:
   ```clojure
   (require '[graphden.storage.protocol.contract-tests :as contract])
   (contract/run-graph-constraints-tests
     (fn [] (my-storage/create-storage ...))
     #(my-storage/close-storage %))
   ```"
  (:require
    [clojure.test :refer [is testing]]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.protocol.core :as sp]))


;; === Schema helper ===

(def ^:private graph-schema
  "Schema for graph constraint testing.
   Uses 2-entity schema: fn + arg."
  (-> (mds/create-builder)
      ;; fn entity (base-fns have parent-id=nil, composed fns have parent-id set)
      (ds/add-entity :fn #uuid "10000000-0000-0000-0000-000000000001"
                     {:name {:uuid #uuid "10000000-0000-0000-0000-000000000002"
                             :type :text}
                      :parent-id {:uuid #uuid "10000000-0000-0000-0000-000000000003"
                                  :type :uuid
                                  :nullable? true}
                      :return-type {:uuid #uuid "10000000-0000-0000-0000-000000000004"
                                    :type :text
                                    :nullable? true}})
      (ds/add-constraint :fn {:type :unique :fields [:name]})

      ;; arg entity (belongs to fn via fn-id, inherits from parent's arg via source-id)
      (ds/add-entity :arg #uuid "10000000-0000-0000-0000-000000000010"
                     {:fn-id {:uuid #uuid "10000000-0000-0000-0000-000000000011"
                              :type :uuid}
                      :name {:uuid #uuid "10000000-0000-0000-0000-000000000012"
                             :type :text}
                      :type {:uuid #uuid "10000000-0000-0000-0000-000000000013"
                             :type :text}
                      :required {:uuid #uuid "10000000-0000-0000-0000-000000000014"
                                 :type :bool}
                      :is-fn {:uuid #uuid "10000000-0000-0000-0000-000000000015"
                              :type :bool}
                      :source-id {:uuid #uuid "10000000-0000-0000-0000-000000000016"
                                  :type :uuid
                                  :nullable? true}
                      :value {:uuid #uuid "10000000-0000-0000-0000-000000000017"
                              :type :text
                              :nullable? true}
                      :ref-id {:uuid #uuid "10000000-0000-0000-0000-000000000018"
                               :type :uuid
                               :nullable? true}})
      (ds/add-constraint :arg {:type :unique :fields [:fn-id :name]})
      ds/build))


;; === Contract test runner ===

(defn run-graph-constraints-tests
  "Runs all GraphConstraints contract tests against a storage.

   Arguments:
   - create-storage-fn: Zero-arg function that creates and returns a storage instance
   - close-storage-fn: One-arg function that closes the storage

   Example:
   ```clojure
   (run-graph-constraints-tests
     #(pg/create-storage {...})
     sp/close)
   ```"
  [create-storage-fn close-storage-fn]

  (testing "validate-no-dependency-cycle! contract - basic tests"

    (testing "allows nil ref-id"
      (let [storage (create-storage-fn)]
        (try
          (sp/initialize storage graph-schema)
          (let [base-fn (sp/create-entity storage :fn
                                          {:name "test-fn"
                                           :parent-id nil
                                           :return-type "int"
})]
            ;; Should allow nil ref-id (literal value, not a fn reference)
            (is (nil? (sp/validate-no-dependency-cycle! storage (:id base-fn) nil))))
          (finally
            (close-storage-fn storage)))))

    (testing "rejects self-reference as cycle"
      (let [storage (create-storage-fn)]
        (try
          (sp/initialize storage graph-schema)
          (let [base-fn (sp/create-entity storage :fn
                                          {:name "test-fn"
                                           :parent-id nil
                                           :return-type "int"
})]
            ;; Self-reference is a cycle at storage level
            ;; Recursion is handled at executor level via lazy evaluation
            (is (thrown-with-msg?
                  clojure.lang.ExceptionInfo
                  #"cycle"
                  (sp/validate-no-dependency-cycle! storage (:id base-fn) (:id base-fn)))))
          (finally
            (close-storage-fn storage)))))

    (testing "allows reference to different fn"
      (let [storage (create-storage-fn)]
        (try
          (sp/initialize storage graph-schema)
          (let [fn-a (sp/create-entity storage :fn
                                       {:name "fn-a"
                                        :parent-id nil
                                        :return-type "int"
})
                fn-b (sp/create-entity storage :fn
                                       {:name "fn-b"
                                        :parent-id nil
                                        :return-type "int"
})]
            ;; Should allow reference to a different fn
            (is (nil? (sp/validate-no-dependency-cycle! storage (:id fn-a) (:id fn-b)))))
          (finally
            (close-storage-fn storage)))))))


(defn concurrent-read-write-test
  "Tests that concurrent reads and writes don't produce stale or corrupt data.
   This is a contract test - implementations must handle concurrency safely."
  [create-storage-fn close-storage-fn]
  (testing "concurrent reads during write don't see partial state"
    (let [storage (create-storage-fn)]
      (try
        (sp/initialize storage graph-schema)
        (let [;; Create initial fn
              fn-record (sp/create-entity storage :fn
                                          {:name "concurrent-fn"
                                           :parent-id nil
                                           :return-type "int"
})
              fn-id (:id fn-record)
              ;; Run concurrent reads while updating
              read-results (atom [])
              update-done (promise)
              num-readers 5]
          ;; Start readers
          (dotimes [_ num-readers]
            (future
              (dotimes [_ 10]
                (when-let [result (sp/read-entity storage :fn fn-id)]
                  (swap! read-results conj result))
                (Thread/sleep 1))))
          ;; Perform update
          (sp/update-entity storage :fn fn-id {:name "updated-concurrent-fn"})
          (deliver update-done true)
          ;; Wait for readers
          (Thread/sleep 100)
          ;; All reads should be valid (either old or new name, never partial)
          (doseq [result @read-results]
            (is (contains? #{"concurrent-fn" "updated-concurrent-fn"} (:name result))
                "Read should return complete record, not partial state")))
        (finally
          (close-storage-fn storage)))))

  (testing "batch create maintains consistency under concurrent access"
    (let [storage (create-storage-fn)]
      (try
        (sp/initialize storage graph-schema)
        (let [;; Create fns concurrently from multiple threads
              results (atom [])
              num-threads 3
              fns-per-thread 5
              latch (java.util.concurrent.CountDownLatch. num-threads)]
          (dotimes [t num-threads]
            (future
              (try
                (let [fns (for [i (range fns-per-thread)]
                            {:name (str "batch-fn-" t "-" i)
                             :parent-id nil
                             :return-type "int"
})]
                  (swap! results concat (sp/create-entities storage :fn fns)))
                (finally
                  (java.util.concurrent.CountDownLatch/.countDown latch)))))
          (java.util.concurrent.CountDownLatch/.await latch 5000 java.util.concurrent.TimeUnit/MILLISECONDS)
          ;; All fns should be created with unique IDs
          (is (= (* num-threads fns-per-thread) (count @results))
              "All batch creates should succeed")
          (is (= (count @results) (count (set (map :id @results))))
              "All IDs should be unique"))
        (finally
          (close-storage-fn storage))))))
