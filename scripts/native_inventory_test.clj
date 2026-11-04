(ns native-inventory-test
  (:require
    [babashka.fs :as fs]
    [clojure.test :refer [deftest is run-tests testing]]
    [native-inventory :as inventory]))


(def fixture-source
  "(ns inventory.fixture
     (:require [graphden.executor.defbase :refer [defbase]]))
   (throw (Exception. \"source must never execute\"))
   (defn helper [x] (inc x))
   (def forwarded helper)
   (defbase implementation [values]
     (let [helper identity]
       (helper values))
     (map forwarded values)
     (missing-operation values))
   (def impls {:public-name {:impl implementation :taint-propagate? true}})")


(deftest source-reader-does-not-evaluate-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Read-eval not allowed"
        (inventory/read-source "#=(System/exit 0)"))))


(deftest registered-alias-helper-and-shadowing-test
  (let [directory (fs/create-temp-dir {:prefix "native-inventory-"})
        path (fs/path directory "fixture.clj")]
    (try
      (spit (str path) fixture-source)
      (let [analysis (:analysis (inventory/analyze "." [(str path)]))
            registrations (inventory/registrations "fixture.clj"
                                                   (inventory/read-source fixture-source)
                                                   {:namespace "fixture"
                                                    :fns [{:name :public-name :return-type :any}]})
            report (inventory/inventory registrations analysis {:external {"consumer" [:fixture/public-name]}})
            entry (first (:entries report))
            graph (:source-graph report)
            edges (get-in graph ['inventory.fixture/implementation :edges])]
        (testing "Graph names are paired through impls, not matched to defbase names"
          (is (= ["fixture" :public-name] (:graph-id entry)))
          (is (= 'inventory.fixture/implementation (:implementation entry)))
          (is (= {:taint-propagate? true} (:metadata entry))))
        (testing "A shadowed local call is not attributed to the native helper"
          (is (not-any? #(= 'inventory.fixture/helper (:target %)) edges)))
        (testing "HOF var values and def aliases preserve the hidden dependency chain"
          (is (some #(and (= 'inventory.fixture/forwarded (:target %)) (= :var-value (:kind %))) edges))
          (is (some #{['inventory.fixture/implementation 'inventory.fixture/forwarded 'inventory.fixture/helper]}
                    (get-in entry [:helper-paths :paths]))))
        (testing "Unresolved calls remain explicit and existing external roots survive"
          (is (some #(and (= "missing-operation" (name (:target %))) (= :unresolved (:resolution %))) edges))
          (is (= {:external {"consumer" [:fixture/public-name]}}
                 (get-in report [:reachability :registry])))))
      (finally (fs/delete-tree directory)))))


(deftest unsupported-registration-and-bounds-test
  (is (= :unsupported
         (:registration-status
           (first (inventory/registrations "dynamic.clj"
                                           (inventory/read-source "(ns dynamic) (def impls (make-registry))")
                                           {})))))
  (let [graph {'a {:edges [{:resolution :source :target 'b}]}
               'b {:edges [{:resolution :source :target 'a}
                           {:resolution :source :target 'c}]}
               'c {:edges []}}]
    (is (= {:paths [['a] ['a 'b] ['a 'b 'c]] :truncated? false :limit-reasons []}
           (inventory/helper-paths graph 'a 6 128)))
    (is (= {:paths [['a] ['a 'b]] :truncated? true :limit-reasons [:depth]}
           (inventory/helper-paths graph 'a 1 128)))
    (is (= {:paths [['a]] :truncated? true :limit-reasons [:nodes]}
           (inventory/helper-paths graph 'a 6 1)))))


(deftest namespace-alias-registration-test
  (let [entries (inventory/registrations "impls.clj"
                                         (inventory/read-source "(ns example) (def impls {:x adapter/run})")
                                         {:namespace "example" :fns [{:name :x}]})
        analysis {:namespace-usages [{:from 'example :alias 'adapter :to 'native.adapter}]
                  :var-definitions [{:ns 'native.adapter :name 'run :row 3 :filename "adapter.clj"}]}
        entry (first (:entries (inventory/inventory entries analysis {})))]
    (is (= 'native.adapter/run (:implementation entry)))
    (is (= :source (:implementation-status entry)))
    (is (= [['native.adapter/run]] (get-in entry [:helper-paths :paths])))))


(deftest targeted-report-and-cli-limits-test
  (let [options (inventory/cli-options ["--entry" "fixture/a" "--max-depth" "12" "--max-nodes" "512"])
        entries [{:graph-id ["fixture" :a] :implementation 'native/a :registration-status :literal}
                 {:graph-id ["fixture" :b] :implementation 'native/b :registration-status :literal}]
        analysis {:var-definitions [{:ns 'native :name 'a} {:ns 'native :name 'b}]}
        report (inventory/inventory entries analysis {} options)]
    (is (= {:entry "fixture/a" :max-depth 12 :max-nodes 512} options))
    (is (= [["fixture" :a]] (mapv :graph-id (:entries report))))
    (is (= #{'native/a} (set (keys (:source-graph report)))))
    (is (= :unreviewed (:review-status (first (:entries report)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"No matching"
          (inventory/inventory entries analysis {} {:entry "fixture/missing"}))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Traversal limit"
        (inventory/cli-options ["--max-depth" "0"])))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Expected"
        (inventory/cli-options ["--entry"]))))


(let [{:keys [fail error]} (run-tests 'native-inventory-test)]
  (when (pos? (+ fail error)) (System/exit 1)))
