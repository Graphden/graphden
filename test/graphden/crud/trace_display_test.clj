(ns graphden.crud.trace-display-test
  "Pure unit tests for the trace display shaping behind
   `/partials/execute-trace` (`fn-execution/trace-display-rows`'s
   private halves): the depth-first tree reassembly from
   `:seq`/`:parent-seq` and the per-row FACT projection (the display
   policy — chips, indent, pretty-print — is graph composition in
   `app/execution/fns.edn`'s `:_ptrace-r-*` chain)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.fn-execution :as fn-exec]))


(def ^:private tree-order #'fn-exec/trace-tree-order)
(def ^:private entry-row #'fn-exec/trace-entry-row)


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


(deftest tree-order-orphans-become-roots-test
  ;; A truncated trace keeps children whose parent entry was dropped
  ;; (byte-cap oldest-first drop / the 10k entry cap stopping before
  ;; outer frames completed) — they must surface as roots, not vanish.
  (let [entries [{:seq 8 :parent-seq 7 :fn-id "orphan"}
                 {:seq 9 :parent-seq 8 :fn-id "orphan-child"}
                 {:seq 0 :fn-id "root"}]
        ordered (tree-order entries)]
    (is (= ["root" "orphan" "orphan-child"] (mapv :fn-id ordered)))
    (is (= [0 0 1] (mapv :depth ordered)))))


(deftest tree-order-keeps-linkless-entries-test
  (let [entries [{:fn-id "old-1"} {:seq 0 :fn-id "root"} {:fn-id "old-2"}]
        ordered (tree-order entries)]
    (testing "pre-tree entries follow the tree at depth 0, stored order kept"
      (is (= ["root" "old-1" "old-2"] (mapv :fn-id ordered)))
      (is (every? #(zero? (:depth %)) ordered)))))


(deftest entry-row-facts-test
  (let [id (str (random-uuid))
        base {:seq 0 :depth 0 :fn-id id}]
    (testing "fresh call → raw status facts; name joined"
      (let [row (entry-row {id "my-fn"}
                           (assoc base :cache-hit? false :duration-ms 12))]
        (is (= "my-fn" (:fn-name row)))
        (is (= 12 (:duration-ms row)))
        (is (false? (:cache-hit? row)))
        (is (not (contains? row :hidden)))))
    (testing "hidden / cache-hit facts pass through raw (chip policy is graph)"
      (is (true? (:cache-hit? (entry-row {} (assoc base :cache-hit? true)))))
      ;; jsonb roundtrip: :hidden may arrive keyword OR string — always a
      ;; string on the way out.
      (is (= "secret" (:hidden (entry-row {} (assoc base :hidden "secret")))))
      (is (= "unknown-type" (:hidden (entry-row {} (assoc base :hidden :unknown-type))))))
    (testing "unnamed id falls back to the short form"
      (is (= (str (subs id 0 8) "…")
             (:fn-name (entry-row {} (assoc base :cache-hit? true))))))
    (testing "captured value rides RAW with the presence flag; derived marker too"
      (let [row (entry-row {} (assoc base :cache-hit? false :duration-ms 1
                                     :value {:a 1} :value-hidden "secret-derived"))]
        (is (true? (:derived? row)))
        (is (true? (:has-value? row)))
        (is (= {:a 1} (:value row))))
      (testing "a captured nil is PRESENT (renders as null graph-side)"
        (let [row (entry-row {} (assoc base :cache-hit? true :value nil))]
          (is (true? (:has-value? row)))
          (is (nil? (:value row)))))
      (testing "no capture → no flag"
        (is (not (contains? (entry-row {} (assoc base :cache-hit? true))
                            :has-value?)))))
    (testing "depth passes through uncapped (indent scaling is graph)"
      (is (= 40 (:depth (entry-row {} (assoc base :depth 40 :cache-hit? true))))))))
