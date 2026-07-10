(ns graphden.storage.sql.pg-test
  "Tests for application-level Postgres HoneySQL bridge.

   Covers the helper layer (`graphden.storage.sql.pg`) directly,
   since the base-fns in `resources/packages/storage/pg/impls.clj`
   are one-line `defbase` shims that just pass `ctx` through."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.sql.pg :as pg]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


(defn- ctx-for-test
  "Build a minimal executor ctx with a fresh test storage. Matches the
   shape `pg/pg-query` and `pg/pg-execute` expect: `{:storage ...}`
   where storage has a `:pool` HikariDataSource."
  []
  {:storage (setup/create-test-storage)})


;; =============================================================================
;; pg-target — decorator-chain unwrap to the pool (regression)
;; =============================================================================
;; pg-target keyword-walks the storage stack, so plain maps mirroring the
;; decorator shapes exercise the exact logic without a DB. Under tenancy the
;; stack is VersionedStorage(:base-storage) → OrgScopedStorage(:base) →
;; Postgres(:pool); a single unwrap stopped at OrgScoped → "storage has no :pool".

(deftest pg-target-unwraps-nested-decorators
  (let [pool       (Object.)                   ; sentinel; pg-target returns it as-is
        pg         {:pool pool}                ; Postgres backend shape
        org-scoped {:base pg}                  ; OrgScopedStorage shape (:base)
        versioned  {:base-storage org-scoped}] ; VersionedStorage shape (:base-storage)
    (testing "a pool-bearing storage returns its pool directly"
      (is (identical? pool (#'pg/pg-target {:storage pg}))))
    (testing "one level: Versioned -> Postgres"
      (is (identical? pool (#'pg/pg-target {:storage {:base-storage pg}}))))
    (testing "two levels: Versioned -> OrgScoped -> Postgres (the tenancy stack)"
      (is (identical? pool (#'pg/pg-target {:storage versioned}))))
    (testing "no pool anywhere -> actionable throw, not an NPE"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no :pool"
            (#'pg/pg-target {:storage {:base {:base {}}}}))))))


;; =============================================================================
;; Round-trip: DDL → INSERT → SELECT → DELETE
;; =============================================================================

(deftest pg-execute-roundtrips-ddl-and-dml
  (testing "DDL returns 0; INSERT/UPDATE/DELETE return affected count"
    (let [ctx (ctx-for-test)]

      (testing "CREATE TABLE returns 0"
        (let [n (pg/pg-execute ctx
                               {:create-table :pg_demo
                                :with-columns [[:id :int [:primary-key]]
                                               [:name :text]]})]
          (is (zero? n) "DDL reports update-count 0")))

      (testing "single INSERT returns 1"
        (let [n (pg/pg-execute ctx
                               {:insert-into :pg_demo
                                :values [{:id 1 :name "alice"}]})]
          (is (= 1 n))))

      (testing "bulk INSERT reports cumulative count"
        (let [n (pg/pg-execute ctx
                               {:insert-into :pg_demo
                                :values [{:id 2 :name "bob"}
                                         {:id 3 :name "carol"}]})]
          (is (= 2 n))))

      (testing "UPDATE reports rows changed"
        (let [n (pg/pg-execute ctx
                               {:update :pg_demo
                                :set {:name "alice2"}
                                :where [:= :id 1]})]
          (is (= 1 n))))

      (testing "DELETE reports rows removed"
        (let [n (pg/pg-execute ctx
                               {:delete-from :pg_demo
                                :where [:in :id [2 3]]})]
          (is (= 2 n)))))))


(deftest pg-query-returns-unqualified-row-maps
  (testing "SELECT returns rows as {:column value} with lower-snake keys"
    (let [ctx (ctx-for-test)]
      (pg/pg-execute ctx {:create-table :pg_demo
                          :with-columns [[:id :int [:primary-key]]
                                         [:full_name :text]]})
      (pg/pg-execute ctx {:insert-into :pg_demo
                          :values [{:id 1 :full_name "alice anderson"}
                                   {:id 2 :full_name "bob baker"}]})

      (testing "vector return + map rows"
        (let [rows (pg/pg-query ctx {:select [:id :full_name]
                                     :from :pg_demo
                                     :order-by [:id]})]
          (is (vector? rows) "rows realised eagerly, not a lazy seq")
          (is (= 2 (count rows)))
          (is (= {:id 1 :full_name "alice anderson"} (first rows)))
          (is (= {:id 2 :full_name "bob baker"} (second rows)))))

      (testing "parameterised WHERE works"
        (let [rows (pg/pg-query ctx {:select [:full_name]
                                     :from :pg_demo
                                     :where [:= :id 2]})]
          (is (= [{:full_name "bob baker"}] rows))))

      (testing "empty result set is empty vector"
        (let [rows (pg/pg-query ctx {:select [:*]
                                     :from :pg_demo
                                     :where [:= :id 999]})]
          (is (= [] rows)))))))


;; =============================================================================
;; Error handling
;; =============================================================================

(deftest invalid-honeysql-raises-typed-error
  (testing "honeysql/format failure surfaces as :storage-pg/invalid-honeysql"
    (let [ctx (ctx-for-test)
          ;; honey.sql/format wants a map (or vector expression). A raw
          ;; string fails IllegalArgumentException — exactly the path
          ;; the wrapper exists to catch.
          ex (try
               (pg/pg-query ctx "not a honeysql map")
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "honeysql rejects a non-map / non-vector input")
      (is (= :storage-pg/invalid-honeysql (:type (ex-data ex)))
          "wrapper tag is set so callers can distinguish parse vs execute errors"))))


(deftest sql-execution-error-propagates
  (testing "Postgres errors come back as plain SQLException; we don't swallow them"
    (let [ctx (ctx-for-test)]
      (is (thrown? java.sql.SQLException
            (pg/pg-query ctx {:select [:*] :from :nonexistent_table})))
      (is (thrown? java.sql.SQLException
            (pg/pg-execute ctx {:insert-into :nonexistent_table
                                :values [{:x 1}]}))))))


(deftest missing-storage-raises-typed-error
  (testing "ctx without :storage produces an actionable error, not NPE"
    (let [ex (try
               (pg/pg-query {} {:select [:*] :from :anything})
               nil
               (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex))
      (is (= :storage-pg/no-storage (:type (ex-data ex)))))))


;; =============================================================================
;; VersionedStorage wrap is unwrapped to find the pool
;; =============================================================================

(deftest versioned-storage-pool-unwrap
  (testing "ctx whose :storage is VersionedStorage (has :base-storage) still works"
    (let [base (setup/create-test-storage)
          wrapped {:base-storage base}     ; minimal VersionedStorage stand-in
          ctx {:storage wrapped}]
      (pg/pg-execute ctx {:create-table :pg_demo
                          :with-columns [[:id :int]]})
      (pg/pg-execute ctx {:insert-into :pg_demo :values [{:id 42}]})
      (is (= [{:id 42}]
             (pg/pg-query ctx {:select [:id] :from :pg_demo}))))))


;; =============================================================================
;; pg-tx — transactions
;; =============================================================================

(deftest pg-tx-commits-on-normal-return
  (testing "All ops in body share one connection and persist on normal return"
    (let [ctx (ctx-for-test)]
      (pg/pg-execute ctx {:create-table :pg_demo
                          :with-columns [[:id :int [:primary-key]]
                                         [:name :text]]})
      (let [result (pg/pg-tx ctx
                             (fn []
                               (pg/pg-execute ctx {:insert-into :pg_demo
                                                   :values [{:id 1 :name "alice"}]})
                               (pg/pg-execute ctx {:insert-into :pg_demo
                                                   :values [{:id 2 :name "bob"}]})
                               :ok))]
        (is (= :ok result) "pg-tx returns body's last value"))
      (is (= [{:id 1 :name "alice"} {:id 2 :name "bob"}]
             (pg/pg-query ctx {:select [:id :name]
                               :from :pg_demo
                               :order-by [:id]}))
          "both inserts committed"))))


(deftest pg-tx-rolls-back-on-throw
  (testing "Body throws → none of the ops persist"
    (let [ctx (ctx-for-test)]
      (pg/pg-execute ctx {:create-table :pg_demo
                          :with-columns [[:id :int [:primary-key]]
                                         [:name :text]]})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
            (pg/pg-tx ctx
                      (fn []
                        (pg/pg-execute ctx {:insert-into :pg_demo
                                            :values [{:id 1 :name "alice"}]})
                        (throw (ex-info "boom" {:type :test/boom})))))
          "exception propagates out of pg-tx")
      (is (zero? (-> (pg/pg-query ctx {:select [:%count.*] :from :pg_demo})
                     first
                     :count))
          "alice insert rolled back — table empty"))))


(deftest pg-tx-shares-connection-across-body
  (testing "Inside body *tx-connection* is non-nil; both pg-query and pg-execute see it"
    (let [ctx (ctx-for-test)]
      (pg/pg-execute ctx {:create-table :pg_demo
                          :with-columns [[:id :int]]})
      (is (nil? pg/*tx-connection*) "no tx outside")
      (pg/pg-tx ctx
                (fn []
                  (is (some? pg/*tx-connection*)
                      "inside body the dynamic var is bound to a Connection")
                  (let [conn-before pg/*tx-connection*]
                    (pg/pg-execute ctx {:insert-into :pg_demo :values [{:id 1}]})
                    (is (identical? conn-before pg/*tx-connection*)
                        "var doesn't change across nested calls"))))
      (is (nil? pg/*tx-connection*) "tx-binding cleaned up after body"))))


(deftest pg-tx-nested-reuses-outer-transaction
  (testing "Inner pg-tx doesn't open its own — outer commit covers both"
    (let [ctx (ctx-for-test)]
      (pg/pg-execute ctx {:create-table :pg_demo
                          :with-columns [[:id :int [:primary-key]]]})
      (pg/pg-tx ctx
                (fn []
                  (let [outer-conn pg/*tx-connection*]
                    (pg/pg-execute ctx {:insert-into :pg_demo :values [{:id 1}]})
                    (pg/pg-tx ctx
                              (fn []
                                (is (identical? outer-conn pg/*tx-connection*)
                                    "nested pg-tx reuses outer connection")
                                (pg/pg-execute ctx {:insert-into :pg_demo :values [{:id 2}]}))))))
      (is (= [{:id 1} {:id 2}]
             (pg/pg-query ctx {:select [:id] :from :pg_demo :order-by [:id]}))
          "both inserts committed via outer transaction"))))
