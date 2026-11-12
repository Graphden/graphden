(ns graphden.executor.browser-source
  "Bounded source collection for personal editor components. Source guards run
   before bindings are read; every database read uses one decorated snapshot."
  (:require
    [clojure.set :as set]
    [graphden.executor.browser-contracts :as contracts]
    [graphden.executor.browser-source-frontier :as frontier]
    [graphden.executor.browser-source-prefetch :as prefetch]
    [graphden.executor.capabilities :as capabilities]
    [graphden.executor.compile.bindings :as bindings]
    [graphden.executor.compile.deps :as deps]
    [graphden.executor.compile.lookups :as lookups]
    [graphden.executor.source-recipe-replay :as replay]
    [graphden.storage.bounded-query :as bounded]
    [graphden.storage.grouped-read :as grouped]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tenancy]
    [graphden.versioning.branch-local :as branch-local]
    [graphden.versioning.storage.resolution :as resolution]
    [next.jdbc :as jdbc]
    [next.jdbc.transaction :as jdbc-tx])
  (:import
    (java.sql
      Connection)))


(def ^:dynamic *snapshot-check*
  "Optional cooperative cancellation/deadline check for an owned snapshot."
  nil)


(def ^:dynamic *statement-timeout-ms*
  "Internal read-only worker bound; never supplied by browser request data."
  nil)


(defn- check-snapshot!
  []
  (when *snapshot-check* (*snapshot-check*)))


(def ^:private max-functions 2048)
(def ^:private max-rows 16384)
(def ^:private batch-size 64)


(def ^:dynamic ^:private *prefetched* nil)
(def ^:dynamic ^:private *identity-hints* nil)


