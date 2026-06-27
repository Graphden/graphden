(ns ^:integration graphden.integration.grants-admin-test
  "GET /partials/grants-admin renders + degrades gracefully when the tenancy
   addon (and its :grant entity) is absent — the single-tenant default."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.system.core :as sys]))


(def ^:dynamic *router* nil)
(def ^:private test-auth-token "grants-admin-test-token")


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-isolated-rich-types
  (fn [t]
    (exec/with-clean-registry
      #(let [storage (setup/create-versioned-test-storage)
             _ (sys/bootstrap-from-packages! storage ["core" "web" "app"]
                                             {:skip-type-check? false})
             ctx (exec/create-context
                   {:storage storage
                    :auth-provider (auth/single-token-provider test-auth-token)})
             _ (cr/rebuild! ctx)
             router (br/create-router ctx "_app-ring-response")]
         (try
           (binding [*router* router] (t))
           (finally (sp/close storage)))))))


(defn- get-grants-admin
  []
  (br/dispatch
    *router*
    {:request-method :get
     :uri "/partials/grants-admin"
     :headers {"authorization" (str "Bearer " test-auth-token)}
     :query-string nil
     :body nil}))


(deftest grants-admin-route-degrades-without-the-addon
  (testing "the route is registered, auth-gated, and serves HTML"
    (let [resp (get-grants-admin)]
      (is (= 200 (:status resp)) (str "got " (:status resp) " body=" (:body resp)))
      (is (string? (:body resp)))
      (testing "with no :grant entity (addon inactive), :try degrades to the notice"
        (is (str/includes? (:body resp) "Tenancy addon not active")
            (str "expected the disabled notice; body=" (:body resp))))
      (testing "the panel shell still renders"
        (is (str/includes? (:body resp) "grants-admin"))
        (is (str/includes? (:body resp) "Grants"))))))


(deftest grants-admin-requires-auth
  (testing "no Bearer → 401 (auth-required route)"
    (let [resp (br/dispatch *router*
                            {:request-method :get :uri "/partials/grants-admin"
                             :headers {} :query-string nil :body nil})]
      (is (= 401 (:status resp))))))
