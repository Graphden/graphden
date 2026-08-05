(ns graphden.executor.compile-permit-test
  "The process-wide full-compile permit (`call-with-compile-permit`) —
   the 2026-08-05 OOM fix: a full read-graph + compile-all working set
   is heap-heavy, and nothing bounded how many ran at once (the
   per-branch build monitor dedupes ONE branch; two cold branches, or a
   cold build racing the epoch heal, each ran their own). These tests
   pin the bound itself and the deadlock-freedom contract's observable
   half: the permit is released on exit, exceptional or not.

   Default permit count is 1 (`GRAPHDEN_MAX_CONCURRENT_COMPILES` env
   widens it) — asserted indirectly: N racing thunks never observe >1
   concurrent holder under the default test env."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.executor.compile-runtime :as cr]))


(deftest permit-bounds-concurrency
  (testing "racing holders serialize — max observed concurrency is 1"
    (let [inside (atom 0)
          max-seen (atom 0)
          run (fn []
                (cr/call-with-compile-permit
                  (fn []
                    (let [n (swap! inside inc)]
                      (swap! max-seen max n)
                      (Thread/sleep 30)
                      (swap! inside dec)
                      :done))))
          threads (mapv #(doto (Thread. ^Runnable run (str "permit-" %)) .start)
                        (range 4))]
      (doseq [^Thread t threads] (Thread/.join t 5000))
      (is (zero? @inside) "every holder exited")
      (is (= 1 @max-seen)
          "the default single permit never admits two compiles at once"))))


(deftest permit-released-on-throw
  (testing "an exceptional compile releases the permit for the next caller"
    (is (thrown? clojure.lang.ExceptionInfo
          (cr/call-with-compile-permit
            (fn [] (throw (ex-info "compile blew up" {}))))))
    (is (= :ok (cr/call-with-compile-permit (fn [] :ok)))
        "the permit is available again")))
