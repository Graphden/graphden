(ns graphden.crud.trace-display-test
  "Pure unit tests for the trace display shaping behind
   `/partials/execute-trace` (`fn-execution/trace-display-rows`'s
   private halves): the depth-first tree reassembly from
   `:seq`/`:parent-seq` and the per-row chip/value shaping."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.fn-execution :as fn-exec]))


(def ^:private tree-order #'fn-exec/trace-tree-order)
(def ^:private display-row #'fn-exec/trace-display-row)


(deftest tree-order-reassembles-depth-first-test
  ;; Stored completion order: leaf before parent. Two roots; root 0 has
  ;; a child (1) with a grandchild (2); root 3 stands alone.
  (let [entries [{:seq 2 :parent-seq 1 :fn-id "c"}
                 {:seq 1 :parent-seq 0 :fn-id "b"}
                 {:seq 0 :fn-id "a"}
                 {:seq 3 :fn-id "d"}]
        ordered (tree-order entries)]
    (is (= ["a" "b" "c" "d"] (mapv :fn-id ordered)))
    (is (= [0 1 2 0] (mapv :depth ordered)))))


(deftest tree-order-keeps-linkless-entries-test
  (let [entries [{:fn-id "old-1"} {:seq 0 :fn-id "root"} {:fn-id "old-2"}]
        ordered (tree-order entries)]
    (testing "pre-tree entries follow the tree at depth 0, stored order kept"
      (is (= ["root" "old-1" "old-2"] (mapv :fn-id ordered)))
      (is (every? #(= 0 (:depth %)) ordered)))))


(deftest display-row-chips-test
  (let [id (str (random-uuid))
        base {:seq 0 :depth 0 :fn-id id}]
    (testing "fresh call → duration chip; name joined"
      (let [row (display-row {id "my-fn"}
                             (assoc base :cache-hit? false :duration-ms 12))]
        (is (= "my-fn" (:fn-name row)))
        (is (= "12ms" (:chip row)))
        (is (= "time" (:chip-kind row)))))
    (testing "cache hit / secret / unknown-type chips"
      (is (= ["cache" "cache"]
             ((juxt :chip :chip-kind)
              (display-row {} (assoc base :cache-hit? true)))))
      (is (= ["secret" "secret"]
             ((juxt :chip :chip-kind)
              ;; jsonb roundtrip: :hidden comes back as a STRING
              (display-row {} (assoc base :hidden "secret")))))
      (is (= ["unknown type" "unknown"]
             ((juxt :chip :chip-kind)
              (display-row {} (assoc base :hidden "unknown-type"))))))
    (testing "unnamed id falls back to the short form"
      (is (= (str (subs id 0 8) "…")
             (:fn-name (display-row {} (assoc base :cache-hit? true))))))
    (testing "value pretty-printed; derived marker rides through"
      (let [row (display-row {} (assoc base :cache-hit? false :duration-ms 1
                                       :value {:a 1} :value-hidden "secret-derived"))]
        (is (true? (:derived? row)))
        (is (string? (:value-str row)))))
    (testing "depth indents, capped"
      (is (= 28 (:indent-px (display-row {} (assoc base :depth 2 :cache-hit? true)))))
      (is (= (* 14 12) (:indent-px (display-row {} (assoc base :depth 40 :cache-hit? true))))))))
