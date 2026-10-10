(ns ^:serial graphden.editor.components-test
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.editor.component-config :as config]
    [graphden.editor.components :as components]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.executor.context :as context]
    [graphden.storage.postgres.graph-epoch :as graph-epoch]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router.epoch :as router-epoch]
    [graphden.tenancy.context :as tenancy]
    [graphden.versioning.storage.core :as versioned]))


(def ^:private fn-id (java.util.UUID/randomUUID))
(def ^:private branch-id (java.util.UUID/randomUUID))


(defn- preference-storage
  [selection]
  (let [row {:id (random-uuid) :owner-id "alice" :key "components" :value selection}]
    (reify sp/StorageCRUD
      (create-entity [_ _ _] (throw (UnsupportedOperationException. "Read-only preference fixture")))

      (update-entity [_ _ _ _] (throw (UnsupportedOperationException. "Read-only preference fixture")))

      (delete-entity [_ _ _] (throw (UnsupportedOperationException. "Read-only preference fixture")))

      (read-entity
        [_ entity id]
        (when (and (= :ui-pref entity) (= id (:id row))) row))

      (query-entities
        [this entity where]
        (sp/query-entities this entity where {}))

      (query-entities
        [_ entity where opts]
        (is (= :ui-pref entity))
        (is (= {:owner-id "alice" :key "components"} where))
        (->> [row]
             (filter #(every? (fn [[field value]] (= value (get % field))) where))
             (drop (:offset opts 0))
             (take (:limit opts 1))
             vec))

      (query-latest-per-group
        [this entity where _]
        (sp/query-entities this entity where)))))


(deftest selected-identity-is-owner-org-and-branch-scoped
  (let [selection {"fn-id" (str fn-id) "org" "org-a" "branch-id" (str branch-id)}]
    (with-redefs [tenancy/current-user-id (constantly "alice")
                  tenancy/current-org (constantly "org-a")
                  versioned/current-branch-id (constantly branch-id)]
      (is (= fn-id (components/selected-id (preference-storage selection))))
      (doseq [changed [(assoc selection "org" "org-b")
                       (assoc selection "branch-id" (str (java.util.UUID/randomUUID)))
                       (assoc selection :fn-id (str fn-id))
                       (assoc selection "fn-id" "not-an-identity")
                       nil]]
        (is (thrown? clojure.lang.ExceptionInfo
              (components/selected-id (preference-storage changed))))))))


(deftest unpublished-policy-does-not-authorize-export
  (with-redefs [router-epoch/validated-watermark (constantly 12)
                graph-epoch/epoch-handle identity]
    (testing "a fully applied source can be exported"
      (with-redefs [graph-epoch/current (constantly 12)]
        (is (nil? (components/assert-current-policy! {})))))
    (testing "committed, in-flight and unavailable source epochs fail closed"
      (doseq [epoch [13 100 nil]]
        (with-redefs [graph-epoch/current (constantly epoch)]
          (is (thrown? clojure.lang.ExceptionInfo
                (components/assert-current-policy! {}))))))))


(def ^:private unavailable
  {:ok false :reason "Personal UI graph is unavailable. Using built-in components."
   :http-status 422})


(deftest export-refusals-disclose-only-the-exact-retryable-category
  (testing "invalid requests keep the existing opaque refusal and never read source"
    (with-redefs [source/with-snapshot (fn [& _] (throw (AssertionError. "Unexpected source read")))]
      (doseq [input [nil {} {:component "unknown"} {:component "fn-picker" :fn-id fn-id}]]
        (is (= unavailable (components/export-current {:storage {}} input))))))
  (testing "ACL, invalid source and unknown failures cannot opt into retry by reason alone"
    (doseq [[error status]
            [[(ex-info "private ACL details" {:type :authz/forbidden
                                              :reason :policy-refresh-required :fn-id fn-id}) 403]
             [(ex-info "private source value" {:type :browser-plan/unsupported
                                               :reason :invalid-configuration :source {:value "secret"}}) 422]
             [(ex-info "private implementation details" {:type :unknown/failure
                                                         :reason :policy-refresh-required}) 422]
             [(ex-info "text is not a trusted category" {:type :browser-plan/unsupported
                                                         :reason "policy-refresh-required"}) 422]
             [(IllegalStateException. "private runtime value") 422]]]
      (with-redefs [source/with-snapshot (fn [& _] (throw error))]
        (is (= (assoc unavailable :http-status status)
               (components/export-current {:storage {}} {:component "fn-picker"})))))))


(deftest stale-policy-has-a-bounded-opaque-retry-envelope
  (let [epoch (atom 13)
        watermark (atom 12)]
    (with-redefs [graph-epoch/epoch-handle identity
                  graph-epoch/current (fn [_] @epoch)
                  router-epoch/validated-watermark (fn [] @watermark)
                  source/with-snapshot (fn [storage _]
                                         (components/assert-current-policy! storage)
                                         {:ok true})]
      (is (= (assoc unavailable :code "policy-refresh-required" :retryable true :retry-after 1)
             (components/export-current {:storage {}} {:component "fn-picker"})))
      (is (= 12 @watermark) "Export never advances policy validation to make itself succeed")
      (reset! watermark 13)
      (is (= {:ok true}
             (components/export-current {:storage {}} {:component "fn-picker"})))
      (reset! epoch nil)
      (is (= (assoc unavailable :code "policy-refresh-required" :retryable true :retry-after 1)
             (components/export-current {:storage {}} {:component "fn-picker"}))))))


(deftest original-policy-and-epoch-are-frozen-before-any-snapshot-read
  (let [events (atom [])
        rich (atom {:original :secret})
        ctx {:storage {} :rich-types-atom rich}
        policy {:captured :secret}
        selected (random-uuid)]
    (with-redefs [context/invalidation-epoch (fn [_] (swap! events conj :epoch) 7)
                  snapshot/capture-policy (fn [basis]
                                            (swap! events conj [:capture basis]) policy)
                  source/with-snapshot (fn [storage f]
                                         (swap! events conj :snapshot)
                                         (reset! rich {:replacement :plain})
                                         (f storage (constantly nil)))
                  components/selected-id (constantly selected)
                  sp/read-entity (fn [& _] {:id selected})
                  source/collect-manifest (constantly {})
                  source/collect-namespaces (constantly [])
                  config/configuration (constantly {:fn-picker {:view fn-id}})
                  components/assert-current-policy! (constantly nil)
                  snapshot/validate-source! (fn [_ _ frozen]
                                              (swap! events conj [:manifest frozen]))
                  source/collect-closure (constantly {})
                  snapshot/export-snapshot (fn [_ _ _ frozen]
                                             (swap! events conj [:component frozen]) {:valid true})]
      (is (:ok (components/export-current ctx {:component "fn-picker"})))
      (is (= [:epoch [:capture {:original :secret}] :snapshot
              [:manifest policy] [:component policy] :epoch] @events)))))
