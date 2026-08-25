(ns graphden.system.init.services-test
  "Unit coverage for the NOTIFY-driven cross-pod service restart wiring.
   `restart-notified-services!` is the hook a sibling pod runs when it hears a
   `fn:invalidate` event, so a cron/loop singleton it owns restarts its closure
   after a fn edit / merge on ANOTHER pod (before this, only the writer pod's
   local hook fired, leaving siblings firing the pre-edit graph)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.services.reconciler :as recon]
    [graphden.system.init.services :as svc]))


(def ^:private restart! #'svc/restart-notified-services!)


(deftest cross-pod-fn-notify-restart-routing-test
  (let [calls (atom [])
        b (str (random-uuid))
        f (str (random-uuid))]
    (with-redefs [recon/restart-services-depending-on!
                  (fn [_ctx _running seeds branch] (swap! calls conj [:depending seeds branch]))
                  recon/restart-services-on-branch!
                  (fn [_ctx _running branch] (swap! calls conj [:on-branch branch]))]
      (testing "a seeded fn:invalidate restarts services depending on that fn on the edit branch"
        (reset! calls [])
        (restart! {} f b)
        (is (= [[:depending [(java.util.UUID/fromString f)] (java.util.UUID/fromString b)]]
               @calls)))
      (testing "a full-clear event (no seed) conservatively restarts every service on the branch"
        (reset! calls [])
        (restart! {} "" b)
        (is (= [[:on-branch (java.util.UUID/fromString b)]] @calls)))
      (testing "no branch-id → no-op (nothing safe to target cross-pod)"
        (reset! calls [])
        (restart! {} f "")
        (is (empty? @calls)))
      (testing "a restart-hook exception is swallowed (best-effort — the write already committed)"
        (reset! calls [])
        (with-redefs [recon/restart-services-depending-on!
                      (fn [& _] (throw (ex-info "boom" {})))]
          (is (nil? (restart! {} f b)) "does not propagate"))))))
