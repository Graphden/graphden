(ns graphden.web-server.interface
  "Web server component combining http-kit and reitit.

   This component provides:
   1. All base functions from http-kit-fns and reitit-fns
   2. A high-level web-server base function that combines routing + HTTP
   3. Utility functions for registering functions and syncing to storage

   ## Usage

   ```clojure
   ;; Get all base function definitions (http-kit + reitit + web-server)
   (get-all-defs)

   ;; Register and sync to storage
   (initialize-storage! storage)
   ```"
  (:require
    [graphden.web-server.core :as core]))


(defn get-all-defs
  "Returns all web server base function definitions.
   Includes http-kit, reitit, and composite functions."
  []
  (core/get-all-defs))


(defn initialize-storage!
  "Initializes storage with web server base functions.

   This function:
   1. Registers all web server functions in the executor
   2. Syncs function schemas to storage

   Should be called after storage is created with graph schema.

   Arguments:
   - storage: an initialized storage instance

   Returns the storage instance."
  [storage]
  (core/initialize-storage! storage))
