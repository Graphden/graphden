(ns ^:integration graphden.crac-test
  "The CRaC quiesce/resume cycle (`graphden.crac`) against REAL DB resources.
   We don't run an actual checkpoint here (that needs a CRaC JDK + CRIU — see
   development/crac/); we validate the risky part: that closing the pool +
   LISTEN + advisory-lock connections and then resuming re-establishes them so
   the system keeps working. If quiesce!→resume! is correct, the CRaC Resource
   that wraps them (and the tens-of-ms restore proven in development/crac/) is
   correct too."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crac :as crac]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.postgres.advisory-lock :as pg-lock]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.postgres.notify :as pg-notify]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [next.jdbc :as jdbc])
  (:import
    (java.sql
      Connection)))


(use-fixtures :once (setup/create-container-fixture) exec/with-clean-registry)


(defn- wait-for
  [ms pred]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (pred)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 50)
            (recur))))))


(deftest quiesce-then-resume-cycles-db-connections
  (let [container @(resolve 'graphden.executor.test-setup/*container*)
        cfg (pth/get-container-config container)
        storage (pg/create-storage cfg)
        listener (pg-notify/create-listener cfg)
        holder (pg-lock/create-lock-holder cfg)
        system {:db/postgres storage
                :db/notify-listener listener
                :db/service-locks holder}
        query-one (fn [] (:one (jdbc/execute-one! (:pool storage) ["SELECT 1 AS one"])))]
    (try
      (testing "every resource works before the checkpoint quiesce"
        (is (= 1 (query-one)) "pool serves a query")
        (is (false? (Connection/.isClosed (pg-lock/holder-conn holder)))
            "advisory-lock connection open")
        (is (false? (Connection/.isClosed @(:conn-atom listener)))
            "LISTEN connection open"))

      (testing "quiesce! closes the sockets CRIU can't snapshot"
        (crac/quiesce! system)
        (is (Connection/.isClosed (pg-lock/holder-conn holder))
            "advisory-lock connection closed for checkpoint")
        (is (Connection/.isClosed @(:conn-atom listener))
            "LISTEN connection closed for checkpoint"))

      (testing "resume! re-establishes everything — the system works again"
        (crac/resume! system)
        (is (= 1 (query-one)) "pool serves queries after resume (suspend lifted)")
        (is (false? (Connection/.isClosed (pg-lock/holder-conn holder)))
            "advisory-lock reconnected by resume!")
        (is (wait-for 6000 #(false? (Connection/.isClosed @(:conn-atom listener))))
            "LISTEN loop self-reconnected after restore"))

      (finally
        (pg-notify/close-listener! listener)
        (pg-lock/close-holder! holder)
        (sp/close storage)))))
