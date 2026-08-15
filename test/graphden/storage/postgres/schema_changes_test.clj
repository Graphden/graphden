(ns graphden.storage.postgres.schema-changes-test
  "Tests for PostgreSQL storage schema changes: adding, renaming, destructive changes."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.storage.postgres.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.test-helpers :as th]
    [next.jdbc :as jdbc]))


(use-fixtures :once (setup/container-fixture))
(use-fixtures :each (setup/clean-db-fixture))


;; === Adding tests ===

(deftest adding-test
  (testing "adding new entity in second init"
    (let [storage (setup/create-test-storage)
          schema1 (th/make-schema)
          _ (sp/initialize storage schema1)
          schema2 (-> (mds/create-builder)
                      (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :text}})
                      (ds/add-entity :post #uuid "00000000-0000-0000-0000-000000000003"
                                     {:title {:uuid #uuid "00000000-0000-0000-0000-000000000004"
                                              :type :text}})
                      ds/build)
          changes (sp/initialize storage schema2)]
      (try
        (is (= [:post] (:created (:entities changes))))
        (is (= #{{:entity :post :field :title}} (set (:created (:fields changes)))))
        (finally
          (sp/close storage)))))

  (testing "adding new field to existing entity"
    (let [storage (setup/create-test-storage)
          schema1 (th/make-schema)
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                  :type :text}
                                           :email {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                   :type :text}})
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:entities changes))))
        (is (= [{:entity :user :field :email}] (:created (:fields changes))))
        (finally
          (sp/close storage)))))

  (testing "adding new enum"
    (let [storage (setup/create-test-storage)
          schema1 (th/make-schema)
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :enum-name :status
                                  :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                                  :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                                 :value :active}])
          changes (sp/initialize storage schema2)]
      (try
        (is (= [:status] (:created (:enums changes))))
        (is (= [{:enum :status :value :active}] (:created (:enum-values changes))))
        (finally
          (sp/close storage)))))

  (testing "adding new enum value"
    (let [storage (setup/create-test-storage)
          schema1 (th/make-schema :enum-name :status
                                  :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                                  :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                                 :value :active}])
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :enum-name :status
                                  :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                                  :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                                 :value :active}
                                                {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                                 :value :inactive}])
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:enums changes))))
        (is (= [{:enum :status :value :inactive}] (:created (:enum-values changes))))
        (finally
          (sp/close storage))))))


;; === Renaming tests ===

(deftest renaming-test
  (testing "renaming entity (same UUID, different name) is tracked"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (th/make-schema :entity-name :user
                                  :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :entity-name :person
                                  :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:entities changes))))
        (is (= {:user :person} (:renamed (:entities changes))))
        (finally
          (sp/close storage)))))

  (testing "renaming field (same UUID, different name) is tracked"
    (let [storage (setup/create-test-storage)
          entity-uuid #uuid "00000000-0000-0000-0000-000000000001"
          field-uuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:name {:uuid field-uuid :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :entity-uuid entity-uuid
                                  :fields {:full-name {:uuid field-uuid :type :text}})
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:fields changes))))
        (is (= [{:entity :user :old-field :name :new-field :full-name}]
               (:renamed (:fields changes))))
        (finally
          (sp/close storage)))))

  (testing "renaming enum (same UUID, different name) is tracked"
    (let [storage (setup/create-test-storage)
          enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
          value-uuid #uuid "00000000-0000-0000-0000-000000000011"
          schema1 (th/make-schema :enum-name :status
                                  :enum-uuid enum-uuid
                                  :enum-values [{:uuid value-uuid :value :active}])
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :enum-name :state
                                  :enum-uuid enum-uuid
                                  :enum-values [{:uuid value-uuid :value :active}])
          changes (sp/initialize storage schema2)]
      (try
        (is (= [] (:created (:enums changes))))
        (is (= {:status :state} (:renamed (:enums changes))))
        (finally
          (sp/close storage))))))


;; === Destructive changes tests ===

;; === Rollback-tolerant removal contract (P0.1 / 2026-08-06 outage class) ===
;;
;; An item the DB knows that the current schema no longer declares is LEFT in
;; place and LOGGED, never thrown on — otherwise an OLD image booting against a
;; DB a NEWER image already migrated (a rolled-back deploy) crashes at "Building
;; schema" and forces a `DROP SCHEMA` recovery. Intentional DROPs still go
;; through the explicit `retire-field` path (covered elsewhere). These assert
;; the tolerant path: re-init with something removed SUCCEEDS and prior data
;; survives.

