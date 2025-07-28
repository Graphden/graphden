(ns fast-refs.impl-test
  (:require
   [clojure.test :refer [deftest is]]
   [fast-refs.impl :as impl]
   [fast-refs.interface :as iface]))

(deftest test-init-empty
  (let [r (impl/init)]
    (is (= #{} (:children-back-refs r)))
    (is (= {} (:args-back-refs r)))
    (is (nil? (:base-node-name r)))
    (is (= [] (:full-args r)))))

(deftest test-add-args-back-ref
  (let [r (impl/init)
        r2 (iface/add-args-back-ref r :foo :bar)]
    (is (= #{:bar} (get-in r2 [:args-back-refs :foo])))))

(deftest test-add-child-back-ref
  (let [r (impl/init)
        r2 (iface/add-child-back-ref r :foo)]
    (is (= #{:foo} (:children-back-refs r2)))))

(deftest test-delete-child-back-ref
  (let [r (-> (impl/init)
              (iface/add-child-back-ref :foo)
              (iface/add-child-back-ref :bar))
        r2 (iface/delete-child-back-ref r :foo)]
    (is (= #{:bar} (:children-back-refs r2)))))

(deftest test-rename-arg-back-ref-node
  (let [r (-> (impl/init)
              (iface/add-args-back-ref :old-node :arg-a))
        r2 (iface/rename-arg-back-ref-node r :old-node :new-node)]
    (is (= #{:arg-a} (get-in r2 [:args-back-refs :new-node])))
    (is (nil? (get-in r2 [:args-back-refs :old-node])))))

(deftest test-set-base-node-name
  (let [r (impl/init)
        r2 (iface/set-base-node-name r :base-123)]
    (is (= :base-123 (:base-node-name r2)))))

(deftest test-set-full-args-with-duplicates
  (is (thrown-with-msg? Exception #"Duplilcates in args"
        (iface/set-full-args (impl/init)
                             [{:arg-name :a} {:arg-name :a}]
                             nil))))

(deftest test-set-full-args-with-parent
  (let [base [{:arg-name :a :arg-val nil}
              {:arg-name :b :arg-val :foo}]
        args  {:a {:arg-name :a :arg-val :bar}}
        result (iface/set-full-args (impl/init) args base)]
    (is (= [{:arg-name :a :arg-val :bar}
            {:arg-name :b :arg-val :foo}]
           (:full-args result)))))

(deftest test-set-full-args-with-nonexistent
  (let [base [{:arg-name :a}]
        args {:b {:arg-name :b :arg-val :foo}}]
    (is (thrown-with-msg? Exception #"Unexisted arg in base"
                          (iface/set-full-args (impl/init) args base)))))

(deftest test-set-full-args-already-set
  (let [base [{:arg-name :a :arg-val :foo}]
        args {:a {:arg-name :a :arg-val :bar}}]
    (is (thrown-with-msg? Exception #"Arg already set in parents"
                          (iface/set-full-args (impl/init) args base)))))
