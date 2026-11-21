(ns ^:integration ^:serial graphden.crud.structured-input-lesson-test
  "A loopback-only rehearsal of the v2 authoring and named execution boundary."
  (:require
    [cheshire.core :as json]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.crud.request :as request]
    [graphden.crud.structured-input :as structured]
    [graphden.executor.browser-source :as source]
    [graphden.executor.compile-runtime :as executor]
    [graphden.executor.interface :as exec]
    [graphden.executor.runtime :as runtime]
    [graphden.input.access :as access]
    [graphden.input.compile :as compile]
    [graphden.input.read-view :as view]
    [graphden.input.runtime :as input-runtime]
    [graphden.packages.records.ids :as record-ids]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as router]
    [graphden.test-infra.golden-app :as golden]
    [org.httpkit.server :as http])
  (:import
    (java.util
      Map)
    (java.util.concurrent
      TimeUnit)))


(use-fixtures :once (golden/fixture (ns-name *ns*)))


(defn- seed-lesson!
  [storage]
  (let [person (:id (sp/create-entity storage :fn {:name "lesson-person-record"}))
        input (:id (sp/create-entity storage :fn {:name "lesson-run-input"}))
        profile (:id (sp/create-entity storage :slot {:name "profile" :type-fn-id person :required true}))
        text-id (:text (record-ids/primitive-fn-ids))
        members (map-indexed (fn [position name]
                               (let [{:keys [id]} (sp/create-entity storage :slot {:name name :type-fn-id text-id :required true})]
                                 {:fn-id person :slot-id id :position position}))
                             ["id" "name" "email"])
        root (:id (sp/create-entity storage :fn {:name "tutorial-structured-profile" :input-type-fn-id input}))]
    (sp/create-entities storage :fn-slot (into [{:fn-id input :slot-id profile :position 0}] members))
    {:root root :profile profile}))


(defn- execute-lesson
  [ctx {:keys [root profile]} args]
  (source/with-snapshot
    (:storage ctx)
    (fn [storage authorize!]
      (let [frozen (view/collect storage root authorize!)
            graph (structured/analyze frozen root)
            profile-type {:id :text :name :text :email :text}
            _ (when-not (= {:profile profile-type} (get-in graph [:scopes [] :configured-contract :type]))
                (throw (ex-info "The lesson contract changed" {:type :input/lesson-contract})))
            implementation {:base-fn-id root :argument-paths {:profile [profile]}
                            :root-entry {:args {:profile profile-type} :return profile-type}
                            :impl (fn [inputs _]
                                    (input-runtime/materialize-record (runtime/resolve-arg inputs :profile)))}
            closure (compile/compile-named graph {:context frozen :implementations {root implementation}})
            invocation (assoc ctx :storage storage :allowed-effects #{}
                              :registry-stale? (atom false) :compiled-registry (atom {root closure}))]
        (exec/execute invocation root args)))))


(defn- json-response
  [status body]
  {:status status :headers {"Content-Type" "application/json"} :body (json/generate-string body)})


