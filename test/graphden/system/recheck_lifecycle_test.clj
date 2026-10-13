(ns graphden.system.recheck-lifecycle-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.executor.registry.core :as registry]
    [graphden.storage.postgres.graph-epoch :as epoch]
    [graphden.system.branch-router.recheck-lifecycle :as lifecycle]
    [graphden.system.branch-router.recheck-state :as state]
    [graphden.types.diagnostics :as diagnostics]))


(use-fixtures :each
  (fn [test-fn]
    (with-redefs [epoch/current (constantly 0)]
      (test-fn))))


(defn- context
  []
  {:storage {} :rich-types-atom (atom {:by-id {} :by-name {}})
   :per-org-rich-atom (atom {}) :invalidation-count (atom 0)
   :type-recheck-state (atom {:status :pending :generation 1})})


(defn- record
  [_ _ ids]
  (doseq [id ids]
    (registry/record-rich-types-raw! id nil {:return :int :args {}})))


(deftest recovery-does-not-skip-branches-larger-than-one-batch
  (let [ctx (context)
        ids (mapv (fn [_] (random-uuid)) (range 1001))
        batches (atom [])]
    (binding [diagnostics/*diagnostics-override* (atom {})]
      (is (lifecycle/run-pass! ctx nil 1 (constantly ids)
                               (fn [& args] (swap! batches conj (last args)) (apply record args))))
      (is (= [500 500 1] (mapv count @batches)))
      (is (= ids (vec (mapcat identity @batches))) "the global dependency order survives partitioning")
      (is (= 1001 (count (:by-id @(:rich-types-atom ctx)))))
      (is (= :ready (:status @(:type-recheck-state ctx))))
      (is (empty? (:pending-ids @(:type-recheck-state ctx)))))))


(deftest a-write-during-snapshot-check-cannot-publish-stale-checker-state
  (let [ctx (context)
        id (random-uuid)
        global (atom 3)]
    (binding [diagnostics/*diagnostics-override* (atom {})]
      (with-redefs [epoch/current (fn [_] @global)]
        (is (nil? (lifecycle/run-pass! ctx nil 1 (constantly [id])
                                       (fn [& args] (apply record args) (swap! global inc)))))
        (is (nil? (get-in @(:rich-types-atom ctx) [:by-id id])))
        (is (not= :ready (:status @(:type-recheck-state ctx))))
        (is (= #{id} (:pending-ids @(:type-recheck-state ctx))))))))


(deftest retirement-and-newer-checker-certificate-refuse-staged-publication
  (doseq [[interpose-change expected-source]
          [[(fn [ctx _] (state/cancel! ctx)) nil]
           [(fn [ctx id]
              (swap! (:rich-types-atom ctx) assoc-in [:by-id id]
                     (with-meta {:return :int} {:source :newer}))) :newer]]]
    (let [ctx (context)
          id (random-uuid)]
      (binding [diagnostics/*diagnostics-override* (atom {})]
        (is (nil? (lifecycle/run-pass! ctx nil 1 (constantly [id])
                                       (fn [& args] (apply record args) (interpose-change ctx id)))))
        (is (not= :ready (:status @(:type-recheck-state ctx))))
        (is (= expected-source (:source (meta (get-in @(:rich-types-atom ctx) [:by-id id])))))))))


(deftest unavailable-storage-epoch-is-not-freshness-proof
  (let [ctx (context)
        checked? (atom false)]
    (with-redefs [epoch/current (constantly nil)]
      (is (= :recheck/storage-epoch-unavailable
             (try (lifecycle/run-pass! ctx nil 1 (constantly [])
                                       (fn [& _] (reset! checked? true)))
                  nil
                  (catch clojure.lang.ExceptionInfo e (:type (ex-data e))))))
      (is (false? @checked?))
      (is (not= :ready (:status @(:type-recheck-state ctx)))))))


(deftest recovery-does-not-wait-on-invalidation-with-a-publication-connection
  (let [lock (java.util.concurrent.locks.ReentrantLock.)
        ctx (assoc (context) :invalidation-lock lock)
        id (random-uuid)]
    (java.util.concurrent.locks.ReentrantLock/.lock lock)
    (try
      (binding [diagnostics/*diagnostics-override* (atom {})]
        (let [attempt (future (lifecycle/run-pass! ctx nil 1 (constantly [id]) record))]
          (is (nil? (deref attempt 2000 ::blocked))
              "recovery yields while a mutation holds the context lock")
          (is (= #{id} (:pending-ids @(:type-recheck-state ctx))))
          (is (nil? (get-in @(:rich-types-atom ctx) [:by-id id])))))
      (finally (java.util.concurrent.locks.ReentrantLock/.unlock lock)))
    (binding [diagnostics/*diagnostics-override* (atom {})]
      (is (lifecycle/run-pass! ctx nil 1 (constantly [id]) record))
      (is (= :ready (:status @(:type-recheck-state ctx)))))))


(deftest delayed-retirement-does-not-cancel-a-reused-context
  (let [ctx (context)
        captured (state/generation ctx)]
    (swap! (:type-recheck-state ctx) update :generation inc)
    (state/cancel! ctx captured)
    (is (= 2 (state/generation ctx)))
    (is (not (:cancelled? @(:type-recheck-state ctx))))
    (state/cancel! ctx 2)
    (is (:cancelled? @(:type-recheck-state ctx)))))
