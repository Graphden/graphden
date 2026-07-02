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
                                           :parent-ids nil})]
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
                                           :parent-ids nil})]
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
                                        :parent-ids nil})
                fn-b (sp/create-entity storage :fn
                                       {:name "fn-b"
                                        :parent-ids nil})]
            ;; Should allow reference to a different fn
            (is (nil? (sp/validate-no-dependency-cycle! storage (:id fn-a) (:id fn-b)))))
          (finally
            (close-storage-fn storage)))))))
