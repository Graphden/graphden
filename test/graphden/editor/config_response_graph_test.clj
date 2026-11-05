(ns ^:integration ^:serial graphden.editor.config-response-graph-test
  "Configuration responses through stored production graph handlers. The native
   operation is stubbed only to exercise private failure data and precedence;
   authorization/receipt/write behavior remains covered by config-resolution."
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.editor.config-preparation :as preparation]
    [graphden.executor.interface :as exec]
    [graphden.packages.records.ids :as ids]
    [graphden.test-infra.golden-app :as ga]))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


(defn- submit
  []
  (let [response (ga/exec-handler
                   :_editor-configuration-prepare-handler
                   {:request-method :post :uri "/api/ui/configuration/prepare"
                    :headers {"content-type" "application/json"} :body "{}"})]
    {:status (:status response)
     :body (json/parse-string (:body response) true)}))


(def ^:private generic-reason
  "Personal UI graph is unavailable. Using built-in components.")


(def ^:private quota-reason
  "Your organization has reached its graph limit. Free space or change your plan before creating more graphs.")


(deftest configuration-failure-policy-preserves-status-overrides-and-redaction
  (doseq [[data status fields]
          [[{} 422 {}]
           [{:type :authz/forbidden} 403 {}]
           [{:type :browser-plan/unsupported :reason :component-missing}
            422 {:code "component-missing" :fallback "builtin"}]
           [{:type :browser-plan/unsupported :reason :policy-refresh-required}
            422 {:code "policy-refresh-required" :retryable true :retry-after 1}]
           [{:type :quota/entity-limit} 429 {:code "graph-limit" :reason quota-reason}]
           [{:reason :invalid-request} 400 {}]
           [{:type :validation-error/delete-receipts} 400 {}]
           [{:reason :stale-configuration} 409
            {:code "stale-configuration"
             :reason "The editor configuration changed. Refresh before selecting another configuration."}]
           [{:type :personal-home/unavailable :reason :personal-home-unavailable}
            422 {:code "personal-home-unavailable"
                 :reason "Your deployment has no available personal graph namespace."}]
           [{:type :browser-plan/unsupported :reason :personal-home-unavailable}
            422 {:code "personal-home-unavailable"
                 :reason "Your deployment has no available personal graph namespace."}]
           ;; Later invalid/stale overrides preserve earlier safe fields.
           [{:type :quota/entity-limit :reason :invalid-request}
            400 {:code "graph-limit" :reason quota-reason}]
           [{:type :quota/entity-limit :reason :stale-configuration}
            409 {:code "stale-configuration"
                 :reason "The editor configuration changed. Refresh before selecting another configuration."}]
           [{:type :private/source :reason {:identity "PRIVATE"}} 422 {}]]]
    (with-redefs [preparation/prepare!
                  (fn [_ _]
                    (throw (ex-info "PRIVATE exception detail"
                                    (assoc data :source "PRIVATE source" :id (random-uuid)))))]
      (let [native (exec/execute-with-named-args
                     (:ctx ga/*bootstrap*)
                     (ids/fn-id "app.ui-components" :_editor-configuration-prepare-outcome)
                     {:input {}})
            response (submit)]
        (is (= #{:succeeded? :value :failure-type :failure-reason} (set (keys native))))
        (is (not (str/includes? (pr-str native) "PRIVATE")))
        (is (= {:status status :body (merge {:ok false :reason generic-reason} fields)} response)
            (pr-str data))))))


(deftest configuration-success-preserves-receipts-and-publication-warnings
  (doseq [receipt [nil
                   {:ok false :committed true :id (str (random-uuid))
                    :publication-warnings [{:reason "Refresh the editor."}]}]]
    (let [calls (atom 0)]
      (with-redefs [preparation/prepare! (fn [_ input]
                                           (is (= {} input))
                                           (swap! calls inc)
                                           receipt)]
        (is (= {:status 200 :body (assoc receipt :ok true)} (submit)))
        (is (= 1 @calls) "Shared outcome is evaluated once, including success projection")))))


(deftest every-configuration-operation-keeps-its-http-input-contract
  (doseq [operation ["prepare" "preview" "apply" "undo" "reconcile"
                     "reconcile-undo" "cleanup-preview"]]
    (let [response (ga/exec-handler
                     (keyword (str "_editor-configuration-" operation "-handler"))
                     {:request-method :post :uri (str "/api/ui/configuration/" operation)
                      :headers {"content-type" "application/json"} :body "42"})]
      (is (= 400 (:status response)) operation)
      (is (= {:ok false :reason generic-reason} (json/parse-string (:body response) true)) operation)))
  (doseq [operation ["preview" "apply"]]
    (let [response (ga/exec-handler
                     (keyword (str "_editor-root-migration-" operation "-handler"))
                     {:request-method :post :uri (str "/api/ui/root/migration/" operation)
                      :headers {"content-type" "application/json"} :body "42"})]
      (is (= 400 (:status response)) operation))))
