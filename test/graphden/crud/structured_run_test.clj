(ns ^:integration ^:serial graphden.crud.structured-run-test
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.crud.fn-execution :as fn-execution]
    [graphden.crud.structured-run :as run]
    [graphden.executor.compile-runtime :as executor]
    [graphden.executor.interface :as exec]
    [graphden.input.access :as access]
    [graphden.packages.records.ids :as ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as router]
    [graphden.tenancy.context :as tenant]
    [graphden.test-infra.golden-app :as golden]))


(use-fixtures :once (golden/fixture (ns-name *ns*)))


(defn- migrate-floor!
  [storage floor]
  (binding [access/*offline?* true]
    (let [input (:id (sp/create-entity storage :fn {:name "stored-floor-arguments"}))
          child (:id (sp/create-entity storage :fn {:name "stored-floor-child" :parent-ids [floor]
                                                    :input-type-fn-id input}))]
      (sp/create-entity storage :fn-slot {:fn-id input :slot-id (ids/slot-id floor :number) :position 0})
      (sp/update-entity storage :fn floor {:input-type-fn-id input})
      child)))


(defn- projected-child!
  "The supplied record's :ref is ordinary numeric data for the floor argument."
  [storage floor]
  (binding [access/*offline?* true]
    (let [number-slot (ids/slot-id floor :number)
          input (:id (sp/create-entity storage :fn {:name "projected-floor-input"}))
          record (:id (sp/create-entity storage :fn {:name "floor-config"}))
          config (:id (sp/create-entity storage :slot {:name "config" :type-fn-id record :required true}))
          leaf (:id (sp/create-entity storage :slot {:name "ref" :required true
                                                     :type-fn-id (:type-fn-id (sp/read-entity storage :slot number-slot))}))
          child (:id (sp/create-entity storage :fn {:name "projected-floor-child" :parent-ids [floor]
                                                    :input-type-fn-id input}))]
      (sp/create-entities storage :fn-slot [{:fn-id record :slot-id leaf :position 0}
                                            {:fn-id input :slot-id number-slot :position 0}
                                            {:fn-id input :slot-id config :position 1}])
      (sp/create-entity storage :input-binding {:fn-id child :scope-path [] :target-path [number-slot]
                                                :expression-kind "projection" :value-present false
                                                :projection-scope-path [] :projection-path [config leaf]})
      [child config])))


(deftest ordinary-run-selects-the-stored-input-contract
  (let [{:keys [storage base-fns]} golden/*bootstrap*
        floor (ids/fn-id "core.arithmetic" :floor)
        ceil (ids/fn-id "core.arithmetic" :ceil)
        child (migrate-floor! storage floor)
        [projected-child config] (projected-child! storage floor)
        token (str (random-uuid))
        denied (atom nil)
        guard (fn [_ id] (when (= @denied id) (throw (ex-info "Denied" {:type :authz/forbidden}))))
        ctx (exec/create-context {:storage storage :base-fns base-fns :execute-guard guard
                                  :auth-provider (auth/single-token-provider token)})
        _ (executor/rebuild! ctx)
        app (router/create-router ctx "_app-ring-response")
        post (fn [id args & [options]]
               (let [response (router/dispatch app {:request-method :post :uri "/api/execute"
                                                    :headers {"content-type" "application/json"
                                                              "authorization" (str "Bearer " token)}
                                                    :body (json/generate-string (merge {:fn-id id :args args :timeout-ms 30000} options))})]
                 (json/parse-string (:body response) true)))
        view (fn [id]
               (let [response (router/dispatch app {:request-method :get :uri "/api/graph/entities"
                                                    :query-string (str "scope=subtree&root-id=" id)
                                                    :headers {"authorization" (str "Bearer " token)}})]
                 (some #(when (= (str id) (:id %)) (:structured-input %))
                       (:fns (json/parse-string (:body response) true)))))]
    (let [projected (view child)] (is (true? (:run-available projected)) (pr-str projected)))
    (is (= -2 (:result (post ceil {:number -2.3}))))
    (is (= -3 (:result (post child {:number -2.3}))))
    (is (= {:reason "invalid-structured-arguments" :code "unknown-input-field"}
           (:error-data (post child {:unknown 10}))))
    (let [value {:ref -7.6}
          response (post projected-child {:config value} {:persist? true})
          execution-id (some-> (:execution-id response) parse-uuid)
          rows (when execution-id (sp/query-entities storage :fn-execution-arg {:execution-id execution-id}))]
      (is (= -8 (:result response)) (pr-str response))
      (is (= [[config value nil]] (mapv (juxt :slot-id :value :ref-fn-version-id) rows))))
    (let [{:keys [invoke]} (run/plan ctx child {:number 6.8})
          attempt (fn [] (try (invoke {:number 6.8} ctx)
                              (catch clojure.lang.ExceptionInfo error (:code (ex-data error)))))]
      (with-redefs [tenant/browser-source-policy (fn [_] #(when (= floor (:id %)) :interface))]
        (is (= :input-source-interface-only (attempt))))
      (binding [access/*offline?* true]
        (sp/create-entity storage :input-binding
                          {:fn-id child :scope-path [] :target-path [(ids/slot-id floor :number)]
                           :expression-kind "value" :value-present true :value -4.6}))
      (is (= :stored-input-changed (attempt))))
    (is (= -5 (:result (post child {}))))
    (is (= -5 (:result (fn-execution/apply-execute ctx {:args {} :timeout-ms 30000} {:id child}))))
    (is (= "rejected" (:status (post child {:number 1}))))
    (reset! denied child)
    (is (= "forbidden" (get-in (post child {:number 2.5}) [:error-data :reason])))
    (reset! denied nil)
    (binding [access/*offline?* true]
      (let [broken (:id (sp/create-entity storage :fn {:name "untrusted-floor-child"
                                                       :input-type-fn-id (:input-type-fn-id (sp/read-entity storage :fn floor))
                                                       :return-type-fn-id (ids/primitive-fn-id :int)}))]
        (is (= "structured-input-unavailable" (get-in (post broken {:number 2.5}) [:error-data :reason])))))))
