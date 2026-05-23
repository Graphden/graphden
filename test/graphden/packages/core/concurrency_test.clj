(ns graphden.packages.core.concurrency-test
  "Unit tests for `core.concurrency` base-fn impls — :do, :sleep,
   :future, :loop-until-interrupted. Each impl wraps a single
   Clojure / Java primitive; tests check the semantic contract
   (left-to-right side effects in :do, blocking + interrupt
   propagation in :sleep, background spawn + stopper in :future,
   cooperative shutdown in :loop-until-interrupted).

   Mirrors `refinements_test`: the package's impls.clj is slurp+
   eval'd via the loader's private `load-module-impls` so the
   defbase-generated symbols become reachable WITHOUT a normal
   require — same path the runtime takes."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]))


(def ^:dynamic *impls* nil)


(defn- load-concurrency-impls-fixture
  [f]
  (binding [*impls* ((requiring-resolve 'graphden.packages.loader/load-module-impls)
                     "core" "concurrency")]
    (f)))


(use-fixtures :once load-concurrency-impls-fixture)


(defn- impl-of
  [kw]
  (let [entry (get *impls* kw)]
    ;; impls map values are either bare fns OR {:impl … :*-rule …}
    ;; — the registry merges with the rule shape for :do.
    (or (and (map? entry) (:impl entry))
        (and (fn? entry) entry)
        (throw (ex-info (str "No impl for " kw) {:available (keys *impls*)})))))


;; ============================================================================
;; :do — sequential side effects in declaration order
;; ============================================================================

(deftest do-forces-steps-in-declaration-order-test
  (testing "side effects fire left-to-right and the LAST step's value is returned"
    (let [order (atom [])
          step  (fn [v] (delay (do (swap! order conj v) v)))
          steps [(step :a) (step :b) (step :c)]
          result ((impl-of :do) {:steps (delay steps)} nil)]
      (is (= :c result) "returns last step's value")
      (is (= [:a :b :c] @order) "side effects fired in declaration order"))))


(deftest do-with-empty-steps-test
  (testing "no steps → nil"
    (is (nil? ((impl-of :do) {:steps (delay [])} nil)))))


(deftest do-forces-every-step-not-just-last-test
  (testing "all steps run — :do is for side effects, not just last-value"
    (let [counter (atom 0)
          step (delay (swap! counter inc))]
      ((impl-of :do) {:steps (delay [step step step])} nil)
      ;; Forced once (a delay caches), so counter increments once even
      ;; though we list it three times. Matches Clojure delay semantics.
      ;; Use distinct delays for distinct side effects.
      (is (= 1 @counter)))))


;; ============================================================================
;; :sleep — Thread/sleep wrapper
;; ============================================================================

(deftest sleep-blocks-the-current-thread-test
  (testing "sleep ~50ms blocks at least 40ms (allows 20% slack)"
    (let [t0 (System/nanoTime)]
      ((impl-of :sleep) {:ms (delay 50)} nil)
      (let [elapsed-ms (/ (- (System/nanoTime) t0) 1000000.0)]
        (is (>= elapsed-ms 40)
            (str "expected >=40ms blocking, got " elapsed-ms "ms"))))))


(deftest sleep-non-positive-is-noop-test
  (testing "0 ms / negative — returns immediately, no exception"
    (is (nil? ((impl-of :sleep) {:ms (delay 0)} nil)))
    (is (nil? ((impl-of :sleep) {:ms (delay -10)} nil)))))


(deftest sleep-honors-interrupt-test
  (testing "sleeping thread interrupted → InterruptedException propagates"
    (let [thrown (atom nil)
          ;; Capture impl in the parent's dynamic-binding scope; the
          ;; child thread doesn't inherit `*impls*`.
          sleep-impl (impl-of :sleep)
          t (Thread. ^Runnable
             (fn []
               (try (sleep-impl {:ms (delay 5000)} nil)
                    (catch InterruptedException e
                      (reset! thrown e)))))]
      (Thread/.start t)
      (Thread/sleep 30)
      (Thread/.interrupt t)
      (Thread/.join t 200)
      (is (instance? InterruptedException @thrown)
          "InterruptedException unwinds out of :sleep"))))


;; ============================================================================
;; :sleep-until-ms — block until a wall-clock target
;; ============================================================================

(deftest sleep-until-ms-blocks-until-target-test
  (testing "blocks until the given epoch-ms — wakes within slack window"
    (let [impl (impl-of :sleep-until-ms)
          t0 (System/currentTimeMillis)
          target (+ t0 80)]
      (impl {:target-ms (delay target)} nil)
      (let [woken-at (System/currentTimeMillis)]
        (is (>= woken-at target)
            (str "woke before target — woken=" woken-at " target=" target))
        (is (<= (- woken-at target) 50)
            (str "woke >50ms late — slack=" (- woken-at target) "ms"))))))


(deftest sleep-until-ms-target-in-past-is-noop-test
  (testing "target ≤ now → returns immediately, no exception"
    (let [impl (impl-of :sleep-until-ms)
          t0 (System/nanoTime)]
      (is (nil? (impl {:target-ms (delay 0)} nil))
          "target=0 (epoch) is way in the past → no-op")
      (is (nil? (impl {:target-ms (delay (System/currentTimeMillis))} nil))
          "target=now → no-op (computed delta is non-positive)")
      (let [elapsed-ms (/ (- (System/nanoTime) t0) 1000000.0)]
        (is (< elapsed-ms 20)
            (str "past-target no-op returned slowly: " elapsed-ms "ms"))))))


(deftest sleep-until-ms-honors-interrupt-test
  (testing "interrupted while blocking → InterruptedException propagates"
    (let [thrown (atom nil)
          impl (impl-of :sleep-until-ms)
          far-target (+ (System/currentTimeMillis) 5000)
          t (Thread. ^Runnable
             (fn []
               (try (impl {:target-ms (delay far-target)} nil)
                    (catch InterruptedException e
                      (reset! thrown e)))))]
      (Thread/.start t)
      (Thread/sleep 30)
      (Thread/.interrupt t)
      (Thread/.join t 200)
      (is (instance? InterruptedException @thrown)
          "InterruptedException unwinds out of :sleep-until-ms"))))


;; ============================================================================
;; :future — spawn body in daemon thread; stopper-thunk interrupts
;; ============================================================================

(deftest future-spawns-body-and-returns-stopper-test
  (let [started? (atom false)
        body-fn (fn [] (reset! started? true))
        stopper ((impl-of :future) {:body (delay body-fn)} nil)]
    (testing "body runs in the background"
      (Thread/sleep 50)
      (is @started?))
    (testing "stopper is callable"
      (is (fn? stopper)))
    (stopper)))


(deftest future-stopper-interrupts-blocking-body-test
  (testing "stopper interrupts a thread blocked in :sleep"
    (let [iters (atom 0)
          body-fn (fn []
                    (try
                      (loop []
                        (swap! iters inc)
                        ;; impl-of :sleep would also work but pulling a
                        ;; direct Thread/sleep keeps the test self-
                        ;; contained.
                        (Thread/sleep 50)
                        (recur))
                      (catch InterruptedException _ nil)))
          stopper ((impl-of :future) {:body (delay body-fn)} nil)]
      (Thread/sleep 120)
      (stopper)
      (Thread/sleep 100)
      (let [n @iters]
        (is (<= 2 n) (str "expected >=2 iterations before stop, got " n))
        (Thread/sleep 200)
        (is (= n @iters)
            "no further iterations after stopper called")))))


(deftest future-body-throwing-doesnt-crash-spawner-test
  (testing "body that throws is logged, stopper still returned cleanly"
    (let [stopper ((impl-of :future)
                   {:body (delay (fn [] (throw (ex-info "boom" {}))))}
                   nil)]
      (Thread/sleep 50)
      (is (fn? stopper) "spawner still produced a callable stopper")
      (is (nil? (stopper)) "stopper is safely invokable even after body died"))))


;; ============================================================================
;; :loop-until-interrupted — cooperative shutdown loop
;; ============================================================================

(deftest loop-until-interrupted-runs-body-until-stopped-test
  (testing "body runs many times; interrupt unwinds cleanly"
    (let [iters (atom 0)
          loop-impl (impl-of :loop-until-interrupted)
          body-fn (fn []
                    (swap! iters inc)
                    (Thread/sleep 20))
          t (Thread. ^Runnable
             (fn []
               (loop-impl {:body (delay body-fn)} nil)))]
      (Thread/.start t)
      (Thread/sleep 100)
      (Thread/.interrupt t)
      (Thread/.join t 300)
      (is (>= @iters 3)
          (str "expected several iterations before interrupt, got " @iters))
      (is (not (Thread/.isAlive t))
          "thread exited cleanly after interrupt"))))


;; ============================================================================
;; :cron-next-after — Quartz cron parsing + next-fire computation
;; ============================================================================

(deftest cron-next-after-every-minute-test
  (testing "`* * * * * ?` fires every second — next-after of any ms is ≤1s later"
    (let [impl (impl-of :cron-next-after)
          ;; A specific epoch ms: 2026-05-23 00:00:00.500 UTC.
          now-ms 1779840000500
          next-ms (impl {:cron (delay "* * * * * ?")
                         :now-ms (delay now-ms)} nil)]
      (is (> next-ms now-ms))
      (is (<= (- next-ms now-ms) 1000)
          (str "next-fire within 1s, got delta=" (- next-ms now-ms) "ms")))))


(deftest cron-next-after-specific-time-test
  (testing "`0 0 9 * * ?` fires at 09:00:00 daily — next-after midnight is 9h later"
    (let [impl (impl-of :cron-next-after)
          ;; 2026-05-23 00:00:00 UTC
          midnight-utc-ms 1779840000000
          next-ms (impl {:cron (delay "0 0 9 * * ?")
                         :now-ms (delay midnight-utc-ms)} nil)
          ;; 09:00:00 same day
          expected-ms (+ midnight-utc-ms (* 9 60 60 1000))]
      ;; Quartz uses default timezone; we allow ±12h slack (one tz width)
      ;; rather than depending on the JVM's tz.
      (is (<= (Math/abs ^long (- next-ms expected-ms)) (* 12 60 60 1000))
          (str "next-fire near 9am, got " (java.util.Date. next-ms))))))


(deftest cron-next-after-rejects-bad-expression-test
  (testing "malformed cron throws :cron/parse-error with the original text"
    (let [impl (impl-of :cron-next-after)
          thrown (try (impl {:cron (delay "garbage")
                             :now-ms (delay 0)} nil)
                      :no-throw
                      (catch clojure.lang.ExceptionInfo e
                        (ex-data e)))]
      (is (= :cron/parse-error (:type thrown)))
      (is (= "garbage" (:cron thrown))))))


(deftest loop-until-interrupted-exits-on-isinterrupted-flag-test
  (testing "body that returns normally → loop checks isInterrupted on next iter"
    (let [iters (atom 0)
          loop-impl (impl-of :loop-until-interrupted)
          ;; Body returns fast (no blocking sleep) — exit path is
          ;; via Thread.isInterrupted check between iterations, NOT
          ;; via InterruptedException from within body.
          body-fn (fn [] (swap! iters inc))
          t (Thread. ^Runnable
             (fn []
               (loop-impl {:body (delay body-fn)} nil)))]
      (Thread/.start t)
      (Thread/sleep 30)
      (Thread/.interrupt t)
      (Thread/.join t 500)
      (is (not (Thread/.isAlive t))
          "non-blocking loop body also exits cleanly on interrupt"))))
