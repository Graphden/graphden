(ns ^:integration graphden.packages.storage.pg-notify-test
  "End-to-end test for the `:pg-notify` graph primitive — verifies
   the executor reaches `(:notify-emitter ctx)` and that the payload
   is delivered unchanged."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.composition.interface :as fn-composition]
    [graphden.executor.context :as exec-ctx]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]))


(def ^:dynamic *context* nil)
(def ^:dynamic *storage* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  (fn [t]
    (exec/with-clean-registry
      #(let [graph (setup/bootstrap-crud-graph-from-golden!)]
         (try
           (binding [*context* (:ctx graph)
                     *storage* (:storage graph)]
             (t))
           (finally (sp/close (:storage graph))))))))


(defn- fn-id
  [nm]
  (:id (first (sp/query-entities *storage* :fn {:name nm}))))


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
      (fn-composition/sync-fns-to-storage!
        *storage*
        [{:name :_notify-test-event
          :parent :const
          :args {:value {:value {:kind :fn :op :invalidate :id "abc-123"}}}}

         {:name :notify-test-emit
          :parent :pg-notify
          :args {:event :_notify-test-event}}])
      (exec-ctx/invalidate-graph-cache! *context*)

      (let [r (exec/execute probe-ctx (fn-id "notify-test-emit") {})]
        (testing ":pg-notify returns nil"
          (is (nil? r) "impl returns nil (matches declared :return-type :null)"))

        (testing "emitter saw the event payload unchanged"
          (is (= [{:kind :fn :op :invalidate :id "abc-123"}] @captured)
              "exactly one emit, with the literal payload supplied by the graph"))))))


(deftest pg-notify-noop-when-no-emitter
  (testing ":pg-notify silently no-ops when ctx has no emitter wired"
    (let [probe-ctx (dissoc *context* :notify-emitter)]
      (fn-composition/sync-fns-to-storage!
        *storage*
        [{:name :_notify-test-event-2
          :parent :const
          :args {:value {:value {:kind :service :op :write :id "xyz-789"}}}}

         {:name :notify-test-emit-2
          :parent :pg-notify
          :args {:event :_notify-test-event-2}}])
      (exec-ctx/invalidate-graph-cache! *context*)

      (testing "doesn't throw — falls through to nil"
        (is (nil? (exec/execute probe-ctx (fn-id "notify-test-emit-2") {})))))))
