(ns graphden.executor.base-fns.collection-test
  "Tests for collection base functions."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.base-fns.test-helpers :as h]
    [graphden.executor.interface :as exec]))


(use-fixtures :each exec/with-clean-registry)


(deftest collection-operations-test
  (h/register-collections!)

  (testing "first"
    (is (= 1 (h/call-base-fn :first {:coll [1 2 3]})))
    (is (nil? (h/call-base-fn :first {:coll []}))))

  (testing "rest"
    (is (= [2 3] (vec (h/call-base-fn :rest {:coll [1 2 3]}))))
    (is (= [] (vec (h/call-base-fn :rest {:coll [1]})))))

  (testing "cons"
    (is (= [0 1 2 3] (vec (h/call-base-fn :cons {:x 0 :coll [1 2 3]})))))

  (testing "conj"
    (is (= [1 2 3 4] (h/call-base-fn :conj {:coll [1 2 3] :x 4}))))

  (testing "get"
    (is (= 2 (h/call-base-fn :get {:coll [1 2 3] :k 1})))
    (is (= "b" (h/call-base-fn :get {:coll {:a "a" :b "b"} :k :b})))
    (is (nil? (h/call-base-fn :get {:coll {:a 1} :k :b})))
    (is (= "default" (h/call-base-fn :get {:coll {:a 1} :k :b :default "default"}))))

  (testing "assoc"
    (is (= {:a 1 :b 2} (h/call-base-fn :assoc {:m {:a 1} :k :b :v 2}))))

  (testing "dissoc"
    (is (= {:a 1} (h/call-base-fn :dissoc {:m {:a 1 :b 2} :k :b}))))

  (testing "count"
    (is (= 3 (h/call-base-fn :count {:coll [1 2 3]})))
    (is (= 2 (h/call-base-fn :count {:coll {:a 1 :b 2}}))))

  (testing "empty?"
    (is (true? (h/call-base-fn :empty? {:coll []})))
    (is (false? (h/call-base-fn :empty? {:coll [1]}))))

  (testing "contains?"
    (is (true? (h/call-base-fn :contains? {:coll {:a 1} :k :a})))
    (is (false? (h/call-base-fn :contains? {:coll {:a 1} :k :b}))))

  (testing "keys"
    (is (= [:a :b] (sort (h/call-base-fn :keys {:m {:a 1 :b 2}})))))

  (testing "vals"
    (is (= [1 2] (sort (h/call-base-fn :vals {:m {:a 1 :b 2}})))))

  (testing "merge"
    (is (= {:a 1 :b 2 :c 3} (h/call-base-fn :merge {:maps [{:a 1 :b 2} {:c 3}]})))
    (is (= {:a 1 :b 2 :c 3 :d 4} (h/call-base-fn :merge {:maps [{:a 1} {:b 2} {:c 3} {:d 4}]})))
    (is (= {:a 2} (h/call-base-fn :merge {:maps [{:a 1} {:a 2}]}))))

  (testing "into"
    (is (= {:a 1 :b 2} (h/call-base-fn :into {:to {} :from [[:a 1] [:b 2]]}))))

  (testing "range"
    (is (= [0 1 2] (h/call-base-fn :range {:end 3})))
    (is (= [1 2 3] (h/call-base-fn :range {:start 1 :end 4})))
    (is (= [0 2 4] (h/call-base-fn :range {:start 0 :end 5 :step 2}))))

  (testing "range - step=0 throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"step cannot be zero"
          (h/call-base-fn :range {:start 0 :end 10 :step 0}))))

  (testing "range - negative step"
    (is (= [10 8 6 4 2] (h/call-base-fn :range {:start 10 :end 1 :step -2})))
    (is (= [5 4 3 2 1] (h/call-base-fn :range {:start 5 :end 0 :step -1}))))

  (testing "range - too large throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"range would produce.*max allowed"
          (h/call-base-fn :range {:start 0 :end 2000000 :step 1}))))

  (testing "repeat"
    (is (= [5 5 5] (h/call-base-fn :repeat {:n 3 :x 5}))))

  (testing "repeat - negative count throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"repeat count cannot be negative"
          (h/call-base-fn :repeat {:n -1 :x 5}))))

  (testing "repeat - too large throws"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"repeat count.*exceeds max allowed"
          (h/call-base-fn :repeat {:n 2000000 :x 5}))))

  (testing "take"
    (is (= [1 2] (h/call-base-fn :take {:n 2 :coll [1 2 3 4]}))))

  (testing "drop"
    (is (= [3 4] (h/call-base-fn :drop {:n 2 :coll [1 2 3 4]}))))

  (testing "reverse"
    (is (= [3 2 1] (h/call-base-fn :reverse {:coll [1 2 3]}))))

  (testing "sort"
    (is (= [1 2 3] (h/call-base-fn :sort {:coll [3 1 2]}))))

  (testing "concat"
    (is (= [1 2 3 4] (h/call-base-fn :concat {:colls [[1 2] [3 4]]})))
    (is (= [1 2 3 4 5 6] (h/call-base-fn :concat {:colls [[1 2] [3 4] [5 6]]})))
    (is (= [] (h/call-base-fn :concat {:colls []}))))

  (testing "flatten"
    (is (= [1 2 3 4] (h/call-base-fn :flatten {:coll [[1 2] [3 [4]]]}))))

  (testing "distinct"
    (is (= [1 2 3] (h/call-base-fn :distinct {:coll [1 2 1 3 2 3]})))))
