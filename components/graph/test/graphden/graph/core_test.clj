(ns graphden.graph.core-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.cache-eager.core :as cache-eager]
    [graphden.graph.core :as graph-core]
    [graphden.graph.interface :as graph]
    [graphden.schema-malli.core :as schema-malli]
    [graphden.storage-memory.core :as storage-memory]))


;; Helper to create test graph without Integrant
(defn- create-test-components
  []
  (let [schema-provider (schema-malli/create-provider
                          {:schemas
                           {:node [:map
                                   [:node-name :keyword]
                                   [:parent-name {:optional true} [:maybe :keyword]]
                                   [:args [:vector
                                           [:map
                                            [:arg-name :keyword]
                                            [:arg-val :any]]]]]
                            :arg [:map
                                  [:arg-name :keyword]
                                  [:arg-val :any]]}
                           :relations {}
                           :derived-queries #{}})
        storage (storage-memory/create-storage {})
        cache (cache-eager/create-cache)]
    {:schema-provider schema-provider
     :storage storage
     :cache cache}))


(defn- create-test-graph
  []
  (let [{:keys [schema-provider storage cache]} (create-test-components)]
    (graph-core/create-graph storage schema-provider cache)))


;; Tests

(deftest test-add-base-node
  (let [g (create-test-graph)]
    (testing "Add base node (no parent)"
      (graph/add-node g {:node-name :sum
                         :args [{:arg-name :a :arg-val nil}
                                {:arg-name :b :arg-val nil}]})
      (let [node (graph/get-node g :sum)]
        (is (= :sum (:node-name node)))
        (is (nil? (:parent-name node)))
        (is (= 2 (count (:args node))))))))


(deftest test-add-child-node
  (let [g (create-test-graph)]
    (testing "Add child node"
      ;; Add parent first
      (graph/add-node g {:node-name :print
                         :args [{:arg-name :val :arg-val nil}]})
      ;; Add base node for reference
      (graph/add-node g {:node-name :sum
                         :args [{:arg-name :a :arg-val nil}
                                {:arg-name :b :arg-val nil}]})
      ;; Add child
      (graph/add-node g {:node-name :print-sum
                         :parent-name :print
                         :args [{:arg-name :val :arg-val :sum}]})

      (let [node (graph/get-node g :print-sum)]
        (is (= :print-sum (:node-name node)))
        (is (= :print (:parent-name node)))))))


(deftest test-add-node-validation-errors
  (let [g (create-test-graph)]
    (testing "Cannot add node with non-existent parent"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Parent node does not exist"
            (graph/add-node g {:node-name :child
                               :parent-name :nonexistent
                               :args []}))))

    (testing "Cannot add node with non-existent arg reference"
      (graph/add-node g {:node-name :parent
                         :args [{:arg-name :x :arg-val nil}]})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Arg references non-existent node"
            (graph/add-node g {:node-name :child
                               :parent-name :parent
                               :args [{:arg-name :x :arg-val :nonexistent}]}))))

    (testing "Cannot add duplicate node"
      (graph/add-node g {:node-name :base
                         :args []})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Node already exists"
            (graph/add-node g {:node-name :base
                               :args []}))))))


(deftest test-delete-node
  (let [g (create-test-graph)]
    (testing "Delete node"
      (graph/add-node g {:node-name :to-delete :args []})
      (is (some? (graph/get-node g :to-delete)))
      (graph/delete-node g :to-delete)
      (is (nil? (graph/get-node g :to-delete))))))


(deftest test-delete-node-with-children-fails
  (let [g (create-test-graph)]
    (testing "Cannot delete node with children"
      (graph/add-node g {:node-name :parent :args []})
      (graph/add-node g {:node-name :child :parent-name :parent :args []})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot delete node with children"
            (graph/delete-node g :parent))))))


(deftest test-get-root-ancestor
  (let [g (create-test-graph)]
    (testing "Root ancestor for child is the base"
      (graph/add-node g {:node-name :base :args []})
      (graph/add-node g {:node-name :level1 :parent-name :base :args []})
      (is (= :base (graph/get-root-ancestor g :level1)))

      (graph/add-node g {:node-name :level2 :parent-name :level1 :args []})
      (is (= :base (graph/get-root-ancestor g :level2))))))


(deftest test-get-full-args
  (let [g (create-test-graph)]
    (testing "Full args for base node"
      (graph/add-node g {:node-name :base
                         :args [{:arg-name :a :arg-val 1}
                                {:arg-name :b :arg-val 2}]})
      (let [full-args (graph/get-full-args g :base)]
        (is (= 2 (count full-args)))
        (is (= 1 (get-in full-args [:a :arg-val])))
        (is (= 2 (get-in full-args [:b :arg-val])))))

    (testing "Full args for child (merged with parent)"
      (graph/add-node g {:node-name :child
                         :parent-name :base
                         :args [{:arg-name :a :arg-val 10}]}) ; Override :a
      (let [full-args (graph/get-full-args g :child)]
        (is (= 2 (count full-args)))
        (is (= 10 (get-in full-args [:a :arg-val]))) ; Overridden
        (is (= 2 (get-in full-args [:b :arg-val])))))) ; Inherited
  )


