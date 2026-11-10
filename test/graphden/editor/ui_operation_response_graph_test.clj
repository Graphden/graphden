(ns ^:integration ^:serial graphden.editor.ui-operation-response-graph-test
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.editor.component-creation :as creation]
    [graphden.editor.components :as components]
    [graphden.editor.root-plan :as root-plan]
    [graphden.executor.browser-server :as server]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records.ids :as ids]))


(def ^:dynamic *graph* nil)


(use-fixtures :once
  (setup/create-container-fixture)
  (fn [f]
    (let [graph (setup/bootstrap-crud-graph-from-golden!*
                  "ui-operation-response" ["core" "web" "app"])]
      (binding [*graph* graph]
        (try (f) (finally (setup/close-graph! graph)))))))


(def ^:private endpoints
  {:component [:_editor-configuration-plan #'components/export-resolved {:input {}}]
   :root [:_editor-root-plan #'root-plan/export-current {:request {}}]
   :server [:_editor-server-call #'server/invoke-current {:request {} :input {}}]
   :preview [:_ui-components-create-preview #'creation/preview {:input {}}]
   :apply [:_ui-components-create-apply #'creation/apply! {:input {}}]})


(defn- response
  [endpoint native-result]
  (let [[fn-name operation args] (get endpoints endpoint)]
    (with-redefs-fn {operation (fn [& _] native-result)}
      #(exec/execute-with-named-args (:ctx *graph*)
                                     (ids/fn-id "app.ui-components" fn-name) args))))


(defn- failure
  [data]
  (components/failure-facts (ex-info "PRIVATE exception text" data)))


(deftest component-and-root-refusal-policy-test
  (doseq [[data status fields]
          [[{} 422 {}]
           [{:type :authz/forbidden :reason :policy-refresh-required} 403 {}]
           [{:type :browser-plan/unsupported :reason :invalid-request} 422 {}]
           [{:type :browser-plan/unsupported :reason :component-missing}
            422 {:code "component-missing" :fallback "builtin"}]
           [{:type :browser-plan/unsupported :reason :policy-refresh-required}
            422 {:code "policy-refresh-required" :retryable true :retry-after 1}]
           [{:type :unknown/error :reason :policy-refresh-required} 422 {}]
           [{:type :quota/entity-limit :identity "PRIVATE"} 429 {:code "graph-limit"}]]
          endpoint [:component :root]]
    (let [out (response endpoint (failure data))]
      (is (= status (:http-status out)))
      (is (false? (:ok out)))
      (is (= fields (dissoc out :ok :http-status :reason)))
      (is (not (str/includes? (pr-str out) "PRIVATE")))
      (when (= endpoint :root)
        (is (= "Editor root is unavailable." (:reason out)))))))


(deftest server-stale-proof-and-forbidden-status-test
  (doseq [[data status stale?]
          [[{} 422 false]
           [{:type :authz/forbidden} 403 false]
           [{:type :browser-plan/unsupported :reason :server-epoch-changed} 422 true]
           [{:type :browser-plan/unsupported :reason :server-epoch-changed :private "PRIVATE"} 422 false]
           [{:type :authz/forbidden :reason :server-epoch-changed} 403 false]]]
    (is (= (cond-> {:ok false :reason "Server call is unavailable." :http-status status}
             stale? (assoc :code "server-call-stale"))
           (response :server (failure data))))))


(deftest creation-status-precedence-and-committed-receipts-test
  (doseq [endpoint [:preview :apply]
          [data status] [[{:http-status 400} 400]
                         [{:http-status 409 :type :authz/forbidden} 409]
                         [{:type :authz/forbidden} 403]
                         [{:type :quota/entity-limit} 429]
                         [{:type :browser-plan/unsupported} 422]
                         [{:type :storage-error/candidate-limit} 422]
                         [{:type :storage-error/bounded-query-unsupported} 422]
                         [{} 500]]]
    (let [out (response endpoint (failure (assoc data :private "PRIVATE")))]
      (is (= status (:http-status out)))
      (is (= #{:ok :committed :http-status :reason} (set (keys out))))
      (when (= 409 status)
        (is (= "The UI graph preview has changed. Refresh and try Create again." (:reason out))))
      (when (= 500 status)
        (is (= "UI graphs were not created. Try Create again after refreshing." (:reason out))))
      (is (false? (:committed out)))
      (is (not (str/includes? (pr-str out) "PRIVATE")))))
  (doseq [endpoint (keys endpoints)]
    (let [receipt {:ok true :committed true :manifest {:functions []}
                   :publication-warnings [{:reason "Reload derived state"}]}]
      (is (= receipt (response endpoint receipt))))))


(deftest post-commit-publication-failure-keeps-the-committed-receipt-test
  (let [receipt {:ok true :committed true :manifest {:functions []}}]
    (is (= (assoc receipt :publication-warnings
                  [{:reason "Your UI graphs were created. Refresh the editor to reload their derived state."}])
           (response :apply (assoc receipt :publication-incomplete? true))))))


(deftest batch-items-use-the-existing-public-response-policy
  (let [calls (atom 0)
        receipt {:ok true :selection-id "selected" :plan {:bounded true}}
        native {:ok true :results {"inspector" receipt
                                   "explorer" (failure {:type :browser-plan/unsupported :reason :component-missing})
                                   "canvas" (failure {:type :authz/forbidden})}}]
    (with-redefs [components/export-resolved-batch (fn [& _] (swap! calls inc) native)]
      (let [out (exec/execute-with-named-args (:ctx *graph*)
                                              (ids/fn-id "app.ui-components" :_editor-configuration-plans)
                                              {:input {:components ["inspector" "explorer" "canvas"]}})]
        (is (true? (:ok out)))
        (is (= 1 @calls) "Response composition invokes the effectful batch capture exactly once")
        (is (= receipt (get-in out [:results "inspector"])))
        (is (= {:ok false :http-status 422 :code "component-missing" :fallback "builtin"
                :reason "Personal UI graph is unavailable. Using built-in components."}
               (get-in out [:results "explorer"])))
        (is (= 403 (get-in out [:results "canvas" :http-status])))
        (is (not (str/includes? (pr-str out) "failure-type"))))))
  (with-redefs [components/export-resolved-batch
                (fn [& _] (failure {:type :browser-plan/unsupported :reason :policy-refresh-required}))]
    (let [out (exec/execute-with-named-args (:ctx *graph*)
                                            (ids/fn-id "app.ui-components" :_editor-configuration-plans)
                                            {:input {:components ["inspector"]}})]
      (is (= [false 422 "policy-refresh-required" true]
             ((juxt :ok :http-status :code :retryable) out)))
      (is (not (contains? out :results))))))