(defn- reject!
  [reason]
  (throw (ex-info "Component source is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn- read-rows
  [storage entity ids]
  (check-snapshot!)
  (cond
    (and *identity-hints* (= :fn entity) (identical? storage (:storage *identity-hints*)))
    (prefetch/read-through *identity-hints* entity ids
                           #(binding [*identity-hints* nil] (read-rows storage entity %)))
    *prefetched*
    (prefetch/read-through *prefetched* entity ids
                           #(binding [*prefetched* nil] (read-rows storage entity %)))
    :else
    (if (satisfies? sp/StorageBatchCRUD storage)
      (vals (sp/read-entities storage entity ids))
      (keep #(sp/read-entity storage entity %) ids))))


(defn- query-rows
  [storage entity where budget]
  (check-snapshot!)
  (when-not (satisfies? sp/StorageBoundedQuery storage)
    (reject! :bounded-storage-unsupported))
  (try
    (let [remaining (- max-rows @budget)
          rows (vec (sp/query-bounded-entities storage entity where remaining))]
      (when (> (count rows) remaining) (reject! :source-row-limit))
      (swap! budget + (or (::sp/candidate-count (meta rows)) (count rows)))
      rows)
    (catch clojure.lang.ExceptionInfo error
      (case (:type (ex-data error))
        :storage-error/candidate-limit (reject! :source-row-limit)
        :storage-error/bounded-query-unsupported (reject! :bounded-storage-unsupported)
        (throw error)))))


(defn- limited-query
  [storage entity where budget]
  (check-snapshot!)
  (if *prefetched*
    (prefetch/query-through *prefetched* entity where
                            #(query-rows storage entity % budget))
    (query-rows storage entity where budget)))


(defn- owner-rows
  "Authorize owners before entry. Scan candidates sequentially, charging their
   physical counts before any grouped payload load. Unknown wrappers and the
   advisory cache keep the ordinary bounded-query path."
  [storage ids budget]
  (let [where {:fn-id (vec ids)}]
    (if (or *prefetched* (not (satisfies? sp/StorageGroupedRead storage)))
      [(limited-query storage :fn-slot where budget)
       (limited-query storage :binding where budget)]
      (try
        (let [requests
              (mapv (fn [entity]
                      (check-snapshot!)
                      (let [remaining (- max-rows @budget)
                            candidates (bounded/candidate-ids storage entity where nil remaining)
                            physical-count (or (::sp/candidate-count (meta candidates))
                                               (count candidates))]
                        (when (> physical-count remaining) (reject! :source-row-limit))
                        (swap! budget + physical-count)
                        {:op :query :entity entity :where (assoc where :id candidates)}))
                    [:fn-slot :binding])]
          (check-snapshot!)
          (grouped/read-operations storage requests))
        (catch clojure.lang.ExceptionInfo error
          (case (:type (ex-data error))
            :storage-error/candidate-limit (reject! :source-row-limit)
            :storage-error/bounded-query-unsupported (reject! :bounded-storage-unsupported)
            (throw error)))))))


(defn- related-ids
  [fns slots bindings items]
  (into #{}
        (remove nil?)
        (concat (mapcat :parent-ids fns)
                (mapcat #(map % [:base-fn-id :return-type-fn-id :element-fn-id]) fns)
                (map :type-fn-id slots)
                (mapcat #(map % [:ref-fn-id :type-override-fn-id :resolver-fn-id]) bindings)
                (map :ref-fn-id items))))


(defn- attach-identity-targets
  "Append terminal identity rows without loading their bodies or captures."
  [storage authorize! graph seen {:keys [terminal server-targets server-bindings manifest? server?]}]
  (let [targets (set (remove seen terminal))
        _ (when (> (+ (count seen) (count targets)) max-functions)
            (reject! :source-function-limit))
        rows (vec (read-rows storage :fn targets))]
    (when-not (= targets (set (map :id rows))) (reject! :source-missing))
    (doseq [row rows]
      (condp contains? (:id row)
        server-targets (authorize! row {:execute? true :server-target? true})
        contracts/component-identities (authorize! row {:identity-only? true})
        (authorize! row)))
    (-> graph
        (update :fns into rows)
        (cond-> (or manifest? server?) (assoc :identity-target-ids targets)
                server? (assoc :server-bindings server-bindings))
        (update :slots #(vec (vals (into {} (map (juxt :id identity)) %)))))))


(defn- validate-owner-metadata!
  [row permission]
  (when (and (= :bare-type permission)
             (or (seq (:parent-ids row)) (:base-fn-id row)
                 (:element-fn-id row) (:return-type-fn-id row) (:constraint row)))
    (reject! :primitive-type-source-mismatch))
  (when-let [contract (:type-contract permission)]
    (when-not (contracts/matches-owner? contract row)
      (reject! :public-type-contract-mismatch))))


(defn- validate-owned-source!
  [{:keys [fns slots fn-slots bindings]} authorizations]
  (let [bare-types (into #{} (keep (fn [[id kind]] (when (= :bare-type kind) id))) authorizations)
        slot-ids (set (map :slot-id fn-slots))]
    (when (or (some #(contains? bare-types (:fn-id %)) fn-slots)
              (some #(contains? bare-types (:fn-id %)) bindings))
      (reject! :primitive-type-source-mismatch))
    (doseq [row fns]
      (validate-owner-metadata! row (get authorizations (:id row)))
      (when-let [contract (:type-contract (get authorizations (:id row)))]
        (let [owned-junctions (filterv #(= (:id row) (:fn-id %)) fn-slots)
              owned-slot-ids (set (map :slot-id owned-junctions))]
          (when-not (contracts/matches? contract row
                                        (filterv #(contains? owned-slot-ids (:id %)) slots)
                                        owned-junctions
                                        (filterv #(= (:id row) (:fn-id %)) bindings))
            (reject! :public-type-contract-mismatch)))))
    (when-not (= slot-ids (set (map :id slots))) (reject! :source-slot-missing))))


(defn- collect-source
  "Collect only requested roots and their source/type dependencies. `authorize!`
   receives each fn row BEFORE any of its binding/item values are loaded.
   Callers must supply an immutable storage/policy pair; HTTP code uses
   `with-snapshot`, not a live context's whole-graph cache."
  [storage roots authorize! manifest? server? & [budget metadata-frontier?]]
  (let [budget (or budget (atom 0))]
    (loop [pending (set roots)
           seen #{}
           graph {:fns [] :slots [] :fn-slots [] :bindings [] :list-items []}]
      (when (> (+ (count pending) (count seen)) max-functions)
        (reject! :source-function-limit))
      (if (empty? pending)
        (let [index (lookups/build-lookups graph)
              classified (when (or manifest? server?)
                           (mapcat #(bindings/collect-bindings (:id %) index) (:fns graph)))
              server-bindings (when server?
                                (filterv #(capabilities/server-call-slot? (:slot-id %)) (:bindings graph)))
              server-targets (into #{} (keep :ref-fn-id) server-bindings)
              terminal (into server-targets
                             (keep #(when (and manifest? (= :fn-ref (:kind %))) (:ref-id %))) classified)
              refs (when manifest?
                     (set (concat (keep :ref-fn-id (:bindings graph))
                                  (keep :ref-fn-id (:list-items graph)))))
              ;; An id used by an ordinary call must still be loaded even if
              ;; another binding names it by identity.
              ordinary (into #{} (keep #(when (and (= :ref (:kind %))
                                                   (not (and server? (capabilities/server-call-slot? (:slot-id %)))))
                                          (:ref-id %))) classified)
              remaining (remove seen (concat ordinary (keep :ref-fn-id (:list-items graph))
                                             (remove terminal refs)))]
          (when (seq (set/intersection server-targets seen))
            (reject! :mixed-server-target))
          (if (seq remaining)
            (recur (set remaining) seen graph)
            (attach-identity-targets storage authorize! graph seen
                                     {:terminal terminal :server-targets server-targets
                                      :server-bindings server-bindings :manifest? manifest? :server? server?})))
        (let [prepared (when metadata-frontier?
                         (frontier/prepare
                           pending seen graph
                           {:read-fns #(read-rows storage :fn %)
                            :read-fn-slots #(query-rows storage :fn-slot {:fn-id (vec %)} budget)
                            :read-slots #(read-rows storage :slot %)
                            :authorize! authorize! :validate-owner! validate-owner-metadata!
                            :validate-structure! validate-owned-source! :max-functions max-functions}))
              ids (or (:ids prepared) (set (take batch-size pending)))
              fns (or (:fns prepared) (vec (read-rows storage :fn ids)))]
          (when-not (= ids (set (map :id fns))) (reject! :source-missing))
          (let [authorizations (or (:authorizations prepared)
                                   (into {} (map (fn [row] [(:id row) (authorize! row)])) fns))
                [fn-slots bindings] (if prepared
                                      [(:fn-slots prepared)
                                       (limited-query storage :binding {:fn-id (vec ids)} budget)]
                                      (owner-rows storage ids budget))
                items (if (seq bindings)
                        (limited-query storage :binding-list-item
                                       {:binding-id (mapv :id bindings)} budget)
                        [])
                slot-ids (set (map :slot-id fn-slots))
                slots (or (:slots prepared)
                          (if (seq slot-ids) (vec (read-rows storage :slot slot-ids)) []))
                seen (into seen ids)
                refs (related-ids fns slots
                                  (cond
                                    manifest? (map #(dissoc % :ref-fn-id) bindings)
                                    server? (map #(if (capabilities/server-call-slot? (:slot-id %))
                                                    (dissoc % :ref-fn-id) %) bindings)
                                    :else bindings)
                                  (if manifest? [] items))]
            (validate-owned-source! {:fns fns :slots slots :fn-slots fn-slots :bindings bindings}
                                    authorizations)
            (recur (into (apply disj pending ids) (remove seen refs)) seen
                   (-> graph
                       (update :fns into fns)
                       (update :slots into slots)
                       (update :fn-slots into fn-slots)
                       (update :bindings into bindings)
                       (update :list-items into items)))))))))


(defn- hinted-ids
  [graph roots & [mode]]
  (when graph
    (let [{:keys [fn-map slot-map fn-slots-by-fn bindings-by-fn items-by-binding] :as index}
          (deps/index-graph graph)
          dependencies (if (= :manifest mode)
                         #(deps/forward-deps-of % index)
                         (fn [id]
                           (let [bindings (get bindings-by-fn id)]
                             (related-ids [(get fn-map id)]
                                          (keep #(get slot-map (:slot-id %)) (get fn-slots-by-fn id))
                                          (if (= :client mode)
                                            (map #(if (capabilities/server-call-slot? (:slot-id %))
                                                    (dissoc % :ref-fn-id) %) bindings)
                                            bindings)
                                          (mapcat #(get items-by-binding (:id %)) bindings)))))]
      (prefetch/bounded-closure roots (set (keys fn-map)) dependencies max-functions))))


(defn capture-nodes
  "Read exact server-owned witness IDs without traversing sibling dependencies.
   This is raw evidence, not a checked executable/export graph. Every owner is
   freshly authorized before any owned value is read; ordinary source limits
   and decorated snapshot storage remain mandatory."
  [storage ids authorize!]
  (let [ids (set ids)
        budget (atom 0)]
    (when (> (count ids) max-functions) (reject! :source-function-limit))
    (reduce
      (fn [graph batch]
        (let [fns (vec (read-rows storage :fn batch))]
          (when-not (= (set batch) (set (map :id fns))) (reject! :source-missing))
          (let [authorizations (into {} (map (fn [row] [(:id row) (authorize! row)])) fns)
                [fn-slots bindings] (owner-rows storage batch budget)
                items (if (seq bindings)
                        (limited-query storage :binding-list-item {:binding-id (mapv :id bindings)} budget)
                        [])
                slot-ids (set (map :slot-id fn-slots))
                slots (vec (read-rows storage :slot slot-ids))]
            (validate-owned-source! {:fns fns :slots slots :fn-slots fn-slots :bindings bindings}
                                    authorizations)
            (-> graph
                (update :fns into fns)
                (update :fn-slots into fn-slots)
                (update :slots into slots)
                (update :bindings into bindings)
                (update :list-items into items)))))
      {:fns [] :fn-slots [] :slots [] :bindings [] :list-items []}
      (partition-all batch-size ids))))


(defn- row-limit?
  [error]
  (= {:type :browser-plan/unsupported :reason :source-row-limit} (ex-data error)))


(defn- collect-hinted-closure
  [storage roots authorize! graph-hint]
  (let [budget (atom 0)
        prepared (try
                   (prefetch/prepare (hinted-ids graph-hint roots)
                                     #(read-rows storage %1 %2)
                                     #(limited-query storage %1 %2 budget)
                                     authorize!)
                   (catch clojure.lang.ExceptionInfo error
                     (when-not (row-limit? error) (throw error))))]
    (if prepared
      (try
        (binding [*prefetched* prepared]
          (collect-source storage roots authorize! false false budget))
        (catch clojure.lang.ExceptionInfo error
          ;; Stale extras may consume the candidate budget. One ordinary retry
          ;; in this SAME snapshot preserves the semantic closure limit. Total
          ;; acquisition work has bounded speculative overhead (at most two
          ;; candidate budgets), not a promise of one physical-query budget.
          (if (row-limit? error)
            (collect-source storage roots authorize! false false)
            (throw error))))
      (collect-source storage roots authorize! false false))))


(defn collect-closure
  "Collect the full authorized source closure. Identity targets are included
   in this general-purpose mode, preserving the existing export boundary.
   An optional graph supplies bounded dependency IDs ONLY; all source rows and
   authorization are fresh. Caller must retain one immutable storage snapshot."
  ([storage roots authorize!]
   (collect-source storage roots authorize! false false))
  ([storage roots authorize! graph-hint]
   (collect-hinted-closure storage roots authorize! graph-hint)))


(defn- call-with-identity-hints
  [storage roots graph-hint mode consume]
  (binding [*prefetched* nil *identity-hints* nil]
    (let [ids (try
                (let [ids (hinted-ids graph-hint roots mode)]
                  (when (every? uuid? ids) ids))
                (catch InterruptedException error (throw error))
                ;; A hint is not an execution authority. Malformed/unavailable
                ;; advisory indexes retain ordinary fresh collection.
                (catch Exception _ nil))
          rows (when (seq ids) (vec (read-rows storage :fn ids)))
          cache (when (= ids (set (map :id rows)))
                  {:storage storage :rows {:fn (into {} (map (juxt :id identity)) rows)}})]
      (binding [*identity-hints* cache] (consume)))))


(defn collect-client-closure
  "Collect browser source with explicit server-call bindings terminal. Their
   source is separately checked by the server boundary, never exported.
   Optional hints batch fresh identities; fresh metadata-reachable owners then
   share bounded owned-value reads after authorization in the same snapshot."
  ([storage roots authorize!]
   (collect-source storage roots authorize! false true))
  ([storage roots authorize! graph-hint]
   (call-with-identity-hints storage roots graph-hint :client
                             #(collect-source storage roots authorize! false true nil (some? *identity-hints*)))))


(defn collect-component-identities
  "Read only server-allowlisted shipped component discriminator metadata.
   An identity-only grant never authorizes binding or implementation reads."
  [storage targets authorize!]
  (let [targets (set targets)]
    (when-not (every? #(contains? contracts/component-identities %) targets)
      (reject! :invalid-component-identities))
    (let [rows (vec (read-rows storage :fn targets))]
      (when-not (= targets (set (map :id rows))) (reject! :source-missing))
      (doseq [row rows] (authorize! row {:identity-only? true}))
      rows)))


(defn collect-manifests
  "Collect manifest source with compiler-classified fn-ref edges terminal.
   Only identity metadata is read for those targets; all other dependencies
   retain the normal bounded ACL-before-values collection. Multiple roots
   share the SAME function/row budget; this never installs a caller policy."
  [storage roots authorize!]
  (collect-source storage roots authorize! true false))


(defn collect-manifest
  "Single-root form of the constrained manifest collector."
  ([storage root authorize!]
   (collect-manifests storage [root] authorize!))
  ([storage root authorize! graph-hint]
   (call-with-identity-hints storage [root] graph-hint :manifest
                             #(collect-manifests storage [root] authorize!))))


(defn- ordinary-snapshot
  "Call `f` with transaction-decorated storage and a snapshot-local source
   policy. Policy creation, configuration reads and bounded closure reads share
   the same REPEATABLE READ snapshot. No nested/connection-backed export."
  [storage f]
  (let [datasource (tx/datasource storage)]
    (when (or (nil? datasource) (instance? Connection datasource))
      (reject! :snapshot-storage-unsupported))
    (binding [jdbc-tx/*nested-tx* :prohibit
              resolution/*branch-chain-cache* (atom {})
              resolution/*merges-memo* (atom {})
              resolution/*graph-load-memo* (atom {})
              branch-local/*storage-caches-override* (atom {})]
      (jdbc/with-transaction [connection datasource {:isolation :repeatable-read :read-only true}]
                             (when *statement-timeout-ms*
                               ;; PostgreSQL configuration RPC; value remains a parameter.
                               (jdbc/execute! connection
                                              ["select set_config('statement_timeout', ?, true)"
                                               (str *statement-timeout-ms*)]))
                             (check-snapshot!)
                             (let [storage (tx/with-connection storage connection)]
                               (grouped/call-with-snapshot-cache
                                 connection #(f storage (tenancy/browser-source-policy storage))))))))


(defn with-snapshot
  "Call f with a fresh decorated read-only snapshot and its live source policy.
   Explicit replay controllers may add physical guards; ordinary execution and
   unknown storage stacks preserve the existing snapshot path."
  [storage f]
  (replay/with-snapshot storage check-snapshot! f #(ordinary-snapshot % f)))


(defn collect-namespaces
  "Namespace/type resolution needs only the authorized closure's namespace
   ancestors. Never seed a personal export with every namespace in an org."
  [storage graph]
  (loop [pending (into #{} (keep :namespace-id) (:fns graph))
         seen #{}
         rows []]
    (when (> (+ (count pending) (count seen)) max-functions)
      (reject! :source-namespace-limit))
    (if (empty? pending)
      rows
      (let [ids (set (take batch-size pending))
            batch (vec (read-rows storage :ns ids))
            seen (into seen ids)]
        (when-not (= ids (set (map :id batch))) (reject! :source-namespace-missing))
        (recur (into (apply disj pending ids) (remove seen (keep :parent-id batch)))
               seen (into rows batch))))))
