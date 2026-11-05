(ns ^:serial graphden.bench.type-policy-test
  "Manual comparison; excluded from the normal test and perf rosters.

   After the host's heavy slot is free:
   bb wt test --config-file tools/bench/type-policy.edn --no-randomize

   Writes target/type-policy-benchmark.edn. Both implementations execute
   through the same executor over ONE captured scoped rich-type snapshot.
   Cold means a fresh execution context with a primed shared compiler cache,
   not a fresh JVM/JIT or database. Any measured compiler miss invalidates A/B.
   Warm samples alternate implementation order after paired warmup.
   Allocation counts are current-thread bytes, not process-wide allocation;
   cold compilation worker allocation, if any, is explicitly not covered.
   The harness does not establish a timing budget or auto-approve a regression."
  (:require
    [clojure.java.io :as io]
    [clojure.java.shell :as sh]
    [clojure.math :as math]
    [clojure.pprint :as pp]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.types-api :as ta]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as context]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.perf.sql :as psql]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.graph-harness :as gh :refer [*graph*]]
    [graphden.util.counters :as counters]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs])
  (:import
    (com.sun.management
      ThreadMXBean)
    (java.lang.management
      ManagementFactory)))


(use-fixtures :once
  (setup/create-container-fixture)
  (gh/graph-fixture (str (ns-name *ns*))))


