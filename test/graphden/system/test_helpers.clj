(ns graphden.system.test-helpers
  "Test helpers for Integrant system tests.

   Provides fixtures that:
   1. Read test config
   2. Override with testcontainer JDBC URL
   3. Start/stop system around tests

   Usage:
     (use-fixtures :once
       (pth/create-container-fixture #'*container*)
       (create-system-fixture))

     (deftest my-test
       (let [storage (storage)]
         ...))"
  (:require
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.system.interface :as sys]
    [integrant.core :as ig]))


;; =============================================================================
;; Dynamic vars for test state
;; =============================================================================

(def ^:dynamic *system*
  "Dynamic var holding the running system for tests."
  nil)


(def ^:dynamic *container*
  "Dynamic var holding the test container."
  nil)


;; =============================================================================
;; Fixtures
;; =============================================================================

(defn create-system-fixture
  "Creates a fixture that starts system with testcontainer config.
   Requires *container* to be bound (from pth/create-container-fixture).

   Usage:
     (use-fixtures :once
       (pth/create-container-fixture #'*container*)
       (create-system-fixture))"
  []
  (fn [f]
    (let [jdbc-url (:jdbc-url (pth/get-container-config *container*))
          config (-> (sys/read-config :test)
                     (assoc-in [:db/postgres :jdbc-url] jdbc-url))]
      (binding [*system* (ig/init config)]
        (try
          (f)
          (finally
            (ig/halt! *system*)))))))


(defn create-partial-system-fixture
  "Creates a fixture that starts only specified components.
   Useful when you don't need the full system.

   Usage:
     (use-fixtures :once
       (pth/create-container-fixture #'*container*)
       (create-partial-system-fixture [:db/schema :db/postgres :db/versioned]))"
  [component-keys]
  (fn [f]
    (let [jdbc-url (:jdbc-url (pth/get-container-config *container*))
          config (-> (sys/read-config :test)
                     (assoc-in [:db/postgres :jdbc-url] jdbc-url))]
      (binding [*system* (ig/init config component-keys)]
        (try
          (f)
          (finally
            (ig/halt! *system*)))))))


;; =============================================================================
;; Accessors
;; =============================================================================

(defn storage
  "Returns the versioned storage from test system."
  []
  (:db/versioned *system*))


(defn postgres-storage
  "Returns the raw PostgreSQL storage from test system."
  []
  (:db/postgres *system*))


(defn context
  "Returns the executor context from test system."
  []
  (:exec/context *system*))


(defn schema
  "Returns the built schema from test system."
  []
  (:db/schema *system*))
