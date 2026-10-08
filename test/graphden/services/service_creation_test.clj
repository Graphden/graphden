(ns ^:integration graphden.services.service-creation-test
  "Proposed service UUIDs pass the ordinary graph form parser and SQL insert.
   A retry cannot update an existing service, and malformed UUIDs fail early."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.crud.entities :as entities]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.storage.protocol.core :as sp]
    [graphden.test-infra.golden-app :as golden]))


(use-fixtures :once (golden/fixture (ns-name *ns*)))


(defn- parsed
  [parser data]
  (let [{:keys [ctx storage]} golden/*bootstrap*]
    (setup/exec-with-storage ctx storage (golden/fn-id parser) {:form-data data})))


(deftest create-identity-parsing-never-changes-the-update-contract
  (let [id (random-uuid)]
    (is (= {:id id} (parsed :_parse-svc-form-create-id-fragment {:create-id (str id)})))
    (is (= {} (parsed :_parse-svc-form-create-id-fragment {})))
    (doseq [value [nil "" "not-a-uuid"]]
      (is (thrown? Exception (parsed :_parse-svc-form-create-id-fragment {:create-id value}))))
    (is (not (contains? (parsed :parse-service-from-form {:create-id (str id)}) :id))
        "The ordinary PUT field parser cannot change service identity")))


(deftest service-form-creation-rejects-an-existing-proposed-identity
  (let [{:keys [ctx storage]} golden/*bootstrap*
        _ (setup/sync-and-invalidate! ctx storage
                                      [{:name :service-identity-worker :parent :future
                                        :args {:body {:parent :const :args {:value true}}}}])
        fn-id (records/fn-id nil :service-identity-worker)
        id (random-uuid)
        form {:create-id (str id) :fn-id (str fn-id) :enabled? "false"
              :restart-policy "never" :cardinality "singleton"}
        create #(entities/apply-create-core
                  {:entity-type :service :type-str "service" :form-data %
                   :entity-data (parsed :parse-service-create-from-form %)} ctx)]
    (try
      (is (= id (:created (create form))))
      (let [original (sp/read-entity storage :service id)
            again (create (assoc form :enabled? "true"))]
        (is (= 409 (:http-status again)))
        (is (string? (:error again)))
        (is (= original (sp/read-entity storage :service id)))
        (is (false? (:enabled? original))))
      (finally
        (when (sp/read-entity storage :service id) (sp/delete-entity storage :service id))))))


(defn- instances-response
  [service-id]
  (setup/via-graph golden/*bootstrap* :_partial-service-instances-handler
                   {:uri "/partials/service-instances" :request-method :get
                    :query-params {"service-id" (str service-id)}}))


(deftest service-instances-partial-reads-only-the-requested-identity
  (let [{:keys [ctx storage]} golden/*bootstrap*
        _ (setup/sync-and-invalidate! ctx storage
                                      [{:name :instance-read-worker :parent :future
                                        :args {:body {:parent :const :args {:value true}}}}])
        fn-id (records/fn-id nil :instance-read-worker)
        services (mapv (fn [_]
                         (sp/create-entity storage :service
                                           {:fn-id fn-id :enabled? false
                                            :restart-policy :never :cardinality :singleton}))
                       (range 2))
        [first-service second-service] services
        now (java.time.Instant/now)
        instance (sp/create-entity storage :service-instance
                                   {:service-id (:id first-service) :executor-id "<pod>"
                                    :host "host-one" :port 9090 :started-at now :seen-at now})]
    (try
      (let [html (:body (instances-response (:id first-service)))]
        (is (str/includes? html (str "data-service-id=\"" (:id first-service) "\"")))
        (is (str/includes? html "data-instance-count=\"1\""))
        (is (str/includes? html "host-one:9090"))
        (is (str/includes? html "&lt;pod&gt;"))
        (is (not (str/includes? html "<pod>"))))
      (let [html (:body (instances-response (:id second-service)))]
        (is (str/includes? html "data-instance-count=\"0\""))
        (is (not (str/includes? html "host-one"))))
      (doseq [invalid ["" "not-a-uuid"]]
        (is (thrown? Exception (instances-response invalid))))
      (sp/delete-entity storage :service-instance (:id instance))
      (is (str/includes? (:body (instances-response (:id first-service)))
                         "data-instance-count=\"0\""))
      (finally
        (when (sp/read-entity storage :service-instance (:id instance))
          (sp/delete-entity storage :service-instance (:id instance)))
        (doseq [service [first-service second-service]]
          (sp/delete-entity storage :service (:id service)))))))
