(ns ^:integration ^:serial graphden.crud.free-arg-display-graph-test
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.fn-execution.lookup :as lookup]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records.ids :as ids]))


(def ^:dynamic *graph* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  (fn [f]
    (let [graph (setup/bootstrap-crud-graph-from-golden!*
                  "free-arg-display" ["core" "web"])]
      (binding [*graph* graph]
        (try (f) (finally (setup/close-graph! graph)))))))


(defn- display
  [fn-id]
  (exec/execute-with-named-args (:ctx *graph*) (ids/fn-id "web.crud" :free-arg-entries)
                                {:fn-id fn-id}))


(deftest public-holes-retain-canonical-identities-and-requiredness-test
  (let [target (ids/fn-id "core.collections" :get)
        rows (display target)
        by-name (into {} (map (juxt :name identity)) rows)]
    (is (= #{:coll :key :default} (set (keys by-name))))
    (is (= (lookup/free-arg-slot-map (:ctx *graph*) target)
           (into {} (map (juxt :name :slot-id)) rows)))
    (is (true? (get-in by-name [:default :optional?])))
    (is (false? (get-in by-name [:coll :optional?])))
    (is (every? false? (map :captured? rows)))
    (is (every? nil? (map :via rows))))
  (let [rows (display nil)]
    (is (= [] rows))
    (is (vector? rows)))
  (is (= {:entries [] :via-names {}} (lookup/free-arg-facts {} nil))))


(deftest captures-display-the-target-name-and-bound-inputs-disappear-test
  (let [{:keys [ctx storage]} *graph*]
    (setup/sync-and-invalidate!
      ctx storage
      [{:name :free-policy-reader :parent :get :lambda-params [:item]
        :args {:coll {:as :context :type :keyword-map}
               :key {:as :item :type :keyword} :default nil}}
       {:name :free-policy-root :parent :map
        :args {:func :free-policy-reader :coll []}}
       {:name :free-policy-bound :parent :free-policy-root
        :args {:context {:value {}}}}])
    (let [rows (display (ids/fn-id nil :free-policy-root))
          [row] rows]
      (is (= 1 (count rows)))
      (is (= :context (:name row)))
      (is (true? (:captured? row)))
      (is (false? (:optional? row)))
      (is (= "free-policy-reader" (:via row))))
    (is (= [] (display (ids/fn-id nil :free-policy-bound))))))


(deftest display-joins-one-fact-batch-with-explicit-false-flags-test
  (let [slot (random-uuid) target (random-uuid) calls (atom 0)]
    (with-redefs [lookup/free-arg-facts
                  (fn [_ _]
                    (swap! calls inc)
                    {:entries [{:ext-name :outer :slot-id slot :optional? true
                                :captured? nil :via target}]
                     :via-names {target "target"}})]
      (let [rows (display (random-uuid))]
        (is (= [{:name :outer :slot-id slot :optional? true :captured? false :via "target"}]
               rows))
        (is (vector? rows))
        (is (= slot (get-in rows [0 :slot-id]))))
      (is (= 1 @calls)))))
