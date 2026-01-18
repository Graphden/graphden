(ns graphden.memory-storage.test-setup
  "Shared test setup for memory-storage tests.

   Provides helper functions for creating test storage and common test utilities."
  (:require
    [graphden.memory-storage.interface :as mem]
    [graphden.storage-protocol.test-helpers :as th]))


(defn create-test-storage
  "Creates a fresh memory storage instance for testing."
  []
  (mem/create-storage))


;; Re-export th/make-schema for convenience
(def make-schema th/make-schema)
