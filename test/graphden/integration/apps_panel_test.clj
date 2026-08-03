(ns ^:integration graphden.integration.apps-panel-test
  "GET/POST /partials/apps-panel via the route-collection seam (Track C4b).

   The Apps panel lives in the addon-only `tenancy-admin` package (module
   `apps`), compiled into `:tenancy-router` and installed into the tenancy-
   routing singleton; `br/dispatch` consults it INSIDE its request-scope.
   Mirrors `grants-admin-test` — bootstraps WITH `tenancy-admin` and type-check
   ON (so the panel's fn-defs are swept), no `plan/install!`, so the
   `list-tenant-app-routes` seam is nil → the panel renders its empty table +
   create form (no throw). Verifies the served HTML shape + auth gating."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.sync :as pkg-sync]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.system.route-collection :as rc]))


(def ^:dynamic *router* nil)
(def ^:dynamic *ctx* nil)
(def ^:private test-auth-token "apps-panel-test-token")


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-isolated-rich-types
  (fn [t]
    (exec/with-clean-registry
      #(let [storage (setup/create-versioned-test-storage)
             _ (pkg-sync/bootstrap-from-packages! storage ["core" "web" "app" "tenancy-admin"]
                                                  {:skip-type-check? false})
             ctx (exec/create-context
                   {:storage storage
                    :auth-provider (auth/single-token-provider test-auth-token)})
             _ (cr/rebuild! ctx)
             router (br/create-router ctx "_app-ring-response")]
         (try
           (binding [*router* router *ctx* ctx] (t))
           (finally
             (rc/remove-router! :tenancy)
             (sp/close storage)))))))


(defn- install-tenancy-router!
  []
  (rc/install-router! :tenancy (cr/execute-by-name *ctx* "tenancy-router" {})))


(defn- get-apps-panel
  ([] (get-apps-panel {"authorization" (str "Bearer " test-auth-token)}))
  ([headers]
   (br/dispatch *router*
                {:request-method :get
                 :uri "/partials/apps-panel"
                 :headers headers
                 :query-string nil
                 :body nil})))


(deftest apps-panel-route-absent-without-the-addon-router
  (rc/remove-router! :tenancy)
  (testing "no tenancy router installed → the seam falls through; core has no
            apps-panel route → 404. The single-tenant default has no Apps panel."
    (is (= 404 (:status (get-apps-panel))))))


(deftest apps-panel-served-by-the-seam-renders-table-and-create-form
  (install-tenancy-router!)
  (try
    (testing "with the tenancy router installed, the route serves HTML"
      (let [resp (get-apps-panel)]
        (is (= 200 (:status resp)) (str "got " (:status resp) " body=" (:body resp)))
        (is (string? (:body resp)))
        (testing "the panel shell + table headers render"
          (is (str/includes? (:body resp) "apps-admin"))
          (is (str/includes? (:body resp) "Apps"))
          (is (str/includes? (:body resp) "Subdomain"))
          (is (str/includes? (:body resp) "Serves")))
        (testing "the create form posts to the panel's own create route"
          (is (str/includes? (:body resp) "/partials/apps-panel/create"))
          (is (str/includes? (:body resp) "name=\"label\""))
          (is (str/includes? (:body resp) "name=\"handler-fn-id\"")))
        (testing "the swap target is the stable panel root"
          (is (str/includes? (:body resp) "data-apps-panel")))))
    (finally (rc/remove-router! :tenancy))))


(deftest apps-panel-requires-auth
  (install-tenancy-router!)
  (try
    (testing "no Bearer → 401 (auth-required route, served by the seam)"
      (is (= 401 (:status (get-apps-panel {})))))
    (finally (rc/remove-router! :tenancy))))
