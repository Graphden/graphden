(ns graphden.executor.source-recipe-replay
  "Explicitly configured source replay for complete editor exports/invocations.
   Ordinary execution is the default. No authorization result or checked graph
   is retained: only guarded raw source recipes enter the bounded store."
  (:require
    [graphden.executor.revision-manifest :as manifest]
    [graphden.executor.source-recipe-lifecycle :as lifecycle]
    [graphden.executor.source-recipe-storage :as storage]
    [graphden.executor.source-recipe-store :as store]
    [graphden.executor.source-recipe-trace :as trace]
    [graphden.storage.grouped-read :as grouped]
    [graphden.storage.postgres.source-certificate :as certificate]
    [graphden.storage.postgres.source-session :as session]
    [graphden.storage.tx :as tx]
    [graphden.tenancy.context :as tenancy]
    [next.jdbc :as jdbc]
    [next.jdbc.transaction :as jdbc-tx])
  (:import
    (com.zaxxer.hikari
      HikariDataSource)
    (java.sql
      Connection
      SQLException)
    (org.postgresql
      PGConnection)))


(deftype ^:private Controller
  [state])


(def ^:dynamic ^:private *snapshot* nil)
(def ^:dynamic ^:private *fallback-description* nil)
(def ^:private admission-seal (Object.))


