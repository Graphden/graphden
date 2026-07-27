(ns graphden.util.abort-shield-test
  "The abort-atomicity shield (audit-7) had ZERO coverage. These pin its two
   load-bearing invariants: (1) an interrupt on the CALLER during the join is
   swallowed and f still delivers its result, with the caller's interrupt flag
   CLEAR on return (a pooled http-kit worker must not be poisoned); (2) dynamic
   bindings are conveyed to the shield thread (the `bound-fn*` that a refactor
   dropping it would silently turn every shielded write back into a full-recompile
   heal — the 'run-9: 41 heals' regression)."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.util.abort-shield :as shield])
  (:import
    (java.util.concurrent
      CountDownLatch
      TimeUnit)))


(def ^:dynamic *conveyed* :root)


(deftest run!-returns-value-and-propagates-f-exception
  (is (= 42 (shield/run! (fn [] 42))))
  (testing "f's own exception is rethrown UNWRAPPED (not the ExecutionException)"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom"
          (shield/run! (fn [] (throw (ex-info "boom" {}))))))))


(deftest run!-conveys-dynamic-bindings-to-the-shield-thread
  ;; Without `bound-fn*` the shield thread would see the ROOT value (:root).
  (is (= :bound (binding [*conveyed* :bound] (shield/run! (fn [] *conveyed*))))))


(deftest run!-runs-inline-when-already-on-a-shield-thread
  ;; A nested write pipeline must NOT submit a second pool task — it runs f
  ;; inline on the same shield thread.
  (let [[nested-name same-thread?]
        (shield/run!
          (fn []
            (let [me (Thread/.getName (Thread/currentThread))
                  inner (shield/run! (fn [] (Thread/.getName (Thread/currentThread))))]
              [inner (= me inner)])))]
    (is (str/starts-with? nested-name "abort-shield-"))
    (is (true? same-thread?) "nested run! executed inline on the same shield thread")))


(deftest run!-swallows-caller-interrupt-and-still-delivers-result
  ;; The pool-poisoning invariant: an interrupt hitting the caller DURING the
  ;; uninterruptible join is consumed, f runs to completion, and the caller's
  ;; interrupt flag is CLEAR on return.
  (let [started (CountDownLatch. 1)
        result (atom nil)
        flag-on-return (atom nil)
        caller (Thread.
                 ^Runnable
                 (fn []
                   (reset! result
                           (shield/run!
                             (fn []
                               (CountDownLatch/.countDown started)
                               (Thread/sleep 200)
                               :completed)))
                   (reset! flag-on-return (Thread/.isInterrupted (Thread/currentThread)))))]
    (Thread/.start caller)
    (CountDownLatch/.await started 2 TimeUnit/SECONDS)   ; f has started on the shield thread
    (Thread/.interrupt caller)                           ; interrupt the caller during its join
    (Thread/.join caller 5000)
    (is (= :completed @result) "f ran to completion despite the caller interrupt")
    (is (false? @flag-on-return)
        "the caller's interrupt flag is clear on return — the pool worker is not poisoned")))
