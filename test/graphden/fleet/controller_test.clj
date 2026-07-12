(ns graphden.fleet.controller-test
  "Move-controller orchestration (`graphden.fleet.controller`, docs/FLEET_RFC.md
   §6.3). The cross-pod effects are seams, so the whole three-step dance +
   its invariants (load-before-flip, abort-before-flip, evict-after-flip) test
   in-JVM against an in-memory `:placement` storage — no container, no k8s."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.fleet.controller :as ctrl]
    [graphden.fleet.placement :as placement]
    [graphden.storage.protocol.core :as sp]))


(defn- mem-placement-storage
  "In-memory `:placement` CRUD backed by an atom — exactly the surface
   `placement/placement-for` + `assign!` touch."
  []
  (let [rows (atom [])]
    (reify sp/StorageCRUD
      (query-entities
        [_ en where]
        (when (= en :placement)
          (filterv #(and (= (:org %) (:org where))
                         (= (:entry-fn-id %) (:entry-fn-id where)))
                   @rows)))

      (query-entities [_ _ _ _] nil)

      (create-entity
        [_ en row]
        (when (= en :placement)
          (let [r (assoc row :id (random-uuid))]
            (swap! rows conj r)
            r)))

      (read-entity [_ _ _] nil)

      (update-entity
        [_ en id patch]
        (when (= en :placement)
          (swap! rows (fn [rs] (mapv #(if (= (:id %) id) (merge % patch) %) rs)))
          (first (filter #(= (:id %) id) @rows))))

      (delete-entity [_ _ _] nil)

      (query-latest-per-group [_ _ _ _] nil))))


(def ^:private ORG "acme")
(def ^:private ENTRY #uuid "00000000-0000-0000-0000-0000000000e1")


(defn- ok-load
  "A `load-on` seam that records its calls + succeeds."
  [log]
  (fn [executor root] (swap! log conj [:load executor root]) true))


(defn- rec-evict
  [log]
  (fn [executor root] (swap! log conj [:evict executor root]) nil))


(deftest initial-placement-loads-and-assigns-no-evict
  (let [storage (mem-placement-storage)
        log (atom [])
        result (ctrl/move-cell! storage {:org ORG :entry-fn-id ENTRY
                                         :to-executor "e1"
                                         :load-on (ok-load log) :evict-on (rec-evict log)})]
    (testing "an unplaced cell is loaded on the target, then assigned at epoch 1"
      (is (= {:ok true :from nil :to "e1" :epoch 1} result))
      (is (= "e1" (placement/executor-for storage ORG ENTRY))))
    (testing "the target loaded; nothing was evicted (no source)"
      (is (= [[:load "e1" ENTRY]] @log)))))


(deftest move-loads-target-flips-epoch-evicts-source
  (let [storage (mem-placement-storage)
        log (atom [])]
    (placement/assign! storage {:org ORG :entry-fn-id ENTRY :executor-id "e1" :epoch 1})
    (let [result (ctrl/move-cell! storage {:org ORG :entry-fn-id ENTRY
                                           :to-executor "e2"
                                           :load-on (ok-load log) :evict-on (rec-evict log)})]
      (testing "cell relocates e1 → e2 with the epoch bumped"
        (is (= {:ok true :from "e1" :to "e2" :epoch 2} result))
        (is (= "e2" (placement/executor-for storage ORG ENTRY)))
        (is (= 2 (:epoch (placement/placement-for storage ORG ENTRY)))))
      (testing "target loaded BEFORE source evicted (ordering)"
        (is (= [[:load "e2" ENTRY] [:evict "e1" ENTRY]] @log))))))


(deftest load-runs-before-the-map-flips
  ;; The load-before-flip invariant: at the instant `load-on` fires, the routing
  ;; map must still name the OLD holder — proving no request could be forwarded
  ;; to a pod that hasn't compiled the cell yet.
  (let [storage (mem-placement-storage)
        holder-at-load (atom :unset)]
    (placement/assign! storage {:org ORG :entry-fn-id ENTRY :executor-id "e1" :epoch 1})
    (ctrl/move-cell! storage {:org ORG :entry-fn-id ENTRY :to-executor "e2"
                              :load-on (fn [_ _]
                                         (reset! holder-at-load
                                                 (placement/executor-for storage ORG ENTRY))
                                         true)
                              :evict-on (fn [_ _] nil)})
    (is (= "e1" @holder-at-load)
        "placement still points at the source while the target is loading")
    (is (= "e2" (placement/executor-for storage ORG ENTRY))
        "and only flips to the target after the load acked")))


(deftest target-load-failure-aborts-before-flip
  (doseq [[label failing-load]
          [["load returns false" (fn [_ _] false)]
           ["load throws" (fn [_ _] (throw (RuntimeException. "compile blew up")))]]]
    (testing label
      (let [storage (mem-placement-storage)
            evicted (atom [])]
        (placement/assign! storage {:org ORG :entry-fn-id ENTRY :executor-id "e1" :epoch 1})
        (let [result (ctrl/move-cell! storage {:org ORG :entry-fn-id ENTRY :to-executor "e2"
                                               :load-on failing-load
                                               :evict-on (rec-evict evicted)})]
          (is (= {:ok false :reason :target-load-failed :from "e1" :to "e2"} result))
          (is (= "e1" (placement/executor-for storage ORG ENTRY))
              "a failed move is a no-op — the source stays the holder")
          (is (= 1 (:epoch (placement/placement-for storage ORG ENTRY)))
              "epoch not bumped")
          (is (empty? @evicted) "source is never evicted on an aborted move"))))))


(deftest already-placed-is-a-noop
  (let [storage (mem-placement-storage)
        log (atom [])]
    (placement/assign! storage {:org ORG :entry-fn-id ENTRY :executor-id "e1" :epoch 3})
    (let [result (ctrl/move-cell! storage {:org ORG :entry-fn-id ENTRY :to-executor "e1"
                                           :load-on (ok-load log) :evict-on (rec-evict log)})]
      (is (= {:ok true :from "e1" :to "e1" :epoch 3 :noop true} result))
      (is (empty? @log) "no load/evict churn when the cell is already on the target")
      (is (= 3 (:epoch (placement/placement-for storage ORG ENTRY))) "epoch untouched"))))


(deftest nil-target-is-rejected
  (let [storage (mem-placement-storage)]
    (is (= {:ok false :reason :no-target}
           (ctrl/move-cell! storage {:org ORG :entry-fn-id ENTRY :to-executor nil
                                     :load-on (fn [_ _] true) :evict-on (fn [_ _] nil)})))))


(deftest evict-failure-is-post-flip-move-still-succeeds
  (let [storage (mem-placement-storage)]
    (placement/assign! storage {:org ORG :entry-fn-id ENTRY :executor-id "e1" :epoch 1})
    (let [result (ctrl/move-cell! storage {:org ORG :entry-fn-id ENTRY :to-executor "e2"
                                           :load-on (fn [_ _] true)
                                           :evict-on (fn [_ _] (throw (RuntimeException. "evict boom")))})]
      (testing "the move succeeds despite the source-evict throwing (routing already correct)"
        (is (= {:ok true :from "e1" :to "e2" :epoch 2} result))
        (is (= "e2" (placement/executor-for storage ORG ENTRY)))))))
