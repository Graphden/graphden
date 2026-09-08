(ns ^:integration graphden.system.graph-epoch-test
  "Parallel-safe: the heal's rebuild counting goes through the
   thread-local `cr/*impl-override*` seam (`binding`, with
   `br/*epoch-heal-sync?*` keeping the heal on this thread) instead of
   `with-redefs` — a root rebind was process-global and pinned this NS
   `^:serial` (a concurrent NS whose rebuild landed in the window never
   actually compiled its graph; serial-reduction batch 4).

   The graph-epoch freshness self-heal, ledger edition (audit-7): the
   watermark advances only when every epoch in (w, global] is
   accounted for — locally noted or NOTIFY-covered — so an interleaved
   foreign write whose NOTIFY was lost can never be buried by a local
   note (the FINDING-1 regression is pinned here)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
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


(deftest a-state-behind-another-still-sees-its-noted-bumps-test
  ;; Two epoch STATES over one ledger (a raw merge-post-commit / heal
  ;; thread under the parallel test plugin's per-thread isolation): the
  ;; one that validates first advances and prunes. The one behind must
  ;; still find the noted entries — with retention it does; the old
  ;; `prune!` dropped them and it healed on a phantom foreign gap.
  (let [base (storage)
        v (vs/wrap-with-versioning base)
        healed (atom 0)
        run (fn [state f]
              (binding [br/*epoch-state-override* state
                        br/*epoch-check-ttl-ms* 0
                        br/*epoch-heal-sync?* true
                        epoch/*request-bump-log* (atom [])
                        cr/*impl-override* {:rebuild-optimistic! (fn [_ _] (swap! healed inc) true)
                                            :rebuild! (fn [_] (swap! healed inc))}]
                (f)))
        router (router-over v {(vs/current-branch-id v) {:ctx {:x 1} :handler :h}})
        ahead (fresh-state)
        behind (fresh-state)]
    (try
      (is (zero? (epoch/current base)) "a never-bumped sequence reads as epoch 0, not 1")
      (run ahead (fn [] (br/handler-for router nil)))
      (run behind (fn [] (br/handler-for router nil)))
      (is (zero? @healed) "both states start level")
      (run ahead (fn []
                   (sp/create-entity v :fn {:name "shared" :parent-ids [] :description "h"})
                   (epoch/note-applied! base)
                   (br/handler-for router nil)))
      (is (zero? @healed) "the writer's state advances over its noted bump")
      (testing "the state behind classifies the same (pruned-by-the-other) range as applied"
        (run behind (fn [] (br/handler-for router nil)))
        (is (zero? @healed)))
      (testing "past the retention window the entry is gone — a genuinely stale state heals"
        (run ahead (fn []
                     (sp/create-entity v :fn {:name "later" :parent-ids [] :description "h"})
                     (epoch/note-applied! base)
                     (binding [epoch/*ledger-retention-ms* 0]
                       (br/handler-for router nil))))
        (run behind (fn [] (br/handler-for router nil)))
        (is (pos? @healed)))
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
                br/*epoch-heal-sync?* true
                epoch/*request-bump-log* (atom [])
                cr/*impl-override* {:rebuild-optimistic! (fn [_ _] (swap! healed inc) true)
                                    :rebuild! (fn [_] (swap! healed inc))}]
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
              (is (= n @healed))))))
      (finally (sp/close base)))))


(deftest heal-rebuilds-the-base-and-drops-other-cached-ctxs-test
  ;; A heal used to rebuild EVERY cached branch ctx — O(cached
  ;; branches) full compiles per heal, minutes on a workspace that keeps
  ;; its merged source branches (they cannot be deleted while the target
  ;; resolves through them), stalling writes past the abort budget and
  ;; feeding the next heal. Now: the base is refreshed in place, every
  ;; other entry is dropped and rebuilt on its next request.
  (let [base (storage)
        v (vs/wrap-with-versioning base)
        rebuilt (atom [])]
    (try
      (binding [br/*epoch-state-override* (fresh-state)
                br/*epoch-check-ttl-ms* 0
                br/*epoch-heal-sync?* true
                epoch/*request-bump-log* (atom [])
                cr/*impl-override* {:rebuild-optimistic! (fn [c _] (swap! rebuilt conj (:x c)) true)
                                    :rebuild! (fn [c] (swap! rebuilt conj (:x c)))}]
        (let [main-id (vs/current-branch-id v)
              other-id (random-uuid)
              router (router-over v {main-id {:ctx {:x :main} :handler :h}
                                     other-id {:ctx {:x :other} :handler :h}})]
          (foreign-bump! base)
          (br/handler-for router nil)
          (is (= [:main] @rebuilt) "only the base ctx is rebuilt in place")
          (is (contains? @(:handlers router) main-id) "the base entry stays")
          (is (not (contains? @(:handlers router) other-id))
              "the other branch's entry is dropped — its next request rebuilds it")))
      (finally (sp/close base)))))


(deftest heal-refreshes-a-pinned-branch-ctx-in-place-test
  ;; A branch with a RUNNING service is pinned (the reconciler registers
  ;; the seam): its ctx is the service's ctx, held by reference. A heal
  ;; that dropped it left the service on a registry nobody refreshed
  ;; while every request built a second, divergent ctx for the same
  ;; branch. Pinned entries rebuild in place like the base; the rest
  ;; still drop; a pinned id whose branch row is gone still drops.
  (let [base (storage)
        v (vs/wrap-with-versioning base)
        rebuilt (atom [])]
    (try
      (binding [br/*epoch-state-override* (fresh-state)
                br/*epoch-check-ttl-ms* 0
                br/*epoch-heal-sync?* true
                epoch/*request-bump-log* (atom [])
                cr/*impl-override* {:rebuild-optimistic! (fn [c _] (swap! rebuilt conj (:x c)) true)
                                    :rebuild! (fn [c] (swap! rebuilt conj (:x c)))}]
        (let [main-id (vs/current-branch-id v)
              svc-id (:id (sp/create-entity base :branch
                                            {:name "svc-branch"
                                             :created-at (java.time.Instant/now)}))
              other-id (random-uuid)
              gone-id (random-uuid)
              router (router-over v {main-id {:ctx {:x :main} :handler :h}
                                     svc-id {:ctx {:x :svc} :handler :h}
                                     other-id {:ctx {:x :other} :handler :h}
                                     gone-id {:ctx {:x :gone} :handler :h}})]
          (br/set-pinned-branches-fn! (fn [] #{svc-id gone-id}))
          (try
            (foreign-bump! base)
            (br/handler-for router nil)
            (is (= #{:main :svc} (set @rebuilt))
                "the base AND the pinned branch rebuild in place")
            (is (contains? @(:handlers router) svc-id) "the pinned entry stays")
            (is (not (contains? @(:handlers router) other-id))
                "an unpinned branch still drops")
            (is (not (contains? @(:handlers router) gone-id))
                "a pinned id with no branch row drops (a deleted branch)")
            (finally (br/set-pinned-branches-fn! nil)))))
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
                br/*epoch-heal-sync?* true
                epoch/*request-bump-log* (atom [])
                cr/*impl-override* {:rebuild-optimistic! (fn [_ _] (swap! healed inc) true)
                                    :rebuild! (fn [_] (swap! healed inc))}]
        (let [router (router-over v {(vs/current-branch-id v)
                                     {:ctx {:x 1} :handler :h}})]
          (sp/create-entity v :fn {:name "pend" :parent-ids [] :description "h"})
          (br/handler-for router nil)
          (is (zero? @healed) "pending local bump: wait, don't heal")
          (epoch/note-applied! base)
          (br/handler-for router nil)
          (is (zero? @healed) "noted range advances without healing")))
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
                br/*epoch-heal-sync?* true
                br/*epoch-heal-grace-ms* 0
                epoch/*request-bump-log* (atom [])
                cr/*impl-override* {:rebuild-optimistic! (fn [_ _] (swap! healed inc) true)
                                    :rebuild! (fn [_] (swap! healed inc))}]
        (let [router (router-over v {(vs/current-branch-id v)
                                     {:ctx {:x 1} :handler :h}})]
          (sp/create-entity v :fn {:name "abt" :parent-ids [] :description "h"})
          ;; NO note — simulated abort; grace 0 ⇒ aged out instantly
          (br/handler-for router nil)
          (is (pos? @healed))))
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
                br/*epoch-heal-sync?* true
                epoch/*request-bump-log* (atom [])
                cr/*impl-override* {:rebuild-optimistic! (fn [_ _] (swap! healed inc) true)
                                    :rebuild! (fn [_] (swap! healed inc))}]
        (let [router (router-over v {(vs/current-branch-id v)
                                     {:ctx {:x 1} :handler :h}})
              foreign (foreign-bump! base)]
          (br/note-graph-epoch-covered! v [foreign])
          (br/handler-for router nil)
          (is (zero? @healed) "covered epoch satisfies the range")))
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
                br/*epoch-heal-sync?* true
                epoch/*request-bump-log* (atom [])
                cr/*impl-override* {:rebuild-optimistic! (fn [_ _] (swap! healed inc) true)
                                    :rebuild! (fn [_] (swap! healed inc))}]
        (let [router (router-over v {(vs/current-branch-id v)
                                     {:ctx {:x 1} :handler :h}})]
          (sp/create-entity v :fn {:name "rgr" :parent-ids [] :description "h"})
          (br/handler-for router nil)
          (testing "regression detected → reseed + heal instead of dead"
            (is (pos? @healed))
            (is (< (:w @state) 999999)))))
      (finally (sp/close base)))))


(deftest cross-pod-branch-delete-drops-ctx-and-ref-cache-test
  ;; SYSTEM F1: a branch DELETE on another pod bumps the shared :branch
  ;; epoch (foreign, un-noted here) AND removes the branch row. The lazy
  ;; heal used to blindly REBUILD every cached ctx — resurrecting a
  ;; phantom ctx for the dead branch and leaving the name→id ref-cache
  ;; pointing at it (a same-name recreate then routes to a dead
  ;; registry). The heal must instead DROP a cached branch whose row is
  ;; gone, exactly like the local delete path.
  (let [base (storage)
        v (vs/wrap-with-versioning base)
        healed (atom 0)]
    (try
      (binding [br/*epoch-state-override* (fresh-state)
                br/*epoch-check-ttl-ms* 0
                br/*epoch-heal-sync?* true
                br/*epoch-heal-grace-ms* 0
                epoch/*request-bump-log* (atom [])
                cr/*impl-override* {:rebuild-optimistic! (fn [_ _] (swap! healed inc) true)
                                    :rebuild! (fn [_] (swap! healed inc))}]
        (let [default-id (vs/current-branch-id v)
              feat (vs/create-branch! v "featB" {:base-branch-id default-id})
              feat-id (:id feat)
              ref-cache (atom {[nil "featB"] feat-id})
              router (assoc (router-over v {default-id  {:ctx {:x 1} :handler :h}
                                            feat-id     {:ctx {:x 2} :handler :h}})
                            :ref-cache ref-cache)]
          ;; Advance the watermark past the (noted) branch creation.
          (epoch/note-applied! base)
          (br/handler-for router nil)
          (is (zero? @healed) "noted create advances without healing")
          ;; Simulate the cross-pod delete: row gone + a FOREIGN (un-noted
          ;; here) :branch epoch bump on a ledger-less handle.
          (sp/delete-entity base :branch feat-id)
          (epoch/bump! (dissoc base :graph-epoch-local :graph-epoch-covered) :branch)
          (br/handler-for router nil)
          (testing "the deleted branch's cached ctx is DROPPED, not rebuilt"
            (is (not (contains? @(:handlers router) feat-id)))
            (is (contains? @(:handlers router) default-id)
                "the still-live default branch stays cached"))
          (testing "its name→id ref-cache entry is forgotten"
            (is (empty? @ref-cache)))))
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


(deftest refused-write-notes-its-own-bump-test
  ;; The epoch is bumped BEFORE a graph-shaped write. A write the storage
  ;; refuses (here: a name collision) changes nothing, but its bump used to
  ;; stay un-noted, age past the grace and cost a heal — a full base
  ;; rebuild per user error. Now the refusing write marks its own bump
  ;; applied.
  (let [base (storage)
        v (vs/wrap-with-versioning base)]
    (try
      (binding [epoch/*request-bump-log* (atom [])]
        (sp/create-entity v :fn {:name "dup-x" :parent-ids [] :description "h"})
        (epoch/note-applied! base)
        (let [before (epoch/current base)]
          (is (thrown? clojure.lang.ExceptionInfo
                (sp/create-entity v :fn {:name "dup-x" :parent-ids [] :description "h"})))
          (let [after (epoch/current base)]
            (is (= (inc before) after) "the refused write still took its bump")
            (is (true? (:noted? (get @(:graph-epoch-local base) after)))
                "…and marked it applied: nothing changed, no heal is owed")
            (is (= #{:applied}
                   (epoch/classify-range base (dec before) after 0))
                "the validator sees a fully applied range even with zero grace"))))
      (finally (sp/close base)))))
