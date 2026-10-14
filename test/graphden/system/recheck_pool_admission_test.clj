(ns ^:integration ^:serial graphden.system.recheck-pool-admission-test
  "Recovery admission keeps a foreground slot on the two-connection test pool."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.storage.tx :as tx]
    [graphden.system.branch-router.recheck-lifecycle :as lifecycle]
    [graphden.system.branch-router.recheck-state :as state]
    [graphden.system.init.storage]
    [integrant.core :as ig]
    [next.jdbc :as jdbc]))


(def ^:dynamic *container* nil)
(use-fixtures :once (pth/create-container-fixture #'*container*))


(defn- recovery-ctx
  [storage]
  {:storage storage :rich-types-atom (atom {:by-id {} :by-name {}})
   :per-org-rich-atom (atom {}) :invalidation-count (atom 0)
   :type-recheck-state (atom {:generation 0 :status :idle})})


(defn- eventually?
  [pred]
  (let [deadline (+ (System/nanoTime) 15000000000)]
    (loop []
      (cond
        (pred) true
        (>= (System/nanoTime) deadline) false
        :else (do (Thread/sleep 25) (recur))))))


(defn- rejection-type
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (:type (ex-data e)))))


(deftest two-recovery-contexts-leave-a-foreground-connection
  (let [storage (pg/create-storage (assoc (pth/get-container-config *container*) :pool-size 2))
        schema (ig/init-key :db/schema {})
        first-ctx (recovery-ctx storage)
        second-ctx (recovery-ctx storage)
        retired-ctx (recovery-ctx storage)
        entered (promise)
        release (promise)
        second-entered (promise)
        retired-reads (atom 0)]
    (try
      (sp/initialize storage schema)
      (with-open [conn (jdbc/get-connection (tx/datasource storage))]
        (is (= :recheck/admission-requires-pool
               (rejection-type
                 #(lifecycle/try-with-recovery-admission (tx/with-connection storage conn)
                                                         (constantly true)))))
        (is (= 1 (:value (jdbc/execute-one! conn ["SELECT 1 AS value"])))))
      (let [discover (fn [ctx]
                       (cond
                         (identical? ctx first-ctx)
                         (jdbc/with-transaction [conn (tx/datasource (:storage ctx))
                                                 {:read-only true}]
                                                (jdbc/execute-one! conn ["SELECT 1"])
                                                (deliver entered true)
                                                @release
                                                [])

                         (identical? ctx second-ctx)
                         (do (deliver second-entered true) [])

                         :else (do (swap! retired-reads inc) [])))
            check-batch (fn [_ _ _] nil)]
        (lifecycle/schedule! first-ctx nil discover check-batch)
        (is (true? (deref entered 5000 ::timeout)) "first worker holds one read transaction")
        (lifecycle/schedule! second-ctx nil discover check-batch)
        (lifecycle/schedule! retired-ctx nil discover check-batch)
        (state/cancel! retired-ctx)
        (is (= ::waiting (deref second-entered 150 ::waiting))
            "second worker cannot start a snapshot before admission")
        (let [foreground (future (sp/initialize storage schema))]
          (is (not= ::timeout (deref foreground 10000 ::timeout))
              "foreground schema initialization finishes with first recovery snapshot open"))
        (deliver release true)
        (is (eventually? #(= :ready (:status @(:type-recheck-state first-ctx)))))
        (is (eventually? #(= :ready (:status @(:type-recheck-state second-ctx))))
            "busy worker retries and publishes after admission")
        (is (eventually? #(false? (:worker-running? @(:type-recheck-state retired-ctx))))
            "cancelled worker exits after admission clears")
        (is (zero? @retired-reads) "retired generation never reads after admission")
        (is (= :idle (:status @(:type-recheck-state retired-ctx)))))
      (finally
        (deliver release true)
        (sp/close storage)))))


(deftest closing-pool-drains-active-and-rejects-queued-work
  (let [storage (pg/create-storage (assoc (pth/get-container-config *container*) :pool-size 2))
        datasource (tx/datasource storage)
        entered (promise)
        release (promise)
        close-started? (atom false)
        busy-count (atom 0)
        queued-reads (atom 0)]
    (try
      (let [active (future
                     (tx/try-with-background-admission
                       datasource
                       #(with-open [conn (jdbc/get-connection datasource)]
                          (jdbc/execute-one! conn ["SELECT 1"])
                          (deliver entered true)
                          @release
                          true)))]
        (is (true? (deref entered 5000 ::timeout)))
        (let [queued (future
                       (loop []
                         (let [result (rejection-type
                                        #(lifecycle/try-with-recovery-admission
                                           storage (fn [] (swap! queued-reads inc) true)))]
                           (if (nil? result)
                             (do (swap! busy-count inc) (Thread/sleep 25) (recur))
                             result))))]
          (is (eventually? #(pos? @busy-count)) "queued work has attempted admission")
          (let [_ (reset! close-started? true)
                closer (future (sp/close storage))]
            (is (eventually?
                  #(= :storage/background-retired
                      (rejection-type
                        (fn [] (lifecycle/try-with-recovery-admission storage (constantly true))))))
                "close retires the pool before draining its active task")
            (deliver release true)
            (is (true? (deref active 5000 ::timeout)))
            (is (nil? (deref closer 5000 ::timeout)))
            (is (= :storage/background-retired (deref queued 5000 ::timeout)))
            (is (zero? @queued-reads) "queued work never borrows a closed pool"))))
      (finally
        (deliver release true)
        (when-not @close-started? (sp/close storage))))))
