(ns ^:integration ^:serial graphden.crud.structured-lesson-lifecycle-test
  (:require
    [cheshire.core :as json]
    [clojure.string :as str]
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as executor]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as router]
    [graphden.system.init.exec]
    [graphden.system.init.packages]
    [graphden.test-infra.browser-loopback :as browser]
    [graphden.test-infra.golden-app :as golden]
    [graphden.test-infra.lesson-contact :as contact]
    [graphden.test-infra.lesson-profile :as lesson]
    [graphden.versioning.storage.core :as vs]
    [integrant.core :as ig]))


(use-fixtures :once setup/ensure-build-hashes-fixture (golden/fixture (ns-name *ns*)))


(defn- request
  [app token method uri branch data]
  (let [form? (string? data)]
    (router/dispatch app (cond-> {:request-method method :uri uri
                                  :headers (cond-> {}
                                             token (assoc "authorization" (str "Bearer " token))
                                             branch (assoc "x-graphden-branch" branch)
                                             data (assoc "content-type" (if form? "application/x-www-form-urlencoded" "application/json")))}
                           data (assoc :body (if form? data (json/generate-string data)))))))


(defn- response-json
  [response]
  (json/parse-string (:body response) true))


(defn- response-header
  [response header]
  (some (fn [[k v]] (when (= header (str/lower-case (name k))) v)) (:headers response)))


(defn- catalogue
  [app token]
  (let [response (request app token :get "/api/tour" nil nil)
        payload (response-json response)
        rows (into {} (map (juxt :id identity)) (:lessons payload))
        expected (cond-> {}
                   (contains? rows "43") (assoc :43 (str lesson/root))
                   (contains? rows "44") (assoc :44 (str contact/root)))]
    (is (= 200 (:status response)))
    (is (= "private, no-store" (response-header response "cache-control")))
    (is (= expected (or (:structured-templates payload) {})))
    rows))


(defn- with-sandbox
  [app token base-id branch exercise]
  (let [branch-id (random-uuid)
        created (request app token :post "/api/branches" nil
                         {:name branch :id branch-id :base-branch-id base-id})]
    (is (= (str branch-id) (get-in (response-json created) [:branch :id])))
    (try
      (exercise)
      (finally
        (let [response (request app token :delete (str "/api/branches/" branch-id) nil nil)
              deletion (response-json response)]
          (is (<= 200 (:status response) 299))
          (is (true? (:ok deletion)) (pr-str deletion)))))))


(defn- bind-sample!
  [app token branch value]
  (let [extension (request app token :post "/api/entities/fn" branch
                           (str "name=tutorial-structured-profile&parent-ids=" lesson/root))
        child (response-header extension "x-graphden-created-id")
        action {:fn-id child :action "bind" :path ["profile" "name"] :value value}
        preview (response-json (request app token :post "/api/structured-input/preview" branch action))
        bound (response-json (request app token :post "/api/structured-input/apply" branch
                                      (assoc action :expected-state (:expected-state preview))))]
    (is (parse-uuid child))
    (is (:ok preview))
    (is (:committed bound))
    {:child child :binding-id (parse-uuid (:binding-id bound))}))


(defn- run-sample
  [app token branch child]
  (:result (response-json (request app token :post "/api/execute" branch
                                   {:fn-id child :args {:profile {:id "42" :email "ada@example.test"}}}))))


(defn- ordinary-branch-exercise!
  [app token storage]
  (let [sandbox "tutorial-43-lifecycle"
        sibling "tutorial-43-sibling"
        base-id (vs/current-branch-id storage)
        raw (vs/unwrap storage)]
    (with-sandbox app token base-id sibling
      (fn []
        (let [{:keys [child binding-id]} (bind-sample! app token sibling "Grace")
              deleted (with-sandbox app token base-id sandbox
                        (fn []
                          (let [sample (bind-sample! app token sandbox "Ada")]
                            (is (= {:id "42" :name "Ada" :email "ada@example.test"}
                                   (run-sample app token sandbox (:child sample))))
                            sample)))]
          (is (nil? (sp/read-entity raw :fn (parse-uuid (:child deleted)))))
          (is (nil? (sp/read-entity raw :input-binding (:binding-id deleted))))
          (is (thrown-with-msg? clojure.lang.ExceptionInfo #"server-authorized writer"
                (sp/delete-entity raw :input-binding binding-id)))
          (let [version (:id (first (sp/query-entities raw :input-binding-version {:input-binding-id binding-id})))]
            (is (uuid? version))
            (is (thrown-with-msg? clojure.lang.ExceptionInfo #"server-authorized writer"
                  (sp/delete-entities raw :input-binding-version [version]))))
          (is (= {:id "42" :name "Grace" :email "ada@example.test"}
                 (run-sample app token sibling child)))
          (is (empty? (sp/query-entities storage :fn {:name "tutorial-structured-profile"}))))))))


(deftest anonymous-catalogue-and-owned-structured-lesson-lifecycle-test
  (let [storage (:storage golden/*bootstrap*)
        packages (ig/init-key :app/packages {:package-names ["core" "web" "app" "lessons" "lesson-contact"]
                                             :primitive-inputs [lesson/admission contact/admission]})
        {:keys [base-fns]} (ig/init-key :exec/base-fns {:storage storage :packages packages})
        token (str (random-uuid))
        denied (atom nil)
        guard (fn [_ id]
                (when (= @denied id)
                  (throw (ex-info "Denied" {:type :authz/forbidden}))))
        ctx (exec/create-context {:storage storage :base-fns base-fns :execute-guard guard
                                  :auth-provider (auth/single-token-provider token)})
        _ (executor/rebuild! ctx)
        app (router/create-router ctx "_app-ring-response")
        anonymous (catalogue app nil)
        eligible (catalogue app token)]
    (is (contains? anonymous "15"))
    (is (not (contains? anonymous "43")))
    (is (= (str lesson/root) (get-in eligible ["43" :template-id])))
    (is (not (contains? anonymous "44")))
    (is (= (str contact/root) (get-in eligible ["44" :template-id])))
    (doseq [[denied-id root available-id] [["43" lesson/root "44"] ["44" contact/root "43"]]]
      (reset! denied root)
      (let [available (catalogue app token)]
        (is (contains? available available-id))
        (is (not (contains? available denied-id)))))
    (reset! denied nil)
    (ordinary-branch-exercise! app token storage)
    (when (= "1" (System/getenv "GD_STRUCTURED_LESSON_LIFECYCLE_PLAYWRIGHT"))
      (let [api-cache (ig/init-key :exec/api-routes-js-cache {:context ctx})]
        (try
          (doseq [[id root] [["43" lesson/root] ["44" contact/root]]]
            (browser/exercise! #(router/dispatch app %) token "tools/browser-test/fixtures/structured-lesson-lifecycle.js" root id))
          (finally (ig/halt-key! :exec/api-routes-js-cache api-cache)))))
    (is (empty? (sp/query-entities (vs/unwrap storage) :fn {:name "tutorial-structured-profile"})))
    (is (empty? (sp/query-entities (vs/unwrap storage) :fn {:name "tutorial-contact-route"})))
    (is (not (contains? (catalogue app nil) "43")))))
