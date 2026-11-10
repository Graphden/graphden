(ns graphden.executor.source-recipe-lifecycle-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.executor.source-recipe-lifecycle :as lifecycle]
    [graphden.executor.source-recipe-store :as store]))


(defn- fixture
  []
  (let [cache (store/create-store {:max-bytes 100 :max-entries 4 :max-pending 4
                                   :max-families 4 :max-leases 4 :max-ttl-ms 100
                                   :clock-ms (constantly 0) :closed? (constantly false)})
        family (store/create-family! cache (Object.) 100)
        physical (Object.)]
    {:store cache :family family :physical physical}))


(defn- pending!
  [{:keys [store family physical]}]
  (let [ticket (store/reserve! store family physical 80)]
    (is (some? ticket))
    (is (true? (lifecycle/track-reservation! ticket)))
    ticket))


(deftest successful-postcheck-publishes-only-after-actual-worker-completion
  (let [{:keys [store family physical] :as controller} (fixture)
        done (atom nil)]
    (is (= {:ok true}
           (lifecycle/call-with-operation controller
                                          (fn []
                                            (reset! done (lifecycle/register-worker!))
                                            (let [ticket (pending! controller)]
                                              (is (true? (lifecycle/complete-record! ticket :checked-recipe 40)))
                                              (is (nil? (store/acquire! store family physical))))
                                            {:ok true}))))
    (is (= 80 (:pending-bytes (store/stats store))))
    (is (nil? (store/acquire! store family physical)))
    (@done)
    (@done)
    (let [lease (store/acquire! store family physical)]
      (is (= :checked-recipe (store/lease-value store lease)))
      (store/release! store lease))
    (is (= {:pending 0 :entries 1 :charged-bytes 40}
           (select-keys (store/stats store) [:pending :entries :charged-bytes])))))


(deftest cancellation-keeps-reservation-until-late-worker-actually-exits
  (let [{:keys [store family physical] :as controller} (fixture)
        done (atom nil) late (atom nil)]
    (is (= {:ok false}
           (lifecycle/call-with-operation controller
                                          (fn []
                                            (reset! done (lifecycle/register-worker!))
                                            (let [ticket (pending! controller)]
                                              (reset! late (bound-fn [] (lifecycle/complete-record! ticket :too-late 40))))
                                            {:ok false}))))
    (is (= 80 (:pending-bytes (store/stats store))))
    (is (false? (@late)))
    (is (nil? (store/acquire! store family physical)))
    (@done)
    (is (zero? (:charged-bytes (store/stats store))))
    (is (false? (@late)))
    (is (nil? (store/acquire! store family physical)))))


(deftest nested-success-cannot-publish-before-outer-postcheck-refusal
  (let [{:keys [store family physical] :as controller} (fixture)]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"postcheck refused"
          (lifecycle/call-with-operation controller
                                         (fn []
                                           (lifecycle/call-with-operation controller
                                                                          (fn []
                                                                            (let [ticket (pending! controller)]
                                                                              (lifecycle/complete-record! ticket :subpass 40))
                                                                            {:ok true}))
                                           (is (nil? (store/acquire! store family physical)))
                                           (throw (ex-info "postcheck refused" {}))))))
    (is (zero? (:charged-bytes (store/stats store))))
    (is (nil? (store/acquire! store family physical)))))


(deftest discarded-builder-and-expired-bound-scope-do-not-leak-charge
  (let [{:keys [store] :as controller} (fixture) late (atom nil)]
    (lifecycle/call-with-operation controller
                                   (fn []
                                     (let [ticket (pending! controller)]
                                       (lifecycle/discard-record! ticket)
                                       (is (zero? (:charged-bytes (store/stats store))))
                                       (reset! late (bound-fn [] [(lifecycle/current-controller)
                                                                  (lifecycle/track-reservation! ticket)
                                                                  (lifecycle/register-worker!)])))
                                     {:ok true}))
    (is (= [nil false nil] (@late)))
    (is (zero? (:charged-bytes (store/stats store))))))
