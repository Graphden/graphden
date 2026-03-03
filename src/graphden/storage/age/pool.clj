(ns graphden.storage.age.pool
  "HikariCP connection pool management for AGE storage.
   Reuses the same pool implementation as postgres-storage."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.storage.protocol.core :as sp])
  (:import
    (com.zaxxer.hikari
      HikariConfig
      HikariDataSource)))


(defn- validate-pool-options!
  "Validates connection pool options. Throws on invalid configuration."
  [{:keys [jdbc-url username password pool-size min-idle
           connection-timeout idle-timeout max-lifetime]}]
  (when-not jdbc-url
    (throw (ex-info "jdbc-url is required for AGE connection pool"
                    {:type :config-error/missing-jdbc-url})))
  (when-not (string? jdbc-url)
    (throw (ex-info "jdbc-url must be a string"
                    {:type :config-error/invalid-jdbc-url
                     :jdbc-url-type (type jdbc-url)})))
  (when-not (str/starts-with? jdbc-url "jdbc:postgresql://")
    (throw (ex-info "jdbc-url must start with 'jdbc:postgresql://' for AGE connections"
                    {:type :config-error/invalid-jdbc-url
                     :hint "Apache AGE runs on PostgreSQL"})))
  (when-not (and username (seq (str/trim username)))
    (throw (ex-info "username is required and cannot be empty"
                    {:type :config-error/missing-username})))
  (when-not (and password (seq (str/trim password)))
    (throw (ex-info "password is required and cannot be empty"
                    {:type :config-error/missing-password})))
  (sp/validate-jdbc-url! jdbc-url)
  (sp/validate-credentials! username password)
  (when-not (pos-int? pool-size)
    (throw (ex-info "pool-size must be a positive integer"
                    {:type :config-error/invalid-pool-size
                     :pool-size pool-size})))
  (when (> pool-size 100)
    (throw (ex-info "pool-size exceeds maximum allowed value of 100"
                    {:type :config-error/invalid-pool-size
                     :pool-size pool-size
                     :max-allowed 100})))
  (when-not (pos-int? min-idle)
    (throw (ex-info "min-idle must be a positive integer"
                    {:type :config-error/invalid-min-idle
                     :min-idle min-idle})))
  (when (> min-idle pool-size)
    (throw (ex-info "min-idle cannot exceed pool-size"
                    {:type :config-error/invalid-pool-config
                     :min-idle min-idle
                     :pool-size pool-size})))
  (when-not (pos-int? connection-timeout)
    (throw (ex-info "connection-timeout must be a positive integer (ms)"
                    {:type :config-error/invalid-timeout
                     :connection-timeout connection-timeout})))
  (when (and (pos? idle-timeout) (>= idle-timeout max-lifetime))
    (throw (ex-info "idle-timeout must be less than max-lifetime"
                    {:type :config-error/invalid-pool-config
                     :idle-timeout idle-timeout
                     :max-lifetime max-lifetime}))))


(defn create-pool
  "Creates a HikariCP connection pool for AGE.

   Options:
   - :jdbc-url - JDBC connection URL (required)
   - :username - database username (required)
   - :password - database password (required)
   - :pool-size - maximum pool size (default 10)
   - :min-idle - minimum idle connections (default 2)
   - :connection-timeout - connection timeout in ms (default 30000)
   - :idle-timeout - idle connection timeout in ms (default 600000)
   - :max-lifetime - maximum connection lifetime in ms (default 1800000)"
  [{:keys [jdbc-url username password pool-size min-idle
           connection-timeout idle-timeout max-lifetime leak-detection-threshold]
    :or {pool-size 10
         min-idle 2
         connection-timeout 30000
         idle-timeout 600000
         max-lifetime 1800000
         leak-detection-threshold 60000}}]
  (let [opts {:jdbc-url jdbc-url
              :username username
              :password password
              :pool-size pool-size
              :min-idle min-idle
              :connection-timeout connection-timeout
              :idle-timeout idle-timeout
              :max-lifetime max-lifetime
              :leak-detection-threshold leak-detection-threshold}]
    (validate-pool-options! opts)
    (log/info "Creating AGE connection pool" {:pool-size pool-size :min-idle min-idle})
    (let [config (HikariConfig.)]
      (HikariConfig/.setJdbcUrl config jdbc-url)
      (HikariConfig/.setUsername config username)
      (HikariConfig/.setPassword config password)
      (HikariConfig/.setMaximumPoolSize config pool-size)
      (HikariConfig/.setMinimumIdle config min-idle)
      (HikariConfig/.setConnectionTimeout config connection-timeout)
      (HikariConfig/.setIdleTimeout config idle-timeout)
      (HikariConfig/.setMaxLifetime config max-lifetime)
      (HikariConfig/.setLeakDetectionThreshold config leak-detection-threshold)
      ;; AGE requires these settings for proper Cypher support
      (HikariConfig/.addDataSourceProperty config "escapeSyntaxCallMode" "callIfNoReturn")
      (HikariDataSource. config))))


(defn close-pool
  "Closes a HikariCP connection pool."
  [^HikariDataSource pool]
  (if (and pool (not (HikariDataSource/.isClosed pool)))
    (do
      (log/info "Closing AGE connection pool")
      (try
        (HikariDataSource/.close pool)
        (log/debug "AGE connection pool closed successfully")
        true
        (catch Exception e
          (log/error e "Failed to close AGE connection pool gracefully")
          false)))
    true))
