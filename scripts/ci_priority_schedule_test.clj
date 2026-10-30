;; The real runner is loaded against runtime-only stubs in this BB subprocess.
;; Keep the stubs out of static namespace analysis: they must not replace the
;; real Kaocha exports used when kondo analyzes the rest of the repository.
(load-string
  "(ns kaocha.plugin)
   (defmacro defplugin [& _] nil)
   (ns kaocha.result)
   (defn failed? [testable]
     (or (pos? (get testable :kaocha.result/fail 0))
         (pos? (get testable :kaocha.result/error 0))
         (some failed? (:kaocha.result/tests testable))))
   (ns kaocha.testable)
   (def ^:dynamic *fail-fast?* false)
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


(deftest layered-hints-keep-exact-roster-and-ignore-malformed-input
  (let [children (mapv #(hash-map :kaocha.testable/id %) [:a :b :c])
        layers (priority/namespace-layers
                 "{:unit-layers [[\"b\" \"missing\"] [\"a\" \"b\"]]}" :unit)
        indexed (priority/indexed-layers children layers)]
    (is (= [[:b] [:a]]
           (mapv #(mapv (comp :kaocha.testable/id second) %) (:layers indexed))))
    (is (= [:c] (mapv (comp :kaocha.testable/id second) (:remaining indexed))))
    (is (= [] (priority/namespace-layers "{:unit-layers [[\"a\"] :bad]}" :unit)))
    (is (= [] (priority/namespace-layers "{:unit-layers [[\"a\"]]}" :perf)))))


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
              result (with-redefs [priority/namespace-layers (fn [_ _] [])
                                   priority/namespace-hints (fn [_ _] ["priority"])
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
      (let [result (with-redefs [priority/namespace-layers (fn [_ _] [])
                                 priority/namespace-hints (fn [_ _] ["priority"])
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


(deftest dependency-layers-finish-in-order-with-parallelism-inside-each-layer
  (let [children (mapv #(hash-map :kaocha.testable/id %) [:rest :indirect :direct-a :direct-b])
        events (atom [])
        suite {:kaocha.testable/type :kaocha.type/clojure.test
               :kaocha.testable/id :unit
               :kaocha/parallelism 2
               :kaocha.test-plan/tests children}
        result (with-redefs [priority/namespace-layers
                             (fn [_ _]
                               [["direct-a" "direct-b" "missing"]
                                ["indirect" "direct-a"]])
                             priority/namespace-hints (fn [_ _] [])
                             clojure.core/requiring-resolve (constantly nil)
                             testable/run-testable
                             (fn [child _]
                               (let [id (:kaocha.testable/id child)]
                                 (swap! events conj [:start id])
                                 (when (= id :direct-a) (Thread/sleep 30))
                                 (swap! events conj [:end id])
                                 (assoc child :ran true)))]
                 (testable/-run suite {}))
        positions (zipmap @events (range))]
    (is (= (mapv #(assoc % :ran true) children) (:kaocha.result/tests result)))
    (is (= 8 (count @events)))
    (is (< (positions [:end :direct-a]) (positions [:start :indirect])))
    (is (< (positions [:end :direct-b]) (positions [:start :indirect])))
    (is (< (positions [:end :indirect]) (positions [:start :rest])))))


(deftest red-affected-layer-skips-indirect-and-remainder-without-losing-results
  (doseq [red-id [:direct-b :indirect]]
    (let [children (mapv #(hash-map :kaocha.testable/id %) [:rest :indirect :direct-a :direct-b])
          calls (atom [])
          suite {:kaocha.testable/type :kaocha.type/clojure.test
                 :kaocha.testable/id :unit
                 :kaocha/parallelism 2
                 :kaocha.test-plan/tests children}
          result (with-redefs [priority/namespace-layers
                               (fn [_ _] [["direct-a" "direct-b"] ["indirect"]])
                               priority/namespace-hints (fn [_ _] [])
                               clojure.core/requiring-resolve (constantly nil)
                               testable/run-testable
                               (fn [child _]
                                 (let [id (:kaocha.testable/id child)]
                                   (swap! calls conj id)
                                   (if (= red-id id)
                                     (assoc child :kaocha.result/tests
                                            [{:kaocha.result/error 1}])
                                     (assoc child :ran true))))]
                   (testable/-run suite {}))
          results (:kaocha.result/tests result)]
      (is (= (cond-> #{:direct-a :direct-b}
               (= red-id :indirect) (conj :indirect))
             (set @calls)))
      (is (= (mapv :kaocha.testable/id children)
             (mapv :kaocha.testable/id results)))
      (is (:kaocha.testable/skip (first results)))
      (is (= (not= red-id :indirect)
             (boolean (:kaocha.testable/skip (second results)))))
      (is (= [{:kaocha.result/error 1}]
             (:kaocha.result/tests (if (= red-id :indirect)
                                     (second results)
                                     (last results))))))))


(let [{:keys [fail error]} (run-tests 'ci-priority-schedule-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
