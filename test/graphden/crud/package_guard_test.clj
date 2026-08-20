(ns ^:serial graphden.crud.package-guard-test
  "The package-owned write guard — the server-side answer to the
   2026-08-20 :add poisoning: one editor click appended a literal onto
   a PACKAGE fn's own slot chain and every descendant in the
   installation inherited it until a restart's re-sync. These tests pin
   the membership contract (the `packages.owned` registry the boot sync
   fills) and the rejection wiring in the create/update cores.

   ^:serial — the guard consults a process-global registry; recording
   ids here must not interleave with another namespace's fixture
   bootstrap (which records the REAL package set)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.crud.package-guard :as pkg-guard]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.owned :as owned]
    [graphden.storage.protocol.core :as sp]))


(use-fixtures :once (setup/create-container-fixture))


(deftest membership-test
  (let [storage (setup/create-test-storage)
        pkg (sp/create-entity storage :fn {:name "pgx-owned"})
        user (sp/create-entity storage :fn {:name "pgx-mine"})]
    (owned/record-owned-ids! [(:id pkg)])
    (testing "recorded id is package-owned; anything else is not"
      (is (true? (pkg-guard/package-owned-fn? storage (:id pkg))))
      (is (false? (pkg-guard/package-owned-fn? storage (:id user))))
      (is (false? (pkg-guard/package-owned-fn? storage nil)))
      (is (false? (pkg-guard/package-owned-fn? storage (random-uuid)))))))


(deftest duplicate-binding-is-a-conflict-test
  "A second binding on the same (fn-id, slot-id) is a CONFLICT, not an
   internal error. The editor can produce one by double-clicking a
   placeholder that has not repainted yet; it used to surface as a 500
   (raw Postgres unique-violation carries no ex-data), which reads as a
   server fault in logs and alerting."
  (let [storage (setup/create-test-storage)
        parent (sp/create-entity storage :fn {:name "dup-parent"})
        slot (sp/create-entity storage :slot {:name "s" :type-fn-id (:id parent)})
        child (sp/create-entity storage :fn {:name "dup-child"
                                             :parent-ids [(:id parent)]})
        mk (fn [] (entities/apply-create-core
                    {:entity-type :binding
                     :type-str "binding"
                     :form-data {}
                     :entity-data {:fn-id (:id child) :slot-id (:id slot)
                                   :value 1 :value-present true}}
                    {:storage storage}))]
    (testing "the first write lands"
      (is (some? (:created (mk)))))
    (testing "the duplicate answers 409, not 500"
      (let [result (mk)]
        (is (string? (:error result)))
        (is (= 409 (:http-status result))
            "duplicate (fn-id, slot-id) is a conflict")
        (is (re-find #"(?i)already exists" (:error result))
            "and says so in words the editor can show")))))


(deftest write-and-delete-rejection-test
  (let [storage (setup/create-test-storage)
        pkg (sp/create-entity storage :fn {:name "pgx-guarded"})
        pkg-id (:id pkg)
        _ (owned/record-owned-ids! [pkg-id])
        user (sp/create-entity storage :fn {:name "pgx-mine2"})
        slot (sp/create-entity storage :slot {:name "s" :type-fn-id pkg-id})
        pkg-binding (sp/create-entity storage :binding
                                      {:fn-id pkg-id :slot-id (:id slot)
                                       :list-append true})
        pkg-item (sp/create-entity storage :binding-list-item
                                   {:binding-id (:id pkg-binding)
                                    :position 0 :value 10})]
    (testing "binding create/update onto the package fn is rejected"
      (is (string? (pkg-guard/write-rejection
                     storage :binding {:fn-id pkg-id :slot-id (:id slot)})))
      (is (nil? (pkg-guard/write-rejection
                  storage :binding {:fn-id (:id user) :slot-id (:id slot)}))))

    (testing "list-item write resolves the owner through its binding"
      (is (string? (pkg-guard/write-rejection
                     storage :binding-list-item
                     {:binding-id (:id pkg-binding) :value 11}))))

    (testing "delete of the package fn row / its binding family is rejected"
      (is (string? (pkg-guard/delete-rejection storage :fn {:id pkg-id})))
      (is (string? (pkg-guard/delete-rejection storage :binding pkg-binding)))
      (is (string? (pkg-guard/delete-rejection storage :binding-list-item pkg-item)))
      (is (nil? (pkg-guard/delete-rejection storage :fn user)))
      (is (nil? (pkg-guard/delete-rejection storage :ns {:id (random-uuid)}))))

    (testing "apply-create-core refuses the write before touching storage"
      (let [result (entities/apply-create-core
                     {:entity-type :binding
                      :type-str "binding"
                      :form-data {}
                      :entity-data {:fn-id pkg-id :slot-id (:id slot)}}
                     {:storage storage})]
        (is (string? (:error result)))
        (is (= 403 (:http-status result)))
        (is (= 1 (count (sp/query-entities storage :binding
                                           {:fn-id pkg-id})))
            "no second binding row was written")))

    (testing "renaming / re-describing the package fn itself is refused"
      (is (string? (pkg-guard/write-rejection storage :fn {:id pkg-id})))
      (is (nil? (pkg-guard/write-rejection storage :fn {:id (:id user)})))
      (let [result (entities/apply-update-core
                     {:entity-type :fn
                      :type-str "fn"
                      :id-uuid pkg-id
                      :form-data {}
                      :entity-data {:name "pgx-renamed"}}
                     {:storage storage})]
        (is (string? (:error result)))
        (is (= "pgx-guarded" (:name (sp/read-entity storage :fn pkg-id)))
            "the package fn kept its name")))

    (testing "apply-update-core refuses touching the package binding"
      (let [result (entities/apply-update-core
                     {:entity-type :binding
                      :type-str "binding"
                      :id-uuid (:id pkg-binding)
                      :form-data {}
                      :entity-data {:terminal true}}
                     {:storage storage})]
        (is (string? (:error result)))
        (is (nil? (:terminal (sp/read-entity storage :binding (:id pkg-binding))))
            "the row is untouched")))))
