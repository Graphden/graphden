(ns ^:serial graphden.types.ref-return-memo-test
  "Unit tests for the sweep-scoped `*ref-return-memo*` over
   `effective-ref-return*` (types.check). The memo is bound per sweep
   pass by `packages.sync/run-type-check-sweep!` / `check-all-defs!`;
   the CRUD single-fn path leaves it unbound and must recompute every
   time. Stubbed registry (`with-redefs` over `rich-type-of` /
   `root-base-fn-name`), no DB — same pattern as
   `narrowing-unit-test`."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.registry.core :as reg]
    [graphden.types.check :as tc]))


(def ^:private err* #'tc/effective-ref-return*)


(def ^:private stub-info
  {:leaf {:resolved-bindings {} :return :int}
   :mid  {:resolved-bindings {:x {:ref :leaf}} :return :text}})


(defn- with-counted-compute
  "Run `f` with the registry stubbed from `stub-info` and
   `effective-ref-return-uncached` counting into `calls` before
   delegating to the real implementation."
  [calls f]
  (let [orig @#'tc/effective-ref-return-uncached]
    (with-redefs-fn
      {#'reg/rich-type-of        (fn [n] (get stub-info n))
       #'reg/root-base-fn-name   identity
       #'tc/effective-ref-return-uncached
       (fn [r c s d] (swap! calls inc) (orig r c s d))}
      f)))


(deftest memo-bound-caches-repeat-calls
  (testing "same (ref, bindings, depth) computes once under the memo"
    (let [calls (atom 0)]
      (with-counted-compute calls
        (fn []
          (binding [tc/*ref-return-memo* (atom {})]
            (let [r1 (err* :mid {} #{} 0)
                  r2 (err* :mid {} #{} 0)]
              (is (= r1 r2))
              ;; :mid + its nested :leaf re-fire = 2 computes, then 0.
              (is (= 2 @calls) "second call served from the memo"))))))))


(deftest memo-unbound-recomputes
  (testing "CRUD path (no binding) recomputes every call"
    (let [calls (atom 0)]
      (with-counted-compute calls
        (fn []
          (err* :leaf {} #{} 0)
          (err* :leaf {} #{} 0)
          (is (= 2 @calls)))))))


(deftest memo-caches-nil-results
  (testing "unknown ref's nil result is cached, not recomputed"
    (let [calls (atom 0)]
      (with-counted-compute calls
        (fn []
          (binding [tc/*ref-return-memo* (atom {})]
            (is (nil? (err* :unknown {} #{} 0)))
            (is (nil? (err* :unknown {} #{} 0)))
            (is (= 1 @calls) "nil hit served from the memo")))))))


(deftest memo-key-carries-depth-and-bindings
  (testing "different depth or caller-bindings do not collide"
    (let [calls (atom 0)]
      (with-counted-compute calls
        (fn []
          (binding [tc/*ref-return-memo* (atom {})]
            (err* :leaf {} #{} 0)
            (err* :leaf {} #{} 3)
            (err* :leaf {:y {:type :text :value nil}} #{} 0)
            (is (= 3 @calls) "each distinct key computes once")))))))


(deftest memo-key-carries-ambient-overrides
  (testing "results computed under different Pass-3 override maps
            don't collide (nested refs see the ambient map)"
    (let [calls (atom 0)]
      (with-counted-compute calls
        (fn []
          (binding [tc/*ref-return-memo* (atom {})]
            (binding [tc/*ref-return-overrides* nil]
              (err* :mid {} #{} 0))
            (binding [tc/*ref-return-overrides* {:leaf :int}]
              (err* :mid {} #{} 0))
            ;; run 1: :mid + nested :leaf = 2; run 2: :mid again (new
            ;; key) but nested :leaf short-circuits on the override
            ;; inside `effective-ref-return` = 1.
            (is (= 3 @calls))))))))
