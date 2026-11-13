(ns ^:integration ^:serial graphden.storage.postgres.source-revision-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.storage.postgres.source-revision :as revision]
    [graphden.storage.postgres.source-revision-snapshot :as snapshot]
    [graphden.storage.postgres.test-setup :as setup]
    [next.jdbc :as jdbc]
    [next.jdbc.result-set :as rs]))


(def ^:dynamic *datasource* nil)


(defn- fixture
  [f]
  (let [config (setup/get-container-config)
        ds (jdbc/get-datasource {:jdbcUrl (:jdbc-url config)
                                 :user (:username config) :password (:password config)})]
    (jdbc/execute! ds ["CREATE TABLE revision_fixture(id integer PRIMARY KEY, value text)"])
    (try
      (revision/install! ds ["revision_fixture"])
      (binding [*datasource* ds] (f))
      (finally
        (jdbc/execute! ds ["DROP TABLE IF EXISTS revision_fixture"])
        (jdbc/execute! ds ["DROP FUNCTION IF EXISTS public.graphden_source_revision_bump() CASCADE"])
        (jdbc/execute! ds ["DROP TABLE IF EXISTS public.graphden_source_revision"])))))


(use-fixtures :once (setup/container-fixture) fixture)


(deftest revision-is-transactional-and-snapshot-consistent
  (let [before (revision/current *datasource*)]
    (jdbc/with-transaction [reader *datasource* {:isolation :repeatable-read :read-only true}]
                           (is (= before (revision/current reader)))
                           (jdbc/execute! *datasource* ["INSERT INTO revision_fixture VALUES(1,'old')"])
                           (is (= before (revision/current reader)) "Old RR sees both old revision and old data")
                           (is (empty? (jdbc/execute! reader ["SELECT * FROM revision_fixture WHERE id=1"]))))
    (is (= (inc (:revision before)) (:revision (revision/current *datasource*))))
    (let [committed (revision/current *datasource*)]
      (jdbc/with-transaction [tx *datasource* {:rollback-only true}]
                             (jdbc/execute! tx ["UPDATE revision_fixture SET value='rolled-back'"])
                             (is (= (inc (:revision committed)) (:revision (revision/current tx)))))
      (is (= committed (revision/current *datasource*)))
      (revision/install! *datasource* ["revision_fixture"])
      (is (not= (:generation committed) (:generation (revision/current *datasource*)))
          "Reinstallation invalidates evidence from any maintenance gap")
      (is (= (:revision committed) (:revision (revision/current *datasource*))))
      (jdbc/execute! *datasource* ["TRUNCATE revision_fixture"])
      (is (= (inc (:revision committed)) (:revision (revision/current *datasource*)))))))


(defn- backend-bucket
  [connection]
  (:bucket (jdbc/execute-one! connection ["SELECT pg_backend_pid()%256 AS bucket"]
                              {:builder-fn rs/as-unqualified-lower-maps})))


(defn- independent-writer
  "Open a different counter bucket, even if backend PIDs happen to collide."
  [excluded-bucket]
  (loop [attempt 0]
    (let [connection (jdbc/get-connection *datasource*)]
      (if (not= excluded-bucket (backend-bucket connection))
        connection
        (do
          (.close connection)
          (when (= attempt 15)
            (throw (ex-info "Could not obtain independent backend bucket" {})))
          (recur (inc attempt)))))))


(deftest independent-writers-commit-out-of-order-without-a-global-mutex
  (let [before (revision/current *datasource*)]
    (jdbc/with-transaction [first-writer *datasource*]
                           (jdbc/execute! first-writer ["INSERT INTO revision_fixture VALUES(101,'first')"])
                           (with-open [second-writer (independent-writer (backend-bucket first-writer))]
                             (jdbc/execute! second-writer ["SET lock_timeout='100ms'"])
                             (jdbc/execute! second-writer ["INSERT INTO revision_fixture VALUES(102,'second')"]))
                           (is (= (inc (:revision before)) (:revision (revision/current *datasource*)))
                               "Second commit is visible while the earlier writer is still uncommitted")
                           (is (= [{:id 102}]
                                  (jdbc/execute! *datasource* ["SELECT id FROM revision_fixture WHERE id IN(101,102)"]
                                                 {:builder-fn rs/as-unqualified-lower-maps}))))
    (is (= (+ 2 (:revision before)) (:revision (revision/current *datasource*)))
        "The late first commit changes SUM too; MAX/sequence would miss this case")))


(deftest revision-refuses-incomplete-or-mixed-generation-buckets
  (jdbc/with-transaction [tx *datasource* {:rollback-only true}]
                         (jdbc/execute! tx ["DELETE FROM public.graphden_source_revision WHERE bucket=0"])
                         (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bucket coverage invalid"
                               (revision/current tx))))
  (jdbc/with-transaction [tx *datasource* {:rollback-only true}]
                         (jdbc/execute! tx ["UPDATE public.graphden_source_revision SET generation=? WHERE bucket=0"
                                            (java.util.UUID/randomUUID)])
                         (is (thrown-with-msg? clojure.lang.ExceptionInfo #"bucket coverage invalid"
                               (revision/current tx)))))