(deftest test-get-children
  (let [g (create-test-graph)]
    (testing "Get children of a node"
      (graph/add-node g {:node-name :parent :args []})
      (graph/add-node g {:node-name :child1 :parent-name :parent :args []})
      (graph/add-node g {:node-name :child2 :parent-name :parent :args []})

      (let [children (graph/get-children g :parent)]
        (is (= #{:child1 :child2} children))))))


(deftest test-rename-node
  (let [g (create-test-graph)]
    (testing "Rename a node"
      (graph/add-node g {:node-name :old-name :args []})
      (graph/rename-node g :old-name :new-name)
      (is (nil? (graph/get-node g :old-name)))
      (is (some? (graph/get-node g :new-name))))))


(deftest test-set-arg-value
  (let [g (create-test-graph)]
    (testing "Set arg value in child node"
      (graph/add-node g {:node-name :parent
                         :args [{:arg-name :x :arg-val nil}]})
      (graph/add-node g {:node-name :child
                         :parent-name :parent
                         :args [{:arg-name :x :arg-val 1}]})
      (graph/set-arg-value g :child :x 42)
      (let [node (graph/get-node g :child)
            x-arg (first (filter #(= :x (:arg-name %)) (:args node)))]
        (is (= 42 (:arg-val x-arg)))))

    (testing "Cannot set arg in base node"
      (graph/add-node g {:node-name :base
                         :args [{:arg-name :a :arg-val 1}]})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot change arg in base node"
            (graph/set-arg-value g :base :a 2))))))


;; === Additional tests for coverage ===

(deftest test-delete-nonexistent-node
  (let [g (create-test-graph)]
    (testing "Cannot delete non-existent node"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Node does not exist"
            (graph/delete-node g :nonexistent))))))


(deftest test-delete-node-with-arg-refs
  (let [g (create-test-graph)]
    (testing "Cannot delete node referenced as arg"
      (graph/add-node g {:node-name :target :args []})
      (graph/add-node g {:node-name :source
                         :args [{:arg-name :ref :arg-val :target}]})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Cannot delete node that is referenced"
            (graph/delete-node g :target))))))


(deftest test-rename-updates-children-parent
  (let [g (create-test-graph)]
    (testing "Renaming parent updates children's parent-name"
      (graph/add-node g {:node-name :old-parent :args []})
      (graph/add-node g {:node-name :child
                         :parent-name :old-parent
                         :args []})
      (graph/rename-node g :old-parent :new-parent)
      (let [child-node (graph/get-node g :child)]
        (is (= :new-parent (:parent-name child-node)))))))


(deftest test-rename-updates-arg-refs
  (let [g (create-test-graph)]
    (testing "Renaming node updates arg references"
      (graph/add-node g {:node-name :old-target :args []})
      (graph/add-node g {:node-name :source
                         :args [{:arg-name :ref :arg-val :old-target}]})
      (graph/rename-node g :old-target :new-target)
      (let [source-node (graph/get-node g :source)
            ref-arg (first (:args source-node))]
        (is (= :new-target (:arg-val ref-arg)))))))


(deftest test-rename-nonexistent-node
  (let [g (create-test-graph)]
    (testing "Cannot rename non-existent node"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Node does not exist"
            (graph/rename-node g :nonexistent :new-name))))))


(deftest test-rename-to-existing-name
  (let [g (create-test-graph)]
    (testing "Cannot rename to existing node name"
      (graph/add-node g {:node-name :node1 :args []})
      (graph/add-node g {:node-name :node2 :args []})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Node already exists"
            (graph/rename-node g :node1 :node2))))))


(deftest test-set-arg-nonexistent-node
  (let [g (create-test-graph)]
    (testing "Cannot set arg on non-existent node"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Node does not exist"
            (graph/set-arg-value g :nonexistent :x 1))))))


(deftest test-set-arg-to-keyword-ref
  (let [g (create-test-graph)]
    (testing "Set arg to keyword reference"
      (graph/add-node g {:node-name :target :args []})
      (graph/add-node g {:node-name :parent :args [{:arg-name :x :arg-val nil}]})
      (graph/add-node g {:node-name :child
                         :parent-name :parent
                         :args [{:arg-name :x :arg-val nil}]})
      (graph/set-arg-value g :child :x :target)
      (let [node (graph/get-node g :child)
            x-arg (first (filter #(= :x (:arg-name %)) (:args node)))]
        (is (= :target (:arg-val x-arg)))))))


(deftest test-set-arg-to-nonexistent-ref
  (let [g (create-test-graph)]
    (testing "Cannot set arg to non-existent node reference"
      (graph/add-node g {:node-name :parent :args [{:arg-name :x :arg-val nil}]})
      (graph/add-node g {:node-name :child
                         :parent-name :parent
                         :args [{:arg-name :x :arg-val nil}]})
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Arg references non-existent node"
            (graph/set-arg-value g :child :x :nonexistent))))))


(deftest test-get-all-nodes
  (let [g (create-test-graph)]
    (testing "Get all nodes returns all"
      (graph/add-node g {:node-name :node1 :args []})
      (graph/add-node g {:node-name :node2 :args []})
      (graph/add-node g {:node-name :node3 :args []})
      (let [all-nodes (graph/get-all-nodes g)]
        (is (= 3 (count all-nodes)))))))


(deftest test-get-arg-refs
  (let [g (create-test-graph)]
    (testing "Get arg refs shows which nodes reference a node"
      (graph/add-node g {:node-name :target :args []})
      (graph/add-node g {:node-name :source1
                         :args [{:arg-name :ref :arg-val :target}]})
      (graph/add-node g {:node-name :source2
                         :args [{:arg-name :ref :arg-val :target}]})
      (let [refs (graph/get-arg-refs g :target)]
        (is (= 2 (count refs)))))))


(deftest test-schema-validation-error
  (let [g (create-test-graph)]
    (testing "Invalid node data throws validation error"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid node data"
            (graph/add-node g {:invalid :data}))))))
