(ns ^:integration ^:serial graphden.storage.postgres.source-revision-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.storage.postgres.source-revision :as revision]
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


(deftest invalid-roster-is-refused-before-sql
  (is (thrown? clojure.lang.ExceptionInfo (revision/install! nil [])))
  (is (thrown? clojure.lang.ExceptionInfo
        (revision/install! nil ["revision_fixture; DROP TABLE revision_fixture"]))))
