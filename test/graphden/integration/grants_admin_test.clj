(ns ^:integration ^:serial graphden.integration.grants-admin-test
  "GET /partials/grants-admin via the route-collection seam (PLATFORM_PLAN §6).

   The grants panel lives in the addon-only `tenancy-admin` package, compiled
   into `:tenancy-router` and installed into the tenancy-routing singleton
   (`graphden.system.tenancy-router/active-router`); `br/dispatch` consults it
   INSIDE its request-scope. This NS is `^:serial` because it mutates that
   process-wide singleton — the parallel-safe routing-logic checks live in
   `graphden.integration.faas-app-test` (which drives the router directly).

   Bootstraps WITH `tenancy-admin` and type-check ON (so the addon package's
   fn-defs are type-checked), but with NO `:grant` entity in the schema, so the
   panel exercises its graceful-degradation path (`:try` → not-active notice).
   With no router installed, the route is absent — the single-tenant default."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.system.core :as sys]
    [graphden.system.tenancy-router :as tr]))


(def ^:dynamic *router* nil)
(def ^:dynamic *ctx* nil)
(def ^:private test-auth-token "grants-admin-test-token")


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-isolated-rich-types
  (fn [t]
    (exec/with-clean-registry
      #(let [storage (setup/create-versioned-test-storage)
             ;; `tenancy-admin` carries the grants route now (route-collection
             ;; seam); type-check ON so the addon package's fn-defs are swept
             ;; too. The schema has NO `:grant` entity, so the panel degrades.
             _ (sys/bootstrap-from-packages! storage ["core" "web" "app" "tenancy-admin"]
                                             {:skip-type-check? false})
             ctx (exec/create-context
                   {:storage storage
                    :auth-provider (auth/single-token-provider test-auth-token)})
             _ (cr/rebuild! ctx)
             router (br/create-router ctx "_app-ring-response")]
         (try
           (binding [*router* router *ctx* ctx] (t))
           (finally
             (tr/clear-active-router!)   ; never leak the singleton to sibling NSs
             (sp/close storage)))))))


(defn- install-tenancy-router!
  []
  (tr/set-active-router! (cr/execute-by-name *ctx* "tenancy-router" {})))


(defn- get-grants-admin
  ([] (get-grants-admin {"authorization" (str "Bearer " test-auth-token)}))
  ([headers]
   (br/dispatch *router*
                {:request-method :get
                 :uri "/partials/grants-admin"
                 :headers headers
                 :query-string nil
                 :body nil})))


(deftest grants-route-absent-without-the-addon-router
  (tr/clear-active-router!)
  (testing "no tenancy router installed → the seam falls through; core has no
            grants route → 404. The single-tenant default has no grants panel."
    (is (= 404 (:status (get-grants-admin))))))


(deftest grants-admin-served-by-the-seam-and-degrades
  (install-tenancy-router!)
  (try
    (testing "with the tenancy router installed, the route is served + serves HTML"
      (let [resp (get-grants-admin)]
        (is (= 200 (:status resp)) (str "got " (:status resp) " body=" (:body resp)))
        (is (string? (:body resp)))
        (testing "no :grant entity (package loaded, schema absent) → degraded notice"
          (is (str/includes? (:body resp) "Tenancy addon not active")
              (str "expected the disabled notice; body=" (:body resp))))
        (testing "the panel shell still renders"
          (is (str/includes? (:body resp) "grants-admin"))
          (is (str/includes? (:body resp) "Grants")))))
    (finally (tr/clear-active-router!))))


(deftest grants-admin-requires-auth
  (install-tenancy-router!)
  (try
    (testing "no Bearer → 401 (auth-required route, served by the seam)"
      (is (= 401 (:status (get-grants-admin {})))))
    (finally (tr/clear-active-router!))))
