(ns ^:integration graphden.packages.storage.storage-query-identities-test
  "End-to-end test for `:storage-query-identities` — the
   versioning-bypass identity loader.

   `:storage-query-identities` calls `sp/query-entities` against the
   UNWRAPPED base storage so callers see raw identity rows without
   version resolution. Critically, the result includes `:ref-many`
   fields (junction-table data like `:fn`'s `:parent-ids`) — the
   bit `:pg-query` + `:decode-row` alone wouldn't populate.

   This is the foundational primitive for Block 1 Step 2 Phase 3:
   per-site versioned reads use it to load identities, then a
   pure-graph `:resolve-versioned-rows` does the version merge."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]))


(def ^:dynamic *context* nil)
(def ^:dynamic *storage* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  (fn [t]
    (exec/with-clean-registry
      #(let [graph (setup/bootstrap-crud-graph-from-golden!)]
         (try
           (binding [*context* (:ctx graph)
                     *storage* (:storage graph)]
             (t))
           (finally (sp/close (:storage graph))))))))


(defn- fn-id
  [nm]
  (:id (first (sp/query-entities *storage* :fn {:name nm}))))


(deftest storage-query-identities-returns-fn-rows-with-parent-ids
  (testing ":storage-query-identities :fn returns identity rows with :parent-ids populated"
    (setup/sync-and-invalidate!
      *context* *storage*
      [{:name :sqi-test-rows
        :parent :storage-query-identities
        :args {:entity-type {:value "fn"}
               :where {:value {}}}}])

    (let [via-graph (exec/execute *context* (fn-id "sqi-test-rows") {})
          via-base  (sp/query-entities (vs/unwrap *storage*) :fn {})
          composed-rows (filter #(seq (:parent-ids %)) via-graph)]

      (testing "returns the same row set as direct sp/query-entities on base storage"
        (is (= (count via-graph) (count via-base))
            "row counts match across the two paths")
        (is (= (set (map :id via-graph)) (set (map :id via-base)))
            "id sets identical"))

      (testing "at least one row has :parent-ids populated (composed fn-defs exist)"
        (is (seq composed-rows)
            ":parent-ids junction was loaded — composed fns visible"))

      (testing "row keys are kebab-case (codec decoded)"
        (let [sample (first via-graph)]
          (is (contains? sample :id))
          (is (contains? sample :parent-ids)
              ":parent-ids field present as kebab-case keyword")
          (is (not (contains? sample :parent_ids))
              "no raw snake_case key"))))))


(deftest storage-query-identities-respects-where-clause
  (testing ":storage-query-identities :fn {:name X} returns matching rows only"
    (setup/sync-and-invalidate!
      *context* *storage*
      [{:name :sqi-where-rows
        :parent :storage-query-identities
        :args {:entity-type {:value "fn"}
               :where {:value {:name "add"}}}}])

    (let [rows (exec/execute *context* (fn-id "sqi-where-rows") {})]
      (testing "exactly one row returned for the base-fn `:add`"
        (is (= 1 (count rows)))
        (is (= "add" (:name (first rows))))))))
