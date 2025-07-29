(ns tree.impl-test
  (:require
   [clojure.test :refer [deftest is]]
   [node.impl :as node-impl]
   [tree.impl :as impl]))

(def base-node
  (node-impl/init {:node-name :base
                   :args      [{:arg-name :a :arg-val 1}]}))

(def parent-node
  (node-impl/init {:node-name :parent
                   :args      [{:arg-name :x :arg-val 2}]}))

(def child-node
  (node-impl/init {:node-name :child
                   :parent-name :parent
                   :args      [{:arg-name :x :arg-val :base}]}))

(def tree
  (-> {}
      impl/->Tree
      (impl/add-nodes [base-node parent-node])
      (impl/add-node {:node-name :child
                      :parent-name :parent
                      :args [{:arg-name :x :arg-val :base}]})))

(deftest test-add-node-errors
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Node already exists"
        (impl/add-node tree {:node-name :child
                             :parent-name :parent
                             :args []})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unexisted parent"
        (impl/add-node (impl/->Tree {}) {:node-name :x :parent-name :nope :args []})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unexisted val-node"
        (impl/add-node (impl/->Tree {:parent parent-node})
                       {:node-name :z :parent-name :parent :args [{:arg-name :x :arg-val :missing}]}))))

(deftest test-add-nodes-and-toposort
  (let [tree (impl/->Tree {:base base-node
                           :parent parent-node})
        nodes [{:node-name :child
                :parent-name :parent
                :args [{:arg-name :x :arg-val :base}]}]
        result (impl/add-nodes tree nodes)]
    (is (impl/node-name->node result :child))))

(deftest test-nodes->ref-layers
  (is (= [[:a] [:b]]
         (impl/nodes->ref-layers {:a #{}, :b #{:a}})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cyclic dependency detected"
        (impl/nodes->ref-layers {:a #{:b} :b #{:a}}))))

(deftest test-rename-node
  (let [renamed (impl/rename-node tree :child :child-renamed)]
    (is (impl/node-name->node renamed :child-renamed))
    (is (not (impl/node-name->node renamed :child))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unexisted node"
        (impl/rename-node tree :xxx :x)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Node-name already exists"
        (impl/rename-node tree :child :base))))

(deftest test-disj-child-back-ref
  (let [with-child (impl/delete-node tree :child)]
    (is (not (impl/node-name->node with-child :child)))))

(deftest test-delete-node-errors
  (let [child-used (impl/add-node (impl/->Tree {:parent parent-node :base base-node})
                                  {:node-name :child
                                   :parent-name :parent
                                   :args [{:arg-name :x :arg-val :base}]})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Can't delete node, some nodes use it as arg"
          (impl/delete-node child-used :base)))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Can't delete node, it has children"
          (impl/delete-node child-used :parent))))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unexsted node"
        (impl/delete-node (impl/->Tree {}) :nope))))

(deftest test-disj-arg-back-ref-missing-parent
  (let [tree (impl/->Tree {:a base-node})]
    (is (= tree (impl/disj-arg-back-ref tree [{:parent-node-name :nope :arg-name :x}] :a)))))

(deftest test-children->rename-parent-node-skip-missing
  (let [tree (impl/->Tree {:a base-node})
        result (impl/children->rename-parent-node tree [:a :nope] :new)]
    (is (= :new (:parent-name (impl/node-name->node result :a))))))

(deftest test-change-args-val-missing-parent
  (let [tree (impl/->Tree {:a base-node})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unexisted arg-backref-node"
          (impl/change-args-val tree [[:nope [:x]]] :z)))))

(deftest test-rename-args-back-ref-node-missing-parent
  (let [tree (impl/->Tree {:a base-node})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Unexisted arg-val-node"
          (impl/rename-args-back-ref-node tree
                                          {:x {:arg-name :x :parent-node-name :nope}}
                                          :old
                                          :new)))))
