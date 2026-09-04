(ns ^:integration ^:serial graphden.crud.graph-cache-race-graph-test
  "The load-on-miss / write race in the per-ctx graph cache.

   `cached-or-load-graph` fills the cache from storage when it is empty.
   A write that commits WHILE that load runs invalidates an empty cache
   (`splice-graph-cache!` has nothing to patch) and then the loader
   installs its pre-write snapshot as the truth — the reader misses the
   fn it just created until a later write splices it back in. This was
   the \"lag\" behind the lint panel's storage-read bypass.

   The test interposes a real CRUD write into the loader (the seam is
   `load-graph-entities-uncached`, redefined to write once between its
   read and its return) and asserts the snapshot the reader gets — and
   the one the cache keeps — both carry the interposed fn.

   `^:serial`: `with-redefs` on a plain var is process-wide."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.crud.types-api :as types-api]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(def ^:dynamic *graph* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  (fn [f]
    (let [graph (setup/bootstrap-crud-graph-from-golden!*
                  "graphden.crud.graph-cache-race-graph-test"
                  ["core"])]
      (binding [*graph* graph]
        (try (f) (finally (sp/close (:storage graph))))))))


(defn- fn-ids-of
  [graph]
  (into #{} (map :id) (:fns graph)))


(deftest load-on-miss-never-publishes-a-snapshot-a-write-outran-test
  (let [{:keys [ctx storage]} *graph*
        const-id (:id (first (sp/query-entities storage :fn {:name "const"})))
        original types-api/load-graph-entities-uncached
        probe (atom nil)
        stale (atom nil)]
    ;; A cold ctx: nothing has primed the cache yet (a fresh pod, a branch
    ;; ctx just built, the reader after a full clear).
    (reset! (:graph-cache ctx) nil)
    (with-redefs [types-api/load-graph-entities-uncached
                  (fn [st]
                    (let [snapshot (original st)]
                      ;; The write lands between the loader's read and its
                      ;; install — the window the epoch guard closes.
                      (when-not @probe
                        (reset! stale snapshot)
                        (reset! probe (:id (entities/create-entity
                                             "fn" {:name "race-probe" :parent-ids [const-id]} ctx))))
                      snapshot))]
      (let [served (types-api/cached-or-load-graph ctx)]
        (testing "the reader is served a snapshot that contains the interposed write"
          (is (some? @probe))
          (is (not (contains? (fn-ids-of @stale) @probe)) "the pre-write read really lacked it")
          (is (contains? (fn-ids-of served) @probe)))))
    (testing "the cache holds the post-write graph — the pre-write read was not installed over it"
      (let [cached (exec-ctx/cached-graph ctx)]
        (is (some? cached))
        (is (not (identical? @stale cached)))
        (is (contains? (fn-ids-of cached) @probe))))))


(deftest fill-with-a-moved-epoch-is-refused-test
  (let [{:keys [ctx]} *graph*]
    (reset! (:graph-cache ctx) nil)
    (let [epoch (exec-ctx/invalidation-epoch ctx)
          stale {:fns [] :slots [] :fn-slots [] :bindings [] :list-items []}]
      ;; A full invalidation moves the epoch (and, on a live ctx, leaves the
      ;; cache to the next reader); the older snapshot must not land.
      (exec-ctx/invalidate-graph-cache! ctx)
      (is (false? (exec-ctx/fill-graph-cache! ctx stale epoch)))
      (is (not (identical? stale (exec-ctx/cached-graph ctx))) "a refused install never publishes the stale snapshot")
      (is (true? (exec-ctx/fill-graph-cache! ctx stale (exec-ctx/invalidation-epoch ctx))))
      (is (identical? stale (exec-ctx/cached-graph ctx))))))
