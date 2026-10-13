(ns graphden.system.branch-router.recheck
  "Re-recording types into a branch ctx's own rich-types slice.

   A slice only learns types from type-checks RUN UNDER ITS BINDING
   (`call-with-ctx-slices`), so every path that changes what a ctx
   resolves without running a check on it re-records here: the ctx build
   (its own divergent fns, synchronously) and the writes a cached child
   inherits (`recheck-ctx-types!`).

   Ctx-build diagnostics recompute (error-tolerance, ROADMAP § Error
   Tolerance): the per-branch type-diagnostics store
   (`graphden.types.diagnostics`) is DERIVED, in-memory state: after a JVM
   restart the package sync sweep re-records first-party fns, but an
   EDITOR-AUTHORED fn broken before the restart would stay absent —
   invisible in the error panel and, worse, admitted by the Phase 4
   execute-refusal gate (absence = allow). Closing that gap here: whenever
   a branch ctx is built (boot seed of the default branch, lazy build / LRU
   re-build of any other), re-run the post-mutation check for the branch's
   editor-authored fns ASYNCHRONOUSLY so the store repopulates without
   blocking the request that triggered the build."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.crud.type-check :as type-check]
    [graphden.executor.compile-runtime :as compile-runtime]
    [graphden.executor.compile.deps :as deps]
    [graphden.executor.registry.core :as registry-core]
    [graphden.packages.owned :as owned]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.system.branch-router.recheck-lifecycle :as lifecycle]
    [graphden.system.branch-router.recheck-state :as recheck-state]
    [graphden.util.ns-path :as ns-path]
    [graphden.versioning.branch-local :as branch-local]
    [graphden.versioning.storage.resolution :as resolution]
    [next.jdbc :as jdbc]
    [next.jdbc.transaction :as jdbc-tx])
  (:import
    (java.sql
      Connection)))


(def ^:dynamic *recheck-user-fns?*
  "Off-switch for the ctx-build diagnostics recompute (default on).
   Bind false in tests that must not see background type-check writes."
  true)


(def ^:private max-user-fn-recheck
  "Synchronous ctx-build bound; asynchronous recovery uses this batch size."
  lifecycle/batch-size)


(defn- user-authored-fn-ids
  "IDs of named, non-anonymous composed fns not owned by this boot's
   bundled packages. Personal copies may also use deterministic IDs;
   identity spelling does not establish package ownership."
  [rows]
  (into []
        (keep (fn [row]
                (when (and (:name row)
                           (seq (:parent-ids row))
                           (nil? (:anonymous-hash row))
                           (not (str/starts-with? (:name row) "_anon-"))
                           (not (owned/owned-fn-id? (:id row))))
                  (:id row))))
        rows))


(declare record-fn-types! record-import-snapshot! record-ordered-snapshot! call-with-read-snapshot)


(defn- ordered-user-fn-ids
  [ctx]
  (call-with-read-snapshot
    (:storage ctx)
    (fn [snapshot]
      (let [graph (compile-runtime/fresh-graph-snapshot snapshot (:executor-orgs ctx))]
        (:ordered (deps/dependency-order (deps/build-reverse-deps graph)
                                         (user-authored-fn-ids (:fns graph))))))))


