(ns ^:serial graphden.editor.component-selection-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.editor.component-config :as config]
    [graphden.editor.component-selection :as selection]
    [graphden.editor.components :as components]
    [graphden.executor.browser-snapshot :as snapshot]
    [graphden.executor.browser-source :as source]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tenancy]
    [graphden.versioning.storage.core :as versioned]))


(deftest captured-preference-and-retained-manifest-come-from-one-authoritative-read
  (let [a (random-uuid)
        b (random-uuid)
        branch (random-uuid)
        namespace-id (random-uuid)
        document (fn [id] {:fn-id (str id) :branch-id (str branch) :org (tenancy/current-org)})
        reads (atom 0)
        captured (atom [])]
    (with-redefs [sp/query-entities (fn [_ entity where opts]
                                      (is (= :ui-pref entity))
                                      (is (= {:owner-id (tenancy/current-user-id) :key "components"} where))
                                      (is (= {:limit 2} opts))
                                      [{:value (document (if (= 1 (swap! reads inc)) a b))}])
                  versioned/current-branch-id (constantly branch)
                  source/collect-manifest (fn [_ id _]
                                            (swap! captured conj id)
                                            {:fns [{:id id :name nil :namespace-id namespace-id :parent-ids []}]})
                  source/collect-namespaces (fn [_ _] [{:id namespace-id :name "personal" :parent-id nil}])
                  snapshot/validate-source! (fn [_ id policy]
                                              (is (= a id)) (is (= ::frozen policy)))
                  components/assert-current-policy! (fn [_])
                  config/configuration (fn [_ id] (is (= a id)) {})]
      (let [result (selection/capture {} :recents (fn [_]) ::frozen "personal.addition")]
        (is (= 1 @reads))
        (is (= [a] @captured))
        (is (= {:present true :value (document a)} (:expected result)))
        (is (= {(keyword "personal.addition" (str "_retained-target-" a)) a} (:names result)))))))


(deftest anonymous-legacy-entries-keep-their-authoritative-identities
  (let [root-id (random-uuid)
        branch (random-uuid)
        entries {:account-menu {:initial (random-uuid) :update (random-uuid) :view (random-uuid)}
                 :fn-picker {:view (random-uuid)}}
        selection-value {:fn-id (str root-id) :org (tenancy/current-org) :branch-id (str branch)}
        graph {:fns (into [{:id root-id :name nil :parent-ids [config/configuration-id]}
                           {:id config/configuration-id :name "ui-components" :parent-ids []}]
                          (map #(hash-map :id % :name nil)) (mapcat vals (vals entries)))}]
    (with-redefs [sp/query-entities (fn [_ _ _ _] [{:value selection-value}])
                  versioned/current-branch-id (constantly branch)
                  source/collect-manifest (fn [_ _ _] graph)
                  source/collect-namespaces (fn [_ _] [])
                  snapshot/validate-source! (fn [_ _ _])
                  components/assert-current-policy! (fn [_])
                  config/configuration (fn [_ _] entries)]
      (let [captured (selection/capture {} :recents (fn [_]) ::frozen "personal.addition")]
        (is (nil? (:retained captured)))
        (is (= (set (mapcat vals (vals entries))) (set (vals (:names captured)))))
        (doseq [[component roles] entries
                [role id] roles]
          (is (= id (get (:names captured) (get-in captured [:legacy component role])))))))))
