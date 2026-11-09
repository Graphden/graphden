(ns ^:serial graphden.storage.remote.index-test
  "Snapshot index semantics and structural work counts; no network or DB.
   Serial because refresh tests replace the public bundle fetcher."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.storage.bounded-query :as bounded]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.remote.core :as remote]))


(deftest indexed-queries-retain-matching-and-order-semantics
  (let [rows [{:id :a :tag :first :fn-id :owner :name "same" :namespace-id :n1}
              {:id :b :tag :second :fn-id :other :name "same" :namespace-id :n2}
              {:id :a :tag :duplicate :fn-id :owner :name "different" :extra true}
              {:id nil :tag :nil :fn-id nil :name nil}
              {:tag :missing}
              {:id :nested :tag :nested :fn-id [:x :y] :namespace-id #{:n1}
               :payload {:k "v"}}]
        storage (remote/from-bundle {:fn rows})
        tags #(mapv :tag (sp/query-entities storage :fn %))]
    (doseq [[where expected]
            [[{} [:first :second :duplicate :nil :missing :nested]]
             [nil [:first :second :duplicate :nil :missing :nested]]
             [{:id [:b :a :a]} [:first :second :duplicate]]
             [{:id #{:b :a}} [:first :second :duplicate]]
             [{:id []} []]
             [{:id #{}} []]
             [{:id nil} [:nil :missing]]
             [{:fn-id [nil]} [:nil :missing]]
             [{:name "same"} [:first :second]]
             [{:name "same" :namespace-id :n1} [:first]]
             [{:fn-id :owner :extra true} [:duplicate]]
             [{:fn-id :owner :extra nil} [:first]]
             [{:fn-id [:owner :other]} [:first :second :duplicate]]
             [{:fn-id (list :x :y)} [:nested]]
             [{:namespace-id #{#{:n1}}} [:nested]]
             [{:payload {:k "v"}} [:nested]]]]
      (testing (str where)
        (is (= expected (tags where)))))
    (testing "single reads keep the first duplicate; batch maps keep the last"
      (is (= (first rows) (sp/read-entity storage :fn :a))))
    (testing "batch membership does not multiply rows for repeated query IDs"
      (is (= {:a (nth rows 2) :b (second rows)}
             (sp/read-entities storage :fn [:b :a :a]))))
    (testing "limits retain original order"
      (is (= [(first rows) (second rows)]
             (sp/query-entities storage :fn {:name "same"} {:limit 2}))))
    (is (= [] (sp/query-entities storage :absent {:id nil})))))


(deftest constructors-and-direct-row-replacement-remain-compatible
  (let [old-row {:id :old :name "old"}
        new-row {:id :new :name "new"}
        storage (remote/->RemoteStorage (atom {:fn (list old-row)}) nil nil nil)]
    (is (= [old-row] (sp/query-entities storage :fn {:name "old"})))
    (testing "assoc inherits metadata, but a changed table cannot use its old index"
      (swap! (:rows storage) assoc :fn [new-row])
      (is (= [new-row] (sp/query-entities storage :fn {:name "new"})))
      (is (= [] (sp/query-entities storage :fn {:name "old"}))))
    (testing "reset with a plain bundle has no index metadata"
      (reset! (:rows storage) {:fn [old-row]})
      (is (= old-row (sp/read-entity storage :fn :old)))
      (is (nil? (sp/read-entity storage :fn :new))))
    (testing "empty tables and newly added entity tables get their own indexes"
      (swap! (:rows storage) assoc :fn [] :slot [{:id :slot}])
      (is (= [] (sp/query-entities storage :fn {:id nil})))
      (is (= {:id :slot} (sp/read-entity storage :slot :slot))))))


(deftest refresh-replaces-rows-and-indexes-together
  (let [old-row {:id :old :fn-id :owner :org-id "a"}
        new-row {:id :new :fn-id :owner :org-id "a"}
        storage (remote/from-bundle {:binding [old-row]})
        checked bounded/check-candidate-count!
        refresh-once (atom true)]
    (is (= [old-row] (sp/query-entities storage :binding {:fn-id :owner})))
    (with-redefs [remote/fetch-graph-rows (fn [& _] {:binding [new-row]})
                  bounded/check-candidate-count!
                  (fn [candidates limit]
                    (when (compare-and-set! refresh-once true false)
                      (is (true? (remote/refresh! storage))))
                    (checked candidates limit))]
      (let [result (sp/query-bounded-entities storage :binding {:fn-id :owner :org-id "a"} 1)]
        (is (= [old-row] result) "refresh between candidate counting and projection cannot mix generations")
        (is (= 1 (::sp/candidate-count (meta result)))))
      (is (= [new-row] (sp/query-entities storage :binding {:fn-id :owner})))
      (is (nil? (sp/read-entity storage :binding :old))))
    (testing "a failed refresh preserves the current rows and indexes"
      (with-redefs [remote/fetch-graph-rows (fn [& _] (throw (ex-info "Offline fixture" {})))]
        (is (false? (remote/refresh! storage)))
        (is (= new-row (sp/read-entity storage :binding :new)))))))


(deftype CountedValue
  [value comparisons]

  clojure.lang.IDeref

  (deref [_] value)


  Object

  (hashCode [_] (hash value))


  (equals
    [_ other]
    (swap! comparisons inc)
    (and (instance? CountedValue other)
         (= value @other))))


(defn- lookup-comparisons
  [size]
  (let [comparisons (atom 0)
        rows (mapv (fn [i] {:id i :name (CountedValue. i comparisons)}) (range size))
        storage (remote/from-bundle {:fn rows})
        probe (CountedValue. (dec size) comparisons)]
    ;; Index construction is paid once. Subsequent reads must not walk the table.
    (sp/query-entities storage :fn {:name probe})
    (reset! comparisons 0)
    (dotimes [_ 3]
      (is (= [(last rows)] (sp/query-entities storage :fn {:name probe}))))
    @comparisons))


(deftest indexed-lookup-work-does-not-grow-with-unrelated-rows
  (is (= 6 (lookup-comparisons 64) (lookup-comparisons 4096))
      "each lookup does one index-key comparison and one full-predicate comparison"))


(deftest bounded-query-stops-matching-after-its-candidate-budget
  (let [comparisons (atom 0)
        owner (CountedValue. 1 comparisons)
        probe (CountedValue. 1 comparisons)
        storage (remote/from-bundle {:binding (mapv (fn [i] {:id i :fn-id owner}) (range 4096))})]
    (sp/query-entities storage :binding {:fn-id probe})
    (reset! comparisons 0)
    (is (= :storage-error/candidate-limit
           (:type (try (sp/query-bounded-entities storage :binding {:fn-id probe} 1)
                       (catch clojure.lang.ExceptionInfo error (ex-data error))))))
    (is (= 3 @comparisons) "one index lookup plus two candidate matches, then stop")))
