(ns ^:serial graphden.system.init.alerter-test
  "Shell tests for the alerter tick — what `alerts_test` (the pure
   `decide` policy) doesn't cover: the delivery-gated cooldown, the
   server-error baseline, the feedback watermark, what counts as a
   DELIVERED POST, and the config gate that keeps the scheduler off.

   `^:serial`: every test here `with-redefs`es a var (a process-wide
   root rebind)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.crud.fn-execution.stats :as stats]
    [graphden.monitoring.alerts :as alerts]
    [graphden.system.init.alerter :as alerter]
    [graphden.util.counters :as counters]
    [integrant.core :as ig]
    [org.httpkit.client :as http]))


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


;; =============================================================================
;; post-alert! — "delivered" must mean delivered
;;
;; httpkit does NOT throw on transport failure; it returns `{:error e}`, and a
;; non-2xx as `{:status n}`. Reporting either as success burns the cooldown on
;; an alert nobody received (the M1 incident above, one layer down).
;; =============================================================================

(defn- stub-post
  "Run `f` with `http/post` returning `response` (a map, or a fn of the url
   + opts). Returns `[result recorded-requests]`."
  [response f]
  (let [reqs (atom [])]
    (with-redefs [http/post (fn [url opts]
                              (swap! reqs conj {:url url :opts opts})
                              (delay (if (fn? response) (response url opts) response)))]
      [(f) @reqs])))


(def ^:private webhook {:webhook-url "https://hook.example/x"})