(def ^:private baseline-source
  {:commit "76f37e7448416f5fed03cd4bc992c05c44d89c61"
   :file "src/graphden/crud/types_api.clj"
   :function 'api-rich-types})


(def ^:private omitted
  [:resolved-bindings :description :source-file :source-line :tags
   :arg-effects :call-time-effects :primary-parent])


(defn- native-projection
  [snapshot]
  ;; Exact projection body from baseline-source; acquisition is frozen for BOTH
  ;; implementations. Keep this copy unchanged when editing the graph projection.
  (update-vals snapshot #(apply dissoc % omitted)))


(defn- datasource-of
  [storage]
  (loop [s storage]
    (cond
      (nil? s) (throw (ex-info "Benchmark storage has no pool" {}))
      (:pool s) (:pool s)
      :else (recur (or (:base-storage s) (:base s) (:storage s))))))


(defn- sql-snapshot
  [ds]
  ;; Cumulative per-DB counters avoid resetting another observer's statistics.
  ;; No query text/data is written into the report; retain IDs and counts only.
  (into {}
        (map (fn [row]
               [(:queryid row)
                (assoc (select-keys row [:calls :rows])
                       :read? (boolean (re-find #"(?is)^\s*(select|with)\b" (:query row))))]))
        (jdbc/execute! ds
                       ["SELECT queryid, calls, rows, query FROM pg_stat_statements
            WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
              AND query NOT ILIKE '%pg_stat_statements%'"]
                       {:builder-fn rs/as-unqualified-maps})))


(defn- sql-delta
  [before after]
  (into {}
        (keep (fn [[id row]]
                (let [calls (- (:calls row) (get-in before [id :calls] 0))
                      rows (- (:rows row) (get-in before [id :rows] 0))]
                  (when (pos? calls) [id {:calls calls :rows rows :read? (:read? row)}]))))
        after))


(defn- allocation-meter
  []
  (let [memory-meter (ManagementFactory/getThreadMXBean)]
    (when-not (and (instance? ThreadMXBean memory-meter)
                   (ThreadMXBean/.isThreadAllocatedMemorySupported memory-meter))
      (throw (ex-info "Current-thread allocation measurement unavailable" {})))
    (ThreadMXBean/.setThreadAllocatedMemoryEnabled memory-meter true)
    (let [id (Thread/.getId (Thread/currentThread))]
      #(ThreadMXBean/.getThreadAllocatedBytes memory-meter id))))


(defn- measure
  [ds allocated expected invoke]
  (let [sql-before (sql-snapshot ds)
        counters-before (counters/snapshot)
        bytes-before (allocated)
        start (System/nanoTime)
        result (invoke)
        elapsed (- (System/nanoTime) start)
        allocated-bytes (- (allocated) bytes-before)
        compile-counts (counters/delta-since counters-before)
        compile-hits (get compile-counts :compile/all-hit 0)
        compile-misses (get compile-counts :compile/all-miss 0)
        statements (sql-delta sql-before (sql-snapshot ds))]
    (when (pos? compile-misses)
      (throw (ex-info "Shared compiler cache missed; A/B samples are not comparable"
                      {:compile-all-hits compile-hits
                       :compile-all-misses compile-misses})))
    ;; Equality/retention happens outside measurement, but every result is used.
    (when-not (= expected result)
      (throw (ex-info "Projection parity failed; no valid timing comparison" {})))
    {:elapsed-ns elapsed
     :compile-all-hits compile-hits
     :compile-all-misses compile-misses
     :current-thread-allocated-bytes allocated-bytes
     :sql-calls (reduce + 0 (map :calls (vals statements)))
     :sql-read-calls (reduce + 0 (map :calls (filter :read? (vals statements))))
     :sql-rows (reduce + 0 (map :rows (vals statements)))
     :sql-by-query-id statements}))


(defn- distribution
  [samples field]
  (let [values (vec (sort (map field samples)))
        n (count values)]
    {:min (first values) :median (nth values (quot n 2))
     :p95 (nth values (min (dec n) (long (math/ceil (* 0.95 (dec n))))))
     :max (peek values)}))


(deftest compare-native-and-graph-projection
  (let [{:keys [ctx storage all-name->id]} *graph*
        revision (sh/sh "git" "rev-parse" "HEAD")
        working-tree (sh/sh "git" "status" "--porcelain")
        snapshot (ta/all-rich-types ctx)
        expected (native-projection snapshot)
        baseline-name :benchmark-native-rich-projection
        baseline (setup/create-base-fn! storage (name baseline-name) :jsonb)
        ds (datasource-of storage)
        allocated (allocation-meter)
        impl (fn [_ _] (cr/record-effect! :db) (native-projection snapshot))
        base-fns (assoc (:base-fns ctx) baseline-name impl)
        new-context #(context/create-context {:storage storage :base-fns base-fns})
        ids {:native (:id baseline) :graph (get all-name->id :api-rich-types)}
        invoke (fn [execution-context kind]
                 #(setup/exec-with-storage execution-context storage (ids kind) {}))]
    (try
      (when-not (and (zero? (:exit revision)) (zero? (:exit working-tree)))
        (throw (ex-info "Cannot record benchmark source identity" {})))
      ;; Do not pad/duplicate a small fixture and pretend it was a real corpus.
      (when-not (<= 3000 (count snapshot) 10000)
        (throw (ex-info "Expected a real package snapshot around 4000 entries"
                        {:entries (count snapshot)})))
      (registry/record-rich-types! (:id baseline) baseline-name
                                   {:args {} :return-type :keyword-map :effects #{:db}})
      (psql/ensure-extension! ds)
      ;; The snapshot object is shared by both paths, including cold calls.
      ;; No acquisition, schema change or test mutation occurs inside the window.
      (with-redefs [ta/all-rich-types (constantly snapshot)]
        ;; The synthetic baseline changes the process-global compiler cache key.
        ;; Prime that final key once so the first native sample does not pay for
        ;; compilation shared by both paths. Fresh contexts still measure their
        ;; own registry/graph setup; compiler misses invalidate every sample.
        (when-not (= expected ((invoke (new-context) :graph)))
          (throw (ex-info "Projection parity failed during compiler priming" {})))
        (let [cold (vec (for [round (range 2)
                              kind (if (even? round) [:native :graph] [:graph :native])]
                          (assoc (measure ds allocated expected (invoke (new-context) kind))
                                 :kind kind :round round)))
              warm-context (new-context)
              _ (dotimes [_ 10]
                  ((invoke warm-context :native))
                  ((invoke warm-context :graph)))
              warm (vec (for [round (range 30)
                              kind (if (even? round) [:native :graph] [:graph :native])]
                          (assoc (measure ds allocated expected (invoke warm-context kind))
                                 :kind kind :round round)))
              report {:baseline baseline-source
                      :candidate-head (str/trim (:out revision))
                      :candidate-dirty? (not (str/blank? (:out working-tree)))
                      :snapshot-entries (count snapshot)
                      :snapshot-hash (hash snapshot)
                      :jvm (System/getProperty "java.version")
                      :cold-definition :fresh-context-warm-shared-compiler
                      :jvm-and-db-state :warm
                      :compile-counter-scope :process-wide
                      :allocation-scope :current-thread-only
                      :sql-scope :all-statements-in-fixture-database
                      :sql-read-classification :select-or-with-prefix
                      :parity :all-measured-results-equal
                      :cold cold :warm warm
                      :warm-summary
                      (into {} (for [[kind samples] (group-by :kind warm)]
                                 [kind (into {} (for [field [:elapsed-ns :current-thread-allocated-bytes :sql-calls :sql-read-calls]]
                                                  [field (distribution samples field)]))]))}
              output (io/file "target/type-policy-benchmark.edn")]
          (io/make-parents output)
          (spit output (with-out-str (pp/pprint report)))
          (println "Benchmark report:" (java.io.File/.getPath output))
          (pp/pprint (:warm-summary report))
          (is (= :all-measured-results-equal (:parity report)))
          (is (every? zero? (map :compile-all-misses (concat cold warm))))))
      (finally
        (sp/delete-entity storage :fn (:id baseline))))))
