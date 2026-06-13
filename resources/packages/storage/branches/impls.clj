(ns graphden.packages.storage.branches.impls
  "Impls for storage/branches base functions.

   Sole base-fn here is `:current-branch-id` — a 0-arg primitive that
   reads the active branch off the request's VersionedStorage wrapper.
   It lives in storage (not app) because every versioned-read site
   needs to pair `:branch-chain` with a starting branch-id, and the
   read paths are storage-layer concerns. Decoupling from `app/branches`
   means lower packages (`web/crud`, future external integrations) can
   compose against the active branch without taking an app-level dep."
  (:require
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.versioning.storage.core :as vs]))


(defbase current-branch-id
  "Read the active branch id off the VersionedStorage wrapper —
   `(vs/current-branch-id storage)`. Single library call (§3.1)."
  []
  (cr/record-effect! :db)
  (vs/current-branch-id (request/require-storage ctx)))


(def impls
  {:current-branch-id current-branch-id})
