(ns graphden.packages.storage.branches.impls
  "Impls for storage/branches base functions.

   Two thin primitives:
     - `:current-branch-id` — active branch id off the request's
       VersionedStorage wrapper.
     - `:effective-branch-local?` — does this fn (or any ancestor)
       carry the `:branch-local?` runtime-config marker?

   Both wrap one library call each. They live in `storage/branches`
   (not `app/branches`) because lower packages (`web/crud`, future
   external integrations) need to compose against branch state
   without taking an app-level dep."
  (:require
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.versioning.branch-local :as bl]
    [graphden.versioning.storage.core :as vs]))


(defbase current-branch-id
  "Read the active branch id off the VersionedStorage wrapper —
   `(vs/current-branch-id storage)`. Single library call (§3.1)."
  []
  (cr/record-effect! :db)
  (vs/current-branch-id (request/require-storage ctx)))


(defbase effective-branch-local?
  "True iff `fn-id` or any ancestor in its `:parent-ids` closure
   carries `:branch-local? true`. Memoized per base-storage by the
   underlying helper. nil fn-id → false."
  [fn-id]
  (cr/record-effect! :db)
  (let [storage (request/require-storage ctx)
        base    (if (instance? graphden.versioning.storage.core.VersionedStorage storage)
                  (graphden.versioning.storage.core.VersionedStorage/.base-storage storage)
                  storage)]
    (bl/effective-branch-local? base fn-id)))


(def impls
  {:current-branch-id current-branch-id
   :effective-branch-local? effective-branch-local?})
