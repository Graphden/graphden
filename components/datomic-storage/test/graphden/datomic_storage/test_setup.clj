(ns graphden.datomic-storage.test-setup
  "Shared test fixtures and helpers for datomic-storage tests."
  (:require
    [graphden.datomic-storage.interface :as dat]))


;; === Test fixtures ===

(def ^:private test-counter (atom 0))


(defn unique-db-name
  "Generates a unique database name for each test."
  []
  (str "test-" (swap! test-counter inc) "-" (System/currentTimeMillis)))


(defn create-test-storage
  "Creates a test storage with a unique database."
  []
  (dat/create-storage {:db-name (unique-db-name)}))
