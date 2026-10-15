(ns graphden.bench.ui-component
  "Opt-in, isolated UI bundle creation benchmark.

   Run once with `clojure -M:dev:bench -m graphden.bench.ui-component` from
   a claimed worktree. Each size gets a fresh golden-clone database that the
   fixture drops, including cohorts whose exact delete receipt exceeds 1000.
   `--binding-heavy` runs one 2048-function cohort with two bindings and
   sixteen list items per added function under the production 30-second abort
   shield.
   JDBC/type-check counters observe only the requesting thread; shield workers
   are intentionally outside those counters, so they are not total work."
  (:require
    [cheshire.core :as json]
    [clojure.test :as test]
    [graphden.crud.entities.delete-batch :as deletion]
    [graphden.crud.entities.function-batch-budget :as batch-budget]
    [graphden.editor.component-bundle :as bundle]
    [graphden.editor.component-creation :as creation]
    [graphden.editor.component-templates :as templates]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.postgres.crud :as pg-crud]
    [graphden.storage.postgres.util :as pg-util]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.golden-app :as ga]
    [graphden.test-infra.impls :as impls]
    [graphden.types.check :as check]
    [graphden.util.abort-shield :as shield]
    [next.jdbc :as jdbc]))


(def ^:private points [991 1500 2500])


(defn- elapsed-ms
  [start]
  (long (/ (- (System/nanoTime) start) 1000000)))


(defn- synthetic-leaves
  [root leaf-count shape]
  (mapv (fn [position]
          (merge {:name (keyword (format "_bench-independent-%04d" position))
                  :namespace (str root ".inspector")}
                 (if (= shape :binding-heavy)
                   {:parent :core.collections/zipmap
                    :args {:keys [{:value :one} {:value :two} {:value :three} {:value :four}
                                  {:value :five} {:value :six} {:value :seven} {:value :eight}]
                           :vals [1 2 3 4 5 6 7 8]}
                    :return-type [:map :keyword :int]}
                   {:parent :core.logic/const :args {:value 0} :return-type :int})))
        (range leaf-count)))


(defn- record-counts
  [records]
  (into (sorted-map) (map (fn [[kind rows]] [kind (count rows)])) (group-by :kind records)))


