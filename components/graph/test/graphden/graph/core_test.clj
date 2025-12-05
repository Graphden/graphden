(ns graphden.graph.core-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.graph.core :as graph-core]
    [graphden.graph.interface :as graph]
    [graphden.schema-malli.core :as schema-malli]))


;; === Basic operations ===

(deftest test-create-graph
  (testing "Create empty graph"
    (let [g (graph/create-graph)]
      (is (= {} (:nodes g)))
      (is (= {} (:children g)))
      (is (= {} (:root-ancestor g))))))


(deftest test-add-base-node
  (testing "Add base node (no parent)"
    (let [g (graph/add-node (graph/create-graph)
                            {:node-name :sum
                             :args [{:arg-name :a :arg-val nil}
                                    {:arg-name :b :arg-val nil}]})]
      (is (graph/node-exists? g :sum))
      (let [node (graph/get-node g :sum)]
        (is (= :sum (:node-name node)))
        (is (nil? (:parent-name node)))
        (is (= 2 (count (:args node))))))))


(deftest test-add-child-node
  (testing "Add child node"
    (let [g (-> (graph/create-graph)
                (graph/add-node {:node-name :print
                                 :args [{:arg-name :val :arg-val nil}]})
                (graph/add-node {:node-name :sum
                                 :args [{:arg-name :a :arg-val nil}
                                        {:arg-name :b :arg-val nil}]})
                (graph/add-node {:node-name :print-sum
                                 :parent-name :print
                                 :args [{:arg-name :val :arg-val :sum}]}))
          node (graph/get-node g :print-sum)]
      (is (= :print-sum (:node-name node)))
      (is (= :print (:parent-name node))))))


(deftest test-add-node-validation-errors
  (testing "Cannot add node with non-existent parent"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Parent node does not exist"
          (graph/add-node (graph/create-graph)
                          {:node-name :child
                           :parent-name :nonexistent
                           :args []}))))

  (testing "Cannot add node with non-existent arg reference"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Arg references non-existent node"
          (-> (graph/create-graph)
              (graph/add-node {:node-name :parent
                               :args [{:arg-name :x :arg-val nil}]})
              (graph/add-node {:node-name :child
                               :parent-name :parent
                               :args [{:arg-name :x :arg-val :nonexistent}]})))))

  (testing "Cannot add duplicate node"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Node already exists"
          (-> (graph/create-graph)
              (graph/add-node {:node-name :base :args []})
              (graph/add-node {:node-name :base :args []}))))))


(deftest test-delete-node
  (testing "Delete node"
    (let [g (graph/add-node (graph/create-graph) {:node-name :to-delete :args []})]
      (is (graph/node-exists? g :to-delete))
      (let [g2 (graph/delete-node g :to-delete)]
        (is (not (graph/node-exists? g2 :to-delete)))))))


(deftest test-delete-node-with-children-fails
  (testing "Cannot delete node with children"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot delete node with children"
          (-> (graph/create-graph)
              (graph/add-node {:node-name :parent :args []})
              (graph/add-node {:node-name :child :parent-name :parent :args []})
              (graph/delete-node :parent))))))


(deftest test-get-root-ancestor
  (testing "Root ancestor for child is the base"
    (let [g (-> (graph/create-graph)
                (graph/add-node {:node-name :base :args []})
                (graph/add-node {:node-name :level1 :parent-name :base :args []})
                (graph/add-node {:node-name :level2 :parent-name :level1 :args []}))]
      (is (= :base (graph/get-root-ancestor g :level1)))
      (is (= :base (graph/get-root-ancestor g :level2))))))


