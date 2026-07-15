(ns ^:integration graphden.packages.storage.branch-chain-test
  "End-to-end test for `:branch-chain` — the first PRODUCTION-graded
   `:fix`-based graph composition. Verifies the walk's correctness
   across a real 4-deep branch tree against a containerised Postgres,
   matching the order + content of the existing Clojure §3.3 chain
   walker (`versioning.storage.resolution/collect-branch-chain-impl`).

   This is the proof-of-concept that `:fix` works for real
   recursive infrastructure walks — the same pattern will scale to
   `:fn-cycle-detect?` / `:fn-mi-compat?` in later phases."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.versioning.storage.core :as vs]
    [graphden.versioning.storage.resolution :as res]))


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


(defn- main-branch-id
  []
  (:id (first (sp/query-entities *storage* :branch {:name "main"}))))


(deftest branch-chain-walks-to-root-end-to-end
  (testing ":branch-chain follows parent links from leaf to root"
    (let [root-id (main-branch-id)
          mid     (:id (vs/create-branch! *storage* "mid-branch"
                                          {:base-branch-id root-id}))
          leaf    (:id (vs/create-branch! *storage* "leaf-branch"
                                          {:base-branch-id mid}))]
      ;; No invalidation needed: the `:branch-chain` fn reads branch rows from
      ;; storage at execute time, and creating a branch changes no fn's compiled
      ;; closure. The 1-arity full clear that used to sit here nil'd the registry
      ;; the fixture had just precompiled, so the first `execute` below rebuilt all
      ;; ~2600 golden fns — ~45 s, for nothing. The branch-chain cache is keyed by
      ;; branch-id, and brand-new branches add fresh keys, never stale ones. The
      ;; assertions confirm the walk is correct without it.

      (testing "leaf → mid → root (3-deep)"
        (is (= [leaf mid root-id]
               (exec/execute *context* (fn-id "branch-chain")
                             {:branch-id leaf}))))

      (testing "mid → root (2-deep)"
        (is (= [mid root-id]
               (exec/execute *context* (fn-id "branch-chain")
                             {:branch-id mid}))))

      (testing "root alone (1-deep)"
        (is (= [root-id]
               (exec/execute *context* (fn-id "branch-chain")
                             {:branch-id root-id})))))))


(deftest branch-chain-matches-existing-clojure-walker
  (testing ":branch-chain output matches versioning.storage.resolution's chain walker"
    (let [root-id (main-branch-id)
          mid     (:id (vs/create-branch! *storage* "match-mid"
                                          {:base-branch-id root-id}))
          leaf    (:id (vs/create-branch! *storage* "match-leaf"
                                          {:base-branch-id mid}))
          via-graph   (exec/execute *context* (fn-id "branch-chain")
                                    {:branch-id leaf})
          via-clojure (#'res/collect-branch-chain-impl
                       (vs/unwrap *storage*) leaf)]
      (is (= via-clojure via-graph)
          "both walkers produce the same chain in the same order"))))
