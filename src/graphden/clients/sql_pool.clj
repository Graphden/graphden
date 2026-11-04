(ns graphden.clients.sql-pool
  "Bounded, executor-owned Hikari pools for caller-supplied external databases.
   A lease protects an active query while rotation, idle retirement or shutdown
   retires its pool. Pool identities are keyed digests: credentials and JDBC
   options never appear in manager keys or metrics."
  (:require
    [clojure.tools.logging :as log]
    [next.jdbc :as jdbc])
  (:import
    (com.zaxxer.hikari
      HikariConfig
      HikariDataSource)
    (java.math
      BigInteger)
    (java.nio.charset
      StandardCharsets)
    (java.security
      SecureRandom)
    (java.sql
      SQLException)
    (java.util
      UUID)
    (java.util.concurrent
      Executors
      ScheduledExecutorService
      ThreadFactory
      TimeUnit)
    (javax.crypto
      Mac)
    (javax.crypto.spec
      SecretKeySpec)))


(def ^:private defaults
  {:max-pools 32 :pool-size 2 :borrow-timeout-ms 3000
   :idle-ms 300000 :sweep-ms 60000})


(defn- digest
  [^bytes salt value]
  (let [mac (Mac/getInstance "HmacSHA256")]
    (Mac/.init mac (SecretKeySpec. salt "HmacSHA256"))
    (format "%064x" (BigInteger. 1 (Mac/.doFinal mac (String/.getBytes (pr-str value)
                                                                       StandardCharsets/UTF_8))))))


(defn- new-pool
  [{:keys [pool-size borrow-timeout-ms]} url user password]
  (let [config (HikariConfig.)]
    (HikariConfig/.setJdbcUrl config url)
    (HikariConfig/.setUsername config user)
    (HikariConfig/.setPassword config password)
    (HikariConfig/.setPoolName config (str "graphden-ext-sql-" (UUID/randomUUID)))
    (HikariConfig/.setMaximumPoolSize config pool-size)
    (HikariConfig/.setMinimumIdle config 0)
    (HikariConfig/.setConnectionTimeout config borrow-timeout-ms)
    (HikariConfig/.setIdleTimeout config 60000)
    (HikariConfig/.setMaxLifetime config 300000)
    (HikariConfig/.setInitializationFailTimeout config -1)
    ;; DISCARD ALL removes server-side prepared statements after each lease.
    ;; Keep pgjdbc from retaining references to them across borrows.
    (HikariConfig/.addDataSourceProperty config "prepareThreshold" 0)
    (HikariDataSource. config)))