(deftest test-get-full-args
  (testing "Full args for base node"
    (let [g (graph/add-node (graph/create-graph)
                            {:node-name :base
                             :args [{:arg-name :a :arg-val 1}
                                    {:arg-name :b :arg-val 2}]})
          full-args (graph/get-full-args g :base)]
      (is (= 2 (count full-args)))
      (is (= 1 (get-in full-args [:a :arg-val])))
      (is (= 2 (get-in full-args [:b :arg-val])))))

  (testing "Full args for child (merged with parent)"
    (let [g (-> (graph/create-graph)
                (graph/add-node {:node-name :base
                                 :args [{:arg-name :a :arg-val 1}
                                        {:arg-name :b :arg-val 2}]})
                (graph/add-node {:node-name :child
                                 :parent-name :base
                                 :args [{:arg-name :a :arg-val 10}]}))
          full-args (graph/get-full-args g :child)]
      (is (= 2 (count full-args)))
      (is (= 10 (get-in full-args [:a :arg-val])))
      (is (= 2 (get-in full-args [:b :arg-val]))))))


(deftest test-get-children
  (testing "Get children of a node"
    (let [g (-> (graph/create-graph)
                (graph/add-node {:node-name :parent :args []})
                (graph/add-node {:node-name :child1 :parent-name :parent :args []})
                (graph/add-node {:node-name :child2 :parent-name :parent :args []}))]
      (is (= #{:child1 :child2} (graph/get-children g :parent))))))


(deftest test-rename-node
  (testing "Rename a node"
    (let [g (-> (graph/create-graph)
                (graph/add-node {:node-name :old-name :args []})
                (graph/rename-node :old-name :new-name))]
      (is (not (graph/node-exists? g :old-name)))
      (is (graph/node-exists? g :new-name)))))


(deftest test-set-arg-value
  (testing "Set arg value in child node"
    (let [g (-> (graph/create-graph)
                (graph/add-node {:node-name :parent
                                 :args [{:arg-name :x :arg-val nil}]})
                (graph/add-node {:node-name :child
                                 :parent-name :parent
                                 :args [{:arg-name :x :arg-val 1}]})
                (graph/set-arg-value :child :x 42))
          node (graph/get-node g :child)
          x-arg (first (filter #(= :x (:arg-name %)) (:args node)))]
      (is (= 42 (:arg-val x-arg)))))

  (testing "Cannot set arg in base node"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot change arg in base node"
          (-> (graph/create-graph)
              (graph/add-node {:node-name :base
                               :args [{:arg-name :a :arg-val 1}]})
              (graph/set-arg-value :base :a 2))))))


;; === Additional tests ===

(deftest test-delete-nonexistent-node
  (testing "Cannot delete non-existent node"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Node does not exist"
          (graph/delete-node (graph/create-graph) :nonexistent)))))


(deftest test-delete-node-with-arg-refs
  (testing "Cannot delete node referenced as arg"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot delete node that is referenced"
          (-> (graph/create-graph)
              (graph/add-node {:node-name :target :args []})
              (graph/add-node {:node-name :source
                               :args [{:arg-name :ref :arg-val :target}]})
              (graph/delete-node :target))))))


(deftest test-rename-updates-children-parent
  (testing "Renaming parent updates children's parent-name"
    (let [g (-> (graph/create-graph)
                (graph/add-node {:node-name :old-parent :args []})
                (graph/add-node {:node-name :child :parent-name :old-parent :args []})
                (graph/rename-node :old-parent :new-parent))]
      (is (= :new-parent (:parent-name (graph/get-node g :child)))))))


(deftest test-rename-updates-arg-refs
  (testing "Renaming node updates arg references"
    (let [g (-> (graph/create-graph)
                (graph/add-node {:node-name :old-target :args []})
                (graph/add-node {:node-name :source
                                 :args [{:arg-name :ref :arg-val :old-target}]})
                (graph/rename-node :old-target :new-target))
          source-node (graph/get-node g :source)
          ref-arg (first (:args source-node))]
      (is (= :new-target (:arg-val ref-arg))))))


(deftest test-rename-nonexistent-node
  (testing "Cannot rename non-existent node"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Node does not exist"
          (graph/rename-node (graph/create-graph) :nonexistent :new-name)))))


