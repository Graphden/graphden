(ns graphden.packages.storage.branches.impls
  "Impls for storage/branches base functions.

   Two primitives that live in `storage/branches` (not `app/branches`)
   because lower packages (`web/crud`, the optional `registry` / `mcp`
   packages) need them without taking an app-level dep:
   `:current-branch-id`, the active branch id off the request's
   VersionedStorage wrapper (one library call), and
   `:sync-fn-defs-branch!`, the branch-targeted bundle sync the MCP
   `upsert-fn-defs` tool and the registry's import share. (The branch-local walk
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
    [graphden.system.branch-router.recheck :as recheck]
    [graphden.versioning.storage.core :as vs]))


(defbase current-branch-id
  "Read the active branch id off the VersionedStorage wrapper —
   `(vs/current-branch-id storage)`. Single library call (§3.1)."
  []
  (cr/record-effect! :db)
  (vs/current-branch-id (request/require-storage ctx)))


(defn- import-context
  "Use the cached target context when routing is installed. Standalone
   imports into another branch get fresh caches and forked type slices;
   none of that branch's derived state belongs to the request context."
  [ctx storage branch-id]
  (or (when-let [router (br/current-router)] (br/ctx-for router branch-id))
      (when (= branch-id (vs/current-branch-id (:storage ctx))) ctx)
      (assoc (exec-ctx/create-context (assoc ctx :storage storage))
             :rich-types-atom
             (registry-core/fork-rich-types-atom
               (or (:rich-types-atom ctx) (registry-core/active-rich-types-atom)))
             :per-org-rich-atom
             (registry-core/fork-per-org-rich-atom
               (or (:per-org-rich-atom ctx) (registry-core/active-per-org-rich-atom))))))


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
        target-ctx (import-context ctx storage branch-id)]
    (recheck/call-with-ctx-slices
      target-ctx
      #(let [fn-ids (pkg-sync/sync-bundle! storage defs)]
         (exec-ctx/invalidate-graph-cache! target-ctx fn-ids)
         (recheck/record-imported-fn-types! target-ctx branch-id fn-ids)
         (mapv str fn-ids)))))


(def impls
  {:current-branch-id current-branch-id
   :sync-fn-defs-branch! {:impl sync-fn-defs-branch! :taint-propagate? true}})
