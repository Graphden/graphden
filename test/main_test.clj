(ns main-test
  (:require
   [clojure.test :refer [deftest is]]
   [main :as sut]))

(deftest foo-test
  (is (= "foo" (sut/foo))))
