(ns graphden.executor.revision-graph-snapshot
  "Opt-in source freshness evidence. A retained graph is a bounded private
   copy from one owned RR snapshot; it is never an authorization decision.
   Runtime wiring must supply the known pool generation and complete roster."
  (:require
    [graphden.executor.source-recipe-jdbc-rows :as rows]
    [graphden.executor.source-recipe-values :as values]
    [graphden.storage.postgres.source-revision :as revision]
    [graphden.storage.postgres.source-revision-snapshot :as snapshot])
  (:import
    (java.lang.ref
      WeakReference)))


(def ^:private graph-keys [:fns :slots :fn-slots :bindings :list-items])
(defonce ^:private seal (Object.))


(deftype ^:private GraphSnapshot
  [stamp owner scope tables token graph charge])


(defn- freeze-graph
  [graph {:keys [max-bytes max-rows]}]
  (when-not (and (integer? max-bytes) (pos? max-bytes)
                 (integer? max-rows) (pos? max-rows)
                 (= (set graph-keys) (set (keys graph)))
                 (every? vector? (vals graph))
                 (<= (reduce + (map count (vals graph))) max-rows))
    (throw (ex-info "Source graph exceeds its explicit shape or budget" {:type ::ineligible})))
  (reduce (fn [{:keys [used] :as result} key]
            (let [frozen (rows/freeze (get graph key) false (- max-bytes used))]
              (-> result
                  (assoc-in [:graph key] frozen)
                  (update :used + (rows/charge-bytes frozen)))))
          {:graph {} :used 1024} graph-keys))


(defn capture!
  "Capture from a trusted loader using the supplied connection, never an
   existing graph-cache. Scope identifies branch/shard; datasource identity
   identifies one immutable pool generation. Retention must be bounded by the
   caller's cache lifecycle; this module creates no global cache."
  [datasource tables scope limits loader]
  (when (nil? scope)
    (throw (ex-info "Explicit source scope required" {:type ::ineligible})))
  (let [tables (vec (sort (distinct (seq tables))))
        frozen-scope (values/freeze scope 4096)
        captured (snapshot/call-with-snapshot
                   datasource tables #(freeze-graph (loader %) limits))]
    (GraphSnapshot. seal (WeakReference. datasource) frozen-scope tables
                    (:revision captured) (get-in captured [:value :graph])
                    (+ (values/charge-bytes frozen-scope) (get-in captured [:value :used])))))


;; Generated deftype fields use direct field access, not Java method values.
^{:splint/disable [#_{:clj-kondo/ignore [:unresolved-namespace]} lint/prefer-method-values]}
(defn with-current
  "Use a fresh private graph only while revision, pool and source scope match.
   Unknown evidence returns a miss without invoking consume. Consume must still
   perform fresh authorization before inspecting owners or returning data.
   Exceptions propagate once; this function never repeats a consumer."
  [datasource tables scope evidence consume]
  (if (and (instance? GraphSnapshot evidence)
           (identical? seal (.-stamp ^GraphSnapshot evidence))
           (identical? datasource (.get ^WeakReference (.-owner ^GraphSnapshot evidence)))
           (= scope (values/thaw (.-scope ^GraphSnapshot evidence)))
           (= (vec (sort (distinct (seq tables)))) (.-tables ^GraphSnapshot evidence)))
    (:value
      (snapshot/call-with-snapshot
        datasource tables
        (fn [connection]
          (if (= (.-token ^GraphSnapshot evidence) (revision/current connection))
            (let [graph (update-vals (.-graph ^GraphSnapshot evidence)
                                     #(rows/thaw % connection {}))]
              {:hit? true :value (consume connection graph)})
            {:hit? false}))))
    {:hit? false}))
