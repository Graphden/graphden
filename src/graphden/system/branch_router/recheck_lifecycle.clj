(ns graphden.system.branch-router.recheck-lifecycle
  "Bounded asynchronous recovery of persisted user checker state. Snapshot
   checking is isolated; only conditional patches publish under the writer."
  (:require
    [graphden.executor.context :as context]
    [graphden.executor.registry.conditional-patch :as patch]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.owned :as owned]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.postgres.graph-epoch :as epoch]
    [graphden.storage.tx :as tx]
    [graphden.types.diagnostics :as diagnostics]
    [graphden.versioning.branch-local :as branch-local]
    [graphden.versioning.storage.resolution :as resolution]))


(def batch-size 500)


(def ^:private publication-permit
  ;; Recovery is best-effort and may be scheduled for several cached branches
  ;; at once. Keep all but one worker OUT of the JDBC pool while a writer holds
  ;; the graph lock; the normal mutation path may need that spare connection.
  (java.util.concurrent.Semaphore. 1 true))


(defn- try-with-invalidation-lock
  [ctx f]
  (if-let [^java.util.concurrent.locks.ReentrantLock lock (:invalidation-lock ctx)]
    (when (java.util.concurrent.locks.ReentrantLock/.tryLock lock)
      (try (f) (finally (java.util.concurrent.locks.ReentrantLock/.unlock lock))))
    (f)))


(defn- current-epoch
  [ctx]
  (epoch/current (epoch/epoch-handle (:storage ctx))))


(defn- token
  [ctx generation]
  (let [storage-epoch (current-epoch ctx)]
    (when-not (and (integer? storage-epoch) (not (neg? storage-epoch)))
      (throw (ex-info "Recovery requires an available storage epoch"
                      {:type :recheck/storage-epoch-unavailable})))
    {:generation generation :context-epoch (context/invalidation-epoch ctx)
     :storage-epoch storage-epoch}))


(defn- current?
  [ctx expected]
  (let [state @(:type-recheck-state ctx)]
    (and (not (:cancelled? state))
         (= (:generation expected) (:generation state))
         (= (:context-epoch expected) (context/invalidation-epoch ctx))
         (= (:storage-epoch expected) (current-epoch ctx)))))


(defn- checker-stores
  [ctx]
  {:rich (or (:rich-types-atom ctx) (registry/active-rich-types-atom))
   :orgs (or (:per-org-rich-atom ctx) (registry/active-per-org-rich-atom))
   :diagnostics (diagnostics/active-store-atom)})


