(ns ^:integration graphden.packages.storage.pg-notify-test
  "End-to-end test for the `:pg-notify` graph primitive — verifies
   the executor reaches `(:notify-emitter ctx)` and that the payload
   is delivered unchanged."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.test-infra.exec-harness :as eh :refer [*context* *storage*]]))


(use-fixtures :once
  (setup/create-container-fixture)
  (eh/exec-fixture (str (ns-name *ns*))))


(deftest pg-notify-delivers-payload-to-emitter
  (testing ":pg-notify routes its `:event` arg through the ctx-installed emitter"
    ;; Capture emitter calls in a thread-local atom; rebind the
    ;; existing ctx's `:notify-emitter` to record without actually
    ;; round-tripping to Postgres.
    (let [captured (atom [])
          probe-ctx (assoc *context* :notify-emitter
                           (fn capture-emit
                             [event]
                             (swap! captured conj event)
                             nil))]
      (setup/sync-and-invalidate!
        *context* *storage*
        [{:name :_notify-test-event
          :parent :const
          :args {:value {:value {:kind :fn :op :invalidate :id "abc-123"}}}}

         {:name :notify-test-emit
          :parent :pg-notify
          :args {:event :_notify-test-event}}])

      (let [r (exec/execute probe-ctx (eh/fn-id "notify-test-emit") {})]
        (testing ":pg-notify returns nil"
          (is (nil? r) "impl returns nil (matches declared :return-type :null)"))

        (testing "emitter saw the event payload unchanged"
          (is (= [{:kind :fn :op :invalidate :id "abc-123"}] @captured)
              "exactly one emit, with the literal payload supplied by the graph"))))))


(deftest pg-notify-noop-when-no-emitter
  (testing ":pg-notify silently no-ops when ctx has no emitter wired"
    (let [probe-ctx (dissoc *context* :notify-emitter)]
      (setup/sync-and-invalidate!
        *context* *storage*
        [{:name :_notify-test-event-2
          :parent :const
          :args {:value {:value {:kind :service :op :write :id "xyz-789"}}}}

         {:name :notify-test-emit-2
          :parent :pg-notify
          :args {:event :_notify-test-event-2}}])

      (testing "doesn't throw — falls through to nil"
        (is (nil? (exec/execute probe-ctx (eh/fn-id "notify-test-emit-2") {})))))))