(deftest post-alert!-only-reports-a-2xx-as-delivered
  (testing "2xx → delivered, and the channel's body is what we POST"
    (let [[ok reqs] (stub-post {:status 200} #(alerter/post-alert! webhook "boom"))]
      (is (true? ok))
      (is (= "https://hook.example/x" (:url (first reqs))))
      (is (= "{\"text\":\"boom\"}" (:body (:opts (first reqs))))
          "the generic webhook shape, JSON-encoded by the shell")))
  (testing "non-2xx → NOT delivered (the alert retries next tick)"
    (is (false? (first (stub-post {:status 500} #(alerter/post-alert! webhook "boom"))))))
  (testing "transport failure comes back as {:error …}, not a throw → NOT delivered"
    (is (false? (first (stub-post {:error (java.net.ConnectException. "refused")}
                                  #(alerter/post-alert! webhook "boom"))))))
  (testing "a throwing client → NOT delivered, never propagated into the tick"
    (is (false? (first (stub-post (fn [_ _] (throw (ex-info "boom" {})))
                                  #(alerter/post-alert! webhook "boom"))))))
  (testing "no channel configured → no request at all, and a no-op reports TRUE"
    (let [[ok reqs] (stub-post {:status 200} #(alerter/post-alert! {} "boom"))]
      (is (true? ok) "a permanent non-delivery must not wedge the cooldown forever")
      (is (= [] reqs) "nothing is POSTed when nothing is configured"))))


;; =============================================================================
;; run-once! — the baselines the cooldown test doesn't touch
;; =============================================================================

(defn- tick
  "One `run-once!` against stubbed reads. Returns `[fired state decide-inputs]`."
  [{:keys [err-now feedback state deliver? totals]
    :or {err-now 0 feedback [] deliver? true totals []}}]
  (let [seen (atom nil)
        st (atom state)
        decide alerts/decide]
    (with-redefs [stats/org-totals (fn [_ _] totals)
                  counters/snapshot (fn [] {:http/server-error err-now})
                  alerter/feedback-since (fn [_pool _since] feedback)
                  alerts/decide (fn [inputs prev cfg now]
                                  (reset! seen inputs)
                                  (decide inputs prev cfg now))
                  alerter/post-alert! (fn [_ _] deliver?)]
      (let [fired (#'alerter/run-once! nil webhook {} st 1000)]
        [fired @st @seen]))))


(deftest run-once!-diffs-the-server-error-counter-against-its-baseline
  ;; The counter is cumulative and process-wide; the policy thresholds on the
  ;; DELTA since the last tick. A baseline that stopped advancing would page
  ;; on the same errors every tick forever.
  (testing "delta = now - baseline, and the baseline advances to now"
    (let [[_ state inputs] (tick {:err-now 50 :state {:fired {} :error-base 20}})]
      (is (= 30 (:server-error-delta inputs)))
      (is (= 50 (:error-base state)))))
  (testing "a counter RESET (process restart) clamps at 0 instead of going negative"
    (let [[_ state inputs] (tick {:err-now 5 :state {:fired {} :error-base 100}})]
      (is (zero? (:server-error-delta inputs)) "no negative delta reaches the policy")
      (is (= 5 (:error-base state)) "and the baseline re-bases to the lower counter")))
  (testing "the baseline advances even when DELIVERY fails — it is not a cooldown"
    (let [[_ state] (tick {:err-now 99 :feedback [{:category "bug" :body "b"}]
                           :deliver? false :state {:fired {} :error-base 0}})]
      (is (= 99 (:error-base state))))))


(deftest run-once!-advances-the-feedback-watermark-only-on-delivery
  ;; The watermark is the DB clock of the newest reported row. Advancing it on
  ;; a failed send silently drops those reports — the intake's only ping.
  (let [rows [{:category "bug" :body "a" :created_at :t1}
              {:category "idea" :body "b" :created_at :t2}]]
    (testing "delivered → watermark = the newest row reported in this batch"
      (let [[fired state] (tick {:feedback rows :state {:fired {} :feedback-base :t0}})]
        (is (= [:feedback] (map :kind fired)))
        (is (= :t2 (:feedback-base state)))))
    (testing "NOT delivered → watermark held, the batch is re-read next tick"
      (let [[_ state] (tick {:feedback rows :deliver? false
                             :state {:fired {} :feedback-base :t0}})]
        (is (= :t0 (:feedback-base state)))))
    (testing "an empty batch leaves the watermark where it was"
      (let [[fired state] (tick {:feedback [] :state {:fired {} :feedback-base :t0}})]
        (is (= [] fired))
        (is (= :t0 (:feedback-base state)))))))


(deftest run-once!-swallows-a-failed-read
  ;; Best-effort by contract: a transient DB error must not kill the scheduled
  ;; task (scheduleAtFixedRate stops the cadence on an escaping throw) nor
  ;; corrupt the baselines.
  (let [st (atom {:fired {:org:a 1} :error-base 7})]
    (with-redefs [stats/org-totals (fn [_ _] (throw (ex-info "db down" {})))]
      (is (nil? (#'alerter/run-once! nil webhook {} st 1000))
          "the tick returns nil instead of propagating"))
    (is (= {:fired {:org:a 1} :error-base 7} @st) "state untouched by a failed tick")))


;; =============================================================================
;; The config gate — no channel, no scheduler
;; =============================================================================

(deftest alert-scheduler-stays-off-without-a-channel
  (testing "neither Telegram pair nor webhook → nil, no thread started"
    (is (nil? (ig/init-key :exec/alert-scheduler {:context {}})))
    (is (nil? (ig/init-key :exec/alert-scheduler
                           {:context {} :telegram-token "t"}))
        "a HALF-configured Telegram pair is not a channel"))
  (testing "a configured webhook arms the scheduler, and halt-key! stops it"
    ;; Period is deliberately long — this pins the wiring, not a tick.
    (let [scheduler (ig/init-key :exec/alert-scheduler
                                 {:context {} :webhook-url "https://hook.example/x"
                                  :period-ms 600000})]
      (is (some? scheduler))
      (ig/halt-key! :exec/alert-scheduler scheduler)
      (is (true? (java.util.concurrent.ExecutorService/.isShutdown scheduler)))))
  (testing "halt on the OFF arm's nil is a no-op, not an NPE"
    (is (nil? (ig/halt-key! :exec/alert-scheduler nil)))))
