(ns graphden.executor.revision-manifest
  "Opt-in bounded manifest retention under an owned revision-bearing RR.
   Controller lifetime belongs to one trusted pool/schema generation. No ACL
   result, authorizer, connection or caller graph enters retained state."
  (:require
    [graphden.executor.manifest-graph-copy :as copy]
    [graphden.executor.source-recipe-storage :as storage]
    [graphden.storage.grouped-read :as grouped]
    [graphden.storage.postgres.source-certificate :as certificate]
    [graphden.storage.postgres.source-revision-snapshot :as snapshot]
    [graphden.tenancy.context :as tenancy]
    [next.jdbc :as jdbc]
    [next.jdbc.transaction :as jdbc-tx])
  (:import
    (com.zaxxer.hikari
      HikariDataSource)))


(deftype ^:private Controller
  [state])


(def ^:dynamic ^:private *controller* nil)
(def ^:dynamic ^:private *snapshot* nil)


(defn create-controller
  "Trusted application setup AFTER coordinated revision migration. Schema/DDL
   mutation requires retiring this controller before mutation and a new pool
   generation after migration. Every limit is explicit; requests cannot configure it."
  [pool tables {:keys [max-bytes max-entries max-pending max-capture-bytes max-rows ttl-ms] :as limits}]
  (when (= HikariDataSource (class pool))
    (when-not (and (seq tables) (every? string? tables)
                   (every? #(and (integer? %) (pos? %))
                           [max-bytes max-entries max-pending max-capture-bytes max-rows ttl-ms])
                   (<= max-capture-bytes max-bytes))
      (throw (ex-info "Explicit bounded manifest limits required" {})))
    (Controller. {:pool pool :tables (vec (sort tables)) :limits limits
                  :installation-state (:installation-state limits)
                  :installation-id (some-> (:installation-state limits) deref :installation-id)
                  :cache (atom {:entries {} :bytes 0 :pending 0 :closed? false})})))


