(ns arg.impl-test
  (:require
   [clojure.test :refer [deftest is]]
   [arg.impl :as impl]
   [arg.interface :as iface]))

(deftest test-init
  (let [arg (impl/init {:arg-name :a
                        :parent-node-name :p
                        :arg-val :v})]
    (is (= :a (:arg-name arg)))
    (is (= :p (:parent-node-name arg)))
    (is (= :v (:arg-val arg)))
    (is (instance? arg.impl.Arg arg))))

(deftest test-init-for-node-name
  (let [base {:arg-name :a
              :arg-val :v}
        result (impl/init-for-node-name "parent-node" base)]
    (is (= :a (:arg-name result)))
    (is (= :v (:arg-val result)))
    (is (= "parent-node" (:parent-node-name result)))
    (is (instance? arg.impl.Arg result))))

(deftest test-set-val
  (let [original (impl/init {:arg-name :a
                             :parent-node-name :p
                             :arg-val :v})
        updated (iface/set-val original :nv)]
    (is (= :a (:arg-name updated)))
    (is (= :p (:parent-node-name updated)))
    (is (= :nv (:arg-val updated)))
    (is (= (type updated) (type original)))
    (is (not= original updated))))

