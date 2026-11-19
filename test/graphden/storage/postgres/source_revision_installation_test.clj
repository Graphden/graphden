(ns ^:integration ^:serial graphden.storage.postgres.source-revision-installation-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.schema.malli.core :as malli]
    [graphden.schema.versioned.schema :as versioned]
    [graphden.storage.postgres.core :as postgres]
    [graphden.storage.postgres.migration :as migration]
    [graphden.storage.postgres.source-revision :as revision]
    [graphden.storage.postgres.source-revision-roster :as roster]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.protocol.core :as storage]))


(use-fixtures :once (setup/container-fixture))


(deftest opt-in-initialization-publishes-only-committed-installations
  (let [schema (versioned/build-schema (malli/create-builder))
        database (postgres/create-storage (assoc (setup/get-container-config) :source-revision? true))
        state (:source-revision-state database)]
    (try
      (is (nil? @state))
      (storage/initialize database schema)
      (is (= (roster/tables schema) (set (:tables @state))))
      (is (revision/roster-installed? (:pool database) (:tables @state)))
      (let [before (revision/current (:pool database))
            installation (:installation-id @state)
            initialize migration/do-initialize]
        (storage/initialize database schema)
        (is (not= installation (:installation-id @state)))
        (is (not= (:generation before) (:generation (revision/current (:pool database)))))
        (let [committed (revision/current (:pool database))]
          (with-redefs [migration/do-initialize
                        (fn [connection source-schema]
                          (initialize connection source-schema)
                          (throw (ex-info "Injected after schema DDL" {})))]
            (is (thrown? clojure.lang.ExceptionInfo (storage/initialize database schema))))
          (is (nil? @state) "Failed reinitialization revokes the runtime controller")
          (is (= committed (revision/current (:pool database))) "Outer maintenance transaction rolled back")))
      (finally (storage/close database)))))