(def ^:dynamic *ctx-build-async-recheck?*
  "Test seam: bind false to SKIP the background user-fn recompute a ctx
   build schedules, so a test can assert what the build itself recorded
   synchronously (the branch's own fns) without racing the future."
  true)


(defn schedule-user-fn-recheck!
  "Coalesce one dependency-ordered recovery worker for this context.
   Each snapshot checks at most 500 user IDs; completed source/epoch proofs
   conditionally publish only their changed checker entries."
  [branch-ctx branch-id]
  (when (and *recheck-user-fns?* *ctx-build-async-recheck?*)
    (lifecycle/schedule! branch-ctx branch-id ordered-user-fn-ids record-ordered-snapshot!))
  nil)


(defn cancel-user-fn-recheck!
  "Retire a removed context's captured recovery generation."
  ([ctx] (recheck-state/cancel! ctx))
  ([ctx generation] (recheck-state/cancel! ctx generation)))


(defn call-with-ctx-slices
  "Call `f` with `ctx`'s own type-registry slices bound — rich-types and
   per-org — so every type-check / lookup / compile inside records into
   and reads from THAT ctx's view. A ctx without a slice (built before
   slice-tagging, or a test stub) keeps the ambient override (test
   isolation). A tenant READ's request-wide type-alias view is narrowed to
   the ctx's branch the same way (`type-check/call-with-branch-alias-view`)."
  [ctx f]
  (binding [registry-core/*rich-types-override*
            (or (:rich-types-atom ctx) registry-core/*rich-types-override*)
            registry-core/*per-org-rich-override*
            (or (:per-org-rich-atom ctx) registry-core/*per-org-rich-override*)]
    (type-check/call-with-branch-alias-view (:storage ctx) f)))


(defn- record-fn-types!
  "Re-run the type-check for exactly `fn-ids`, SYNCHRONOUSLY — the caller
   binds `branch-ctx`'s slices (`call-with-ctx-slices`) and decides
   whether to run it inline or on a future. Best-effort per fn."
  ([branch-ctx branch-id fn-ids]
   (record-fn-types! branch-ctx branch-id fn-ids nil))
  ([branch-ctx branch-id fn-ids options]
   (when (seq fn-ids)
     (let [{:keys [ordered cyclic]}
           (if (:preserve-order? options)
             {:ordered fn-ids}
             (deps/dependency-order (compile-runtime/ctx-reverse-deps branch-ctx) fn-ids))]
       (when (seq cyclic)
         (log/warn "cycle in branch type recheck; checking remaining fns in stable order"
                   {:branch-id branch-id :cycle-fn-ids cyclic}))
       (doseq [id ordered]
         (try
           (if options
             (type-check/type-check-fn-after-mutation! (:storage branch-ctx) id (dissoc options :preserve-order?))
             (type-check/type-check-fn-after-mutation! (:storage branch-ctx) id))
           (catch Exception t
             (log/debug t "slice type re-record failed for fn"
                        {:branch-id branch-id :fn-id id}))))))))


(defn- call-with-read-snapshot
  [storage f]
  (let [datasource (tx/datasource storage)]
    (if (and datasource (not (instance? Connection datasource)))
      (binding [jdbc-tx/*nested-tx* :prohibit
                resolution/*branch-chain-cache* (atom {})
                branch-local/*storage-caches-override* (atom {})]
        (resolution/call-with-fresh-memos
          #(jdbc/with-transaction [connection datasource {:isolation :repeatable-read :read-only true}]
                                  (f (tx/with-connection storage connection)))))
      (f storage))))


(defn- record-import-snapshot!
  "Reuse reconstruction metadata only within one decorated, read-only snapshot.
   Recovery batches preserve the authoritative order captured by their pass."
  ([branch-ctx branch-id fn-ids]
   (record-import-snapshot! branch-ctx branch-id fn-ids false))
  ([branch-ctx branch-id fn-ids preserve-order?]
   (call-with-read-snapshot
     (:storage branch-ctx)
     (fn [snapshot]
       (let [options {:namespace-paths (delay (ns-path/path-map (sp/query-entities snapshot :ns {})))
                      :preserve-order? preserve-order?}]
         (type-check/call-with-reconstruction-memo
           snapshot
           #(record-fn-types! (assoc branch-ctx :storage snapshot) branch-id fn-ids options)))))))


(defn- record-ordered-snapshot!
  [branch-ctx branch-id fn-ids]
  (record-import-snapshot! branch-ctx branch-id fn-ids true))


(defn record-own-fn-types!
  "Record `fn-ids` — the fns a delta-built branch ctx itself diverges on —
   into its slice SYNCHRONOUSLY, before the entry is served. Callers bind
   the slice (`call-with-ctx-slices`). The async recompute covers the rest,
   but it used to cover these too — so the first `/api/types` after a
   rebuild (a heal, an eviction) could miss a branch-authored fn until the
   future landed (main-CI flake: `rich-types-registry-branch-scope-test`
   on a slow runner). Bounded like the sweep; a wider divergence stays
   async."
  [branch-ctx branch-id fn-ids]
  (when (and *recheck-user-fns?*
             (<= (count fn-ids) max-user-fn-recheck))
    (record-fn-types! branch-ctx branch-id fn-ids)))


(defn record-imported-fn-types!
  "Record a successful import's exact IDs before returning to its caller.
   A mutation must not silently skip a large bundle; startup recovers the
   same persisted user definitions in bounded asynchronous batches. Invalid definitions keep their ordinary diagnostics;
   this does not grant a type to a definition that failed checking."
  [branch-ctx branch-id fn-ids]
  (when (seq fn-ids)
    (call-with-ctx-slices
      branch-ctx
      #(record-import-snapshot! branch-ctx branch-id fn-ids))))


(defn recheck-ctx-types!
  "Re-record rich-types (+ diagnostics) INTO `branch-ctx`'s own
   rich-types slice — the propagation channel between per-branch
   slices. `fn-ids` non-empty → re-check exactly that set (a write's
   blast radius); empty/nil → the bounded full user-fn sweep.

   Why this exists: a slice only ever learns types from type-checks
   RUN UNDER ITS BINDING. A merge into a branch, or a base-branch
   edit inherited by a cached child, changes what the child RESOLVES
   without any check running on the child — its slice would stay
   stale forever (the default branch's entry is pinned and never
   rebuilt; non-default entries heal on rebuild only). Async +
   best-effort, same contract as the ctx-build recompute."
  [branch-ctx branch-id fn-ids]
  (when *recheck-user-fns?*
    (if (seq fn-ids)
      (future (call-with-ctx-slices branch-ctx
                                    #(record-fn-types! branch-ctx branch-id fn-ids)))
      (call-with-ctx-slices branch-ctx
                            #(lifecycle/schedule! branch-ctx branch-id ordered-user-fn-ids record-ordered-snapshot!))))
  nil)
