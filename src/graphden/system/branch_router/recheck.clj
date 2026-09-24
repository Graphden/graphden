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
    [graphden.executor.registry.core :as registry-core]
    [graphden.packages.records :as records]
    [graphden.storage.protocol.core :as sp]
    [graphden.util.ns-path :as ns-path]))


(def ^:dynamic *recheck-user-fns?*
  "Off-switch for the ctx-build diagnostics recompute (default on).
   Bind false in tests that must not see background type-check writes."
  true)


(def ^:private max-user-fn-recheck
  "Upper bound on how many editor-authored fns one ctx build will
   re-check. A branch beyond this logs a warn and skips — the ROADMAP
   restart caveat then still applies to it (huge editor graphs are
   rare; the bound keeps a pathological branch from soaking a core)."
  500)


(defn- user-authored-fn-ids
  "IDs of the branch's editor-authored composed fns — named, non-anon
   rows whose id is NOT the deterministic package derivation
   `uuid-v5(ns-path, name)` (see docs/adr/ADR-identity-model.md: the
   package-sync world derives ids from names; the editor world mints
   `random-uuid`s). Package fns are excluded because the sync sweep
   already re-records them at boot."
  [storage]
  (let [fn-rows (sp/query-entities storage :fn {})
        ns-path (ns-path/path-map (sp/query-entities storage :ns {}))]
    (into []
          (keep (fn [row]
                  (when (and (:name row)
                             (seq (:parent-ids row))
                             (nil? (:anonymous-hash row))
                             (not (str/starts-with? (:name row) "_anon-"))
                             (not= (:id row)
                                   (some-> (:namespace-id row)
                                           ns-path
                                           (records/fn-id (keyword (:name row))))))
                    (:id row))))
          fn-rows)))


(defn- recheck-user-fns!
  "Re-run `type-check-fn-after-mutation!` for every editor-authored fn
   visible on `branch-ctx`'s branch, repopulating the per-branch
   diagnostics store (failure records, success clears). Best-effort:
   any throw is logged and swallowed — a diagnostics gap must never
   fail a ctx build or a request."
  [branch-ctx branch-id]
  (try
    (let [storage (:storage branch-ctx)
          ids (user-authored-fn-ids storage)]
      (cond
        (empty? ids) nil

        (> (count ids) max-user-fn-recheck)
        (log/warn (str "skipping ctx-build diagnostics recompute — user-fn count over bound; "
                       "this branch's rich-types slice may miss branch-authored fns "
                       "until they are edited (post-eviction rebuild re-forks from base)")
                  {:branch-id branch-id :count (count ids)
                   :cap max-user-fn-recheck})

        :else
        (do (doseq [id ids]
              (try
                (type-check/type-check-fn-after-mutation! storage id)
                (catch Exception t
                  (log/debug t "ctx-build diagnostics recheck failed for fn"
                             {:branch-id branch-id :fn-id id}))))
            (log/debug "ctx-build diagnostics recompute done"
                       {:branch-id branch-id :checked (count ids)}))))
    (catch Exception t
      ;; Includes storages a test hand-constructed without :fn/:ns tables.
      (log/debug t "ctx-build diagnostics recompute failed"
                 {:branch-id branch-id}))))


(def ^:dynamic *ctx-build-async-recheck?*
  "Test seam: bind false to SKIP the background user-fn recompute a ctx
   build schedules, so a test can assert what the build itself recorded
   synchronously (the branch's own fns) without racing the future."
  true)


(defn schedule-user-fn-recheck!
  "Fire `recheck-user-fns!` on a background future. `future` conveys
   the caller's dynamic bindings (org context, the diagnostics-store
   override the parallel test plugin binds, THIS ctx's rich-types
   slice), so the recompute records into the same stores the
   triggering thread would."
  [branch-ctx branch-id]
  (when (and *recheck-user-fns?* *ctx-build-async-recheck?*)
    (future (recheck-user-fns! branch-ctx branch-id)))
  nil)


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
  [branch-ctx branch-id fn-ids]
  (doseq [id fn-ids]
    (try
      (type-check/type-check-fn-after-mutation! (:storage branch-ctx) id)
      (catch Exception t
        (log/debug t "slice type re-record failed for fn"
                   {:branch-id branch-id :fn-id id})))))


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
    (future
      (call-with-ctx-slices
        branch-ctx
        #(if (seq fn-ids)
           (record-fn-types! branch-ctx branch-id fn-ids)
           (recheck-user-fns! branch-ctx branch-id)))))
  nil)
