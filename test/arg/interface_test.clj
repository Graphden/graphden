(ns arg.interface-test
  (:require
   [arg.interface :as sut]
   [clojure.test :refer [deftest is]]))

(def test-parent-node-name :test-parent-node-name)

(def test-arg-map
  {:arg-name :test-arg-name
   :arg-val "test-arg-val"})

(def test-arg-map-with-parent-node-name
  (assoc test-arg-map
         :parent-node-name
         test-parent-node-name))

(deftest init-arg-test
  (is (= test-arg-map-with-parent-node-name
         (into {}
               (sut/init-arg test-arg-map-with-parent-node-name)))))

(deftest init-arg-for-node-name-test
  (is (= test-arg-map-with-parent-node-name
         (into {}
               (sut/init-arg-for-node-name test-parent-node-name
                                           test-arg-map)))))