^{:splint/disable [#_{:clj-kondo/ignore [:unresolved-namespace]} lint/prefer-method-values]}
(defn- state
  [controller]
  (when (instance? Controller controller) (.-state ^Controller controller)))


(defn retire-controller!
  [controller]
  (when-let [{:keys [cache]} (state controller)]
    (locking cache (swap! cache assoc :closed? true :entries {} :bytes 0)))
  nil)


(defn stats
  [controller]
  (when-let [{:keys [cache]} (state controller)]
    (let [current @cache] (assoc (select-keys current [:bytes :pending :closed?]) :entries (count (:entries current))))))


(defn call-with-controller
  [controller f]
  (binding [*controller* (state controller)] (f)))


(defn- now-ms
  []
  (quot (System/nanoTime) 1000000))


(defn- prune
  [current now]
  (let [entries (into {} (filter (fn [[_ entry]] (> (:expires entry) now))) (:entries current))]
    (assoc current :entries entries :bytes (reduce + 0 (map :charge (vals entries))))))


(defn- publish!
  [{:keys [cache limits]} staged]
  (locking cache
    (when-not (:closed? @cache)
      (swap! cache
             (fn [current]
               (reduce (fn [current [key graph]]
                         (let [charge (copy/charge-bytes graph)
                               current (update current :entries dissoc key)
                               current (assoc current :bytes (reduce + 0 (map :charge (vals (:entries current)))))
                               current (loop [current current]
                                         (if (and (seq (:entries current))
                                                  (or (>= (count (:entries current)) (:max-entries limits))
                                                      (> (+ (:bytes current) charge) (:max-bytes limits))))
                                           (let [[old entry] (apply min-key (comp :expires val) (:entries current))]
                                             (recur (-> current (update :entries dissoc old) (update :bytes - (:charge entry)))))
                                           current))]
                           (-> current
                               (assoc-in [:entries key] {:graph graph :charge charge :expires (+ (now-ms) (:ttl-ms limits))})
                               (update :bytes + charge))))
                       (prune current (now-ms)) staged))))))


(defn create-runtime-controller
  "Build one optional controller from a successfully migrated known storage
   stack. Never unwrap arbitrary datasource delegates or infer installation."
  [source-storage installation-state]
  (when-let [installation (some-> installation-state deref)]
    (when-let [description (storage/describe source-storage (tenancy/source-replay-registration))]
      (create-controller (:pool description) (:tables installation)
                         {:installation-state installation-state
                          :max-bytes (* 64 1024 1024) :max-entries 64 :max-pending 4
                          :max-capture-bytes (* 16 1024 1024) :max-rows 16384 :ttl-ms 30000}))))


(defn with-snapshot
  "Use only an admitted live stack and exact underlying pool. Unknown stacks
   keep ordinary reads. Pending copies are bounded per snapshot, and concurrent
   snapshot reservations by max-pending. Cancellation releases on actual unwind."
  [source-storage check! statement-timeout-ms consume ordinary]
  (let [controller *controller*
        registration (when controller (tenancy/source-replay-registration))
        description (when registration (storage/describe source-storage registration))
        tables (when controller (set (map keyword (:tables controller))))
        provider (:policy-contract-for description)
        contract (when provider (provider tables))
        cache (:cache controller)
        admitted? (when (and (nil? *snapshot*) description
                             (identical? (:pool description) (:pool controller))
                             (or (nil? provider) contract))
                    (locking cache
                      (when (and (not (:closed? @cache))
                                 (or (nil? (:installation-state controller))
                                     (= (:installation-id controller)
                                        (:installation-id @(:installation-state controller))))
                                 (not (HikariDataSource/.isClosed ^HikariDataSource (:pool controller)))
                                 (< (:pending @cache) (get-in controller [:limits :max-pending])))
                        (swap! cache update :pending inc) true)))]
    (if-not admitted?
      (ordinary source-storage)
      (let [staged (atom [])]
        (try
          (let [result
                (binding [jdbc-tx/*nested-tx* :prohibit]
                  (:value
                    (snapshot/call-with-snapshot
                      (:datasource description) (:tables controller)
                      {:with-revision? true
                       :before-lock (fn [connection]
                                      (check!)
                                      (when statement-timeout-ms
                                        (when-not (and (integer? statement-timeout-ms)
                                                       (<= 1 statement-timeout-ms 2147483647))
                                          (throw (ex-info "Invalid source statement deadline" {})))
                                        ;; SET LOCAL does not establish an MVCC snapshot before LOCK.
                                        (jdbc/execute! connection
                                                       [(str "SET LOCAL statement_timeout = '"
                                                             statement-timeout-ms "ms'")])))}
                      (fn [connection token]
                        (check!)
                        (let [policy-context (certificate/verify-policy-context! connection tables contract check!)
                              fixed (storage/fresh-storage description connection)
                              authorize! (tenancy/browser-source-policy fixed)
                              _ (when-not (identical? registration (tenancy/source-replay-policy-info authorize!))
                                  (throw (ex-info "Source policy changed" {:type ::policy-changed})))
                              active? (atom true)
                              scope {:controller controller :storage fixed :connection connection :active? active?
                                     :revision token :policy-context policy-context
                                     :branch (:branch-id description) :registration registration
                                     :authorize! authorize! :check! check! :staged staged :charged (atom 0)}]
                          (binding [*snapshot* scope]
                            (try
                              (storage/call-with-fresh-memos
                                #(grouped/call-with-snapshot-cache connection (fn [] (consume fixed authorize!))))
                              (finally (reset! active? false)))))))))]
            (check!)
            (publish! controller @staged)
            result)
          (finally (reset! staged []) (locking cache (swap! cache update :pending dec))))))))


(defn capture
  "The manifest collector alone calls this boundary. Reuse still performs the
   supplied fresh authorization/contract pass before returning a private copy."
  [source-storage roots authorize! run validate!]
  (let [scope *snapshot*]
    (when (and scope (not @(:active? scope)))
      (throw (ex-info "Source snapshot expired" {:type ::expired})))
    (if-not (and scope (identical? source-storage (:storage scope))
                 (identical? authorize! (:authorize! scope))
                 (identical? (:registration scope) (tenancy/source-replay-policy-info authorize!))
                 (<= 1 (count roots) 8) (every? uuid? roots))
      (run)
      (let [{:keys [controller connection check! staged charged]} scope
            {:keys [cache limits]} controller
            key [(:branch scope) (:generation (:registration scope)) (:revision scope) (:policy-context scope) (set roots)]
            _ (check!)
            entry (locking cache
                    (swap! cache prune (now-ms))
                    (when-not (:closed? @cache) (get-in @cache [:entries key])))]
        (if entry
          (if (locking charged
                (when (<= (+ @charged (:charge entry)) (:max-capture-bytes limits))
                  (swap! charged + (:charge entry)) true))
            (let [graph (copy/thaw (:graph entry) connection)]
              (check!) (validate! graph) (check!) graph)
            (run))
          (let [graph (run)]
            (check!)
            (when-not (:closed? @cache)
              (try
                (locking charged
                  (let [frozen (copy/freeze graph (max 1 (- (:max-capture-bytes limits) @charged)) (:max-rows limits))]
                    (swap! charged + (copy/charge-bytes frozen))
                    (swap! staged conj [key frozen])))
                (catch clojure.lang.ExceptionInfo error
                  (when-not (contains? #{::copy/ineligible
                                         :graphden.executor.source-recipe-values/ineligible}
                                       (:type (ex-data error)))
                    (throw error)))))
            graph))))))