(defn- fixture-request?
  [ctx req]
  (and access/*offline?* (:authenticated? (auth/authenticate (:auth-provider ctx) req))))


(defn- run-response
  [ctx {:keys [root] :as fixture} req]
  (if-not (fixture-request? ctx req)
    (json-response 403 {:ok false})
    (try
      (let [{:keys [fn-id args] :as body} (request/read-json-body req)]
        (if (and (map? body) (= #{:fn-id :args} (set (keys body))) (= (str root) fn-id) (map? args))
          (json-response 200 {:ok true :value (execute-lesson ctx fixture args)})
          (json-response 400 {:ok false})))
      (catch clojure.lang.ExceptionInfo error
        (json-response (if (= :authz/forbidden (:type (ex-data error))) 403 422) {:ok false})))))


(defn- lesson-handler
  [ctx fixture app lesson]
  (fn [{:keys [request-method uri] :as req}]
    (case [request-method uri]
      [:get "/"] {:status 200 :headers {"Content-Type" "text/html"} :body "<!doctype html><title>Structured input fixture</title>"}
      [:get "/fixture/lesson"] (if (fixture-request? ctx req)
                                 (json-response 200 lesson)
                                 (json-response 403 {:ok false}))
      [:post "/fixture/run"] (run-response ctx fixture req)
      ([:get "/api/graph/entities"]
       [:post "/api/structured-input/preview"]
       [:post "/api/structured-input/apply"]) (router/dispatch app req)
      {:status 404 :body "Not part of the lesson"})))


(defn- post
  [handler token uri body]
  (handler {:request-method :post :uri uri
            :headers {"content-type" "application/json" "authorization" (str "Bearer " token)}
            :body (json/generate-string body)}))


(defn- bind-name!
  [handler token command]
  (let [preview (post handler token "/api/structured-input/preview" command)
        basis (:expected-state (json/parse-string (:body preview) true))]
    (is (= 200 (:status preview)))
    (is (= 200 (:status (post handler token "/api/structured-input/apply" (assoc command :expected-state basis)))))))


(defn- remaining-profile
  [handler token root]
  (let [response (handler {:request-method :get :uri "/api/graph/entities"
                           :query-string (str "scope=subtree&root-id=" root)
                           :headers {"authorization" (str "Bearer " token)}})]
    (is (= 200 (:status response)))
    (let [rows (:fns (json/parse-string (:body response) true))]
      (some #(when (= (str root) (:id %)) (get-in % [:structured-input :fields 0 :fields])) rows))))


(defn- exercise-in-browser!
  [handler token root]
  (let [http-handler (bound-fn [req]
                       ;; Graphden's Ring response carries keyword header keys.
                       ;; Match the app's HTTP boundary before handing it to http-kit.
                       (update (handler req) :headers update-keys name))
        stop (http/run-server http-handler {:ip "127.0.0.1" :port 0})]
    (try
      (let [origin (str "http://127.0.0.1:" (:local-port (meta stop)))
            builder (ProcessBuilder. ["node" "tools/browser-test/fixtures/structured-input-lesson.js" origin (str root)])
            _ (Map/.put (ProcessBuilder/.environment builder) "GD_LESSON_TOKEN" token)
            process (ProcessBuilder/.start (ProcessBuilder/.inheritIO builder))]
        (try
          (is (Process/.waitFor process 45 TimeUnit/SECONDS) "Playwright completed the live fixture")
          (when-not (Process/.isAlive process)
            (is (zero? (Process/.exitValue process))))
          (finally
            (when (Process/.isAlive process) (Process/.destroyForcibly process)))))
      (finally (stop)))))


(deftest bind-name-then-run-through-the-checked-named-closure-test
  (let [storage (:storage golden/*bootstrap*)
        {:keys [root] :as fixture} (binding [access/*offline?* true] (seed-lesson! storage))
        token (str (random-uuid))
        ctx (exec/create-context {:storage storage :auth-provider (auth/single-token-provider token)})
        _ (executor/rebuild! ctx)
        app (router/create-router ctx "_app-ring-response")
        lesson (some #(when (= "structured-input" (:slug %)) %)
                     (:lessons (golden/exec-handler :_tour-lessons {})))
        allow? (atom true)
        handler (lesson-handler (assoc ctx :execute-guard
                                       (fn [_ id]
                                         (when-not (and @allow? (= root id))
                                           (throw (ex-info "Outside the fixture" {:type :authz/forbidden})))))
                                fixture app lesson)
        registry (:compiled-registry ctx)
        run {:fn-id (str root) :args {:profile {:id "42" :email "ada@example.test"}}}
        command {:fn-id (str root) :action "bind" :path ["profile" "name"] :value "Ada"}]
    (is (= 403 (:status (post handler token "/fixture/run" run))))
    (is (= 403 (:status (handler {:request-method :get :uri "/fixture/lesson"
                                  :headers {"authorization" (str "Bearer " token)}}))))
    (is (= "structured-input-run" (:requires lesson)))
    (binding [access/*offline?* true]
      (is (= 403 (:status (post handler "invalid" "/fixture/run" run))))
      (is (= 400 (:status (post handler token "/fixture/run" (assoc run :fn-id (str (random-uuid)))))))
      (is (= 422 (:status (post handler token "/fixture/run" run))))
      (if (= "1" (System/getenv "GD_STRUCTURED_INPUT_PLAYWRIGHT"))
        (exercise-in-browser! handler token root)
        (bind-name! handler token command))
      (is (= ["id" "email"] (mapv :name (remaining-profile handler token root))))
      (let [result (post handler token "/fixture/run" run)]
        (is (= 200 (:status result)))
        (is (= {:ok true :value {:id "42" :name "Ada" :email "ada@example.test"}}
               (json/parse-string (:body result) true)))
        (is (not (::executor/named-input? (meta (get @registry root))))))
      (is (= 422 (:status (post handler token "/fixture/run" (assoc-in run [:args :profile :name] "overwrite")))))
      (is (= 422 (:status (post handler token "/fixture/run" (assoc-in run [:args :profile :id] 42)))))
      (reset! allow? false)
      (is (= 403 (:status (post handler token "/fixture/run" run)))))))
