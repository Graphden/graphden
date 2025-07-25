(ns node.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [node.interface :as sut]))

(defn record->map [r]
  (into {} r))

(defn node->map [node]
  (-> node
      record->map
      (update :args #(if (map? %) (update-vals % record->map) (mapv record->map %)))
      (update :node-meta record->map)))

(def empty-meta-map
  {:children-back-refs #{},
   :args-back-refs {},
   :base-node-name nil,
   :full-args []})

(deftest init-node-test
  (testing "base node init"
    (let [base-arg {:arg-name :a :arg-val 1}
          base-name :base-node
          node (sut/init-node base-name [base-arg])]
      (is (= {:node-name base-name
              :parent-name nil
              :args [(assoc base-arg :parent-node-name base-name)]
              :node-meta empty-meta-map}
             (node->map node)))))
  (testing "child node init"
    (let [arg {:arg-name :a :arg-val :base-node}
          node (sut/init-node :child-node :base-node [arg])]
      (is (= {:node-name :child-node
              :parent-name :base-node
              :args {:a (assoc arg :parent-node-name :child-node)}
              :node-meta empty-meta-map}
             (node->map node)))))
  (testing "throws if arg has incorrect parent-node-name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Incorrect arg parent-node-name"
                          (sut/init-node :x [{:arg-name :a
                                              :arg-val 1
                                              :parent-node-name :y}])))))
