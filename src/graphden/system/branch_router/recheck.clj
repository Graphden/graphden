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
    [graphden.tenancy.context :as tenancy]
    [graphden.types.core :as types]
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
    (lifecycle/schedule! (assoc branch-ctx :storage (or (:compile-storage branch-ctx) (:storage branch-ctx)))
                         branch-id ordered-user-fn-ids record-ordered-snapshot!))
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


(defn- record-check-outcome!
  [summary id diagnostic error]
  (when summary
    (swap! summary
           (fn [current]
             (cond-> (update current :checked-count inc)
               diagnostic (update :diagnostic-count inc)
               error (update :exception-count inc)
               (and (or diagnostic error) (< (count (:issues current)) 10))
               (update :issues conj
                       (cond-> {:fn-id id :outcome (if error :exception :diagnostic)}
                         error (assoc :exception-class (Class/.getName (class error))))))))))


(defn- record-fn-types!
  "Re-run exactly these IDs synchronously under the caller's slices.
   Best-effort per fn. Existing callers return nil; targeted rechecks may
   collect counts and at most ten identity/class examples, never payloads."
  ([branch-ctx branch-id fn-ids]
   (record-fn-types! branch-ctx branch-id fn-ids nil))
  ([branch-ctx branch-id fn-ids options]
   (when (seq fn-ids)
     (let [summary (when (:collect-outcomes? options)
                     (atom {:checked-count 0 :diagnostic-count 0 :exception-count 0 :issues []}))
           {:keys [ordered cyclic]}
           (if (:preserve-order? options)
             {:ordered fn-ids}
             (deps/dependency-order (compile-runtime/ctx-reverse-deps branch-ctx) fn-ids))]
       (when (seq cyclic)
         (log/warn "cycle in branch type recheck; checking remaining fns in stable order"
                   {:branch-id branch-id :cycle-fn-ids cyclic}))
       (doseq [id ordered]
         (try
           (let [result (if options
                          (type-check/type-check-fn-after-mutation!
                            (:storage branch-ctx) id (dissoc options :preserve-order? :collect-outcomes?))
                          (type-check/type-check-fn-after-mutation! (:storage branch-ctx) id))]
             (record-check-outcome! summary id result nil))
           (catch Exception error
             (record-check-outcome! summary id nil error)
             (log/debug "slice type re-record failed for fn"
                        {:branch-id branch-id :fn-id id :exception-class (Class/.getName (class error))}))))
       (some-> summary deref)))))


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
  "Reuse reconstruction metadata only within one decorated, read-only snapshot."
  [branch-ctx branch-id fn-ids]
  (call-with-read-snapshot
    (:storage branch-ctx)
    (fn [snapshot]
      (let [options {:namespace-paths (delay (ns-path/path-map (sp/query-entities snapshot :ns {})))}]
        (type-check/call-with-reconstruction-memo
          snapshot
          #(do
             (type-check/prefetch-reconstruction-owners! snapshot fn-ids)
             (record-fn-types! (assoc branch-ctx :storage snapshot) branch-id fn-ids options)))))))


