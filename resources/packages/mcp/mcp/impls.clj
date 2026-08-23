(ns graphden.packages.app.mcp.impls
  "The two Clojure boundaries the MCP mutation tools need. Everything
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
    [clojure.edn :as edn]
    [graphden.crud.request :as request]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.packages.owned :as owned]
    [graphden.packages.records :as records]
    [graphden.packages.sync :as pkg-sync]
    [graphden.system.branch-router :as br]
    [graphden.versioning.storage.core :as vs]))


(defbase parse-edn
  "Read one EDN value from `string`; nil when it doesn't parse. Generic
   counterpart to `:parse-json` — the MCP tools take fn-defs as EDN text
   because that is the ONE encoding where `:other-fn` (a reference) and
   \"other-fn\" (a string) stay distinguishable, which JSON loses."
  [string]
  (try (edn/read-string string) (catch Exception _ nil)))


(defbase platform-owned-def-names
  "Names among `fn-defs` whose deterministic `(namespace, name)` fn-id was
   written by the package sync this boot — the fns the editor API's
   package-guard refuses to touch (`crud.package-guard`, the 2026-08-20
   `:add`-poisoning class). The MCP upsert guard consults this so the sync
   path stops being the one write route around that protection."
  [fn-defs]
  (into []
        (comp (filter #(owned/owned-fn-id? (records/fn-id (:namespace %) (:name %))))
              (map #(some-> (:name %) name)))
        fn-defs))


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
        fn-ids (pkg-sync/sync-bundle! storage fn-defs)]
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
  {:parse-edn parse-edn
   ;; taint-propagate: returns the caller bundle's own :name fields —
   ;; content passthrough (SECRETS.md § T3).
   :platform-owned-def-names {:impl platform-owned-def-names
                              :taint-propagate? true}
   :sync-fn-defs-branch! sync-fn-defs-branch!})
