(ns ^:integration graphden.system.graph-epoch-test
  "The graph-epoch freshness self-heal (audit-6): a Postgres sequence
   bumped before every graph-shaped write; the branch-router validates
   its cached ctxs against it on fetch, so a skipped eager invalidate
   (client abort, missing/lost NOTIFY) heals instead of serving stale
   compiled closures forever."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.context :as ctx]
    [graphden.schema.executions.schema :as es]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.postgres.graph-epoch :as epoch]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as th]
    [graphden.system.branch-router :as br]
    [graphden.versioning.storage.core :as vs]))


(def ^:dynamic *container* nil)


(use-fixtures :once (th/create-container-fixture #'*container*))
(use-fixtures :each (th/create-clean-db-fixture #'*container*))


(defn- storage
  []
  (let [schema (-> (mds/create-builder)
                   (gds/extend-builder)
                   (vds/extend-builder)
                   (vts/extend-builder)
                   (es/extend-builder)
                   (ds/build))]
    (-> (pg/create-storage (th/get-container-config *container*))
        (sp/initialize-with-cleanup! schema))))


(deftest bump-on-graph-write-only-test
  (let [base (storage)
        v (vs/wrap-with-versioning base)]
    (try
      (let [before (epoch/current base)
            _ (sp/create-entity v :fn {:name "ep-fn" :parent-ids []
                                       :description "h"})
            mid (epoch/current base)
            _ (sp/create-entity v :fn {:name "ep-fn2" :parent-ids []
                                       :description "h"})
            after-graph (epoch/current base)]
        (testing "graph writes advance the epoch monotonically"
          ;; A fresh sequence's first nextval RETURNS last_value (1),
          ;; so the first write asserts >=; strict growth shows
          ;; between two writes.
          (is (some? before))
          (is (>= mid before))
          (is (> after-graph mid)))
        (testing "the handle remembers its own bump"
          (is (= after-graph (epoch/last-bumped base))))
        (testing "a non-graph entity does NOT bump"
          (is (nil? (epoch/bump! base :fn-execution)))
          (is (nil? (epoch/bump! base :service)))
          (is (= after-graph (epoch/current base)))))
      (finally (sp/close base)))))


(deftest lazy-heal-invalidates-stale-ctxs-test
  ;; The self-heal contract end-to-end at the router level: the epoch
  ;; moved (an out-of-band write — abort / lost NOTIFY / no-NOTIFY
  ;; path), the watermark is behind, so a context fetch invalidates
  ;; every cached ctx exactly ONCE and advances the watermark.
  (let [base (storage)
        v (vs/wrap-with-versioning base)
        invalidated (atom [])
        fake-ctx-a {:tag :a}
        fake-ctx-b {:tag :b}
        router {:default-branch-id (vs/current-branch-id v)
                :handlers (atom {(random-uuid) {:ctx fake-ctx-a}
                                 (vs/current-branch-id v) {:ctx fake-ctx-b
                                                           :handler :h}})
                :base-ctx {:storage v}}]
    (try
      (binding [br/*epoch-check-ttl-ms* 0]
        (with-redefs [ctx/invalidate-graph-cache!
                      (fn [c] (swap! invalidated conj (:tag c)))]
          ;; Simulate a write whose eager path was SKIPPED: the bump
          ;; happens (VersionedStorage does it), nobody notes.
          (sp/create-entity v :fn {:name "heal-fn" :parent-ids []
                                   :description "h"})
          (reset! br/validated-graph-epoch 0)
          (br/handler-for router nil)
          (testing "every cached ctx invalidated once"
            (is (= #{:a :b} (set @invalidated)))
            (is (= 2 (count @invalidated))))
          (testing "watermark advanced — second fetch heals nothing"
            (br/handler-for router nil)
            (is (= 2 (count @invalidated))))))
      (finally (sp/close base)))))


(deftest eager-note-prevents-spurious-heal-test
  ;; The write path's own eager invalidate marks the watermark with
  ;; the EXACT bump value, so a normal (uninterrupted) write never
  ;; triggers the heal.
  (let [base (storage)
        v (vs/wrap-with-versioning base)
        invalidated (atom 0)
        router {:default-branch-id (vs/current-branch-id v)
                :handlers (atom {(vs/current-branch-id v) {:ctx {:x 1}
                                                           :handler :h}})
                :base-ctx {:storage v}}]
    (try
      (binding [br/*epoch-check-ttl-ms* 0]
        (with-redefs [ctx/invalidate-graph-cache!
                      (fn [_] (swap! invalidated inc))]
          (reset! br/validated-graph-epoch 0)
          (sp/create-entity v :fn {:name "noted-fn" :parent-ids []
                                   :description "h"})
          ;; ...the eager path (crud invalidate!) ends with:
          (br/note-graph-epoch-validated! v)
          (br/handler-for router nil)
          (testing "no heal — the eager path already covered the bump"
            (is (zero? @invalidated)))))
      (finally (sp/close base)))))


(deftest degrades-to-noop-without-pool-test
  (testing "bump!/current on a pool-less handle are nil no-ops"
    (is (nil? (epoch/bump! {:no :pool} :fn)))
    (is (nil? (epoch/current {:no :pool})))
    (is (zero? (epoch/last-bumped {:no :pool})))))
