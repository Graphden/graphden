(ns graphden.monitoring.alerts-test
  "Pure policy tests for the built-in domain alerter (Phase C3). No IO — the
   scheduler shell (`system.init.alerter`) is a thin wrapper over `decide`."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.monitoring.alerts :as alerts]))


(def ^:private cfg
  {:error-ratio 0.5 :min-runs 10 :server-error-min 20
   :cooldown-ms 3600000 :window-mins 60})


(deftest error-spike-fires-above-ratio-and-volume
  (let [{:keys [fire state]}
        (alerts/decide {:org-totals [{:org "acme" :runs 20 :failed 12}   ; 60% → fire
                                     {:org "beta" :runs 20 :failed 2}     ; 10% → no
                                     {:org "tiny" :runs 4 :failed 4}]     ; 100% but < min-runs
                        :server-error-delta 0}
                       {} cfg 1000)]
    (is (= 1 (count fire)))
    (is (= :error-spike (:kind (first fire))))
    (is (= "acme" (:org (first fire))))
    (is (contains? state "org:acme") "acme's cooldown timestamp recorded")))


(deftest server-error-delta-fires-and-cooldown-suppresses
  (testing "a 5xx burst over the threshold fires"
    (let [{:keys [fire]} (alerts/decide {:org-totals [] :server-error-delta 25}
                                        {} cfg 1000)]
      (is (= [:server-errors] (map :kind fire)))
      (is (= 25 (:count (first fire))))))
  (testing "under the threshold is silent"
    (is (empty? (:fire (alerts/decide {:org-totals [] :server-error-delta 5}
                                      {} cfg 1000))))))


(deftest cooldown-suppresses-a-sustained-incident
  (let [inputs {:org-totals [{:org "acme" :runs 20 :failed 20}] :server-error-delta 0}
        first-round (alerts/decide inputs {} cfg 1000)]
    (is (= 1 (count (:fire first-round))) "first tick pages")
    (testing "same org still failing INSIDE the cooldown → silent"
      (let [again (alerts/decide inputs (:state first-round) cfg (+ 1000 60000))]
        (is (empty? (:fire again)))
        (is (= (:state first-round) (:state again)) "timestamp carried, not bumped")))
    (testing "AFTER the cooldown → pages again"
      (let [later (alerts/decide inputs (:state first-round) cfg (+ 1000 3600001))]
        (is (= 1 (count (:fire later))))))))


(deftest summary-text-joins-messages
  (let [{:keys [fire]} (alerts/decide {:org-totals [{:org "acme" :runs 20 :failed 20}]
                                       :server-error-delta 30}
                                      {} cfg 1000)]
    (is (= 2 (count fire)))
    (is (re-find #"^⚠️ graphden alert" (alerts/summary-text fire)))
    (is (re-find #"acme" (alerts/summary-text fire)))
    (is (re-find #"server errors" (alerts/summary-text fire)))))
