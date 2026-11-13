(ns ^:integration ^:serial graphden.storage.postgres.source-revision-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.revision-graph-snapshot :as graph-snapshot]
    [graphden.storage.postgres.source-revision :as revision]
    [graphden.storage.postgres.source-revision-snapshot :as snapshot]
    [graphden.storage.postgres.test-setup :as setup]
    [next.jdbc :as jdbc]))


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
        (jdbc/execute! ds ["DROP TABLE revision_fixture"])
        (jdbc/execute! ds ["DROP FUNCTION public.graphden_source_revision_bump() CASCADE"])
        (jdbc/execute! ds ["DROP TABLE public.graphden_source_revision"])))))


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
      (is (= committed (revision/current *datasource*)) "Reinstallation never resets generation or revision")
      (jdbc/execute! *datasource* ["TRUNCATE revision_fixture"])
      (is (= (inc (:revision committed)) (:revision (revision/current *datasource*)))))))


(deftest concurrent-writes-cannot-bypass-the-revision-lock
  (let [before (revision/current *datasource*)]
    (jdbc/with-transaction [first-writer *datasource*]
                           (jdbc/execute! first-writer ["INSERT INTO revision_fixture VALUES(101,'first')"])
                           (with-open [second-writer (jdbc/get-connection *datasource*)]
                             (jdbc/execute! second-writer ["SET lock_timeout='100ms'"])
                             (try
                               (jdbc/execute! second-writer ["INSERT INTO revision_fixture VALUES(102,'second')"])
                               (is false "Second writer must wait for the uncommitted revision")
                               (catch java.sql.SQLException error
                                 (is (= "55P03" (java.sql.SQLException/.getSQLState error)))))))
    (is (= (inc (:revision before)) (:revision (revision/current *datasource*))))
    (is (empty? (jdbc/execute! *datasource* ["SELECT * FROM revision_fixture WHERE id=102"]))
        "Failed revision acquisition aborts the source insert too")
    (jdbc/execute! *datasource* ["INSERT INTO revision_fixture VALUES(102,'second')"])
    (is (= (+ 2 (:revision before)) (:revision (revision/current *datasource*))))))


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


(deftest graph-evidence-binds-source-scope-and-private-values
  (let [scope {:branch "fixture" :org "fixture-org"}
        date (java.util.Date. 1000)
        loads (atom 0)
        calls (atom 0)
        evidence (graph-snapshot/capture!
                   *datasource* ["revision_fixture"] scope
                   {:max-bytes 1048576 :max-rows 10}
                   (fn [connection]
                     (swap! loads inc)
                     {:fns [(assoc (first (jdbc/execute! connection ["SELECT 1 AS id"])) :date date)]
                      :slots [] :fn-slots [] :bindings [] :list-items []}))
        use-graph (fn [_ graph]
                    (swap! calls inc)
                    (java.util.Date/.getTime (:date (first (:fns graph)))))]
    (java.util.Date/.setTime date 2000)
    (is (= {:hit? true :value 1000}
           (graph-snapshot/with-current *datasource* ["revision_fixture"] scope evidence use-graph)))
    (is (= {:hit? false}
           (graph-snapshot/with-current *datasource* ["revision_fixture"]
                                        (assoc scope :branch "other") evidence use-graph)))
    (is (= {:hit? false}
           (graph-snapshot/with-current (Object.) ["revision_fixture"] scope evidence use-graph)))
    (is (= {:hit? false}
           (graph-snapshot/with-current *datasource* ["revision_fixture"] scope {} use-graph)))
    (jdbc/execute! *datasource* ["INSERT INTO revision_fixture VALUES(301,'revision changed')"])
    (is (= {:hit? false}
           (graph-snapshot/with-current *datasource* ["revision_fixture"] scope evidence use-graph)))
    (is (= 1 @loads))
    (is (= 1 @calls) "Mismatches never reach the graph consumer")))
