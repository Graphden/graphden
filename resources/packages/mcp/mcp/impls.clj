(ns graphden.packages.app.mcp.impls
  "The one Clojure boundary the MCP mutation tools need. Everything
   else in `app/mcp` — parsing, validation, error envelopes, dispatch —
   is graph composition, in the `parse → validate → apply` shape
   `app/execution` established.

   `parse-edn` is a one-call library boundary. `sync-fn-defs-branch!`
   is an atomic EFFECT sequence gated on runtime state (switch the
   storage handle to another branch, sync, delta-invalidate THAT
   branch's ctx) — the same carve-out `merge-branch!` documents in
   app/branches, and for the same reason: splitting it would scatter
   an all-or-nothing write across fn-defs that could be recombined
   wrongly."
  (:require
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.executor.registry.core :as registry-core]
    [graphden.packages.sync :as pkg-sync]
    [graphden.system.branch-router :as br]
    [graphden.versioning.storage.core :as vs]))


;; (`parse-edn` and `platform-owned-def-names` moved to core/system/impls.clj
;; — the registry's import route and fork needed them too, and base-fn
;; names are globally unique.)


(defbase sync-fn-defs-branch!
  "Sync `fn-defs` into the branch `branch-id` and delta-invalidate that
   branch's compiled registry. Returns the synced fn-ids.

   Atomic by construction: the namespace upsert, the fn sync and the
   invalidation must all land or none — a half-synced proposal leaves a
   branch whose registry disagrees with its rows. Writes go through the
   SAME `sync-fns-to-storage!` the package loader uses, so an AI's
   proposal meets the same constraints (cycles, name collisions,
   type-check) as a human's fns.edn."
  [branch-id fn-defs]
  (cr/record-effect! :db)
  (let [storage (vs/switch-branch (request/require-storage ctx) branch-id)
        ;; The sync records rich-types as it checks; the POST /mcp request
        ;; rides MAIN, so without this rebind those records land in the
        ;; REQUEST branch's slice — and the sync world's deterministic
        ;; uuid-v5 ids would clobber main's entry for a same-named fn.
        target-slice (when-let [router (br/current-router)]
                       (:rich-types-atom (br/ctx-for router branch-id)))
        fn-ids (if target-slice
                 (binding [registry-core/*rich-types-override* target-slice]
                   (pkg-sync/sync-bundle! storage fn-defs))
                 (pkg-sync/sync-bundle! storage fn-defs))]
    ;; Invalidate the TARGET branch's ctx, not the request's own: the AI
    ;; writes to `ai/…` while its POST /mcp rides main. Same shape as
    ;; merge-branch!'s post-merge delta.
    (exec-ctx/invalidate-graph-cache!
      (if-let [router (br/current-router)]
        (br/ctx-for router branch-id)
        ctx)
      fn-ids)
    (mapv str fn-ids)))


(def impls
  {:sync-fn-defs-branch! sync-fn-defs-branch!})
