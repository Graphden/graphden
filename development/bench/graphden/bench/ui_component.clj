(ns graphden.bench.ui-component
  "Opt-in, isolated UI bundle creation benchmark.

   Run once with `clojure -M:dev:bench -m graphden.bench.ui-component` from
   a claimed worktree. Each size gets a fresh golden-clone database that the
   fixture drops, including bundles whose exact delete receipt exceeds 1000.
   JDBC/type-check counters observe only the requesting thread; shield workers
   are intentionally outside those counters, so they are not total work."
  (:require
    [cheshire.core :as json]
    [clojure.test :as test]
    [graphden.crud.entities.delete-batch :as deletion]
    [graphden.editor.component-bundle :as bundle]
    [graphden.editor.component-creation :as creation]
    [graphden.editor.component-templates :as templates]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.golden-app :as ga]
    [graphden.test-infra.impls :as impls]
    [graphden.types.check :as check]
    [next.jdbc :as jdbc]))


(def ^:private points [991 1500 2500])


(defn- elapsed-ms
  [start]
  (long (/ (- (System/nanoTime) start) 1000000)))


(defn- synthetic-leaves
  [root leaf-count]
  (mapv (fn [position]
          {:name (keyword (format "_bench-independent-%04d" position))
           :namespace (str root ".inspector")
           :parent :core.logic/const
           :args {:value 0}
           :return-type :int})
        (range leaf-count)))


(defn- record-counts
  [records]
  (into (sorted-map) (map (fn [[kind rows]] [kind (count rows)])) (group-by :kind records)))


(defn- request!
  [handler input]
  (let [start (System/nanoTime)
        response (ga/exec-handler handler
                                  {:request-method :post :uri "/api/ui/components/create"
                                   :headers {"content-type" "application/json"}
                                   :body (json/generate-string input)})]
    {:status (:status response)
     :elapsed-ms (elapsed-ms start)
     :body (json/parse-string (:body response) true)}))


(defn- sql-family
  [sql]
  (case (second (re-find #"(?i)\b(?:FROM|INTO|UPDATE)\s+\"(fn_slot|binding_list_item|binding|slot|fn|ns)(?:_version)?\""
                         (if (vector? sql) (first sql) sql)))
    "fn" :fn "fn_slot" :fn-slot "slot" :slot "binding" :binding
    "binding_list_item" :binding-list-item "ns" :ns :other))


(defn- count-phase
  [phase metrics f]
  (let [start (System/nanoTime)
        before (:caller-jdbc @metrics)
        checks (:caller-type-checks @metrics)]
    (try (f)
         (finally
           (swap! metrics update-in [:phases phase] (fnil conj [])
                  {:elapsed-ms (elapsed-ms start)
                   :caller-jdbc (- (:caller-jdbc @metrics) before)
                   :caller-type-checks (- (:caller-type-checks @metrics) checks)})))))


(defn- instrumented
  [extra metrics f]
  (let [owner (Thread/currentThread)
        original-definitions @#'templates/definitions
        original-parse @#'bundle/parse
        original-sql jdbc/execute!
        original-check check/check-fn-def!
        original-failure creation/failure-response
        watched [[#'creation/plan :plan] [#'creation/create! :create] [#'creation/publish :publish]]
        wrappers (into
                   {#'templates/definitions
                    (fn [root]
                      (let [all (into (original-definitions root) (synthetic-leaves root extra))]
                        (swap! metrics assoc :definitions (count all)
                               :definition-bytes (count (.getBytes (pr-str all) "UTF-8")))
                        all))
                    #'bundle/parse
                    (fn [& args]
                      (let [parsed (apply original-parse args)]
                        (swap! metrics assoc :records (record-counts (:records parsed)))
                        parsed))
                    #'jdbc/execute!
                    (fn [connection sql & opts]
                      (when (identical? owner (Thread/currentThread))
                        (swap! metrics (fn [m]
                                         (-> m (update :caller-jdbc inc)
                                             (update-in [:caller-jdbc-families (sql-family sql)] (fnil inc 0))))))
                      (apply original-sql connection sql opts))
                    #'check/check-fn-def!
                    (fn [& args]
                      (when (identical? owner (Thread/currentThread))
                        (swap! metrics update :caller-type-checks inc))
                      (apply original-check args))
                    #'creation/failure-response
                    (fn [error]
                      (swap! metrics assoc :failure
                             (select-keys (ex-data error) [:type :reason :http-status]))
                      (original-failure error))}
                   (map (fn [[target-var phase]]
                          [target-var (let [original @target-var]
                                        (fn [& args]
                                          (count-phase phase metrics #(apply original args))))]))
                   watched)]
    (with-redefs-fn wrappers f)))


(defn- exact-delete
  [ctx manifest]
  (let [receipts (:functions manifest)
        start (System/nanoTime)]
    (try
      (let [result (deletion/delete-receipts! ctx receipts)]
        {:accepted true :deleted (count (:deleted result)) :elapsed-ms (elapsed-ms start)})
      (catch clojure.lang.ExceptionInfo error
        {:accepted false :reason (:type (ex-data error))
         :elapsed-ms (elapsed-ms start)}))))


(defn- run-point
  [target]
  (let [{:keys [ctx storage]} ga/*bootstrap*
        baseline (count (templates/definitions "bench.baseline"))
        extra (- target baseline)
        destination (sp/create-entity storage :ns {:name (str "ui-bench-" (random-uuid))})
        metrics (atom {:target target :baseline baseline :caller-jdbc 0
                       :caller-jdbc-families (sorted-map) :caller-type-checks 0 :phases {}})]
    (when (neg? extra)
      (throw (ex-info "Benchmark point is below the shipped bundle" {:target target :baseline baseline})))
    (instrumented
      extra metrics
      #(let [preview (request! :_ui-components-create-preview-handler
                               {:owner "anonymous" :namespace-id (str (:id destination))})
             _ (swap! metrics assoc :preview (select-keys preview [:status :elapsed-ms]))
             applied (when (= 200 (:status preview))
                       (request! :_ui-components-create-apply-handler (get-in preview [:body :request])))
             _ (when applied
                 (swap! metrics assoc :apply (select-keys applied [:status :elapsed-ms])))
             manifest (get-in applied [:body :manifest])]
         (when (and (= 200 (:status applied)) (:committed (:body applied)))
           (let [deleted (exact-delete ctx manifest)]
             (swap! metrics assoc :exact-delete deleted)
             (when (and (> target 1000) (:accepted deleted))
               (throw (ex-info "Oversized exact receipt was accepted" {:target target})))))))
    @metrics))


(defn- isolated-point
  [target]
  (let [result (atom nil)
        fixture (test/join-fixtures [(ga/fixture (symbol (str "graphden.bench.ui-component-" target)))
                                     (impls/impls-fixture "storage" "branches")])]
    (fixture #(reset! result (run-point target)))
    @result))


(defn -main
  [& args]
  (when (seq args)
    (throw (ex-info "This benchmark accepts no options" {})))
  (doseq [target points]
    (let [result (try (isolated-point target)
                      (catch Exception error
                        {:target target :harness-error (.getName (class error))
                         :reason (:reason (ex-data error))}))]
      (println (pr-str result))
      (when (:harness-error result)
        (System/exit 2)))))
