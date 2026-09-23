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
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.executor.registry.core :as registry-core]
    [graphden.packages.sync :as pkg-sync]
    [graphden.system.branch-router :as br]
    [graphden.versioning.storage.core :as vs]))


(defbase current-branch-id
  "Read the active branch id off the VersionedStorage wrapper —
   `(vs/current-branch-id storage)`. Single library call (§3.1)."
  []
  (cr/record-effect! :db)
  (vs/current-branch-id (request/require-storage ctx)))


(defbase sync-fn-defs-branch!
  "Sync `fn-defs` into the branch `branch-id` and delta-invalidate THAT
   branch's compiled registry. Returns the synced fn-ids as text.

   Atomic by construction: the namespace upsert, the fn sync and the
   invalidation land together — a half-synced bundle leaves a branch
   whose registry disagrees with its rows (same carve-out as
   `merge-branch!`). Writes go through the SAME `sync-bundle!` the
   package loader uses, so an AI's proposal or an imported bundle meets
   the same constraints (cycles, name collisions, seals, type-check) as a
   human's fns.edn. The two callers — the MCP `upsert-fn-defs` tool and
   the registry's bundle import — write to a NAMED branch while their
   request rides its own, so the sync's rich-type records are rebound to
   the TARGET's slice (else they land in, and via the sync world's
   deterministic uuid-v5 ids clobber, the request branch's registry), and
   the TARGET's ctx is the one invalidated. An empty bundle writes
   nothing and invalidates nothing (`[]` is \"no closure changed\")."
  [branch-id fn-defs]
  (cr/record-effect! :db)
  (let [storage (vs/switch-branch (request/require-storage ctx) branch-id)
        defs (vec fn-defs)
        target-ctx (when-let [router (br/current-router)] (br/ctx-for router branch-id))
        fn-ids (if-let [slice (:rich-types-atom target-ctx)]
                 (binding [registry-core/*rich-types-override* slice]
                   (pkg-sync/sync-bundle! storage defs))
                 (pkg-sync/sync-bundle! storage defs))]
    (exec-ctx/invalidate-graph-cache! (or target-ctx ctx) fn-ids)
    (mapv str fn-ids)))


(def impls
  {:current-branch-id current-branch-id
   :sync-fn-defs-branch! sync-fn-defs-branch!})
