(ns node.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [node.interface :as sut]))

(defn record->map
  [r]
  (into {} r))

(defn node->map
  [node]
  (-> node
      record->map
      (update :args #(if (map? %) (update-vals % record->map) (mapv record->map %)))
      (update :node-meta record->map)))

(def empty-meta-map
  {:children-back-refs #{}
   :args-back-refs {}
   :base-node-name nil
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

(deftest set-parent-node-test
  (let [node (sut/init-node :n [{:arg-name :a :arg-val 1}])
        updated (sut/set-parent-node node :parent)]
    (is (= :parent (:parent-name updated)))))

(deftest set-base-node-name-test
  (let [child (sut/init-node :c :parent [{:arg-name :a :arg-val :parent}])
        updated (sut/set-base-node-name child :parent)]
    (is (= :parent (-> updated :node-meta :base-node-name))))
  (testing "noop on base node"
    (let [node (sut/init-node :n [{:arg-name :a :arg-val 1}])]
      (is (= node (sut/set-base-node-name node :other))))))

(deftest add-child-back-ref-test
  (let [node (sut/init-node :n [{:arg-name :a :arg-val 1}])
        updated (sut/add-child-back-ref node :child)]
    (is (= #{:child} (-> updated :node-meta :children-back-refs)))))

(deftest delete-child-back-ref-test
  (let [node (-> (sut/init-node :n [{:arg-name :a :arg-val 1}])
                 (sut/add-child-back-ref :c)
                 (sut/delete-child-back-ref :c))]
    (is (= #{} (-> node :node-meta :children-back-refs)))))

(deftest rename-child-back-ref-test
  (let [node (-> (sut/init-node :n [{:arg-name :a :arg-val 1}])
                 (sut/add-child-back-ref :old)
                 (sut/rename-child-back-ref :old :new))]
    (is (= #{:new} (-> node :node-meta :children-back-refs)))))

(deftest add-args-back-ref-test
  (let [node (sut/init-node :n [{:arg-name :a :arg-val 1}])
        updated (sut/add-args-back-ref node :other :a)]
    (is (= #{:a} (-> updated :node-meta :args-back-refs :other)))))

(deftest rename-arg-back-ref-node-test
  (let [node (-> (sut/init-node :n [{:arg-name :a :arg-val 1}])
                 (sut/add-args-back-ref :n1 :a)
                 (sut/rename-arg-back-ref-node :n1 :n2))]
    (is (= #{:a} (-> node :node-meta :args-back-refs :n2)))))

(deftest rename-node-test
  (let [node (sut/init-node :n [{:arg-name :a :arg-val 1}])
        updated (sut/rename-node node :renamed)]
    (is (= :renamed (:node-name updated)))))

(deftest change-arg-val-test
  (let [child (sut/init-node :child :parent [{:arg-name :a :arg-val :parent}])
        updated (sut/change-arg-val child :a 42)]
    (is (= 42 (-> updated :args :a))))
  (testing "throws when changing base node arg"
    (let [node (sut/init-node :n [{:arg-name :a :arg-val 1}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Can't change arg in base node"
            (sut/change-arg-val node :a 2))))))

(deftest rename-arg-val-test
  (let [child (sut/init-node :child :parent [{:arg-name :a :arg-val :parent}])
        updated (sut/rename-arg-val child :a :renamed)]
    (is (= :renamed (-> updated :args :a))))
  (testing "throws if new arg val is not keyword"
    (let [child (sut/init-node :child :parent [{:arg-name :a :arg-val 1}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Can't rename not node arg"
            (sut/rename-arg-val child :a :x))))))

(deftest set-full-args-test
  (testing "sets full args from parent"
    (let [parent-arg {:arg-name :a :arg-val 1}
          second-parent-arg {:arg-name :b :arg-val 2}
          child-arg {:arg-name :a :arg-val :parent}
          child (sut/init-node :child :parent [child-arg])
          updated (sut/set-full-args child [parent-arg
                                            second-parent-arg])]
      (is (= [(assoc child-arg
                     :parent-node-name
                     :child)
              second-parent-arg]
             (->> updated
                  :node-meta
                  :full-args
                  (mapv record->map))))))

  (testing "throws if arg is not in parent full-args"
    (let [child (sut/init-node :child :parent [{:arg-name :x :arg-val :parent}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Unexisted arg in base for node"
            (sut/set-full-args child [])))))

  (testing "throws if arg already set in parent (non-symbolic)"
    (let [parent-arg {:arg-name :a :arg-val :foo}
          child-arg {:arg-name :a :arg-val :bar}
          child (sut/init-node :child :parent [child-arg])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"already set in parents for node"
            (sut/set-full-args child [parent-arg])))))

  (testing "throws on duplicate args in base node"
    (let [node (sut/init-node :n [{:arg-name :a :arg-val 1}
                                  {:arg-name :a :arg-val 2}])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Duplilcates in args when add node"
            (sut/set-full-args node nil))))))

(deftest full-args-with-parent-test
  (is (= 1 1)))
