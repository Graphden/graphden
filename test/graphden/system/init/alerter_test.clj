(ns graphden.system.init.alerter-test
  "Shell tests for the alerter tick — the delivery-gated cooldown that
   `alerts_test` (pure `decide`) doesn't cover."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.fn-execution.stats :as stats]
    [graphden.monitoring.alerts :as alerts]
    [graphden.system.init.alerter :as alerter]
    [graphden.util.counters :as counters]))


(deftest cooldown-only-advances-on-successful-delivery
  ;; M1 regression: a lost alert must NOT burn the cooldown. The old
  ;; order advanced :fired before the POST, so a sustained incident over
  ;; a broken channel paged once (or never) and then went silent.
  (with-redefs [stats/org-totals (fn [_ _] [])
                counters/snapshot (fn [] {:http/server-error 0})
                alerts/decide (fn [_ _ _ _] {:fire [{:k :probe}] :state {:probe 123}})]
    (testing "delivery FAILS → :fired keeps the prior (empty) cooldown, so the alert retries"
      (with-redefs [alerter/post-alert! (fn [_ _] false)]
        (let [st (atom {:fired {} :error-base 0})]
          (#'alerter/run-once! nil nil {} st 1000)
          (is (= {} (:fired @st)) "cooldown NOT committed on a failed send"))))
    (testing "delivery SUCCEEDS → :fired advances to the new cooldown state"
      (with-redefs [alerter/post-alert! (fn [_ _] true)]
        (let [st (atom {:fired {} :error-base 0})]
          (#'alerter/run-once! nil nil {} st 1000)
          (is (= {:probe 123} (:fired @st)) "cooldown committed on a delivered send"))))))
