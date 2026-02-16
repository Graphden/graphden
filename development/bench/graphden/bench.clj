(ns graphden.bench
  "Performance benchmarks for graphden storage operations.

   Usage:
     clj -M:bench -m graphden.bench

   Options:
     --quick    Run quick benchmarks (fewer samples)"
  (:require
    [criterium.core :as crit]
    [graphden.graph-data-schema.interface :as gds]
    [graphden.graph-storage-age.interface :as age]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.storage-protocol.interface :as sp])
  (:import
    (org.testcontainers.containers
      PostgreSQLContainer)
    (org.testcontainers.utility
      DockerImageName)))


;; Apache AGE Docker image
(def age-image-name
  (-> (DockerImageName/parse "apache/age:latest")
      (DockerImageName/.asCompatibleSubstituteFor "postgres")))


;; === Test schema ===

(defn create-test-schema
  "Creates the standard graph schema for benchmarks."
  []
  (gds/build-schema (mds/create-builder)))


;; === Benchmark runners ===

(defn bench-age-storage
  "Benchmark Apache AGE storage operations."
  [quick?]
  (println "\n=== Apache AGE Storage Benchmarks ===\n")
  (println "Starting AGE container...")

  (let [container (doto (PostgreSQLContainer. age-image-name)
                    (PostgreSQLContainer/.withStartupAttempts 3))]
    (try
      (PostgreSQLContainer/.start container)
      (let [opts {:jdbc-url (PostgreSQLContainer/.getJdbcUrl container)
                  :username (PostgreSQLContainer/.getUsername container)
                  :password (PostgreSQLContainer/.getPassword container)
                  :pool-size 5}
            schema (create-test-schema)]

        (println "-- create-storage + initialize --")
        (if quick?
          (crit/quick-bench
            (let [storage (age/create-storage opts)]
              (sp/initialize storage schema)
              (sp/close storage)))
          (crit/bench
            (let [storage (age/create-storage opts)]
              (sp/initialize storage schema)
              (sp/close storage))))

        (println "\n-- current-entities (after init) --")
        (let [storage (age/create-storage opts)]
          (try
            (sp/initialize storage schema)
            (if quick?
              (crit/quick-bench (sp/current-entities storage))
              (crit/bench (sp/current-entities storage)))
            (finally
              (sp/close storage))))

        (println "\n-- current-fields --")
        (let [storage (age/create-storage opts)]
          (try
            (sp/initialize storage schema)
            (if quick?
              (crit/quick-bench (sp/current-fields storage :fn-schema))
              (crit/bench (sp/current-fields storage :fn-schema)))
            (finally
              (sp/close storage))))

        (println "\n-- create-entity :fn-schema --")
        (let [storage (age/create-storage opts)]
          (try
            (sp/initialize storage schema)
            (if quick?
              (crit/quick-bench
                (sp/create-entity storage :fn-schema
                                  {:name (str "bench-" (random-uuid))
                                   :returned-type :int}))
              (crit/bench
                (sp/create-entity storage :fn-schema
                                  {:name (str "bench-" (random-uuid))
                                   :returned-type :int})))
            (finally
              (sp/close storage))))

        (println "\n-- resolve-execution-graph (simple fn) --")
        (let [storage (age/create-storage opts)]
          (try
            (sp/initialize storage schema)
            (let [fn-schema (sp/create-entity storage :fn-schema
                                              {:name "bench-schema"
                                               :returned-type :int})
                  fn-entity (sp/create-entity storage :fn
                                              {:name "bench-fn"
                                               :fn-schema-id (:id fn-schema)})]
              (if quick?
                (crit/quick-bench (sp/resolve-execution-graph storage (:id fn-entity)))
                (crit/bench (sp/resolve-execution-graph storage (:id fn-entity)))))
            (finally
              (sp/close storage)))))
      (finally
        (PostgreSQLContainer/.stop container)))))


;; === Main ===

(defn parse-args
  "Parse command line arguments."
  [args]
  (let [args-set (set args)]
    {:quick? (contains? args-set "--quick")}))


(defn -main
  "Run benchmarks."
  [& args]
  (let [{:keys [quick?]} (parse-args args)]
    (println "========================================")
    (println "      Graphden Performance Benchmarks")
    (println "========================================")
    (println (str "Mode: " (if quick? "quick" "full")))
    (println "Storage: Apache AGE")

    (bench-age-storage quick?)

    (println "\n========================================")
    (println "           Benchmarks Complete")
    (println "========================================")))
