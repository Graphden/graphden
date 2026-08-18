(ns graphden.crud.test-runs-test
  "Pure-selection tests for the runner's runnability gate. The
   storage-backed runner/status coverage lives in
   `graphden.crud.fn-execution-test` (shared fixture); here we pin
   `nullable-type?` (which declared slot types admit the absent case)
   and `blocking-frees` (which unbound args actually block a zero-arg
   run)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.test-runs :as test-runs]))


(deftest nullable-type?-classification
  (testing "explicitly-nil-admitting type expressions"
    (is (test-runs/nullable-type? [:union :null :text]))
    (is (test-runs/nullable-type? [:union :null :int :keyword]))
    (is (test-runs/nullable-type? :null)))
  (testing "everything else stays blocking-grade"
    (is (not (test-runs/nullable-type? :int)))
    (is (not (test-runs/nullable-type? :any))
        ":any is not EXPLICITLY nullable — an :any free is usually a forgotten binding")
    (is (not (test-runs/nullable-type? 'a))
        "bare typevar")
    (is (not (test-runs/nullable-type? [:union :int :text]))
        "union without :null")
    (is (not (test-runs/nullable-type? nil)))))


(deftest blocking-frees-drops-nullable-slots
  (let [free {:message :slot-1 :count :slot-2}]
    (is (= {:count :slot-2}
           (test-runs/blocking-frees free #{:slot-1}))
        "a nullable slot's free runs as nil; the concrete one blocks")
    (is (= free (test-runs/blocking-frees free #{}))
        "nothing nullable → everything blocks")
    (is (= {} (test-runs/blocking-frees free #{:slot-1 :slot-2}))
        "all nullable → runnable")))
