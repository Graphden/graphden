(ns ^:integration graphden.integration.storage-protocol-poc-test
  "Block 1 Step 2 — POC validating the storage chain end-to-end.

   `:pg-query` / `:pg-execute` / `:pg-tx` are registered as base-fns;
   a fn-def with `:parent :pg-query` is sync'd; the executor compiles
   and runs it through to the real test container, returning live
   rows.

   This test answers the load-bearing question for Step 2 — \"can a
   graph-only fn-def reach the application's own Postgres datasource
   through the executor?\" — without committing to migrating any
   existing /api/* handler yet. Once green, the same shape (sync
   fn-def → execute) scales mechanically to actual API-route
   migrations.

   The richer protocol-through-:Storage path (free-arg `:storage`
   typed `:Storage`, injected with `:postgres-storage-impl` at the
   top of the chain, navigated via `:get` + `:invoke`) is the
   follow-up POC-B."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.interface :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.sql.pg :as pg]))


(use-fixtures :once (setup/create-container-fixture))


(use-fixtures :each
  (setup/create-clean-db-fixture)
  exec/with-clean-registry)


(deftest poc-fn-def-of-pg-query-reaches-the-db
  (testing "graph fn-def parented to :pg-query runs end-to-end through the executor"
    (let [storage (setup/create-test-storage)]
      (try
        ;; Loading "core" + "storage" registers :pg-query etc. AND
        ;; syncs the :Storage type-row + :postgres-storage-impl
        ;; fn-def into storage. We only exercise :pg-query directly
        ;; here; protocol-routed access is the follow-up POC-B.
        (registry/initialize-with-base-fns! storage ["core" "storage"])

        (let [ctx (exec/create-context {:storage storage})]
          ;; Seed a test table BEFORE syncing the consumer fn-def. We
          ;; reach the helper directly to avoid bootstrap circularity
          ;; (the executor isn't compiled yet at this point).
          (pg/pg-execute ctx {:create-table :poc_demo
                              :with-columns [[:id :int [:primary-key]]
                                             [:name :text]]})
          (pg/pg-execute ctx {:insert-into :poc_demo
                              :values [{:id 1 :name "alice"}
                                       {:id 2 :name "bob"}]})

          ;; Define the consumer: just a fn-def with the hsql bound
          ;; as a literal. The executor compiles + invokes it through
          ;; the real :pg-query base-fn impl.
          (fn-composition/sync-fns-to-storage!
            storage
            [{:name :poc-list-rows
              :parent :pg-query
              :args {:hsql {:select [:id :name]
                            :from :poc_demo
                            :order-by [:id]}}}])

          (let [fn-id (-> (sp/query-entities storage :fn {:name "poc-list-rows"})
                          first
                          :id)
                result (exec/execute ctx fn-id nil)]
            (is (= [{:id 1 :name "alice"}
                    {:id 2 :name "bob"}]
                   result)
                "executor delivered the live rows from the test container")))
        (finally
          (sp/close storage))))))


(deftest poc-fn-def-can-write-and-read
  (testing "pg-execute + pg-query both reachable from sync'd fn-defs"
    (let [storage (setup/create-test-storage)]
      (try
        (registry/initialize-with-base-fns! storage ["core" "storage"])

        (let [ctx (exec/create-context {:storage storage})]
          (pg/pg-execute ctx {:create-table :poc_wr
                              :with-columns [[:id :int [:primary-key]]]})

          (fn-composition/sync-fns-to-storage!
            storage
            [;; Writer: inserts row with id=42, returns row count
             {:name :poc-write
              :parent :pg-execute
              :args {:hsql {:insert-into :poc_wr :values [{:id 42}]}}}
             ;; Reader: lists the table
             {:name :poc-read
              :parent :pg-query
              :args {:hsql {:select [:id] :from :poc_wr}}}])

          (let [write-id (-> (sp/query-entities storage :fn {:name "poc-write"})
                             first
                             :id)
                read-id (-> (sp/query-entities storage :fn {:name "poc-read"})
                            first
                            :id)]
            (testing "writer fn-def reports rows-affected through the executor"
              (is (= 1 (exec/execute ctx write-id nil))))

            (testing "reader fn-def sees the freshly written row"
              (is (= [{:id 42}] (exec/execute ctx read-id nil))))))
        (finally
          (sp/close storage))))))


;; =============================================================================
;; POC-B — storage protocol via free-arg-per-operation pattern
;; =============================================================================
;;
;; PHILOSOPHY § Self-Describing System → Protocols via type-row sketched
;; a record-with-:fn-typed-fields contract (`:Storage` with `:query`,
;; `:execute`, `:tx` fields, each holding a fn-id). The runtime gap that
;; surfaced while trying to run that example end-to-end:
;;
;;   - Inheriting from a type-row (e.g. `:postgres-storage-impl
;;     :parent :Storage :args {:query :pg-query …}`) does NOT
;;     auto-produce a record-of-fn-ids value at run time. Records are
;;     type-level; instance construction is up to the body fn.
;;   - The natural alternative (`:assoc` chain to build the map) won't
;;     keep the values as raw fn-ids — `:assoc :value` evaluates the
;;     ref and stores the result.
;;
;; So the realisable shape of the protocol pattern in graphden today is
;; **free-arg-per-operation**: the consumer declares one free-arg per
;; abstract operation (each `:fn`-typed), the caller binds each to a
;; concrete base-fn at the top of the chain. Swappability still works:
;; one top-level fn-def per backend, picked by callers.
;;
;; POC-B below validates this realisable shape end-to-end.
;; PHILOSOPHY's worked-example shape (record-of-fn-ids) is a documented
;; gap to revisit if/when we introduce a record-of-callables primitive.

(deftest poc-b-storage-protocol-via-free-args
  (testing "consumer with :storage-query free-arg, bound to :pg-query at top"
    (let [storage (setup/create-test-storage)]
      (try
        (registry/initialize-with-base-fns! storage ["core" "storage"])

        (let [ctx (exec/create-context {:storage storage})]
          (pg/pg-execute ctx {:create-table :pocb_demo
                              :with-columns [[:id :int [:primary-key]]
                                             [:name :text]]})
          (pg/pg-execute ctx {:insert-into :pocb_demo
                              :values [{:id 10 :name "via-protocol-alice"}
                                       {:id 20 :name "via-protocol-bob"}]})

          (fn-composition/sync-fns-to-storage!
            storage
            [;; Consumer: takes a `:storage-query` :fn-typed free-arg and
             ;; calls it (via :call → hof-wrap) with a fixed hsql map.
             {:name :_pocb-rows-consumer
              :parent :call
              :args {:func {:as :storage-query}
                     :arg {:select [:id :name]
                           :from :pocb_demo
                           :order-by [:id]}}}

             ;; Top-level: binds the abstract `:storage-query` slot to
             ;; the concrete `:pg-query` base-fn. Swapping to a future
             ;; backend = a sibling top-level binding to its `:*-query`.
             {:name :pocb-rows-via-pg-storage
              :parent :_pocb-rows-consumer
              :args {:storage-query :pg-query}}])

          (let [top-id (-> (sp/query-entities storage :fn
                                              {:name "pocb-rows-via-pg-storage"})
                           first
                           :id)
                result (exec/execute ctx top-id nil)]
            (is (= [{:id 10 :name "via-protocol-alice"}
                    {:id 20 :name "via-protocol-bob"}]
                   result)
                "consumer's :storage-query free-arg dispatched to :pg-query and returned live rows")))
        (finally
          (sp/close storage))))))