(deftest removals-are-rollback-tolerant-test
  (testing "removing an entity does NOT throw — the old table is left in place"
    (let [storage (setup/create-test-storage)
          schema1 (-> (mds/create-builder)
                      (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                     {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                             :type :text}})
                      (ds/add-entity :post #uuid "00000000-0000-0000-0000-000000000003"
                                     {:title {:uuid #uuid "00000000-0000-0000-0000-000000000004"
                                              :type :text}})
                      ds/build)
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema)]
      (try
        (is (some? (sp/initialize storage schema2))
            "re-init with :post dropped from the schema must not throw")
        (finally
          (sp/close storage)))))

  (testing "removing a field does NOT throw AND the entity's data survives"
    (let [storage (setup/create-test-storage)
          schema1 (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000000002"
                                                  :type :text}
                                           :email {:uuid #uuid "00000000-0000-0000-0000-000000000003"
                                                   :type :text}})
          _ (sp/initialize storage schema1)
          eid #uuid "00000000-0000-0000-0000-0000000000aa"
          _ (sp/upsert-entities storage :user [{:id eid :name "Ada" :email "ada@x.io"}])
          schema2 (th/make-schema)]
      (try
        (is (some? (sp/initialize storage schema2))
            "re-init with :email dropped from the schema must not throw")
        ;; the row (and its still-declared :name) survive the tolerant re-init
        (is (= "Ada" (:name (sp/read-entity storage :user eid)))
            "prior data must survive — the migration never dropped the column/row")
        (finally
          (sp/close storage)))))

  (testing "removing an enum does NOT throw"
    (let [storage (setup/create-test-storage)
          schema1 (th/make-schema :enum-name :status
                                  :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                                  :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                                 :value :active}])
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema)]
      (try
        (is (some? (sp/initialize storage schema2))
            "re-init with the enum dropped from the schema must not throw")
        (finally
          (sp/close storage)))))

  (testing "removing an enum value does NOT throw"
    (let [storage (setup/create-test-storage)
          schema1 (th/make-schema :enum-name :status
                                  :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                                  :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                                 :value :active}
                                                {:uuid #uuid "00000000-0000-0000-0000-000000000012"
                                                 :value :inactive}])
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :enum-name :status
                                  :enum-uuid #uuid "00000000-0000-0000-0000-000000000010"
                                  :enum-values [{:uuid #uuid "00000000-0000-0000-0000-000000000011"
                                                 :value :active}])]
      (try
        (is (some? (sp/initialize storage schema2))
            "re-init with an enum value dropped from the schema must not throw")
        (finally
          (sp/close storage))))))


;; === Enum migration tests ===

(deftest enum-migration-test
  (testing "adding enum during migration (not first-time init)"
    (let [storage (setup/create-test-storage)]
      (try
        ;; First initialize WITHOUT enums
        (let [schema1 (th/make-schema :entity-name :user
                                      :entity-uuid #uuid "00000000-0000-0000-0000-000000006001"
                                      :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000006002"
                                                      :type :text}})]
          (sp/initialize storage schema1))
        ;; Now add an enum in the second initialize
        (let [schema2 (-> (mds/create-builder)
                          (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000006010"
                                       [{:uuid #uuid "00000000-0000-0000-0000-000000006011"
                                         :value :active}
                                        {:uuid #uuid "00000000-0000-0000-0000-000000006012"
                                         :value :inactive}])
                          (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000006001"
                                         {:name {:uuid #uuid "00000000-0000-0000-0000-000000006002"
                                                 :type :text}})
                          ds/build)
              changes (sp/initialize storage schema2)]
          ;; Verify enum was created during migration
          (is (= [:status] (:created (:enums changes))))
          (is (= #{{:enum :status :value :active}
                   {:enum :status :value :inactive}}
                 (set (:created (:enum-values changes)))))
          ;; Verify enum exists in database
          (is (contains? (sp/current-enums storage) :status)))
        (finally
          (sp/close storage))))))


;; === Type widening ===

