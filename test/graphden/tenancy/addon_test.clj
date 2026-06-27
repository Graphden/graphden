(ns graphden.tenancy.addon-test
  "The tenancy addon fragment splices OrgScopedStorage into the storage
   stack via the manifest (PLATFORM_PLAN §3.0 B3)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.auth.provider :as auth]
    [graphden.system.branch-router :as br]
    [graphden.system.config :as config]
    [graphden.tenancy.addon]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.storage :as ts]
    [integrant.core :as ig]))


(deftest fragment-redirects-versioned-base-through-decorator
  (let [cfg (config/read-config :test ["graphden/tenancy/addon.edn"])]
    (testing "addon adds :org/scoped-storage wrapping the :app/storage seam"
      (is (= (ig/ref :app/storage) (:base (:org/scoped-storage cfg)))))
    (testing ":db/versioned's base is redirected through the decorator"
      (is (= (ig/ref :org/scoped-storage) (:base-storage (:db/versioned cfg)))
          "stack becomes Versioned(OrgScoped(app/storage(Postgres)))"))
    (testing "deep-merge preserves the core :app/storage seam unchanged"
      (is (= (ig/ref :db/postgres) (:base (:app/storage cfg)))))))


(deftest init-key-builds-org-scoped-storage
  (testing ":org/scoped-storage init-key wraps its base in the decorator"
    (let [s (ig/init-key :org/scoped-storage {:base ::stub-base})]
      (is (instance? graphden.tenancy.storage.OrgScopedStorage s))
      (is (= ::stub-base (:base s)))
      (is (= ts/default-scoped-entities (:scoped? s)) "defaults to the graph entities")))
  (testing "an explicit scoped-entities set is honoured"
    (let [s (ig/init-key :org/scoped-storage {:base ::stub-base :scoped-entities [:fn]})]
      (is (= #{:fn} (:scoped? s))))))


;; --- B4: the request-scope seam ---

(defn- org-provider
  "A stand-in addon AuthProvider that resolves a fixed org."
  [org]
  (reify auth/AuthProvider
    (authenticate [_ _request] {:authenticated? true :org org})))


(defn- router-with
  "Minimal BranchRouter whose default-branch handler is `handler`, carrying
   `base-ctx` (so dispatch sees its :request-scope)."
  [base-ctx handler]
  (br/->BranchRouter base-ctx "main" (atom {"main" {:handler handler}}) nil))


(deftest fragment-wires-request-scope-seam
  (let [cfg (config/read-config :test ["graphden/tenancy/addon.edn"])]
    (is (contains? cfg :tenancy/request-scope))
    (is (= (ig/ref :tenancy/request-scope) (:request-scope (:exec/context cfg)))
        ":exec/context's :request-scope seam points at the addon's wrap")))


(deftest dispatch-binds-current-org-from-principal
  (let [scope (ig/init-key :tenancy/request-scope {})
        base-ctx {:auth-provider (org-provider "acme") :request-scope scope}
        ;; the "handler" simply reports the org in scope when it runs
        router (router-with base-ctx (fn [_req] (tc/current-org)))]
    (is (= "acme" (br/dispatch router {:request-method :get :uri "/x"
                                       :headers {} :query-string nil}))
        "dispatch wraps the handler so it runs with *current-org* bound")
    (is (= "public" (tc/current-org)) "binding is restored after dispatch")))


(deftest dispatch-without-request-scope-runs-public
  ;; Core (no addon) — base-ctx has no :request-scope, handler runs at public.
  (let [router (router-with {} (fn [_req] (tc/current-org)))]
    (is (= "public" (br/dispatch router {:request-method :get :uri "/x"
                                         :headers {} :query-string nil})))))


(deftest request-scope-defaults-public-without-org
  (let [scope (ig/init-key :tenancy/request-scope {})
        ;; the default single-token provider authenticates but returns no :org
        base-ctx {:auth-provider (auth/single-token-provider "tok") :request-scope scope}
        router (router-with base-ctx (fn [_req] (tc/current-org)))]
    (is (= "public" (br/dispatch router {:request-method :get :uri "/x"
                                         :headers {"authorization" "Bearer tok"}
                                         :query-string nil}))
        "a provider that resolves no :org → public org (single-tenant behaviour)")))
