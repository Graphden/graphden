(ns graphden.cache-postgres.test-setup
  "Test setup and fixtures for cache-postgres tests."
  (:require
    [graphden.cache-data-schema.interface :as cds]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.postgres-storage.interface :as pg]
    [graphden.storage-protocol.interface :as sp]
    [graphden.storage-protocol.postgres-test-helpers :as pth]))


;; === Testcontainers setup ===

(def ^:dynamic *container* nil)


(defn container-fixture
  "Returns the container fixture for :once use."
  []
  (pth/create-container-fixture #'*container*))


(defn clean-db-fixture
  "Returns the clean-db fixture for :each use."
  []
  (pth/create-clean-db-fixture #'*container*))


(defn create-test-storage
  "Creates a test storage with cache schema initialized."
  []
  (pth/clean-database-fast! *container*)
  (let [storage (pg/create-storage (pth/get-container-config *container*))
        schema (cds/build-schema (mds/create-builder))]
    (sp/initialize storage schema)
    storage))


(defn get-datasource
  "Gets the datasource (pool) from storage."
  [storage]
  (:pool storage))
