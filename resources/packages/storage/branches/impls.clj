(ns graphden.packages.storage.branches.impls
  "Impls for storage/branches base functions.

   One thin primitive — `:current-branch-id`, the active branch id off
   the request's VersionedStorage wrapper (one library call). It lives
   in `storage/branches` (not `app/branches`) because lower packages
   (`web/crud`, future external integrations) need to compose against
   branch state without taking an app-level dep. (The branch-local walk
   `graphden.versioning.branch-local/effective-branch-local?` is read by
   the layout's strip facts, not through a graph base-fn.)"
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