(deftest widen-scalar-to-jsonb-test
  (testing "widening a scalar column to :jsonb succeeds + preserves data"
    (let [storage (setup/create-test-storage)
          cuuid #uuid "00000000-0000-0000-0000-0000000000c0"
          nuuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (th/make-schema :fields {:name {:uuid nuuid :type :text}
                                           :count {:uuid cuuid :type :int}})
          _ (sp/initialize storage schema1)
          _ (sp/create-entity storage :user {:name "alice" :count 7})
          ;; Widen :count :int -> :jsonb (blessed as a safe widening).
          schema2 (th/make-schema :fields {:name {:uuid nuuid :type :text}
                                           :count {:uuid cuuid :type :jsonb}})]
      (try
        ;; Before the fix this emitted `ALTER ... USING count::jsonb`, which
        ;; PostgreSQL cannot cast from bigint -> the whole migration aborts.
        ;; `to_jsonb(count)` makes the widening actually work.
        (sp/initialize storage schema2)
        (let [rows (sp/query-entities storage :user {})]
          (is (= 1 (count rows)) "row survived the widening")
          (is (= 7 (:count (first rows))) "int value preserved through the jsonb widening"))
        (finally
          (sp/close storage))))))


(deftest drop-not-null-on-nullable-flip-test
  (testing "flipping a field NOT NULL → nullable drops the DB NOT NULL constraint"
    (let [storage (setup/create-test-storage)
          nuuid #uuid "00000000-0000-0000-0000-000000000002"
          euuid #uuid "00000000-0000-0000-0000-0000000000e0"
          ;; email starts NOT NULL (no :nullable? key → defaults to false).
          schema1 (th/make-schema :fields {:name  {:uuid nuuid :type :text}
                                           :email {:uuid euuid :type :text}})
          _ (sp/initialize storage schema1)
          ;; Flip email to nullable.
          schema2 (th/make-schema :fields {:name  {:uuid nuuid :type :text}
                                           :email {:uuid euuid :type :text :nullable? true}})]
      (try
        (sp/initialize storage schema2)
        ;; Before the fix the column stayed NOT NULL, so this nil write — which
        ;; the migrated schema now permits — failed at the DB. `DROP NOT NULL`
        ;; reconciles the column to the schema.
        (is (some? (sp/create-entity storage :user {:name "alice" :email nil}))
            "a nil write the schema now permits succeeds")
        (finally
          (sp/close storage))))))


;; === Index maintenance across init/migration passes ===

(defn- index-names-on
  "Set of PG index names currently on `table` (via pg_indexes)."
  [storage table]
  (into #{}
        (map :pg_indexes/indexname)
        (jdbc/execute! (:pool storage)
                       ["SELECT indexname FROM pg_indexes WHERE tablename = ?"
                        table])))


(deftest retired-index-dropped-on-migration-test
  (testing "an index named in migration/retired-indexes is dropped by the next
            initialize pass, idempotently"
    (let [storage (setup/create-test-storage)
          schema (th/make-schema)]
      (try
        (sp/initialize storage schema)
        ;; Simulate a cross-version dev DB: the retired NAME exists (its
        ;; definition is irrelevant — the drop is by name only).
        (jdbc/execute! (:pool storage)
                       ["CREATE INDEX \"idx_fn_namespace_id_name_unique\" ON \"user\" (name)"])
        (is (contains? (index-names-on storage "user")
                       "idx_fn_namespace_id_name_unique"))
        (sp/initialize storage schema)
        (is (not (contains? (index-names-on storage "user")
                            "idx_fn_namespace_id_name_unique"))
            "the migration pass drops the retired index")
        (sp/initialize storage schema)
        (is (not (contains? (index-names-on storage "user")
                            "idx_fn_namespace_id_name_unique"))
            "a further pass with the index already gone stays clean")
        (finally
          (sp/close storage))))))


(deftest indexed-field-has-index-from-first-init-test
  (testing "an :indexed? field's index exists right after FIRST init — not only
            after the next boot's migration pass"
    (let [storage (setup/create-test-storage)
          nuuid #uuid "00000000-0000-0000-0000-000000000002"
          schema (th/make-schema :fields {:name {:uuid nuuid :type :text
                                                 :indexed? true}})]
      (try
        (sp/initialize storage schema)
        (is (contains? (index-names-on storage "user") "idx_user_name")
            "fresh DB carries the declared index immediately")
        (finally
          (sp/close storage))))))


