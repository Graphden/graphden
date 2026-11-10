(ns ^:serial graphden.editor.components-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing]]
    [graphden.editor.components :as components]
    [graphden.editor.config-resolution :as resolution]
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


(deftest configuration-quota-refusal-retains-category-without-private-data
  (let [response (components/failure-facts
                   (ex-info "PRIVATE quota detail" {:type :quota/entity-limit
                                                    :reason "PRIVATE detail" :owner "PRIVATE owner"}))]
    (is (= "quota" (:failure-type response)))
    (is (nil? (:failure-status response)))
    (is (false? (:ok response)))
    (is (not (str/includes? (pr-str response) "PRIVATE")))
    (is (not (contains? response :retryable)))))


(deftest selected-identity-is-owner-org-and-branch-scoped
  (let [selection {"fn-id" (str fn-id) "org" "org-a" "branch-id" (str branch-id)}]
    (with-redefs [tenancy/current-user-id (constantly "alice")
                  tenancy/current-org (constantly "org-a")
                  versioned/current-branch-id (constantly branch-id)]
      (is (= fn-id (components/selected-id-from-rows {} [{:value selection}])))
      (doseq [changed [(assoc selection "org" "org-b")
                       (assoc selection "branch-id" (str (java.util.UUID/randomUUID)))
                       (assoc selection :fn-id (str fn-id))
                       (assoc selection "fn-id" "not-an-identity")
                       nil]]
        (is (thrown? clojure.lang.ExceptionInfo
              (components/selected-id-from-rows {} [{:value changed}])))))))


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
  {:ok false :failure-type "unsupported" :failure-reason nil
   :failure-status nil :server-stale? false})


(deftest export-refusals-disclose-only-the-exact-retryable-category
  (testing "invalid requests keep the existing opaque refusal and never read source"
    (with-redefs [source/with-snapshot (fn [& _] (throw (AssertionError. "Unexpected source read")))]
      (doseq [input [nil {} {:component "unknown"} {:component "fn-picker" :fn-id fn-id}]]
        (is (= unavailable (components/export-resolved {:storage {}} input))))))
  (testing "ACL, invalid source and unknown failures retain their distinct safe categories"
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
        (is (= (assoc unavailable :failure-type (case status 403 "forbidden"
                                                      (when (= :browser-plan/unsupported (:type (ex-data error))) "unsupported"))
                      :failure-reason (when (= :policy-refresh-required (:reason (ex-data error))) "policy-refresh-required"))
               (components/export-resolved {:storage {}} {:component "fn-picker"})))))))


(deftest stale-policy-exports-only-trusted-freshness-facts
  (let [epoch (atom 13)
        watermark (atom 12)]
    (with-redefs [graph-epoch/epoch-handle identity
                  graph-epoch/current (fn [_] @epoch)
                  router-epoch/validated-watermark (fn [] @watermark)
                  source/with-snapshot (fn [storage _]
                                         (components/assert-current-policy! storage)
                                         {:ok true})]
      (is (= (assoc unavailable :failure-reason "policy-refresh-required")
             (components/export-resolved {:storage {}} {:component "fn-picker"})))
      (is (= 12 @watermark) "Export never advances policy validation to make itself succeed")
      (reset! watermark 13)
      (is (= {:ok true}
             (components/export-resolved {:storage {}} {:component "fn-picker"})))
      (reset! epoch nil)
      (is (= (assoc unavailable :failure-reason "policy-refresh-required")
             (components/export-resolved {:storage {}} {:component "fn-picker"}))))))


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
                  resolution/capture (fn [_ _ frozen]
                                       (swap! events conj [:manifest frozen])
                                       {:id selected :configuration {:fn-picker {:view fn-id}}})
                  resolution/owned-writable-components (constantly {})
                  sp/query-entities (constantly [])
                  sp/read-entity (fn [& _] {:id selected})
                  source/collect-namespaces (constantly [])
                  components/assert-current-policy! (constantly nil)
                  source/collect-closure (constantly {})
                  snapshot/export-snapshot (fn [_ _ _ frozen]
                                             (swap! events conj [:component frozen]) {:valid true})]
      (is (:ok (components/export-resolved ctx {:component "fn-picker"})))
      (is (= [:epoch [:capture {:original :secret}] :snapshot
              [:manifest (assoc policy :type-recheck nil :checker-cache nil)]
              [:component (assoc policy :type-recheck nil :checker-cache nil)] :epoch] @events)))))


(deftest resolved-export-uses-authorized-configuration-not-legacy-selector
  (let [selected (random-uuid)
        storage {:snapshot :fixture}
        policy {:original :policy}
        calls (atom [])
        authorize! (constantly nil)
        ctx {:storage storage :rich-types-atom (atom {})}]
    (with-redefs [snapshot/capture-policy (constantly policy)
                  source/with-snapshot (fn [actual f]
                                         (is (= storage actual))
                                         (f actual authorize!))
                  resolution/capture (fn [actual guard frozen]
                                       (swap! calls conj :resolve)
                                       (is (= [storage authorize! (assoc policy :type-recheck nil :checker-cache nil)]
                                              [actual guard frozen]))
                                       {:id selected :revision "authoritative-revision"
                                        :configuration {:fn-picker {:view fn-id}}
                                        :snapshot {:graph {} :namespaces []}})
                  resolution/owned-writable-components (constantly {})
                  components/assert-current-policy! (constantly nil)
                  sp/read-entity (fn [_ _ id] {:id id})
                  sp/query-entities (fn [actual entity where opts]
                                      (is (= storage actual))
                                      (is (= :ui-pref entity))
                                      (is (= "components" (:key where)))
                                      (is (= {:limit 2} opts))
                                      (swap! calls conj :preference)
                                      [{:value {:fn-id "unselected-legacy-identity"}}])
                  source/collect-closure (fn [actual ids _ _]
                                           (is (= storage actual))
                                           (is (= [fn-id] (vec ids)))
                                           (swap! calls conj :body) {})
                  source/collect-namespaces (constantly [])
                  snapshot/export-snapshot (fn [_ _ entries frozen]
                                             (is (= {:view fn-id} entries))
                                             (is (= (assoc policy :type-recheck nil :checker-cache nil) frozen))
                                             (swap! calls conj :export) {:checked true})]
      (let [result (components/export-resolved ctx {:component "fn-picker"})]
        (is (:ok result))
        (is (= (str selected) (:selection-id result)))
        (is (= "authoritative-revision" (:configuration-revision result)))
        (is (= {:present true :value {:fn-id "unselected-legacy-identity"}} (:configuration-preference result)))
        (is (= [:resolve :body :export :preference] @calls))))))
