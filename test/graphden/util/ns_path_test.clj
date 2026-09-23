(ns graphden.util.ns-path-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.util.ns-path :as ns-path]))


(def ^:private rows
  [{:id 1 :name "my"}
   {:id 2 :name "app" :parent-id 1}
   {:id 3 :name "utils" :parent-id 2}
   {:id 4 :name "orphan" :parent-id 99}
   {:id 5 :name "loop-a" :parent-id 6}
   {:id 6 :name "loop-b" :parent-id 5}])


(deftest path-map-test
  (let [m (ns-path/path-map rows)]
    (testing "full dotted path at every depth"
      (is (= {1 "my" 2 "my.app" 3 "my.app.utils"} (select-keys m [1 2 3]))))
    (testing "an unknown id is absent, not an empty path"
      (is (nil? (get m 42))))
    (testing "a dangling parent degrades to the partial path"
      (is (= "orphan" (get m 4))))
    (testing "a parent cycle terminates"
      (is (= "loop-a.loop-b" (get m 6))))))


(deftest path-map-dangling-placeholder-test
  (let [m (ns-path/path-map rows "?")]
    (testing "the placeholder marks the missing ancestor"
      (is (= "?.orphan" (get m 4))))
    (testing "intact chains are unaffected"
      (is (= "my.app.utils" (get m 3))))))