(deftest unavailable-bucket-aborts-the-source-write
  (let [before (revision/current *datasource*)]
    (with-open [writer (jdbc/get-connection *datasource*)]
      (jdbc/execute! writer ["SET lock_timeout='100ms'"])
      (jdbc/with-transaction [blocker *datasource*]
                             (jdbc/execute! blocker ["SELECT bucket FROM public.graphden_source_revision WHERE bucket=? FOR UPDATE"
                                                     (backend-bucket writer)])
                             (try
                               (jdbc/execute! writer ["INSERT INTO revision_fixture VALUES(103,'blocked')"])
                               (is false "A counter collision must wait or abort, never bypass revision")
                               (catch java.sql.SQLException error
                                 (is (= "55P03" (java.sql.SQLException/.getSQLState error)))))))
    (is (= before (revision/current *datasource*)))
    (is (empty? (jdbc/execute! *datasource* ["SELECT id FROM revision_fixture WHERE id=103"])))))


(deftest catalog-check-refuses-disabled-missing-and-changed-triggers
  (is (revision/roster-installed? *datasource* ["revision_fixture"]))
  (is (false? (revision/roster-installed? *datasource* [])))
  (is (false? (revision/roster-installed? *datasource* ["revision_fixture" "missing_source_table"])))
  (jdbc/with-transaction [tx *datasource* {:rollback-only true}]
                         (jdbc/execute! tx ["ALTER TABLE revision_fixture DISABLE TRIGGER graphden_source_revision_bump"])
                         (is (false? (revision/roster-installed? tx ["revision_fixture"]))))
  (jdbc/with-transaction [tx *datasource* {:rollback-only true}]
                         (jdbc/execute! tx ["CREATE OR REPLACE FUNCTION public.graphden_source_revision_bump() RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog AS $$ BEGIN RETURN NULL; END $$"])
                         (is (false? (revision/roster-installed? tx ["revision_fixture"]))))
  (is (revision/roster-installed? *datasource* ["revision_fixture"]) "Rollback restores the verified definition"))


(deftest owned-snapshot-pairs-loaded-data-with-its-revision
  (let [before (revision/current *datasource*)
        result (snapshot/call-with-snapshot
                 *datasource* ["revision_fixture"]
                 (fn [connection]
                   (jdbc/execute! *datasource* ["INSERT INTO revision_fixture VALUES(201,'after-snapshot')"])
                   (is (= before (revision/current connection)))
                   (jdbc/execute! connection ["SELECT * FROM revision_fixture WHERE id=201"])))]
    (is (= before (:revision result)))
    (is (empty? (:value result)))
    (is (= (inc (:revision before)) (:revision (revision/current *datasource*))))))


(deftest snapshot-never-retries-loader-errors
  (let [calls (atom 0)]
    (is (thrown? java.sql.SQLException
          (snapshot/call-with-snapshot *datasource* ["revision_fixture"]
                                       (fn [_]
                                         (swap! calls inc)
                                         (throw (java.sql.SQLException. "fixture"))))))
    (is (= 1 @calls))))


(deftest invalid-roster-is-refused-before-sql
  (is (thrown? clojure.lang.ExceptionInfo (revision/install! nil [])))
  (is (thrown? clojure.lang.ExceptionInfo (revision/install! nil ["graphden_source_revision"])))
  (is (thrown? clojure.lang.ExceptionInfo
        (revision/install! nil ["revision_fixture; DROP TABLE revision_fixture"]))))


(deftest snapshot-refuses-unverified-coverage-before-loading-data
  (let [calls (atom 0)]
    (jdbc/execute! *datasource* ["ALTER TABLE revision_fixture DISABLE TRIGGER graphden_source_revision_bump"])
    (try
      (is (thrown? clojure.lang.ExceptionInfo
            (snapshot/call-with-snapshot *datasource* ["revision_fixture"]
                                         (fn [_] (swap! calls inc)))))
      (is (zero? @calls))
      (finally
        (jdbc/execute! *datasource* ["ALTER TABLE revision_fixture ENABLE TRIGGER graphden_source_revision_bump"])))
    (with-open [connection (jdbc/get-connection *datasource*)]
      (is (thrown? clojure.lang.ExceptionInfo
            (snapshot/call-with-snapshot connection ["revision_fixture"] (constantly nil)))))))


(deftest maintenance-and-certified-readers-exclude-each-other
  (let [before (revision/current *datasource*)]
    (revision/call-with-maintenance
      *datasource*
      (fn [_]
        (jdbc/with-transaction [reader *datasource* {:isolation :repeatable-read :read-only true}]
                               (is (thrown? clojure.lang.ExceptionInfo (revision/claim-snapshot! reader))))
        (is (thrown-with-msg? java.sql.SQLException #"Source maintenance in progress"
              (jdbc/execute! *datasource* ["INSERT INTO revision_fixture VALUES(104,'maintenance')"])))))
    (is (empty? (jdbc/execute! *datasource* ["SELECT id FROM revision_fixture WHERE id=104"])))
    (is (not= (:generation before) (:generation (revision/current *datasource*))))
    (jdbc/with-transaction [reader *datasource* {:isolation :repeatable-read :read-only true}]
                           (is (true? (revision/claim-snapshot! reader)))
                           (with-open [writer (jdbc/get-connection *datasource*)]
                             (jdbc/execute! writer ["SET lock_timeout='100ms'"])
                             (try
                               (revision/call-with-maintenance writer (constantly nil))
                               (is false "Maintenance must wait until the source reader finishes")
                               (catch java.sql.SQLException error
                                 (is (= "55P03" (java.sql.SQLException/.getSQLState error)))))))))
