(ns graphden.executor.browser-source
  "Bounded source collection for personal editor components. Source guards run
   before bindings are read; every database read uses one decorated snapshot."
  (:require
    [graphden.executor.browser-contracts :as contracts]
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


(def ^:private max-functions 2048)
(def ^:private max-rows 16384)
(def ^:private batch-size 64)


(defn- reject!
  [reason]
  (throw (ex-info "Component source is unavailable"
                  {:type :browser-plan/unsupported :reason reason})))


(defn- read-rows
  [storage entity ids]
  (if (satisfies? sp/StorageBatchCRUD storage)
    (vals (sp/read-entities storage entity ids))
    (keep #(sp/read-entity storage entity %) ids)))


(defn- limited-query
  [storage entity where budget]
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


(defn- related-ids
  [fns slots bindings items]
  (into #{}
        (remove nil?)
        (concat (mapcat :parent-ids fns)
                (mapcat #(map % [:base-fn-id :return-type-fn-id :element-fn-id]) fns)
                (map :type-fn-id slots)
                (mapcat #(map % [:ref-fn-id :type-override-fn-id :resolver-fn-id]) bindings)
                (map :ref-fn-id items))))


(defn collect-closure
  "Collect only requested roots and their source/type dependencies. `authorize!`
   receives each fn row BEFORE any of its binding/item values are loaded.
   Callers must supply an immutable storage/policy pair; HTTP code uses
   `with-snapshot`, not a live context's whole-graph cache."
  [storage roots authorize!]
  (let [budget (atom 0)]
    (loop [pending (set roots)
           seen #{}
           graph {:fns [] :slots [] :fn-slots [] :bindings [] :list-items []}]
      (when (> (+ (count pending) (count seen)) max-functions)
        (reject! :source-function-limit))
      (if (empty? pending)
        (update graph :slots #(vec (vals (into {} (map (juxt :id identity)) %))))
        (let [ids (set (take batch-size pending))
              fns (vec (read-rows storage :fn ids))]
          (when-not (= ids (set (map :id fns))) (reject! :source-missing))
          (let [authorizations (into {} (map (fn [row] [(:id row) (authorize! row)])) fns)
                bare-types (into #{} (keep (fn [[id kind]] (when (= :bare-type kind) id))) authorizations)
                fn-slots (limited-query storage :fn-slot {:fn-id (vec ids)} budget)
                bindings (limited-query storage :binding {:fn-id (vec ids)} budget)
                items (if (seq bindings)
                        (limited-query storage :binding-list-item
                                       {:binding-id (mapv :id bindings)} budget)
                        [])
                slot-ids (set (map :slot-id fn-slots))
                slots (if (seq slot-ids) (vec (read-rows storage :slot slot-ids)) [])
                seen (into seen ids)
                refs (related-ids fns slots bindings items)]
            (when (or (some #(contains? bare-types (:fn-id %)) fn-slots)
                      (some #(contains? bare-types (:fn-id %)) bindings)
                      (some #(and (contains? bare-types (:id %))
                                  (or (seq (:parent-ids %)) (:base-fn-id %)
                                      (:element-fn-id %) (:return-type-fn-id %) (:constraint %)))
                            fns))
              (reject! :primitive-type-source-mismatch))
            (doseq [row fns]
              (when-let [contract (:type-contract (get authorizations (:id row)))]
                (let [owned-junctions (filterv #(= (:id row) (:fn-id %)) fn-slots)
                      owned-slot-ids (set (map :slot-id owned-junctions))]
                  (when-not (contracts/matches? contract row
                                                (filterv #(contains? owned-slot-ids (:id %)) slots)
                                                owned-junctions
                                                (filterv #(= (:id row) (:fn-id %)) bindings))
                    (reject! :public-type-contract-mismatch)))))
            (when-not (= slot-ids (set (map :id slots))) (reject! :source-slot-missing))
            (recur (into (apply disj pending ids) (remove seen refs)) seen
                   (-> graph
                       (update :fns into fns)
                       (update :slots into slots)
                       (update :fn-slots into fn-slots)
                       (update :bindings into bindings)
                       (update :list-items into items)))))))))


(defn with-snapshot
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
                             (let [storage (tx/with-connection storage connection)]
                               (f storage (tenancy/browser-source-policy storage)))))))


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
