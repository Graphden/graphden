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
    [graphden.system.init.packages]
    [graphden.test-infra.browser-loopback :as browser]
    [graphden.test-infra.golden-app :as golden]
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
  (let [response (request app token :get "/api/tour" nil nil)]
    (is (= 200 (:status response)))
    (is (= "private, no-store" (response-header response "cache-control")))
    (into {} (map (juxt :id identity)) (:lessons (response-json response)))))


(defn- ordinary-branch-exercise!
  [app token storage]
  (let [branch "tutorial-43-lifecycle"
        branch-id (random-uuid)
        base-id (vs/current-branch-id storage)
        created (request app token :post "/api/branches" nil
                         {:name branch :id branch-id :base-branch-id base-id})]
    (is (= (str branch-id) (get-in (response-json created) [:branch :id])))
    (try
      (let [extension (request app token :post "/api/entities/fn" branch
                               (str "name=tutorial-structured-profile&parent-ids=" lesson/root))
            child (response-header extension "x-graphden-created-id")
            action {:fn-id child :action "bind" :path ["profile" "name"] :value "Ada"}
            preview (response-json (request app token :post "/api/structured-input/preview" branch action))
            bound (response-json (request app token :post "/api/structured-input/apply" branch
                                          (assoc action :expected-state (:expected-state preview))))
            run (response-json (request app token :post "/api/execute" branch
                                        {:fn-id child :args {:profile {:id "42" :email "ada@example.test"}}}))]
        (is (parse-uuid child))
        (is (:ok preview))
        (is (:committed bound))
        (is (= {:id "42" :name "Ada" :email "ada@example.test"} (:result run)))
        (is (empty? (sp/query-entities storage :fn {:name "tutorial-structured-profile"}))))
      (finally
        (is (<= 200 (:status (request app token :delete (str "/api/branches/" branch-id) nil nil)) 299))))))


(deftest anonymous-catalogue-and-owned-structured-lesson-lifecycle-test
  (let [storage (:storage golden/*bootstrap*)
        packages (ig/init-key :app/packages {:package-names ["core" "web" "app" "lessons"]
                                             :primitive-input lesson/admission})
        {:keys [base-fns]} (ig/init-key :exec/base-fns {:storage storage :packages packages})
        token (str (random-uuid))
        denied (atom false)
        guard (fn [_ id]
                (when (and @denied (= lesson/root id))
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
    (reset! denied true)
    (is (not (contains? (catalogue app token) "43")))
    (reset! denied false)
    (ordinary-branch-exercise! app token storage)
    (when (= "1" (System/getenv "GD_STRUCTURED_LESSON_LIFECYCLE_PLAYWRIGHT"))
      (browser/exercise! #(router/dispatch app %) token "tools/browser-test/fixtures/structured-lesson-lifecycle.js" lesson/root))
    (is (not (contains? (catalogue app nil) "43")))))
