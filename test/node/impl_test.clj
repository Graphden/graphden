 (ns node.impl-test
   (:require
    [clojure.test :refer [deftest is]]
    [node.impl :as impl]
    [node.interface :as iface]))

(deftest test-init-base-node
  (let [n (impl/init :A [{:arg-name :x :arg-val 1}
                         {:arg-name :y :arg-val 2}])]
    (is (= :A (:node-name n)))
    (is (nil? (:parent-name n)))
    (is (= 2 (count (:args n))))
    (is (= 1 (-> n :args first :arg-val)))
    (is (= 2 (-> n :args second :arg-val)))))

(deftest test-init-child-node
  (let [n (impl/init :B :A [{:arg-name :z :arg-val 3}])]
    (is (= :B (:node-name n)))
    (is (= :A (:parent-name n)))
    (is (= 3 (:arg-val ((:args n) :z))))))

(deftest test-change-arg-val
  (let [n (impl/init :B :A [{:arg-name :x :arg-val 1}])
        updated (iface/change-arg-val n :x 42)]
    (is (= 42 (:arg-val ((:args updated) :x))))))

(deftest test-change-arg-val-on-base
  (let [n (impl/init :Root [{:arg-name :a :arg-val 1}])]
    (is (thrown-with-msg? Exception #"Can't change arg in base node"
          (iface/change-arg-val n :a 2)))))

(deftest test-add-delete-child-back-ref
  (let [n (impl/init :N [])
        n2 (iface/add-child-back-ref n :child-1)
        n3 (iface/delete-child-back-ref n2 :child-1)]
    (is (contains? (:children-back-refs (:fast-refs n2)) :child-1))
    (is (not (contains? (:children-back-refs (:fast-refs n3)) :child-1)))))

(deftest test-rename-child-back-ref
  (let [n1 (-> (impl/init :N [])
               (iface/add-child-back-ref :old-child)
               (iface/rename-child-back-ref :old-child :new-child))]
    (is (contains? (:children-back-refs (:fast-refs n1)) :new-child))
    (is (not (contains? (:children-back-refs (:fast-refs n1)) :old-child)))))

(deftest test-rename-node
  (let [n (impl/init :A [])
        renamed (iface/rename-node n :B)]
    (is (= :B (:node-name renamed)))))

(deftest test-add-args-back-ref
  (let [n (impl/init :X [])
        n2 (iface/add-args-back-ref n :arg-node :arg-a)]
    (is (= #{:arg-a} (get-in n2 [:fast-refs :args-back-refs :arg-node])))))

(deftest test-set-base-node-name
  (let [n (impl/init :X :P [{:arg-name :a}])
        n2 (iface/set-base-node-name n :base-1)]
    (is (= :base-1 (get-in n2 [:fast-refs :base-node-name])))))

(deftest test-set-full-args
  (let [base-args [{:arg-name :a :arg-val nil}]
        child (impl/init :C :B [{:arg-name :a :arg-val 42}])
        updated (iface/set-full-args child base-args)]
    (is (= 42 (-> updated :fast-refs :full-args first :arg-val)))))

(deftest test-delete-arg-back-ref-node-removes-entry
  (let [n (impl/init {:node-name :A
                      :args [{:arg-name :a}]
                      :fast-refs {:args-back-refs {:N1 #{:x :y}}}})
        n2 (impl/delete-arg-back-ref-node n :N1 :x)]
    (is (= #{:y} (get-in n2 [:fast-refs :args-back-refs :N1])))))

(deftest test-delete-arg-back-ref-node-removes-key-if-empty
  (let [n (impl/init {:node-name :A
                      :args [{:arg-name :a}]
                      :fast-refs {:args-back-refs {:N1 #{:x}}}})
        n2 (impl/delete-arg-back-ref-node n :N1 :x)]
    (is (nil? (get-in n2 [:fast-refs :args-back-refs :N1])))
    (is (= {} (get-in n2 [:fast-refs :args-back-refs])))))

(deftest test-delete-arg-back-ref-node-does-nothing-if-arg-missing
  (let [n (impl/init {:node-name :A
                      :args [{:arg-name :a}]
                      :fast-refs {:args-back-refs {:N1 #{:a}}}})
        n2 (impl/delete-arg-back-ref-node n :N1 :zzz)]
    (is (= #{:a} (get-in n2 [:fast-refs :args-back-refs :N1])))))

(deftest test-delete-arg-back-ref-node-does-nothing-if-node-missing
  (let [n (impl/init {:node-name :A
                      :args [{:arg-name :a}]
                      :fast-refs {:args-back-refs {:N1 #{:a}}}})
        n2 (impl/delete-arg-back-ref-node n :UNKNOWN :a)]
    (is (= {:N1 #{:a}} (get-in n2 [:fast-refs :args-back-refs])))))
