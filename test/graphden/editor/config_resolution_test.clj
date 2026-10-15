(ns ^:serial graphden.editor.config-resolution-test
  (:require
    [clojure.test :refer [deftest is]]
    [graphden.editor.config-resolution :as resolution]
    [graphden.graph.metadata :as metadata]
    [graphden.storage.graph-writer :as writer]
    [graphden.storage.protocol.core :as sp]))


(deftest personal-action-metadata-checks-one-home-and-never-reads-role-bodies
  (let [home-id (random-uuid) own-id (random-uuid) denied-id (random-uuid) shared-id (random-uuid)
        walks (atom 0) checked (atom [])
        current {:home {:namespace-path "profile"}
                 :configuration {:fn-picker {:view own-id} :inspector {:view denied-id} :explorer {:view shared-id}}
                 :snapshot {:graph {:fns [{:id own-id :namespace-id home-id}
                                          {:id denied-id :namespace-id home-id}
                                          {:id shared-id :namespace-id (random-uuid)}]}}}]
    (with-redefs [metadata/namespace-row (fn [& _] (swap! walks inc) {:id home-id})
                  sp/read-entity (fn [& _] (throw (ex-info "Unexpected role read" {})))
                  sp/query-entities (fn [& _] (throw (ex-info "Unexpected role query" {})))
                  writer/assert-write-authorized! (fn [_ _ _ id]
                                                    (swap! checked conj id)
                                                    (when (= denied-id id)
                                                      (throw (ex-info "Denied" {:type :authz/forbidden}))))]
      (is (= {"account-menu" false "fn-picker" true "inspector" false "explorer" false "recents" false
              "tutorial-catalog" false}
             (resolution/owned-writable-components nil current)))
      (is (= 1 @walks))
      (is (= #{own-id denied-id} (set @checked)))
      (is (not (resolution/owned-writable-view? nil current :explorer)))
      (with-redefs [writer/assert-write-authorized! (fn [& _] (throw (ex-info "Storage failure" {:type :storage/failure})))]
        (is (= :storage/failure
               (try (resolution/owned-writable-view? nil current :fn-picker) nil
                    (catch clojure.lang.ExceptionInfo error (:type (ex-data error))))))))))
