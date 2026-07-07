(ns graphden.tenancy.addon-test
  "The tenancy addon fragment splices OrgScopedStorage into the storage
   stack via the manifest (PLATFORM_PLAN §3.0 B3)."
  (:require
    [clojure.test :refer [deftest is testing]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as cr]
    [graphden.system.branch-router :as br]
    [graphden.system.config :as config]
    [graphden.tenancy.addon]
    [graphden.tenancy.auth :as tauth]
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


;; --- effect gate on the org ctx (§5) ---

(deftest tenant-requests-are-effect-gated
  (let [scope (ig/init-key :tenancy/request-scope {})
        provider (tauth/token-map-provider {"acme-tok" {:user "a" :org "acme"}})
        base-ctx {:auth-provider provider :request-scope scope}
        ;; dispatch a request whose handler attempts `effect`
        run (fn [effect authz]
              (br/dispatch
                (router-with base-ctx (fn [_req] (cr/record-effect! effect) :ran))
                {:request-method :get :uri "/x"
                 :headers (cond-> {} authz (assoc "authorization" authz))
                 :query-string nil}))]
    (testing "a tenant CANNOT perform a forbidden effect (env / network / io / process)"
      (is (thrown? clojure.lang.ExceptionInfo (run :env "Bearer acme-tok")))
      (is (thrown? clojure.lang.ExceptionInfo (run :network "Bearer acme-tok"))))
    (testing "a tenant CAN perform an allowed effect (db / time)"
      (is (= :ran (run :db "Bearer acme-tok")))
      (is (= :ran (run :time "Bearer acme-tok"))))
    (testing "platform (public / unauthenticated) is unrestricted"
      (is (= :ran (run :env nil)))
      (is (= :ran (run :network nil))))
    (testing "*allowed-effects* is restored after dispatch (no leak)"
      (run :db "Bearer acme-tok")
      (is (nil? cr/*allowed-effects*)))))


;; --- grant enforcement on the request-scope gate (§4.2) ---

(deftest grant-enforcement-gates-tenant-writes
  (let [grant-store (ig/init-key :tenancy/grant-store
                                 {:grants [{:subject "alice" :capability :write :namespace "acme"}]})
        scope (ig/init-key :tenancy/request-scope {:grant-store grant-store})
        provider (tauth/token-map-provider {"alice-tok" {:user "alice" :org "acme"}
                                            "mallory-tok" {:user "mallory" :org "acme"}})
        base-ctx {:auth-provider provider :request-scope scope}
        router (router-with base-ctx (fn [_req] :ran))
        call (fn [method uri authz]
               (br/dispatch router {:request-method method :uri uri
                                    :headers (cond-> {} authz (assoc "authorization" authz))
                                    :query-string nil}))]
    (testing "a granted tenant write proceeds"
      (is (= :ran (call :post "/api/entities/fn" "Bearer alice-tok"))))
    (testing "an ungranted tenant write → 403"
      (is (= 403 (:status (call :post "/api/entities/fn" "Bearer mallory-tok")))))
    (testing "reads are open even for the ungranted tenant (OrgScoped governs visibility)"
      (is (= :ran (call :get "/api/graph/entities" "Bearer mallory-tok"))))
    (testing "execute needs :execute — alice holds only :write"
      (is (= 403 (:status (call :post "/api/execute" "Bearer alice-tok")))))
    (testing "platform (public / no token) is never grant-gated"
      (is (= :ran (call :post "/api/entities/fn" nil))))))


(deftest capabilities-header-reflects-grants
  (let [grant-store (ig/init-key :tenancy/grant-store
                                 {:grants [{:subject "alice" :capability :write :namespace "acme"}]})
        scope (ig/init-key :tenancy/request-scope {:grant-store grant-store})
        provider (tauth/token-map-provider {"alice-tok" {:user "alice" :org "acme"}
                                            "mallory-tok" {:user "mallory" :org "acme"}})
        base-ctx {:auth-provider provider :request-scope scope}
        ;; a handler that returns a Ring response map (so the header attaches)
        router (router-with base-ctx (fn [_req] {:status 200 :body "ok"}))
        caps (fn [authz]
               (get-in (br/dispatch router {:request-method :get :uri "/api/graph/entities"
                                            :headers (cond-> {} authz (assoc "authorization" authz))
                                            :query-string nil})
                       [:headers "X-Graphden-Capabilities"]))]
    (testing "a tenant's header lists only the capabilities the user is granted"
      (is (= "write" (caps "Bearer alice-tok"))))
    (testing "an ungranted tenant gets an empty capability set"
      (is (= "" (caps "Bearer mallory-tok"))))
    (testing "platform / no token → all capabilities (admin edits freely)"
      (is (= "write,execute" (caps nil))))))


(deftest authz-forbidden-throw-maps-to-403
  ;; A per-namespace denial is thrown at the storage layer as :authz/forbidden;
  ;; the request-scope catches it and returns a clean 403.
  (let [grant-store (ig/init-key :tenancy/grant-store
                                 {:grants [{:subject "alice" :capability :write :namespace "acme"}]})
        scope (ig/init-key :tenancy/request-scope {:grant-store grant-store})
        provider (tauth/token-map-provider {"alice-tok" {:user "alice" :org "acme"}})
        base-ctx {:auth-provider provider :request-scope scope}
        post (fn [handler]
               (br/dispatch (router-with base-ctx handler)
                            {:request-method :post :uri "/api/entities/fn"
                             :headers {"authorization" "Bearer alice-tok"} :query-string nil}))]
    (testing "a downstream :authz/forbidden becomes a 403"
      (is (= 403 (:status (post (fn [_req] (throw (ex-info "no" {:type :authz/forbidden}))))))))
    (testing "other exceptions are NOT swallowed as 403"
      (is (thrown? clojure.lang.ExceptionInfo
            (post (fn [_req] (throw (ex-info "boom" {:type :other})))))))
    (testing "*current-principal* is bound for the handler / storage guard"
      (is (= "alice" (post (fn [_req] (:user tc/*current-principal*))))))))


(deftest domain-error-throws-map-to-4xx
  ;; The tenancy-admin seam impls throw domain :types on bad / duplicate /
  ;; missing input. The request-scope maps each to its HTTP status so the
  ;; control-plane routes return a 4xx, not a 500. Without the mapping these
  ;; uncaught throws became 500s (and HTMX won't swap on a 500).
  (let [grant-store (ig/init-key :tenancy/grant-store
                                 {:grants [{:subject "alice" :capability :write :namespace "acme"}]})
        scope (ig/init-key :tenancy/request-scope {:grant-store grant-store})
        provider (tauth/token-map-provider {"alice-tok" {:user "alice" :org "acme"}})
        base-ctx {:auth-provider provider :request-scope scope}
        respond (fn [err-type]
                  (br/dispatch
                    (router-with base-ctx (fn [_req] (throw (ex-info "x" {:type err-type}))))
                    {:request-method :post :uri "/api/entities/fn"
                     :headers {"authorization" "Bearer alice-tok"} :query-string nil}))
        status (comp :status respond)]
    (testing "validation domain errors → 400"
      (is (= 400 (status :grant/invalid-capability)))
      (is (= 400 (status :user/invalid)))
      (is (= 400 (status :domain/unverified))))
    (testing "not-found → 404, conflict → 409"
      (is (= 404 (status :user/not-found)))
      (is (= 409 (status :user/exists)))
      ;; A UNIQUE-column duplicate (org name / domain host / token hash) from a
      ;; direct create-org/domain/token — must be 409, not a bare 500.
      (is (= 409 (status :constraint-violation/unique))))
    (testing "internal error types are NOT masked as 4xx (still surface / 500)"
      (is (thrown? clojure.lang.ExceptionInfo (status :org/scoped-storage))))
    (testing "the 4xx body carries the machine-readable error type (no leading colon)"
      (is (re-find #"\"error\":\"user/exists\"" (:body (respond :user/exists)))))))


(deftest workspace-header-lists-the-users-namespaces
  (let [grant-store (ig/init-key :tenancy/grant-store
                                 {:grants [{:subject "alice" :capability :write :namespace "acme.team"}]
                                  :personal-ns-prefix "users"})
        scope (ig/init-key :tenancy/request-scope {:grant-store grant-store})
        provider (tauth/token-map-provider {"alice-tok" {:user "alice" :org "acme"}})
        base-ctx {:auth-provider provider :request-scope scope}
        router (router-with base-ctx (fn [_req] {:status 200 :body "ok"}))
        ws (fn [authz]
             (get-in (br/dispatch router {:request-method :get :uri "/api/graph/entities"
                                          :headers (cond-> {} authz (assoc "authorization" authz))
                                          :query-string nil})
                     [:headers "X-Graphden-Workspace"]))]
    (testing "a tenant's workspace lists granted + personal namespaces (sorted)"
      (is (= "acme.team,users.alice" (ws "Bearer alice-tok"))))
    (testing "platform / no token → empty (no workspace hint)"
      (is (= "" (ws nil))))))


(deftest grant-enforcement-is-opt-in
  (let [scope (ig/init-key :tenancy/request-scope {})       ; no grant-store
        provider (tauth/token-map-provider {"alice-tok" {:user "alice" :org "acme"}})
        base-ctx {:auth-provider provider :request-scope scope}
        router (router-with base-ctx (fn [_req] :ran))]
    (is (= :ran (br/dispatch router {:request-method :post :uri "/api/entities/fn"
                                     :headers {"authorization" "Bearer alice-tok"}
                                     :query-string nil}))
        "without a grant-store wired, tenant writes pass (subject only to OrgScoped + effects)")))