(deftest newly-flagged-indexed-field-gains-index-on-migration-test
  (testing ":indexed? added to an EXISTING table's field lands on
            already-migrated DBs (ensure-field-indexes! gap-close)"
    (let [storage (setup/create-test-storage)
          nuuid #uuid "00000000-0000-0000-0000-000000000002"
          schema1 (th/make-schema :fields {:name {:uuid nuuid :type :text}})
          _ (sp/initialize storage schema1)
          schema2 (th/make-schema :fields {:name {:uuid nuuid :type :text
                                                  :indexed? true}})]
      (try
        (is (not (contains? (index-names-on storage "user") "idx_user_name"))
            "no index before the flag lands")
        (sp/initialize storage schema2)
        (is (contains? (index-names-on storage "user") "idx_user_name")
            "the migration pass creates the newly-declared index")
        (finally
          (sp/close storage))))))


(deftest enum-value-rename-test
  (testing "an enum VALUE keyword change under a stable uuid renames the
            pg label (uuid is the identity — same contract as
            entity/field renames; previously a silent no-op that left
            metadata claiming the new label while pg kept the old)"
    (let [storage (setup/create-test-storage)]
      (try
        (let [schema1 (-> (mds/create-builder)
                          (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000007010"
                                       [{:uuid #uuid "00000000-0000-0000-0000-000000007011"
                                         :value :actve}]) ; typo release
                          (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000007001"
                                         {:name {:uuid #uuid "00000000-0000-0000-0000-000000007002"
                                                 :type :text}})
                          ds/build)
              _ (sp/initialize storage schema1)
              schema2 (-> (mds/create-builder)
                          (ds/add-enum :status #uuid "00000000-0000-0000-0000-000000007010"
                                       [{:uuid #uuid "00000000-0000-0000-0000-000000007011"
                                         :value :active}]) ; fixed
                          (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000007001"
                                         {:name {:uuid #uuid "00000000-0000-0000-0000-000000007002"
                                                 :type :text}})
                          ds/build)
              changes (sp/initialize storage schema2)]
          (is (= [{:enum :status :old :actve :new :active}]
                 (:renamed (:enum-values changes))))
          ;; The pg label really changed — re-running the migration is a
          ;; no-op (uuid matches, keyword matches).
          (let [changes3 (sp/initialize storage schema2)]
            (is (empty? (:renamed (:enum-values changes3))))))
        (finally (sp/close storage))))))


(deftest retired-field-dropped-and-uuid-resolved-test
  (testing "retire-field drops the column on migration"
    (let [storage (setup/create-test-storage)]
      (try
        (let [schema1 (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000008002"
                                                      :type :text}
                                               :flag {:uuid #uuid "00000000-0000-0000-0000-000000008003"
                                                      :type :text
                                                      :nullable? true}})
              _ (sp/initialize storage schema1)
              schema2 (-> (mds/create-builder)
                          (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                         {:name {:uuid #uuid "00000000-0000-0000-0000-000000008002"
                                                 :type :text}})
                          (ds/retire-field :user :flag #uuid "00000000-0000-0000-0000-000000008003")
                          ds/build)
              _ (sp/initialize storage schema2)
              cols (->> (jdbc/execute! (:pool storage)
                                       ["select column_name from information_schema.columns where table_name = 'user'"])
                        (map :columns/column_name)
                        set)]
          (is (not (contains? cols "flag"))
              "retired column is DROPped on the migration path"))
        (finally (sp/close storage)))))

  (testing "the drop resolves the CURRENT column name from the tombstone
            uuid when a rename release was skipped"
    (let [storage (setup/create-test-storage)]
      (try
        (let [schema1 (th/make-schema :fields {:name {:uuid #uuid "00000000-0000-0000-0000-000000008012"
                                                      :type :text}
                                               :old-flag {:uuid #uuid "00000000-0000-0000-0000-000000008013"
                                                          :type :text
                                                          :nullable? true}})
              _ (sp/initialize storage schema1)
              ;; The tombstone declares the NEW name the column never
              ;; had on this deployment (the rename release was
              ;; skipped) — uuid resolution must find :old-flag.
              schema2 (-> (mds/create-builder)
                          (ds/add-entity :user #uuid "00000000-0000-0000-0000-000000000001"
                                         {:name {:uuid #uuid "00000000-0000-0000-0000-000000008012"
                                                 :type :text}})
                          (ds/retire-field :user :new-flag #uuid "00000000-0000-0000-0000-000000008013")
                          ds/build)
              _ (sp/initialize storage schema2)
              cols (->> (jdbc/execute! (:pool storage)
                                       ["select column_name from information_schema.columns where table_name = 'user'"])
                        (map :columns/column_name)
                        set)]
          (is (not (contains? cols "old_flag"))
              "the STALE-named column is dropped via uuid resolution"))
        (finally (sp/close storage))))))