(defn- stage
  [ctx branch-id ids check-batch]
  (let [targets (checker-stores ctx)
        before (update-vals targets deref)
        staged (update-vals before atom)]
    (binding [registry/*rich-types-override* (:rich staged)
              registry/*per-org-rich-override* (:orgs staged)
              diagnostics/*diagnostics-override* (:diagnostics staged)]
      (check-batch ctx branch-id ids))
    {:targets targets :before before :after (update-vals staged deref)}))


(defn- paths
  [before after branch-id ids]
  (let [protected (into #{} (filter owned/owned-fn-id?)
                        (concat (keys (:by-id (:rich before)))
                                (vals (:by-name (:rich before)))))
        orgs (into (set (keys (:orgs before))) (keys (:orgs after)))]
    {:rich (patch/rich-paths (:rich before) (:rich after) ids protected)
     :orgs (into [] (mapcat (fn [org]
                              (map #(into [org] %)
                                   (patch/rich-paths (get-in before [:orgs org])
                                                     (get-in after [:orgs org]) ids protected)))) orgs)
     :diagnostics (mapv #(vector branch-id %) ids)}))


(defn- publish-snapshot!
  [ctx branch-id ids expected targets before after last-batch?]
  (when (current? ctx expected)
    (let [selected (paths before after branch-id ids)
          ;; Do not roll back an earlier successful atom patch if a different
          ;; store conflicts. Restart from fresh baselines instead.
          applied (mapv #(patch/apply! (get targets %) (get before %)
                                       (get after %) (get selected %))
                        [:rich :orgs :diagnostics])]
      (when (every? true? applied)
        (let [failed (into #{} (filter #(or (seq (get-in after [:diagnostics branch-id %]))
                                            (nil? (get-in after [:rich :by-id %])))) ids)]
          (swap! (:type-recheck-state ctx)
                 (fn [state]
                   (if (= (:generation expected) (:generation state))
                     (cond-> (-> state
                                 (update :pending-ids #(reduce disj % ids))
                                 (update :failed-ids into failed))
                       last-batch? (assoc :status (if (seq (into (:failed-ids state) failed)) :failed :ready)))
                     state))))
        true))))


(defn- publish-staged!
  [ctx branch-id ids expected {:keys [targets before after]} last-batch?]
  ;; Never wait for this ctx's invalidation lock while holding a JDBC
  ;; connection. A mutation holds that lock while re-reading its graph; on a
  ;; two-connection pool, two recovery workers previously occupied both slots
  ;; (one inside the writer, one waiting for it), blocking the mutation's read
  ;; for the full connection timeout. A failed try is retried from a fresh
  ;; snapshot by work!, with no stale publication.
  (when (try-with-invalidation-lock ctx (constantly true))
    (java.util.concurrent.Semaphore/.acquire publication-permit)
    (try
      (writer/call-with-try-write
        (:storage ctx) :graph
        (fn [locked]
          (try-with-invalidation-lock
            ctx
            #(publish-snapshot! (assoc ctx :storage locked) branch-id ids expected
                                targets before after last-batch?))))
      (finally (java.util.concurrent.Semaphore/.release publication-permit)))))


(defn run-pass!
  "Discover/order once, then check contiguous dependency-ordered batches.
   Every batch releases its snapshot before a short conditional publication."
  [ctx branch-id generation discover check-batch]
  (let [expected (token ctx generation)
        ids (binding [branch-local/*storage-caches-override* (atom {})]
              (resolution/call-with-fresh-memos #(vec (discover ctx))))
        batches (vec (partition-all batch-size ids))]
    (swap! (:type-recheck-state ctx)
           #(if (= generation (:generation %))
              (assoc % :status :running :pending-ids (set ids) :failed-ids #{}) %))
    (if (empty? batches)
      (publish-staged! ctx branch-id [] expected (stage ctx branch-id [] check-batch) true)
      (loop [remaining batches]
        (when (current? ctx expected)
          (let [batch (first remaining)
                staged (stage ctx branch-id batch check-batch)]
            (when (publish-staged! ctx branch-id batch expected staged (= 1 (count remaining)))
              (if (next remaining)
                (do (Thread/yield) (recur (next remaining)))
                true))))))))


(defn- work!
  [ctx branch-id discover check-batch]
  (let [state (:type-recheck-state ctx)]
    (loop []
      (let [{:keys [generation cancelled?]} @state]
        (if cancelled?
          (let [stopped? (volatile! false)]
            (swap! state (fn [current]
                           (let [stop (and (= generation (:generation current)) (:cancelled? current))]
                             (vreset! stopped? stop)
                             (cond-> current stop (assoc :worker-running? false)))))
            (when-not @stopped? (recur)))
          (let [completed? (try (run-pass! ctx branch-id generation discover check-batch)
                                (catch Exception _
                                  (swap! state #(if (= generation (:generation %))
                                                  (assoc % :status :failed :pending-ids #{}) %))
                                  true))
                finished? (volatile! false)]
            (swap! state (fn [current]
                           (let [finished (and completed? (= generation (:generation current)))]
                             (vreset! finished? finished)
                             (cond-> current finished (assoc :worker-running? false)))))
            (when-not @finished?
              ;; Yield/back off only after a confirmed stale/conflicting
              ;; generation; never hold a snapshot or writer during the delay.
              (Thread/sleep 50)
              (recur))))))))


(defn schedule!
  "Coalesce repeated schedules into one worker per context. Futures convey
   the caller's org/type/diagnostic bindings; no request executes a source graph."
  [ctx branch-id discover check-batch]
  (when-let [state (:type-recheck-state ctx)]
    (let [[old _] (swap-vals! state #(-> % (update :generation inc)
                                         (assoc :status :pending :cancelled? false :pending-ids nil
                                                :worker-running? true)))]
      (when-not (:worker-running? old)
        (future
          (binding [tx/*transaction-context* nil]
            (work! ctx branch-id discover check-batch))))))
  nil)
