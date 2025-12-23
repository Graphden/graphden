(ns graphden.bench
  "Performance benchmarks for graphden storage operations.

   Usage:
     clj -M:bench -m graphden.bench

   Options:
     --quick    Run quick benchmarks (fewer samples)
     --storage  Benchmark specific storage (memory, postgres, datomic, all)"
  (:require
    [criterium.core :as crit]
    [graphden.data-schema-protocol.interface :as ds]
    [graphden.graph-storage-datomic.interface :as gsd]
    [graphden.graph-storage-memory.interface :as gsm]
    [graphden.graph-storage-postgres.interface :as gsp]
    [graphden.malli-data-schema.interface :as mds]
    [graphden.storage-protocol.interface :as sp])
  (:import
    (org.testcontainers.containers
      PostgreSQLContainer)))


;; === Test schema ===

(defn create-test-schema
  "Creates a test schema with multiple entities and fields."
  []
  (-> (mds/create-builder)
      (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000000001"
                   [{:uuid #uuid "00000000-0000-0000-0000-000000000002" :value :active}
                    {:uuid #uuid "00000000-0000-0000-0000-000000000003" :value :inactive}
                    {:uuid #uuid "00000000-0000-0000-0000-000000000004" :value :pending}])
      (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000010"
                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000000011" :type :text}
                      :email {:uuid #uuid "00000000-0000-0000-0000-000000000012" :type :text}
                      :age {:uuid #uuid "00000000-0000-0000-0000-000000000013" :type :int :nullable? true}
                      :status {:uuid #uuid "00000000-0000-0000-0000-000000000014"
                               :type :enum :enum-name :status}})
      (ds/add-entity :post #uuid "00000000-0000-0000-0000-000000000020"
                     {:title {:uuid #uuid "00000000-0000-0000-0000-000000000021" :type :text}
                      :content {:uuid #uuid "00000000-0000-0000-0000-000000000022" :type :text}
                      :author-id {:uuid #uuid "00000000-0000-0000-0000-000000000023"
                                  :type :ref :ref-entity :user}
                      :published {:uuid #uuid "00000000-0000-0000-0000-000000000024" :type :bool}})
      (ds/add-constraint :user {:type :unique :fields [:email]})
      (ds/build)))


;; === Benchmark runners ===

(defn bench-memory-storage
  "Benchmark memory storage operations."
  [quick?]
  (println "\n=== Memory Storage Benchmarks ===\n")

  (let [schema (create-test-schema)]

    (println "-- create-storage + initialize --")
    (if quick?
      (crit/quick-bench
        (let [storage (gsm/create-storage)]
          (sp/close storage)))
      (crit/bench
        (let [storage (gsm/create-storage)]
          (sp/close storage))))

    (println "\n-- current-entities (after init) --")
    (let [storage (gsm/create-storage)]
      (try
        (if quick?
          (crit/quick-bench (sp/current-entities storage))
          (crit/bench (sp/current-entities storage)))
        (finally
          (sp/close storage))))

    (println "\n-- current-fields --")
    (let [storage (gsm/create-storage)]
      (try
        (if quick?
          (crit/quick-bench (sp/current-fields storage :user))
          (crit/bench (sp/current-fields storage :user)))
        (finally
          (sp/close storage))))

    (println "\n-- validate-entity (valid data) --")
    (let [storage (gsm/create-storage)]
      (try
        (let [valid-user {:id (random-uuid) :name "John" :email "john@test.com"
                          :age 30 :status :active}]
          (if quick?
            (crit/quick-bench (ds/validate-entity schema :user valid-user))
            (crit/bench (ds/validate-entity schema :user valid-user))))
        (finally
          (sp/close storage))))))


(defn bench-postgres-storage
  "Benchmark PostgreSQL storage operations."
  [quick?]
  (println "\n=== PostgreSQL Storage Benchmarks ===\n")
  (println "Starting PostgreSQL container...")

  (let [container (PostgreSQLContainer. "postgres:16-alpine")]
    (try
      (PostgreSQLContainer/.start container)
      (let [opts {:jdbc-url (PostgreSQLContainer/.getJdbcUrl container)
                  :username (PostgreSQLContainer/.getUsername container)
                  :password (PostgreSQLContainer/.getPassword container)
                  :pool-size 5}]

        (println "-- create-storage + initialize --")
        (if quick?
          (crit/quick-bench
            (let [storage (gsp/create-storage opts)]
              (sp/close storage)))
          (crit/bench
            (let [storage (gsp/create-storage opts)]
              (sp/close storage))))

        (println "\n-- current-entities (after init) --")
        (let [storage (gsp/create-storage opts)]
          (try
            (if quick?
              (crit/quick-bench (sp/current-entities storage))
              (crit/bench (sp/current-entities storage)))
            (finally
              (sp/close storage))))

        (println "\n-- current-fields --")
        (let [storage (gsp/create-storage opts)]
          (try
            (if quick?
              (crit/quick-bench (sp/current-fields storage :fn-schema))
              (crit/bench (sp/current-fields storage :fn-schema)))
            (finally
              (sp/close storage))))

        (println "\n-- schema-metadata (cached) --")
        (let [storage (gsp/create-storage opts)]
          (try
            ;; Warm up cache
            (sp/schema-metadata storage)
            (if quick?
              (crit/quick-bench (sp/schema-metadata storage))
              (crit/bench (sp/schema-metadata storage)))
            (finally
              (sp/close storage)))))
      (finally
        (PostgreSQLContainer/.stop container)))))


(defn bench-datomic-storage
  "Benchmark Datomic storage operations."
  [quick?]
  (println "\n=== Datomic Storage Benchmarks ===\n")

  (println "-- create-storage + initialize --")
  (if quick?
    (crit/quick-bench
      (let [storage (gsd/create-storage {:db-name (str "bench-" (random-uuid))})]
        (sp/close storage)))
    (crit/bench
      (let [storage (gsd/create-storage {:db-name (str "bench-" (random-uuid))})]
        (sp/close storage))))

  (println "\n-- current-entities (after init) --")
  (let [storage (gsd/create-storage {:db-name (str "bench-entities-" (random-uuid))})]
    (try
      (if quick?
        (crit/quick-bench (sp/current-entities storage))
        (crit/bench (sp/current-entities storage)))
      (finally
        (sp/close storage))))

  (println "\n-- current-fields --")
  (let [storage (gsd/create-storage {:db-name (str "bench-fields-" (random-uuid))})]
    (try
      (if quick?
        (crit/quick-bench (sp/current-fields storage :fn-schema))
        (crit/bench (sp/current-fields storage :fn-schema)))
      (finally
        (sp/close storage)))))


;; === Main ===

(defn parse-args
  "Parse command line arguments."
  [args]
  (let [args-set (set args)]
    {:quick? (contains? args-set "--quick")
     :storage (cond
                (contains? args-set "--memory") :memory
                (contains? args-set "--postgres") :postgres
                (contains? args-set "--datomic") :datomic
                :else :all)}))


(defn -main
  "Run benchmarks."
  [& args]
  (let [{:keys [quick? storage]} (parse-args args)]
    (println "========================================")
    (println "      Graphden Performance Benchmarks")
    (println "========================================")
    (println (str "Mode: " (if quick? "quick" "full")))
    (println (str "Storage: " (name storage)))

    (case storage
      :memory (bench-memory-storage quick?)
      :postgres (bench-postgres-storage quick?)
      :datomic (bench-datomic-storage quick?)
      :all (do
             (bench-memory-storage quick?)
             (bench-datomic-storage quick?)
             (bench-postgres-storage quick?)))

    (println "\n========================================")
    (println "           Benchmarks Complete")
    (println "========================================")))