(deftest test-rename-to-existing-name
  (testing "Cannot rename to existing node name"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Node already exists"
          (-> (graph/create-graph)
              (graph/add-node {:node-name :node1 :args []})
              (graph/add-node {:node-name :node2 :args []})
              (graph/rename-node :node1 :node2))))))


(deftest test-set-arg-nonexistent-node
  (testing "Cannot set arg on non-existent node"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Node does not exist"
          (graph/set-arg-value (graph/create-graph) :nonexistent :x 1)))))


(deftest test-set-arg-to-keyword-ref
  (testing "Set arg to keyword reference"
    (let [g (-> (graph/create-graph)
                (graph/add-node {:node-name :target :args []})
                (graph/add-node {:node-name :parent :args [{:arg-name :x :arg-val nil}]})
                (graph/add-node {:node-name :child
                                 :parent-name :parent
                                 :args [{:arg-name :x :arg-val nil}]})
                (graph/set-arg-value :child :x :target))
          node (graph/get-node g :child)
          x-arg (first (filter #(= :x (:arg-name %)) (:args node)))]
      (is (= :target (:arg-val x-arg))))))


(deftest test-set-arg-to-nonexistent-ref
  (testing "Cannot set arg to non-existent node reference"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Arg references non-existent node"
          (-> (graph/create-graph)
              (graph/add-node {:node-name :parent :args [{:arg-name :x :arg-val nil}]})
              (graph/add-node {:node-name :child
                               :parent-name :parent
                               :args [{:arg-name :x :arg-val nil}]})
              (graph/set-arg-value :child :x :nonexistent))))))


(deftest test-get-all-nodes
  (testing "Get all nodes returns all"
    (let [g (-> (graph/create-graph)
                (graph/add-node {:node-name :node1 :args []})
                (graph/add-node {:node-name :node2 :args []})
                (graph/add-node {:node-name :node3 :args []}))]
      (is (= 3 (count (graph/get-all-nodes g)))))))


(deftest test-get-arg-refs
  (testing "Get arg refs shows which nodes reference a node"
    (let [g (-> (graph/create-graph)
                (graph/add-node {:node-name :target :args []})
                (graph/add-node {:node-name :source1
                                 :args [{:arg-name :ref :arg-val :target}]})
                (graph/add-node {:node-name :source2
                                 :args [{:arg-name :ref :arg-val :target}]}))]
      (is (= 2 (count (graph/get-arg-refs g :target)))))))


;; === Schema validation tests ===

(deftest test-schema-validation-error
  (testing "Invalid node data throws validation error"
    (let [schema-provider (schema-malli/create-provider
                            {:schemas {:node [:map
                                              [:node-name :keyword]
                                              [:parent-name {:optional true} [:maybe :keyword]]
                                              [:args [:vector [:map
                                                               [:arg-name :keyword]
                                                               [:arg-val :any]]]]]}
                             :relations {}
                             :derived-queries #{}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid node data"
            (graph-core/add-node-validated
              (graph/create-graph)
              schema-provider
              {:invalid :data}))))))


(deftest test-schema-validation-success
  (testing "Valid node data passes validation and adds node"
    (let [schema-provider (schema-malli/create-provider
                            {:schemas {:node [:map
                                              [:node-name :keyword]
                                              [:parent-name {:optional true} [:maybe :keyword]]
                                              [:args [:vector [:map
                                                               [:arg-name :keyword]
                                                               [:arg-val :any]]]]]}
                             :relations {}
                             :derived-queries #{}})
          g (graph-core/add-node-validated
              (graph/create-graph)
              schema-provider
              {:node-name :valid-node
               :args [{:arg-name :x :arg-val 1}]})]
      (is (graph/node-exists? g :valid-node))
      (is (= :valid-node (:node-name (graph/get-node g :valid-node)))))))