(defn- retired-pools!
  "Remove idle or retired entries under the manager lock; caller closes them."
  [{:keys [state clock idle-ms]}]
  (let [now (clock)
        expired? (fn [{:keys [active retiring? last-used]}]
                   (and (zero? active)
                        (or retiring? (>= (- now last-used) idle-ms))))
        old @state
        dead (filter (fn [[_ entry]] (expired? entry)) (:entries old))]
    (when (seq dead)
      (swap! state update :entries #(apply dissoc % (map first dead))))
    (mapv (comp :pool second) dead)))


(defn reap!
  "Retire idle generations. Active leases finish before their pool closes."
  [{:keys [lock] :as manager}]
  (let [pools (locking lock (retired-pools! manager))]
    (doseq [^HikariDataSource pool pools] (HikariDataSource/.close pool))
    (count pools)))


(defn create-manager
  "Own at most `max-pools` datasource generations, each with `pool-size`
   connections. The daemon reaps idle generations; `close!` stops it."
  ([] (create-manager {}))
  ([options]
   (let [{:keys [max-pools pool-size borrow-timeout-ms idle-ms sweep-ms] :as config}
         (merge defaults options)
         _ (when-not (and (pos-int? max-pools) (pos-int? pool-size)
                          (<= 250 borrow-timeout-ms) (<= 30000 idle-ms)
                          (<= 1000 sweep-ms))
             (throw (ex-info "Invalid SQL pool limits" {:type :sql/invalid-pool-limits})))
         salt (byte-array 32)
         _ (SecureRandom/.nextBytes (SecureRandom.) salt)
         manager (assoc config :lock (Object.)
                        :state (atom {:entries {} :closed? false :paused? false})
                        :salt salt :clock (or (:clock options) #(System/currentTimeMillis)))
         ^ScheduledExecutorService scheduler
         (Executors/newSingleThreadScheduledExecutor
           (reify ThreadFactory
             (newThread
               [_ task]
               (doto (Thread. ^Runnable task "graphden-ext-sql-reaper")
                 (Thread/.setDaemon true)))))
         manager (assoc manager :scheduler scheduler)]
     (ScheduledExecutorService/.scheduleWithFixedDelay scheduler
                                                       ^Runnable (fn []
                                                                   (try (reap! manager)
                                                                        (catch Exception e
                                                                          (log/warn e "External SQL pool reaper failed"))))
                                                       sweep-ms sweep-ms TimeUnit/MILLISECONDS)
     manager)))


(defn- acquire!
  [{:keys [lock state salt clock max-pools] :as manager} scope url user password]
  (let [base (digest salt [scope url user])
        key (digest salt [base password])
        to-close (volatile! [])]
    (try
      (locking lock
        (when (:closed? @state)
          (throw (ex-info "SQL pool manager is closed" {:type :sql/pool-closed})))
        (when (:paused? @state)
          (throw (ex-info "SQL pool manager is paused" {:type :sql/pool-paused})))
        ;; A new credential generation retires its predecessor. Remove idle
        ;; predecessors before enforcing the global entry limit.
        (swap! state update :entries
               (fn [entries]
                 (into {}
                       (map (fn [[k entry]]
                              [k (if (and (not= k key) (= base (:base entry)))
                                   (assoc entry :retiring? true)
                                   entry)]))
                       entries)))
        (vswap! to-close into (retired-pools! manager))
        (let [entries (:entries @state)
              current (get entries key)]
          (when (and (nil? current) (>= (count entries) max-pools))
            (throw (ex-info "External SQL pool limit reached"
                            {:type :sql/pool-limit})))
          (let [entry (or current {:pool (new-pool manager url user password)
                                   :base base :active 0 :retiring? false})]
            (swap! state assoc-in [:entries key]
                   (-> entry (update :active inc)
                       (assoc :last-used (clock) :retiring? false)))
            [key (:pool entry)])))
      (finally
        (doseq [^HikariDataSource pool @to-close] (HikariDataSource/.close pool))))))


(defn- release!
  [{:keys [lock state clock] :as manager} key]
  (let [pools (locking lock
                (swap! state update-in [:entries key]
                       (fn [entry]
                         (when entry
                           (-> entry (update :active dec) (assoc :last-used (clock))))))
                (retired-pools! manager))]
    (doseq [^HikariDataSource pool pools] (HikariDataSource/.close pool))))


(defn with-datasource
  "Execute `f` under a lease. `scope` must identify tenant, principal and
   branch. The caller must perform effect, egress and rate checks per call."
  [manager scope url user password f]
  (let [[key pool] (acquire! manager scope url user password)]
    (try
      (f pool)
      (finally (release! manager key)))))


(defn- retire-generation!
  [{:keys [lock state salt]} scope url user password]
  (let [base (digest salt [scope url user])
        key (digest salt [base password])]
    (locking lock
      (swap! state update-in [:entries key]
             (fn [entry] (when entry (assoc entry :retiring? true)))))))


(defn with-connection
  "Borrow a PostgreSQL connection and clear session state before reuse. Even
   user SQL that sends BEGIN/SET or creates temp tables cannot affect the next
   query. A failed reset retires the whole generation; no query is retried."
  [manager scope url user password f]
  (with-datasource manager scope url user password
    (fn [ds]
      (with-open [conn (try (jdbc/get-connection ds)
                            (catch SQLException e
                              ;; A failed borrow (invalid credentials, dead
                              ;; destination or timeout) must not leave an
                              ;; unusable generation occupying the global cap.
                              (retire-generation! manager scope url user password)
                              (throw e)))]
        (let [outcome (try {:result (f conn)}
                           (catch Exception e {:error e}))
              reset-error (try
                            (jdbc/execute! conn ["ROLLBACK"])
                            (jdbc/execute! conn ["DISCARD ALL"])
                            nil
                            (catch Exception e
                              (retire-generation! manager scope url user password)
                              e))]
          (when reset-error
            (if-let [original (:error outcome)]
              (Throwable/.addSuppressed ^Throwable original reset-error)
              (throw reset-error)))
          (if-let [error (:error outcome)]
            (throw error)
            (:result outcome)))))))


(defn close!
  "Stop the manager. Active queries keep their leased pool until completion."
  [{:keys [lock state scheduler]}]
  (ScheduledExecutorService/.shutdownNow ^ScheduledExecutorService scheduler)
  (let [pools (locking lock
                (let [entries (:entries @state)
                      idle (filter (fn [[_ e]] (zero? (:active e))) entries)]
                  (reset! state {:closed? true :paused? false
                                 :entries (into {}
                                                (keep (fn [[k e]]
                                                        (when (pos? (:active e))
                                                          [k (assoc e :retiring? true)])))
                                                entries)})
                  (mapv (comp :pool second) idle)))]
    (doseq [^HikariDataSource pool pools] (HikariDataSource/.close pool))
    nil))


(defn quiesce!
  "Close external sockets before a CRaC checkpoint. The manager itself stays
   live and can create fresh pools after `resume!`."
  [{:keys [lock state]}]
  (let [pools (locking lock
                (let [entries (:entries @state)]
                  (when (some (comp pos? :active val) entries)
                    (throw (ex-info "External SQL query active during checkpoint"
                                    {:type :sql/pool-busy})))
                  (swap! state assoc :paused? true
                         :entries {})
                  (mapv (comp :pool second) entries)))]
    (doseq [^HikariDataSource pool pools] (HikariDataSource/.close pool))
    (count pools)))


(defn resume!
  [{:keys [lock state]}]
  (locking lock
    (when-not (:closed? @state)
      (swap! state assoc :paused? false))))
