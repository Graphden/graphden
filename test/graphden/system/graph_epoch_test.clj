(ns ^:integration graphden.system.graph-epoch-test
  "The graph-epoch freshness self-heal, ledger edition (audit-7): the
   watermark advances only when every epoch in (w, global] is
   accounted for — locally noted or NOTIFY-covered — so an interleaved
   foreign write whose NOTIFY was lost can never be buried by a local
   note (the FINDING-1 regression is pinned here)."
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
    [graphden.storage.postgres.notify :as notify]
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


(defn- fresh-state
  []
  (atom (br/epoch-state-seed)))


(defn- router-over
  [v handlers-map]
  {:default-branch-id (vs/current-branch-id v)
   :handlers (atom handlers-map)
   :base-ctx {:storage v}})


(defn- foreign-bump!
  "Bump the shared sequence as ANOTHER pod would — no entry in OUR
   handle's ledger, no NOTIFY."
  [base]
  (epoch/bump! (dissoc base :graph-epoch-local :graph-epoch-covered) :fn))


(deftest bump-ledger-and-request-log-test
  (let [base (storage)
        v (vs/wrap-with-versioning base)]
    (try
      (binding [epoch/*request-bump-log* (atom [])]
        (let [before (epoch/current base)
              _ (sp/create-entity v :fn {:name "ep-fn" :parent-ids []
                                         :description "h"})
              mid (epoch/current base)
              _ (sp/create-entity v :fn {:name "ep-fn2" :parent-ids []
                                         :description "h"})
              after (epoch/current base)]
          (testing "graph writes advance the epoch monotonically"
            ;; a fresh sequence's first nextval RETURNS last_value, so
            ;; the first write asserts >=; strict growth shows between
            ;; two writes
            (is (some? before))
            (is (>= mid before))
            (is (> after mid)))
          (testing "every bump lands in the handle ledger AND the request log"
            (is (>= (count @(:graph-epoch-local base)) 2))
            (is (>= (count @epoch/*request-bump-log*) 2)))
          (testing "a non-graph entity does NOT bump"
            (is (nil? (epoch/bump! base :fn-execution)))
            (is (= after (epoch/current base))))
          (testing "note-applied! drains the log and marks the ledger"
            (epoch/note-applied! base)
            (is (empty? @epoch/*request-bump-log*))
            (is (every? :noted? (vals @(:graph-epoch-local base)))))))
      (finally (sp/close base)))))


(deftest foreign-gap-heals-despite-recent-local-write-test
  ;; FINDING-1 regression: local write (noted), FOREIGN missed write,
  ;; local write (noted). The old max-advance note buried the foreign
  ;; epoch forever; the ledger sees the hole and heals IMMEDIATELY —
  ;; no grace suppression for foreign gaps.
  (let [base (storage)
        v (vs/wrap-with-versioning base)
        healed (atom 0)]
    (try
      (binding [br/*epoch-state-override* (fresh-state)
                br/*epoch-check-ttl-ms* 0
                epoch/*request-bump-log* (atom [])]
        (with-redefs [ctx/invalidate-graph-cache! (fn [_] (swap! healed inc))]
          (let [router (router-over v {(vs/current-branch-id v)
                                       {:ctx {:x 1} :handler :h}})]
            (sp/create-entity v :fn {:name "f1" :parent-ids [] :description "h"})
            (epoch/note-applied! base)
            (br/handler-for router nil)
            (is (zero? @healed) "fully-noted range advances without healing")
            (foreign-bump! base)
            (sp/create-entity v :fn {:name "f2" :parent-ids [] :description "h"})
            (epoch/note-applied! base)
            (br/handler-for router nil)
            (testing "the foreign hole heals now, despite the fresh local bump"
              (is (pos? @healed)))
            (testing "watermark advanced past the healed range — no re-heal"
              (let [n @healed]
                (br/handler-for router nil)
                (is (= n @healed)))))))
      (finally (sp/close base)))))


(deftest pending-local-write-waits-not-heals-test
  ;; A young un-noted local bump (eager invalidation in flight) must
  ;; neither heal nor advance; once noted, the range advances quietly.
  (let [base (storage)
        v (vs/wrap-with-versioning base)
        healed (atom 0)]
    (try
      (binding [br/*epoch-state-override* (fresh-state)
                br/*epoch-check-ttl-ms* 0
                epoch/*request-bump-log* (atom [])]
        (with-redefs [ctx/invalidate-graph-cache! (fn [_] (swap! healed inc))]
          (let [router (router-over v {(vs/current-branch-id v)
                                       {:ctx {:x 1} :handler :h}})]
            (sp/create-entity v :fn {:name "pend" :parent-ids [] :description "h"})
            (br/handler-for router nil)
            (is (zero? @healed) "pending local bump: wait, don't heal")
            (epoch/note-applied! base)
            (br/handler-for router nil)
            (is (zero? @healed) "noted range advances without healing"))))
      (finally (sp/close base)))))


(deftest aborted-local-write-heals-after-grace-test
  ;; An un-noted local bump older than the grace = the eager path died
  ;; (client abort) — heal.
  (let [base (storage)
        v (vs/wrap-with-versioning base)
        healed (atom 0)]
    (try
      (binding [br/*epoch-state-override* (fresh-state)
                br/*epoch-check-ttl-ms* 0
                br/*epoch-heal-grace-ms* 0
                epoch/*request-bump-log* (atom [])]
        (with-redefs [ctx/invalidate-graph-cache! (fn [_] (swap! healed inc))]
          (let [router (router-over v {(vs/current-branch-id v)
                                       {:ctx {:x 1} :handler :h}})]
            (sp/create-entity v :fn {:name "abt" :parent-ids [] :description "h"})
            ;; NO note — simulated abort; grace 0 ⇒ aged out instantly
            (br/handler-for router nil)
            (is (pos? @healed)))))
      (finally (sp/close base)))))


(deftest notify-covered-epochs-do-not-heal-test
  ;; A sibling's NOTIFY carried the writer's bump values; covering them
  ;; satisfies the range without a heal.
  (let [base (storage)
        v (vs/wrap-with-versioning base)
        healed (atom 0)]
    (try
      (binding [br/*epoch-state-override* (fresh-state)
                br/*epoch-check-ttl-ms* 0
                epoch/*request-bump-log* (atom [])]
        (with-redefs [ctx/invalidate-graph-cache! (fn [_] (swap! healed inc))]
          (let [router (router-over v {(vs/current-branch-id v)
                                       {:ctx {:x 1} :handler :h}})
                foreign (foreign-bump! base)]
            (br/note-graph-epoch-covered! v [foreign])
            (br/handler-for router nil)
            (is (zero? @healed) "covered epoch satisfies the range"))))
      (finally (sp/close base)))))


(deftest sequence-regression-reseeds-and-heals-test
  ;; DB restored from a dump with a LOWER sequence while the JVM lives:
  ;; global < watermark used to make the heal silently dead forever.
  (let [base (storage)
        v (vs/wrap-with-versioning base)
        healed (atom 0)
        state (atom {:w 999999 :read {:value nil :at 0}})]
    (try
      (binding [br/*epoch-state-override* state
                br/*epoch-check-ttl-ms* 0
                epoch/*request-bump-log* (atom [])]
        (with-redefs [ctx/invalidate-graph-cache! (fn [_] (swap! healed inc))]
          (let [router (router-over v {(vs/current-branch-id v)
                                       {:ctx {:x 1} :handler :h}})]
            (sp/create-entity v :fn {:name "rgr" :parent-ids [] :description "h"})
            (br/handler-for router nil)
            (testing "regression detected → reseed + heal instead of dead"
              (is (pos? @healed))
              (is (< (:w @state) 999999))))))
      (finally (sp/close base)))))


(deftest notify-payload-roundtrips-epochs-test
  (let [ev {:kind :fn :op :invalidate :id "abc"
            :branch-id "b1" :epochs [7 8 9]}]
    (is (= ev (#'notify/parse-payload
               (#'notify/format-payload ev))))))


(deftest degrades-to-noop-without-pool-test
  (testing "bump!/current on a pool-less handle are nil no-ops"
    (is (nil? (epoch/bump! {:no :pool} :fn)))
    (is (nil? (epoch/current {:no :pool})))))