(defn- record-ordered-snapshot!
  "Recover the compiled shard, binding each contiguous group's actual row
   organization and branch alias view instead of a conveyed request scope."
  [branch-ctx branch-id fn-ids]
  (call-with-read-snapshot
    (:storage branch-ctx)
    (fn [snapshot]
      (let [rows (sp/read-entities snapshot :fn fn-ids)
            _ (when-not (every? #(contains? rows %) fn-ids)
                (throw (ex-info "Recovery function metadata is unavailable"
                                {:type :recheck/function-metadata-unavailable})))
            org-of #(or (:org-id (get rows %)) tenancy/public-org)
            namespace-paths (delay (ns-path/path-map (sp/query-entities snapshot :ns {})))
            alias-source (compile-runtime/storage-alias-source snapshot)]
        (doseq [group (partition-by org-of fn-ids)]
          (let [org (org-of (first group))]
            (binding [tenancy/*current-org* org
                      types/*type-aliases-override* (atom (compile-runtime/org-alias-snapshot tenancy/public-org org alias-source))
                      type-check/*request-alias-view?* false]
              (type-check/call-with-reconstruction-memo
                snapshot
                #(record-fn-types! (assoc branch-ctx :storage snapshot) branch-id group
                                   {:namespace-paths namespace-paths :preserve-order? true})))))))))


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


(defn- current-recheck-generation?
  [ctx generation]
  (if-let [state (:type-recheck-state ctx)]
    (let [current @state]
      (and (= generation (:generation current)) (not (:cancelled? current))))
    true))


(defn- check-targeted-types!
  [ctx branch-id fn-ids]
  (call-with-ctx-slices
    ctx
    (fn []
      (let [present-count (fn []
                            (let [rich (registry-core/effective-rich-types)]
                              (count (filter #(contains? (:by-id rich) %) fn-ids))))
            before (present-count)
            summary (record-fn-types! ctx branch-id fn-ids {:collect-outcomes? true})
            after (present-count)
            missing (- (count fn-ids) after)]
        (assoc summary :present-before before :present-after after :missing-count missing
               :outcome (cond (pos? (:exception-count summary)) :failed
                              (pos? (:diagnostic-count summary)) :diagnostic
                              (pos? missing) :missing
                              :else :present))))))


(defn- run-targeted-recheck!
  [ctx branch-id fn-ids generation queued-at started]
  (let [admitted-after (volatile! nil)
        elapsed #(long (/ (- (System/nanoTime) started) 1e6))
        report! (fn [status details]
                  (log/log (if (= status :failed) :warn :info)
                           (str "targeted branch type recheck "
                                (pr-str (merge {:branch-id branch-id :generation generation
                                                :context (System/identityHashCode ctx)
                                                :rich-atom (System/identityHashCode (:rich-types-atom ctx))
                                                :requested-count (count fn-ids) :queued-at-ms queued-at
                                                :admitted-after-ms @admitted-after :elapsed-ms (elapsed)
                                                :status status}
                                               details)))))]
    (try
      (loop []
        (if-not (current-recheck-generation? ctx generation)
          (report! :cancelled {:outcome :cancelled})
          (when-not (lifecycle/try-with-recovery-admission
                      (:storage ctx)
                      (fn []
                        (vreset! admitted-after (elapsed))
                        (if (current-recheck-generation? ctx generation)
                          (report! :checked (check-targeted-types! ctx branch-id fn-ids))
                          (report! :cancelled {:outcome :cancelled}))
                        true))
            (Thread/sleep 50)
            (recur))))
      (catch Exception error
        (report! :failed {:outcome :failed :exception-class (Class/.getName (class error))})
        (throw error)))))


(defn recheck-ctx-types!
  "Re-record types and diagnostics into this branch's own slice.
   Explicit IDs retain their existing best-effort asynchronous admission;
   nil/empty schedules the coalesced full user-fn recovery. One bounded
   terminal receipt distinguishes admission delay, diagnostics, missing
   entries, cancellation and exceptions. Presence does not prove a fresh
   publication, and a diagnostic takes precedence over an old rich entry.
   Returns nil; no caller waits on the targeted future."
  [branch-ctx branch-id fn-ids]
  (when *recheck-user-fns?*
    (if (seq fn-ids)
      (let [generation (recheck-state/generation branch-ctx)
            queued-at (System/currentTimeMillis)
            started (System/nanoTime)]
        (future
          (binding [tx/*transaction-context* nil]
            (run-targeted-recheck! branch-ctx branch-id fn-ids generation queued-at started))))
      (call-with-ctx-slices branch-ctx
                            #(lifecycle/schedule! (assoc branch-ctx :storage (or (:compile-storage branch-ctx) (:storage branch-ctx)))
                                                  branch-id ordered-user-fn-ids record-ordered-snapshot!))))
  nil)