(defn create-controller
  "Explicit trusted setup, once per UNDERLYING POOL generation. Share this
   instance across its graph contexts; never create per request/branch. Pool
   mode must be explicitly :direct or :session, not inferred from Hikari.
   All store limits and :max-capture-bytes are required. Unknown modes return
   nil. The owner retires this controller only together with its shared pool."
  [pool {:keys [pool-mode max-bytes max-capture-bytes max-ttl-ms] :as options}]
  (when (and (= HikariDataSource (class pool)) (contains? #{:direct :session} pool-mode))
    (when-not (and (integer? max-capture-bytes)
                   (> max-capture-bytes (+ trace/preparation-scratch-bytes (* 1024 1024)))
                   (integer? max-bytes)
                   (<= max-capture-bytes max-bytes)
                   (<= max-capture-bytes (* 256 1024 1024)))
      (throw (ex-info "Source replay requires an explicit capture reservation" {})))
    (let [closed? (atom false)
          clock #(quot (System/nanoTime) 1000000)
          manager (session/create-manager {:mode pool-mode :max-sessions (HikariDataSource/.getMaximumPoolSize pool)})
          cache (store/create-store
                  (assoc (select-keys options [:max-bytes :max-entries :max-pending :max-families :max-leases :max-ttl-ms])
                         :clock-ms clock
                         :closed? #(or @closed? (HikariDataSource/.isClosed pool) (Connection/.isClosed %))))]
      (when manager
        (Controller. {:id (Object.) :pool pool :manager manager :store cache :closed? closed?
                      :clock clock :ttl max-ttl-ms :capture-bytes max-capture-bytes})))))


;; Generated type fields are field reads, not uniform Class method calls.
^{:splint/disable [#_{:clj-kondo/ignore [:unresolved-namespace]} lint/prefer-method-values]}
(defn- controller-state
  [controller]
  (when (instance? Controller controller) (.-state ^Controller controller)))


(defn retire-controller!
  "Revoke cache work without touching a borrowed connection. The pool lifecycle
   owner must subsequently retire/close its pool to release session markers."
  [controller]
  (when-let [{:keys [closed? manager store]} (controller-state controller)]
    (reset! closed? true)
    (session/close-manager! manager)
    (store/stats store)
    nil))


(defn stats
  [controller]
  (when-let [state (controller-state controller)]
    (store/stats (:store state))))


(defn call-with-operation
  "Wrap the entire endpoint result, including invocation's fresh postcheck.
   Only a Controller supplied in trusted ctx configuration enables replay."
  [ctx f]
  (let [controller (controller-state (:source-replay ctx))]
    (manifest/call-with-controller
      (:source-manifest ctx)
      #(lifecycle/call-with-operation (when (and controller (not @(:closed? controller))) controller) f))))


(defn- unavailable!
  [reason]
  (throw (ex-info "Source replay is unavailable for this snapshot"
                  {:type ::unavailable :reason reason ::seal admission-seal})))


(defn- retryable?
  [error]
  (or (trace/restart? error)
      (and (= ::unavailable (:type (ex-data error)))
           (identical? admission-seal (::seal (ex-data error))))))


(defn- family!
  [controller key]
  (store/create-family! (:store controller) key (+ ((:clock controller)) (:ttl controller))))


(defn- ordinary-fallback
  [description ordinary]
  (binding [*snapshot* nil *fallback-description* description]
    (ordinary (storage/fresh-storage description (:datasource description)))))


(defn- consume-snapshot
  [{:keys [description registration connection] :as state} consume]
  (let [snapshot-storage (storage/fresh-storage description connection)
        authorize! (tenancy/browser-source-policy snapshot-storage)]
    (when-not (identical? registration (tenancy/source-replay-policy-info authorize!))
      (unavailable! :policy-replaced))
    (let [scope (atom (assoc state :storage snapshot-storage :authorize! authorize!))]
      (binding [*snapshot* scope]
        (try
          (storage/call-with-fresh-memos
            #(grouped/call-with-snapshot-cache connection (fn [] (consume snapshot-storage authorize!))))
          (finally (reset! scope ::closed)))))))


(defn- guarded-snapshot
  [controller description registration check! consume]
  ;; Borrow through the classified original OrgAware datasource, not its pool
  ;; delegate. Session markers are admitted in autocommit BEFORE owned RR.
  (with-open [connection (jdbc/get-connection (:datasource description))]
    (check!)
    (when-not (Connection/.getAutoCommit connection) (unavailable! :borrowed-transaction))
    (let [marker (try (session/ensure-marker! (:manager controller) connection check!)
                      (catch SQLException _ (unavailable! :session-sql-failed)))]
      (when-not marker (unavailable! :session-unavailable))
      (let [physical (Connection/.unwrap connection PGConnection)
            key [:source-snapshot-v1 (:id controller) (:branch-id description) (:generation registration)]
            family (family! controller key)
            ticket (when family (store/reserve! (:store controller) family physical trace/preparation-scratch-bytes))
            staged (atom #{})
            completed? (volatile! false)]
        (when-not ticket (unavailable! :snapshot-budget))
        (try
          (let [result
                (let [contract (when-let [provider (:policy-contract-for description)] (provider storage/source-tables))]
                  (when (and (:policy-contract-for description) (nil? contract)) (unavailable! :policy-unavailable))
                  (binding [jdbc-tx/*nested-tx* :prohibit]
                    (jdbc/with-transaction [tx connection {:isolation :repeatable-read :read-only true}]
                                           (let [guard-entered? (volatile! false)]
                                             (try
                                               (certificate/call-with-guard
                                                 tx storage/source-tables {:policy-contract contract :session-marker marker} check!
                                                 (fn [guard]
                                                   (vreset! guard-entered? true)
                                                   (consume-snapshot {:controller controller :description description :registration registration
                                                                      :connection tx :physical physical :guard guard :check! check! :staged staged}
                                                                     consume)))
                                               (catch SQLException error
                                                 ;; A target/policy/export SQL error is NOT replay failure.
                                                 ;; Preserve its original exception; never execute it twice.
                                                 (if @guard-entered? (throw error) (unavailable! :guard-sql-failed)))
                                               (catch clojure.lang.ExceptionInfo error
                                                 (if (and (not @guard-entered?) (= ::certificate/miss (:type (ex-data error))))
                                                   (unavailable! (:reason (ex-data error)))
                                                   (throw error))))))))]
            (vreset! completed? true)
            result)
          (finally
            (try
              ;; Includes transaction completion after the callback returned.
              ;; An aborted snapshot cannot publish a staged subpass, even on Error.
              (when-not @completed?
                (doseq [record-ticket @staged] (lifecycle/discard-record! record-ticket)))
              (finally (store/abort! (:store controller) ticket)))))))))


(defn with-snapshot
  "Use an owned guarded RR only for an explicitly configured, known stack.
   A replay/certificate miss unwinds it completely before ordinary fallback.
   ordinary takes the storage to use for its NEW transaction."
  [source-storage check! consume ordinary]
  (let [controller (lifecycle/current-controller)
        registration (when controller (tenancy/source-replay-registration))
        description (when registration (storage/describe source-storage registration))]
    (if (and description (identical? (:pool description) (:pool controller)) (not @(:closed? controller)))
      (try
        (guarded-snapshot controller description registration check! consume)
        (catch clojure.lang.ExceptionInfo error
          (if (retryable? error) (ordinary-fallback description ordinary) (throw error))))
      (ordinary source-storage))))


(defn- entries-key
  [entries]
  (when (and (map? entries) (<= 1 (count entries) 8)
             (every? keyword? (keys entries)) (every? uuid? (vals entries))
             (every? #(<= (count (str %)) 128) (keys entries)))
    (into {} entries)))


(defn- scope-for-trace
  [{:keys [connection guard check! registration controller]}]
  {:connection connection :guard guard :check! check! :generation (:generation registration)
   :authority-fields (:authority-fields registration) :max-bytes (:capture-bytes controller)})


(defn- fresh-pass
  [{:keys [description connection]} run authorize!]
  (storage/call-with-fresh-memos #(run (storage/fresh-storage description connection) authorize!)))


(defn- replay-entry
  [{:keys [controller physical] :as snapshot} family lease run authorize!]
  (try
    (let [recipe (store/lease-value (:store controller) lease)
          ticket (store/reserve! (:store controller) family physical (trace/charge-bytes recipe))]
      (if ticket
        (try
          (trace/replay (scope-for-trace snapshot) recipe #(fresh-pass snapshot run %) authorize!)
          (finally (store/abort! (:store controller) ticket)))
        (fresh-pass snapshot run authorize!)))
    (catch clojure.lang.ExceptionInfo error
      ;; A personal denial does not invalidate source shared by other callers.
      ;; An incompatible read/witness retires only this entry, not its family.
      (when (and (trace/restart? error)
                 (contains? #{:source-changed :read-changed :unrecorded-read}
                            (:reason (ex-data error))))
        (store/retire-lease! (:store controller) lease))
      (throw error))
    (finally (store/release! (:store controller) lease))))


(defn- record-entry
  [{:keys [controller physical] :as snapshot} family run authorize!]
  (let [reserved (- (:capture-bytes controller) trace/preparation-scratch-bytes)
        ticket (store/reserve! (:store controller) family physical reserved)]
    (if-not ticket
      (fresh-pass snapshot run authorize!)
      (if-not (lifecycle/track-reservation! ticket)
        (do (store/abort! (:store controller) ticket) (fresh-pass snapshot run authorize!))
        (let [staged? (volatile! false)]
          (try
            (let [{:keys [value recipe charge-bytes]}
                  (trace/record (scope-for-trace snapshot) #(fresh-pass snapshot run %) authorize!)]
              (when recipe
                (vreset! staged? (lifecycle/complete-record! ticket recipe charge-bytes))
                (when @staged? (swap! (:staged snapshot) conj ticket)))
              value)
            (finally (when-not @staged? (lifecycle/discard-record! ticket)))))))))


(defn capture
  "The ONLY traced boundary: complete client-source capture. Anchor, caller,
   grant decisions, checking, export and execution remain ordinary/fresh.
   run takes this pass's storage and authorizer, not a cached graph."
  [source-storage authorize! entries run]
  (if *snapshot*
    (let [snapshot @*snapshot*]
      (when-not (map? snapshot) (unavailable! :snapshot-expired))
      ((:check! snapshot))
      (if-not (and (identical? source-storage (:storage snapshot))
                   (identical? authorize! (:authorize! snapshot)))
        (run source-storage authorize!)
        (let [controller (:controller snapshot)
              entries (entries-key entries)
              description (:description snapshot)
              key [:client-source-v1 (:id controller) (:branch-id description)
                   (get-in snapshot [:registration :generation]) entries]
              family (when (and entries
                                (identical? controller (lifecycle/current-controller)))
                       (family! controller key))]
          (if family
            (if-let [lease (or (store/acquire! (:store controller) family (:physical snapshot))
                               (store/acquire-family! (:store controller) family))]
              (replay-entry snapshot family lease run authorize!)
              (record-entry snapshot family run authorize!))
            (fresh-pass snapshot run authorize!)))))
    (if *fallback-description*
      (fresh-pass {:description *fallback-description* :connection (tx/datasource source-storage)} run authorize!)
      (run source-storage authorize!))))
