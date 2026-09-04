(ns ^:integration graphden.packages.storage.migrations-test
  "The `:migration` / `:migrate` templates (storage/pg) end-to-end:
   fn-defs synced through the real loader, run through the executor
   against the test container's Postgres.

   Pins the contract SERVICES.md § Startup steps documents — a run
   applies pending migrations in order and journals them, a re-run is
   a no-op, a derived `{:append …}` list applies only the new tail,
   and a throwing migration rolls the WHOLE run back (one transaction,
   markers included)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.storage.sql.pg :as pg]
    [graphden.test-infra.exec-harness :as harness]))


(use-fixtures :once (harness/exec-fixture (str (ns-name *ns*)) ["core" "storage"]))


(defn- table-exists?
  [table]
  (some? (:t (first (pg/pg-query harness/*context*
                                 {:select [[[:to_regclass [:inline table]] :t]]})))))


(defn- journal
  "Applied migration ids, in id order — `[]` while the journal table
   itself does not exist yet (a rolled-back first run leaves none)."
  []
  (if (table-exists? "schema_migrations")
    (mapv :id (pg/pg-query harness/*context*
                           {:select [:id] :from :schema_migrations :order-by [:id]}))
    []))


(defn- columns
  [table]
  (mapv :column_name
        (pg/pg-query harness/*context*
                     {:select [:column_name]
                      :from :information_schema.columns
                      :where [:= :table_name table]
                      :order-by [:ordinal_position]})))


(deftest migrate-applies-once-in-order-and-extends-test
  (harness/sync!
    [{:name :mt-001 :parent :migration
      :args {:id "001-mt"
             :ddl {:value {:create-table [:mt_demo :if-not-exists]
                           :with-columns [[:id :int [:primary-key]]]}}}}
     {:name :mt-002 :parent :migration
      :args {:id "002-mt"
             :ddl {:value {:alter-table :mt_demo :add-column [:name :text]}}}}
     {:name :mt-run :parent :migrate :args {:migrations [:mt-001 :mt-002]}}
     ;; The journal only grows: a derived list APPENDS the new migration.
     {:name :mt-003 :parent :migration
      :args {:id "003-mt"
             :ddl {:value {:alter-table :mt_demo :add-column [:email :text]}}}}
     {:name :mt-run-v2 :parent :mt-run :args {:migrations {:append [:mt-003]}}}])

  (testing "the first run applies both, in order, and journals them"
    (is (= 1 (exec/execute-by-name harness/*context* "mt-run" {}))
        "the last migration's result — its marker insert count")
    (is (= ["001-mt" "002-mt"] (journal)))
    (is (= ["id" "name"] (columns "mt_demo"))))

  (testing "a second run is a no-op"
    (is (nil? (exec/execute-by-name harness/*context* "mt-run" {})))
    (is (= ["001-mt" "002-mt"] (journal))))

  (testing "the appended migration is the only one that runs on the derived list"
    (is (= 1 (exec/execute-by-name harness/*context* "mt-run-v2" {})))
    (is (= ["001-mt" "002-mt" "003-mt"] (journal)))
    (is (= ["id" "name" "email"] (columns "mt_demo")))))


(deftest migrate-then-listener-is-service-eligible-test
  ;; The documented service shape: `:do [migrator listener]`. The fn
  ;; must carry the listener's `:process` (the create-service guard's
  ;; marker) AND the migrator's `:db` — effects union through the
  ;; `:do` list items — and expose no start-blocking free args.
  (harness/sync!
    [{:name :ms-001 :parent :migration
      :args {:id "001-ms"
             :ddl {:value {:create-table [:ms_demo :if-not-exists]
                           :with-columns [[:id :int]]}}}}
     {:name :ms-migrate :parent :migrate :args {:migrations [:ms-001]}}
     ;; `:future` stands in for `:http-server` — same `:process` marker,
     ;; no port to bind in a test.
     {:name :ms-listener :parent :future :args {:body :ms-001}}
     {:name :ms-service :parent :do :args {:steps [:ms-migrate :ms-listener]}}
     {:name :ms-t :parent :pg-tx :args {:body :ms-001}}])
  ;; Effects union through the `:do` items. (The migrator's own
  ;; `:db :raw-sql` is asserted on `ms-t` below rather than on the
  ;; package-synced `:migrate`: the golden-clone harness registers
  ;; package fn-defs without their computed effects — a fixture
  ;; artefact; the real boot computes them, see /api/types.)
  (let [info (registry/rich-type-of-id (harness/fn-id "ms-service"))]
    (is (contains? (set (:effects info)) :process) "listener's :process reaches the :do")
    (is (empty? (:args info))
        "no free args — in particular `:if`'s `:else` must not leak out of `:migration`"))
  (is (= #{:db :raw-sql}
         (set (:effects (registry/rich-type-of-id (harness/fn-id "ms-t")))))
      "a harness-synced :pg-tx derivative carries the db effects the migrator inherits"))


(deftest migrate-rolls-back-the-whole-run-test
  (harness/sync!
    [{:name :mr-001 :parent :migration
      :args {:id "001-mr"
             :ddl {:value {:create-table [:mr_demo :if-not-exists]
                           :with-columns [[:id :int]]}}}}
     {:name :mr-bad :parent :migration
      :args {:id "002-mr"
             :ddl {:value {:alter-table :mr_no_such_table :add-column [:x :int]}}}}
     {:name :mr-run :parent :migrate :args {:migrations [:mr-001 :mr-bad]}}])

  (is (thrown? Exception (exec/execute-by-name harness/*context* "mr-run" {}))
      "the failing migration surfaces")
  (is (not (contains? (set (journal)) "001-mr"))
      "one transaction — the earlier migration's marker rolled back with it")
  (is (not (table-exists? "mr_demo"))
      "and so did its DDL"))
