(ns ^:serial graphden.crud.branch-derived-cache-test
  "Derived branch snapshots are released, including a read finishing after deletion."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.entities.list :as entity-list]
    [graphden.lint.graph :as lint]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]))


(defn- private-var
  [ns-name sym]
  (ns-resolve ns-name sym))


(defn- lint-memo
  []
  @(private-var 'graphden.lint.graph 'memo))


(defn- tree-memo
  []
  @(private-var 'graphden.crud.entities.list 'tree-kinds-memo))


(use-fixtures :each
  (fn [test]
    (with-redefs-fn {(private-var 'graphden.lint.graph 'memo) (atom {})
                     (private-var 'graphden.crud.entities.list 'tree-kinds-memo) (atom {})}
      test)))


(defn- context
  [branch-id]
  {:storage (vs/->VersionedStorage
              (reify sp/StorageCRUD
                (query-entities [_ _ _] [])

                (query-entities [_ _ _ _] []))
              branch-id)
   :graph-cache (atom {:fns [] :slots [] :fn-slots [] :bindings [] :list-items []})})


(defn- read-both!
  [ctx]
  (lint/lint-branch ctx #{})
  (entity-list/graph-tree ctx))


(defn- lint-entries
  [branch-id]
  (into {} (filter (fn [[[_ bid _] _]] (= branch-id bid))) @(lint-memo)))


(deftest deleting-one-branch-releases-snapshots-and-preserves-other-branches
  (let [deleted (random-uuid) kept (random-uuid)
        deleted-ctx (context deleted) kept-ctx (context kept)]
    (read-both! deleted-ctx)
    (read-both! kept-ctx)
    (let [kept-lint (lint-entries kept) kept-tree (get @(tree-memo) kept)]
      (is (seq (lint-entries deleted)))
      (is (contains? @(tree-memo) deleted))
      (lint/forget-branch! deleted)
      (entity-list/forget-branch! deleted)
      (is (empty? (lint-entries deleted)))
      (is (not (contains? @(tree-memo) deleted)))
      (is (= kept-lint (lint-entries kept)))
      (is (identical? kept-tree (get @(tree-memo) kept)))
      (testing "a recreated branch has a fresh identity and may cache normally"
        (let [recreated (random-uuid)]
          (read-both! (context recreated))
          (is (seq (lint-entries recreated)))
          (is (contains? @(tree-memo) recreated))
          (is (empty? (lint-entries deleted))))))))


(deftest in-flight-reads-return-but-cannot-republish-after-eviction
  (doseq [[ns-name work-name read! forget! cached?]
          [['graphden.lint.graph 'full-state #(lint/lint-branch % #{})
            lint/forget-branch! #(seq (lint-entries %))]
           ['graphden.crud.entities.list 'ns-kind-counts entity-list/graph-tree
            entity-list/forget-branch! #(contains? @(tree-memo) %)]]]
    (testing (str ns-name)
      (let [branch-id (random-uuid) ctx (context branch-id)
            work-var (private-var ns-name work-name) work @work-var
            entered (promise) release (promise)]
        (with-redefs-fn
          {work-var (fn [& args]
                      (deliver entered true)
                      (when-not (= :continue (deref release 10000 :timeout))
                        (throw (ex-info "Cache publication barrier timed out" {})))
                      (apply work args))}
          (fn []
            (let [running (future (read! ctx))]
              (try
                (is (true? (deref entered 10000 :timeout)))
                (forget! branch-id)
                (deliver release :continue)
                (is (not= ::timeout (deref running 10000 ::timeout)))
                (is (not (cached? branch-id)))
                (finally
                  (deliver release :continue)
                  (future-cancel running))))))
        (read! ctx)
        (is (cached? branch-id) "a later computation can publish normally")))))
