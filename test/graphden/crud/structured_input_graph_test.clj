(ns ^:integration ^:serial graphden.crud.structured-input-graph-test
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as runtime]
    [graphden.executor.interface :as exec]
    [graphden.input.access :as access]
    [graphden.packages.records.ids :as record-ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as router]
    [graphden.test-infra.golden-app :as golden]))


(use-fixtures :once (golden/fixture (ns-name *ns*)))


(defn- seed-owner!
  [storage]
  (let [inner (sp/create-entity storage :fn {:name "profile-transport-fixture"})
        input (sp/create-entity storage :fn {:name "input-transport-fixture"})
        profile (sp/create-entity storage :slot {:name "profile" :type-fn-id (:id inner)})
        name-field (sp/create-entity storage :slot {:name "name" :type-fn-id (:text (record-ids/primitive-fn-ids))})
        owner (sp/create-entity storage :fn {:name "owner-transport-fixture" :input-type-fn-id (:id input)})]
    (sp/create-entities storage :fn-slot
                        [{:fn-id (:id input) :slot-id (:id profile) :position 0}
                         {:fn-id (:id inner) :slot-id (:id name-field) :position 0}])
    (:id owner)))


(defn- send-command
  [app token phase body]
  (router/dispatch app
                   {:request-method :post
                    :uri (str "/api/structured-input/" phase)
                    :headers (cond-> {"content-type" "application/json"}
                               token (assoc "authorization" (str "Bearer " token)))
                    :body (json/generate-string body)}))


(defn- decode
  [response]
  (json/parse-string (:body response) true))


(defn- remaining-fields
  [app token owner]
  (let [response (router/dispatch app
                                  {:request-method :get :uri "/api/graph/entities"
                                   :query-string (str "scope=subtree&root-id=" owner)
                                   :headers {"authorization" (str "Bearer " token)}})]
    (is (= 200 (:status response)))
    (some #(when (= (str owner) (:id %)) (get-in % [:structured-input :fields]))
          (:fns (decode response)))))


(defn- prepare!
  [app token command]
  (let [response (send-command app token "preview" command)]
    (is (= 200 (:status response)))
    (merge command (select-keys (decode response) [:expected-version :expected-state]))))


(deftest authenticated-fixture-routes-roundtrip-test
  (let [storage (:storage golden/*bootstrap*)
        token (str (random-uuid))
        ctx (exec/create-context {:storage storage :auth-provider (auth/single-token-provider token)})
        _ (runtime/rebuild! ctx)
        app (router/create-router ctx "_app-ring-response")
        owner (binding [access/*offline?* true] (seed-owner! storage))
        create {:action "bind" :fn-id (str owner) :path ["profile" "name"] :value "original"}]
    (is (= 403 (:status (send-command app token "preview" (assoc create :offline? true)))))
    (binding [access/*offline?* true]
      (is (= 401 (:status (send-command app nil "preview" create))))
      (is (= 400 (:status (send-command app token "preview" []))))
      (is (= 422 (:status (send-command app token "preview" (assoc create :value 42)))))
      (let [bound (send-command app token "apply" (prepare! app token create))
            id (:binding-id (decode bound))
            edit {:action "edit" :fn-id (str owner) :binding-id id :value "revised"}
            removal {:action "remove" :fn-id (str owner) :binding-id id}]
        (is (= 200 (:status bound)))
        (is (= "original" (:value (sp/read-entity storage :input-binding (parse-uuid id)))))
        (is (= [] (remaining-fields app token owner)))
        (let [prepared (prepare! app token edit)
              wrong-action (merge removal (select-keys prepared [:expected-version :expected-state]))]
          (is (= 409 (:status (send-command app token "apply" wrong-action))))
          (is (= 200 (:status (send-command app token "apply" prepared))))
          (is (= 409 (:status (send-command app token "apply" prepared))))
          (is (= "revised" (:value (sp/read-entity storage :input-binding (parse-uuid id))))))
        (let [prepared (prepare! app token removal)]
          (is (= 200 (:status (send-command app token "apply" prepared))))
          (is (nil? (sp/read-entity storage :input-binding (parse-uuid id))))
          (is (= "name" (get-in (remaining-fields app token owner) [0 :fields 0 :name])))
          (is (= 409 (:status (send-command app token "apply" prepared)))))))))