(defn- request!
  [handler input]
  (let [start (System/nanoTime)]
    (try
      (let [response (ga/exec-handler handler
                                      {:request-method :post :uri "/api/ui/components/create"
                                       :headers {"content-type" "application/json"}
                                       :body (json/generate-string input)})]
        {:status (:status response)
         :elapsed-ms (elapsed-ms start)
         :body (json/parse-string (:body response) true)})
      (catch Exception error
        {:status :exception :elapsed-ms (elapsed-ms start)
         :error-type (or (:type (ex-data error)) :unclassified)}))))


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
    (try
      (if (= phase :create)
        (let [batch-write @#'pg-crud/create-entities
              observed-sql (fn [operation actual ds sql opts]
                             (let [start (System/nanoTime)]
                               (try (actual ds sql opts)
                                    (finally
                                      (swap! metrics
                                             (fn [m]
                                               (-> m
                                                   (update-in [:worker-observed-jdbc operation :count]
                                                              (fnil inc 0))
                                                   (update-in [:worker-observed-jdbc operation :elapsed-ms]
                                                              (fnil + 0)
                                                              (elapsed-ms start)))))))))]
          ;; Both seams are dynamically bound on the actual shield worker.
          ;; This is a scoped observer, not a total SQL counter: BYO SQL and
          ;; prepared execute-batch! calls do not pass through pg-util.
          (binding [pg-util/*jdbc-override*
                    {:execute! #(observed-sql :execute! jdbc/execute! %1 %2 %3)
                     :execute-one! #(observed-sql :execute-one! jdbc/execute-one! %1 %2 %3)}
                    pg-crud/*create-entities-override*
                    (fn [ds entity rows fields]
                      (swap! metrics (fn [m]
                                       (-> m
                                           (update-in [:worker-pg-batches entity :calls] (fnil inc 0))
                                           (update-in [:worker-pg-batches entity :rows] (fnil + 0) (count rows)))))
                      (binding [pg-crud/*create-entities-override* nil]
                        (batch-write ds entity rows fields)))]
            (f)))
        (f))
      (finally
        (swap! metrics update-in [:phases phase] (fnil conj [])
               {:elapsed-ms (elapsed-ms start)
                :caller-jdbc (- (:caller-jdbc @metrics) before)
                :caller-type-checks (- (:caller-type-checks @metrics) checks)})))))


(defn- instrumented
  [extra shape metrics worker-done f]
  (let [owner (Thread/currentThread)
        original-definitions @#'templates/definitions
        original-parse @#'bundle/parse
        original-sql jdbc/execute!
        original-check check/check-fn-def!
        original-failure creation/failure-response
        original-shield shield/run!
        watched [[#'creation/plan :plan] [#'creation/create! :create] [#'creation/publish :publish]]
        wrappers (into
                   {#'templates/definitions
                    (fn [root]
                      (let [all (into (original-definitions root) (synthetic-leaves root extra shape))]
                        (swap! metrics assoc :definitions (count all)
                               :definition-bytes (count (String/.getBytes (pr-str all) "UTF-8")))
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
                      (original-failure error))
                    #'shield/run!
                    (fn [work]
                      (original-shield
                        (fn []
                          (try (work)
                               (finally (deliver worker-done true))))))}
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
  [target shape]
  (let [{:keys [ctx storage]} ga/*bootstrap*
        baseline (count (templates/definitions "bench.baseline"))
        extra (- target baseline)
        destination (sp/create-entity storage :ns {:name (str "ui-bench-" (random-uuid))})
        worker-done (promise)
        metrics (atom {:target target :shape shape :baseline baseline :caller-jdbc 0
                       :caller-jdbc-families (sorted-map) :caller-type-checks 0 :phases {}})]
    (when (neg? extra)
      (throw (ex-info "Benchmark point is below the shipped bundle" {:target target :baseline baseline})))
    (try
      (instrumented
        extra shape metrics worker-done
        #(let [preview (request! :_ui-components-create-preview-handler
                                 {:owner "anonymous" :namespace-id (str (:id destination))})
               _ (swap! metrics assoc :preview (select-keys preview [:status :elapsed-ms]))
               applied (when (= 200 (:status preview))
                         (request! :_ui-components-create-apply-handler (get-in preview [:body :request])))
               _ (when applied
                   (swap! metrics assoc :apply (assoc (select-keys applied [:status :elapsed-ms])
                                                      :committed (true? (get-in applied [:body :committed])))))
               manifest (get-in applied [:body :manifest])]
           ;; The timeout cancels a Future, not necessarily its JDBC callable.
           ;; The callable's own finally runs after the writer TX has exited.
           ;; Never drop this fixture's database while that callable is alive.
           (when (= :abort-shield/timeout (:error-type applied))
             (when-not (true? (deref worker-done 60000 ::still-running))
               (println (pr-str {:target target :shape shape :worker :not-drained
                                 :phase (keys (:phases @metrics))}))
               ;; This opt-in benchmark is a standalone JVM. Exiting here
               ;; releases its connections and lets Testcontainers reap the
               ;; container without forcing DROP under a live write.
               (System/exit 3)))
           (when (and (> target batch-budget/max-functions) (= 200 (:status preview)))
             (throw (ex-info "Oversized cohort passed preview" {:target target})))
           (when (and (= 200 (:status applied)) (:committed (:body applied)))
             (let [deleted (exact-delete ctx manifest)]
               (swap! metrics assoc :exact-delete deleted)
               (when-not (:accepted deleted)
                 (throw (ex-info "Admitted cohort could not be deleted by exact receipt"
                                 {:target target :reason (:reason deleted)})))))))
      (catch Exception error
        (swap! metrics assoc :harness-error (Class/.getName (class error))
               :reason (:reason (ex-data error)))))
    (let [outcome @metrics
          preview (:preview outcome)
          applied (:apply outcome)
          deleted (:exact-delete outcome)]
      (when (and (not (:harness-error @metrics))
                 (if (<= target batch-budget/max-functions)
                   (not (and (= 200 (:status preview)) (= 200 (:status applied))
                             (:committed applied) (:accepted deleted)))
                   (not= 422 (:status preview))))
        (swap! metrics assoc :harness-error "unexpected-outcome")))
    @metrics))


(defn- isolated-point
  [target shape]
  (let [result (atom nil)
        fixture (test/join-fixtures [(ga/fixture (symbol (str "graphden.bench.ui-component-" target)))
                                     (impls/impls-fixture "storage" "branches")])]
    (fixture #(try (reset! result (run-point target shape))
                   (finally (setup/close-graph! ga/*bootstrap*))))
    @result))


(defn -main
  [& args]
  (when-not (or (empty? args) (= ["--binding-heavy"] (vec args)))
    (throw (ex-info "This benchmark accepts only --binding-heavy" {})))
  (let [shape (if (seq args) :binding-heavy :independent)]
    (doseq [target (if (= shape :binding-heavy) [2048] points)]
      (let [result (try (isolated-point target shape)
                        (catch Exception error
                          {:target target :harness-error (Class/.getName (class error))
                           :reason (:reason (ex-data error))}))]
        (println (pr-str result))
        (when (:harness-error result)
          (System/exit 2))))))
