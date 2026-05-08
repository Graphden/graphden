(ns graphden.storage.protocol.contract-tests
  "Contract tests for GraphConstraints protocol.
   These tests verify that any storage implementation correctly enforces
   graph integrity constraints. Each storage module should run these tests
   with their specific storage factory function.

   The schema is the production graph schema (fn / slot / fn-slot /
   binding / binding-list-item) so the cycle walker reaches the
   `:binding` table even when the test only exercises fn rows.

   Usage in storage implementation tests:
   ```clojure
   (require '[graphden.storage.protocol.contract-tests :as contract])
   (contract/run-graph-constraints-tests
     (fn [] (my-storage/create-storage ...))
     #(my-storage/close-storage %))
   ```"
  (:require
    [clojure.test :refer [is testing]]
    [graphden.schema.graph.schema :as graph-schema]
    [graphden.schema.malli.core :as mds]
    [graphden.storage.protocol.core :as sp]))


;; === Schema helper ===

(def ^:private graph-schema
  "Production graph schema (fn / slot / fn-slot / binding /
   binding-list-item plus namespaces). The cycle walker reads
   `:binding` for ref-fn-id chains; tests pass even with no
   bindings present, as long as the table exists."
  (graph-schema/build-schema (mds/create-builder)))


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
                                           :parent-ids nil
                                           :impl-hash "test-hash"})]
            ;; Should allow nil ref-id (literal value, not a fn reference)
            (is (nil? (sp/validate-no-dependency-cycle! storage (:id base-fn) nil))))
          (finally
            (close-storage-fn storage)))))

    (testing "allows self-reference (recursion is intended; depth bounded by executor)"
      (let [storage (create-storage-fn)]
        (try
          (sp/initialize storage graph-schema)
          (let [base-fn (sp/create-entity storage :fn
                                          {:name "test-fn"
                                           :parent-ids nil
                                           :impl-hash "test-hash"})]
            ;; Self-reference is the WHOLE point of recursion. The
            ;; storage protocol carves it out per docs/CONSTRAINTS.md
            ;; § Self-reference; executor's *max-depth* bounds the
            ;; runtime cost. Without this carve-out, a user couldn't
            ;; write a recursive fn-def at all.
            (is (nil? (sp/validate-no-dependency-cycle!
                        storage (:id base-fn) (:id base-fn)))))
          (finally
            (close-storage-fn storage)))))

    (testing "allows reference to different fn"
      (let [storage (create-storage-fn)]
        (try
          (sp/initialize storage graph-schema)
          (let [fn-a (sp/create-entity storage :fn
                                       {:name "fn-a"
                                        :parent-ids nil
                                        :impl-hash "test-hash"})
                fn-b (sp/create-entity storage :fn
                                       {:name "fn-b"
                                        :parent-ids nil
                                        :impl-hash "test-hash"})]
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
                                           :parent-ids nil
                                           :impl-hash "test-hash"})
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
                             :parent-ids nil
                             :impl-hash (str "test-hash-" t "-" i)})]
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
