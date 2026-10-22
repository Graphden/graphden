;; The real runner is loaded against runtime-only stubs in this BB subprocess.
;; Keep the stubs out of static namespace analysis: they must not replace the
;; real Kaocha exports used when kondo analyzes the rest of the repository.
(load-string
  "(ns kaocha.plugin)
   (defmacro defplugin [& _] nil)
   (ns kaocha.testable)
   (defmulti -run {:arglists '([testable plan])}
     (fn [testable _] (:kaocha.testable/type testable)))
   (defn run-testable [testable _] testable)
   (defn run-testables [testables plan]
     (mapv #(run-testable % plan) testables))
   (ns kaocha.type.clojure.test)
   (ns kaocha.type.ns)
   (ns kaocha.type.var)
   (ns user)")


(load-file "test/kaocha/plugin/priority.clj")
(load-file "test/kaocha/plugin/parallel.clj")


(ns ci-priority-schedule-test
  (:require
    [clojure.test :refer [deftest is run-tests]]
    [kaocha.plugin.priority :as priority]
    [kaocha.testable :as testable])
  (:import
    (java.util.concurrent
      CountDownLatch
      TimeUnit)))


(deftest hints-cannot-filter-or-duplicate-selected-roster
  (let [children (mapv #(hash-map :kaocha.testable/id %) [:a :b :c :d])
        hints (priority/namespace-hints "{:unit [\"c\" \"unknown\" \"c\" \"a\"]}" :unit)
        {:keys [priority remaining]} (priority/indexed-order children hints)
        calls (atom [])
        ;; Model the scheduling boundary: complete priority before starting rest.
        results (mapv (fn [[i child]]
                        (swap! calls conj (:kaocha.testable/id child))
                        [i (assoc child :ran true)])
                      (concat priority remaining))]
    (is (= [:c :a :b :d] @calls))
    (is (= (frequencies (map :kaocha.testable/id children)) (frequencies @calls)))
    (is (= (mapv #(assoc % :ran true) children)
           (mapv second (sort-by first results))))))


(deftest malformed-or-wrong-suite-hints-retain-original-order
  (is (= [] (priority/namespace-hints "{:perf [\"a\"]}" :perf)))
  (doseq [encoded [nil "not edn [" "{:unit [:a]}" "{:unit {}}" "{:integration [\"a\"]}"]]
    (let [children [{:kaocha.testable/id :a} {:kaocha.testable/id :b}]
          hints (priority/namespace-hints encoded :unit)
          {:keys [priority remaining]} (priority/indexed-order children hints)]
      (is (= [] priority))
      (is (= children (mapv second remaining))))))


(deftest real-runner-keeps-red-priority-and-restores-full-results-in-both-modes
  (let [serial-ns (create-ns 'priority-fixture-serial)
        _ (alter-meta! serial-ns assoc :serial true)
        children (mapv #(hash-map :kaocha.testable/id %)
                       [:regular :priority-fixture-serial :priority :other])]
    (try
      (doseq [n [1 3]]
        (let [calls (atom [])
              suite {:kaocha.testable/type :kaocha.type/clojure.test
                     :kaocha.testable/id :unit
                     :kaocha/parallelism n
                     :kaocha.test-plan/tests children}
              result (with-redefs [priority/namespace-hints (fn [_ _] ["priority"])
                                   ;; Production isolation vars are outside this fixture.
                                   clojure.core/requiring-resolve (constantly nil)
                                   testable/run-testable
                                   (fn [child _]
                                     (swap! calls conj (:kaocha.testable/id child))
                                     (assoc child :ran true
                                            :kaocha.result/fail
                                            (if (= :priority (:kaocha.testable/id child)) 1 0)))]
                       (testable/-run suite {}))]
          (is (= :priority (first @calls)))
          (when (> n 1)
            (is (= :priority-fixture-serial (second @calls))))
          (is (= (frequencies (map :kaocha.testable/id children)) (frequencies @calls)))
          (is (= (mapv #(assoc % :ran true :kaocha.result/fail
                               (if (= :priority (:kaocha.testable/id %)) 1 0))
                       children)
                 (:kaocha.result/tests result)))
          (is (= 1 (reduce + (map :kaocha.result/fail (:kaocha.result/tests result)))))))
      (finally
        (remove-ns 'priority-fixture-serial)))))


(deftest real-runner-finishes-priority-and-serial-before-concurrent-pool
  (let [serial-ns (create-ns 'priority-fixture-serial)
        _ (alter-meta! serial-ns assoc :serial true)
        pool-ids #{:pool-a :pool-b}
        children (mapv #(hash-map :kaocha.testable/id %)
                       [:pool-a :priority-fixture-serial :priority :pool-b])
        both-started (CountDownLatch. 2)
        active (atom #{})
        events (atom [])
        suite {:kaocha.testable/type :kaocha.type/clojure.test
               :kaocha.testable/id :unit
               :kaocha/parallelism 3
               :kaocha.test-plan/tests children}]
    (try
      (let [result (with-redefs [priority/namespace-hints (fn [_ _] ["priority"])
                                 clojure.core/requiring-resolve (constantly nil)
                                 testable/run-testable
                                 (fn [child _]
                                   (let [id (:kaocha.testable/id child)]
                                     (swap! active conj id)
                                     (swap! events conj [:start id @active])
                                     (try
                                       (when (pool-ids id)
                                         (CountDownLatch/.countDown both-started)
                                         (when-not (CountDownLatch/.await both-started 5 TimeUnit/SECONDS)
                                           (throw (ex-info "Pool workers did not overlap" {}))))
                                       (assoc child :ran true)
                                       (finally
                                         (swap! active disj id)
                                         (swap! events conj [:end id @active])))))]
                     (testable/-run suite {}))
            sequential-events (take 4 @events)
            pool-events (drop 4 @events)
            pool-starts (filter #(= :start (first %)) pool-events)]
        (is (= [[:start :priority #{:priority}]
                [:end :priority #{}]
                [:start :priority-fixture-serial #{:priority-fixture-serial}]
                [:end :priority-fixture-serial #{}]]
               sequential-events))
        (is (= pool-ids (set (map second pool-starts))))
        (is (every? #(every? pool-ids (nth % 2)) pool-events))
        (is (some #(= pool-ids (nth % 2)) pool-starts))
        (is (= (mapv #(assoc % :ran true) children) (:kaocha.result/tests result)))
        (is (= #{} @active)))
      (finally
        (remove-ns 'priority-fixture-serial)))))


(let [{:keys [fail error]} (run-tests 'ci-priority-schedule-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
