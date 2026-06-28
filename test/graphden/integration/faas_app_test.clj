(ns ^:integration graphden.integration.faas-app-test
  "Addon-active FaaS app-routing (PLATFORM_PLAN §3.4) — the INTEGRATED cloud
   chain, not the isolated pieces: an org's handler fn, resolved from its
   subdomain and executed by the app-router, runs SANDBOXED (effect-gated) and
   in the org's context (so OrgScoped confines it). The reusable addon-active
   fixture below — real `Versioned(OrgScoped(Postgres))` + the `:org`/`:grant`
   schema + a full package bootstrap — underpins this and future multi-tenant
   integration tests (the project's first addon-active harness)."
  (:require
    [clojure.string :as str]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records :as records]
    [graphden.schema.executions.schema :as es]
    [graphden.schema.graph.schema :as gds]
    [graphden.schema.malli.core :as mds]
    [graphden.schema.packages.schema :as pkgs]
    [graphden.schema.protocol.protocol :as ds]
    [graphden.schema.services.schema :as svcs]
    [graphden.schema.traits.schema :as vts]
    [graphden.schema.versioned.schema :as vds]
    [graphden.storage.postgres.core :as pg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.protocol.postgres-test-helpers :as pth]
    [graphden.system.core :as sys]
    [graphden.tenancy.app-router :as app]
    [graphden.tenancy.auth :as tauth]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.deploy :as deploy]
    [graphden.tenancy.domain :as domain]
    [graphden.tenancy.domain-schema :as domain-schema]
    [graphden.tenancy.grant-schema :as grant-schema]
    [graphden.tenancy.org-schema :as org-schema]
    [graphden.tenancy.storage :as ts]
    [graphden.tenancy.subdomain :as subdomain]
    [graphden.tenancy.token-schema :as token-schema]
    [graphden.tenancy.user-schema :as user-schema]
    [graphden.tenancy.users :as users]
    [graphden.versioning.storage.core :as vs]))


(def ^:dynamic *ctx* nil)
(def ^:dynamic *fn-id* nil)   ; {:env … :list-grants …}


(defn- addon-schema
  "The production schema + the addon's `:grant` / `:org` entities."
  []
  (-> (mds/create-builder)
      (gds/extend-builder)
      (vts/extend-builder)
      (vds/extend-builder)
      (es/extend-builder)
      (svcs/extend-builder)
      (pkgs/extend-builder)
      (grant-schema/extend-builder)
      (org-schema/extend-builder)
      (token-schema/extend-builder)
      (domain-schema/extend-builder)
      (user-schema/extend-builder)
      (ds/build)))


(defn- fn-id-of
  [storage nm]
  (:id (first (sp/query-entities storage :fn {:name nm}))))


;; ---------------------------------------------------------------------------
;; The addon-active fixture: Versioned(OrgScoped(Postgres)) + bootstrap, the
;; same stack the manifest assembles in production.
;; ---------------------------------------------------------------------------
(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-isolated-rich-types
  (fn [t]
    (exec/with-clean-registry
      #(let [_ (pth/clean-database-fast! setup/*container*)
             base (pg/create-storage (pth/get-container-config setup/*container*))
             _ (sp/initialize base (addon-schema))
             _ (sp/upsert-entities base :fn
                                   (mapv (fn [r] (dissoc r :kind)) (records/boot-primitive-records)))
             storage (vs/wrap-with-versioning (ts/org-scoped-storage base) "main")
             _ (sys/bootstrap-from-packages! storage ["core" "web" "app"] {:skip-type-check? true})
             ctx (exec/create-context {:storage storage})
             _ (cr/rebuild! ctx)]
         (try
           (binding [*ctx* ctx
                     *fn-id* {:env (fn-id-of storage "env")
                              :list-grants (fn-id-of storage "list-grants")}]
             (t))
           (finally (sp/close base)))))))


(defn- request
  [host]
  {:headers {"host" host} :request-method :get :uri "/"})


(defn- set-org!
  "Register an org (platform context) pointing at `handler-fn-id` (or nil)."
  [name handler-fn-id]
  (sp/create-entity (:storage *ctx*) :org
                    (cond-> {:name name} handler-fn-id (assoc :handler-fn-id handler-fn-id))))


(defn- router
  []
  (app/make-app-router (subdomain/identity-org-resolver) "graphden.app" nil))


(deftest fixture-bootstraps-addon-active-stack
  (testing "the bootstrap ran through OrgScoped and the base-fns are resolvable"
    (is (some? (:env *fn-id*)) "core :env base-fn compiled + queryable")
    (is (some? (:list-grants *fn-id*)) "app :list-grants base-fn compiled + queryable")))


(deftest app-router-resolves-the-right-handler-per-org
  (set-org! "rez-acme" (:list-grants *fn-id*))
  (set-org! "rez-beta" nil)
  (let [target (fn [host]
                 (app/app-handler-target (:storage *ctx*) (request host)
                                         (subdomain/identity-org-resolver) "graphden.app" nil))]
    (testing "a tenant subdomain → that org + its handler (real OrgScoped + :org)"
      (is (= {:org "rez-acme" :handler-fn-id (:list-grants *fn-id*)} (target "rez-acme.graphden.app"))))
    (testing "an org with no handler → nil handler-fn-id (→ 404)"
      (is (nil? (:handler-fn-id (target "rez-beta.graphden.app")))))
    (testing "apex → nil (not an app request → editor/API)"
      (is (nil? (target "graphden.app"))))))


(deftest app-router-runs-handler-effect-gated
  (set-org! "sandbox-org" (:env *fn-id*))
  (testing "a tenant handler doing a forbidden :env effect is BLOCKED → 500"
    ;; :env's impl calls record-effect! :env first; the app-router runs it with
    ;; *allowed-effects* = default-cloud-allowed-effects (no :env), so the gate
    ;; throws before any env read → app-error. This is the cloud sandbox.
    (is (= 500 (:status ((router) *ctx* (request "sandbox-org.graphden.app")))))))


(deftest app-router-runs-handler-in-org-context
  (set-org! "exec-org" (:list-grants *fn-id*))
  (testing "the handler EXECUTES in the org context (resolved + run); reading a
            tenant-forbidden entity (:grant) is denied → [] (not 404 / 500)"
    ;; Proves the chain end-to-end: app-router → cr/execute the org's handler
    ;; with *current-org* bound. :list-grants reads :grant via OrgScoped; as a
    ;; tenant that's read-denied → []. A 404 would mean unresolved; a 500 would
    ;; mean an execution error — [] means it ran org-scoped.
    (is (= [] ((router) *ctx* (request "exec-org.graphden.app"))))))


(deftest app-router-404-when-no-handler
  (set-org! "noh-org" nil)
  (testing "org exists but no app handler configured → 404"
    (is (= 404 (:status ((router) *ctx* (request "noh-org.graphden.app")))))))


;; ---------------------------------------------------------------------------
;; Self-serve deploy (§3.4 4b) — the controlled-privilege seam against REAL
;; OrgScoped storage (the unit test used a fake), + the base-fn→seam wiring.
;; ---------------------------------------------------------------------------
(deftest self-serve-seam-sets-own-org-handler
  (set-org! "selfsrv-org" nil)
  (let [fid (:list-grants *fn-id*)]
    (testing "a tenant points its own org at a fn it can read → :org updated"
      (tc/with-org "selfsrv-org" (deploy/set-org-handler! *ctx* fid))
      (is (= fid (:handler-fn-id (first (sp/query-entities (:storage *ctx*) :org {:name "selfsrv-org"}))))))
    (testing "public / unauthenticated caller → :authz/forbidden"
      (let [ex (try (tc/with-org tc/public-org (deploy/set-org-handler! *ctx* fid))
                    nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= :authz/forbidden (:type (ex-data ex))))))))


(deftest invoke-set-org-handler-base-fn-drives-the-seam
  (set-org! "invoke-org" nil)
  (let [invoke-id (fn-id-of (:storage *ctx*) "invoke-set-org-handler")
        seam-ctx (assoc *ctx* :set-org-handler deploy/set-org-handler!)
        fid (:list-grants *fn-id*)]
    (testing "the core invoke-set-org-handler base-fn calls the ctx seam → :org updated"
      (tc/with-org "invoke-org"
                   (cr/execute seam-ctx invoke-id {:fn-id (str fid)}))
      (is (= fid (:handler-fn-id (first (sp/query-entities (:storage *ctx*) :org {:name "invoke-org"}))))))))


;; ---------------------------------------------------------------------------
;; Grants-admin panel happy-path — with the addon active (:grant exists), the
;; partial renders the real grants table, not the degraded notice.
;; ---------------------------------------------------------------------------
(deftest grants-panel-renders-real-grants
  (sp/create-entity (:storage *ctx*) :grant {:subject "alice" :capability "write" :namespace "acme"})
  (sp/create-entity (:storage *ctx*) :grant {:subject "bob" :capability "read" :namespace "shared"})
  (let [handler-id (fn-id-of (:storage *ctx*) "_partial-grants-admin-handler")
        resp (cr/execute *ctx* handler-id {})
        body (:body resp)]
    (testing "200 + the grants table with the seeded rows, NOT the degraded notice"
      (is (= 200 (:status resp)))
      (is (str/includes? body "grants-admin-table"))
      (is (str/includes? body "alice"))
      (is (str/includes? body "shared"))
      (is (not (str/includes? body "Tenancy addon not active"))))))


;; ---------------------------------------------------------------------------
;; Storage-backed token-source (§3.4 #1) — onboarding is a row insert. The
;; round-trip proves create-token's hash (core impls) == auth/token-hash (addon
;; src), so a minted bearer authenticates.
;; ---------------------------------------------------------------------------
(defn- bearer-req
  [token]
  {:headers {"authorization" (str "Bearer " token)}})


(deftest storage-token-provider-round-trip
  (let [create-id (fn-id-of (:storage *ctx*) "create-token")
        provider (tauth/storage-token-provider (:storage *ctx*))]
    (cr/execute *ctx* create-id {:token "s3cr3t-bearer" :user "alice" :org "acme"})
    (testing "a minted bearer authenticates to its principal (create→hash→store→provider→hash→match)"
      (is (= {:authenticated? true :user "alice" :org "acme"}
             (auth/authenticate provider (bearer-req "s3cr3t-bearer")))))
    (testing "an unknown bearer fails closed"
      (is (= {:authenticated? false} (auth/authenticate provider (bearer-req "wrong-token")))))
    (testing "no bearer → not authenticated"
      (is (= {:authenticated? false} (auth/authenticate provider {:headers {}}))))))


(deftest tenant-cannot-mint-tokens
  (testing "a tenant writing :token directly is denied (no self-escalation)"
    (let [ex (try (tc/with-org "evil-org"
                               (sp/create-entity (:storage *ctx*) :token
                                                 {:token-hash "deadbeef" :user "evil" :org "victim"}))
                  nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :authz/forbidden (:type (ex-data ex)))))))


;; ---------------------------------------------------------------------------
;; Storage-backed custom domains (§3.4 #2) — provisionable hostname → org. Only
;; VERIFIED rows resolve; create-domain registers unverified (safe default).
;; ---------------------------------------------------------------------------
(deftest storage-host-resolver-only-routes-verified
  (let [create-id (fn-id-of (:storage *ctx*) "create-domain")
        resolver (domain/storage-host-resolver (:storage *ctx*))]
    (cr/execute *ctx* create-id {:hostname "app.acme.com" :org "acme"})
    (testing "create-domain registers UNVERIFIED → does NOT route yet"
      (is (nil? (domain/org-for-host resolver "app.acme.com")))
      (is (false? (:verified? (first (sp/query-entities (:storage *ctx*) :domain {:hostname "app.acme.com"}))))))
    (testing "after the operator flips :verified? → resolves to the org (port/case-insensitive)"
      (let [row (first (sp/query-entities (:storage *ctx*) :domain {:hostname "app.acme.com"}))]
        (sp/update-entity (:storage *ctx*) :domain (:id row) (assoc row :verified? true)))
      (is (= "acme" (domain/org-for-host resolver "app.acme.com")))
      (is (= "acme" (domain/org-for-host resolver "APP.acme.com:8443"))))
    (testing "an unregistered host → nil (falls through to subdomain / token)"
      (is (nil? (domain/org-for-host resolver "evil.com"))))))


(deftest tenant-cannot-register-domains
  (testing "a tenant writing :domain directly is denied (no routing hijack)"
    (let [ex (try (tc/with-org "evil-org"
                               (sp/create-entity (:storage *ctx*) :domain
                                                 {:hostname "victim.com" :org "victim" :verified? true}))
                  nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :authz/forbidden (:type (ex-data ex)))))))


;; ---------------------------------------------------------------------------
;; Self-serve DNS-verify seam (§3.4 #2) — a tenant proves DNS ownership of a
;; domain registered to its org and flips it verified (DNS lookup injected).
;; ---------------------------------------------------------------------------
(deftest self-serve-verify-domain-seam
  (sp/create-entity (:storage *ctx*) :domain {:hostname "app.vorg.com" :org "vorg" :verified? false})
  (let [ok-dns (fn [_] ["junk=x" "graphden-verify=vorg"])
        bad-dns (fn [_] ["graphden-verify=someone-else"])]
    (testing "tenant proves ownership (graphden-verify=<org> TXT) → row flips verified?"
      (tc/with-org "vorg" (deploy/verify-domain! *ctx* "app.vorg.com" ok-dns))
      (is (true? (:verified? (first (sp/query-entities (:storage *ctx*) :domain {:hostname "app.vorg.com"}))))))
    (testing "DNS doesn't prove ownership → :domain/unverified (row not flipped)"
      (sp/create-entity (:storage *ctx*) :domain {:hostname "x.vorg.com" :org "vorg" :verified? false})
      (let [ex (try (tc/with-org "vorg" (deploy/verify-domain! *ctx* "x.vorg.com" bad-dns))
                    nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= :domain/unverified (:type (ex-data ex))))
        (is (false? (:verified? (first (sp/query-entities (:storage *ctx*) :domain {:hostname "x.vorg.com"})))))))
    (testing "verifying ANOTHER org's domain → forbidden (own-org only)"
      (let [ex (try (tc/with-org "intruder" (deploy/verify-domain! *ctx* "app.vorg.com" ok-dns))
                    nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= :authz/forbidden (:type (ex-data ex))))))
    (testing "public / unauthenticated → forbidden"
      (let [ex (try (tc/with-org tc/public-org (deploy/verify-domain! *ctx* "app.vorg.com" ok-dns))
                    nil (catch clojure.lang.ExceptionInfo e e))]
        (is (= :authz/forbidden (:type (ex-data ex))))))))


(deftest invoke-verify-domain-base-fn-drives-the-seam
  (sp/create-entity (:storage *ctx*) :domain {:hostname "wired.worg.com" :org "worg" :verified? false})
  (let [invoke-id (fn-id-of (:storage *ctx*) "invoke-verify-domain")
        seam-ctx (assoc *ctx* :verify-domain
                        (fn [c h] (deploy/verify-domain! c h (fn [_] ["graphden-verify=worg"]))))]
    (testing "the core invoke-verify-domain base-fn calls the ctx seam → :domain verified"
      (tc/with-org "worg" (cr/execute seam-ctx invoke-id {:hostname "wired.worg.com"}))
      (is (true? (:verified? (first (sp/query-entities (:storage *ctx*) :domain {:hostname "wired.worg.com"}))))))))


;; ---------------------------------------------------------------------------
;; User model (§4.1) — operator creates users, users log in for a session token
;; that the storage-token-provider resolves. Exercised through the base-fns +
;; the :user-ops seam (the same wiring the addon/system installs).
;; ---------------------------------------------------------------------------
(deftest user-model-create-login-roundtrip
  (let [create-id (fn-id-of (:storage *ctx*) "invoke-create-user")
        login-id (fn-id-of (:storage *ctx*) "invoke-login")
        seam-ctx (assoc *ctx* :user-ops {:create-user users/create-user! :login users/login!})
        provider (tauth/storage-token-provider (:storage *ctx*))]
    (testing "operator creates a user (invoke-create-user base-fn → seam)"
      (cr/execute seam-ctx create-id {:username "alice" :password "s3cret-pw" :org "acme"})
      (is (some? (first (sp/query-entities (:storage *ctx*) :user {:username "alice"})))))
    (testing "login with the right password → a session token that authenticates"
      (let [result (cr/execute seam-ctx login-id {:username "alice" :password "s3cret-pw"})
            token (:token result)]
        (is (string? token))
        (is (= {:authenticated? true :user "alice" :org "acme"}
               (auth/authenticate provider (bearer-req token))))))
    (testing "login with the wrong password → nil (no token minted)"
      (is (nil? (cr/execute seam-ctx login-id {:username "alice" :password "WRONG"}))))
    (testing "login for an unknown user → nil"
      (is (nil? (cr/execute seam-ctx login-id {:username "ghost" :password "x"}))))))


(deftest tenant-cannot-create-users
  (testing "a tenant writing :user directly is denied (no cross-org account mint)"
    (let [ex (try (tc/with-org "evil-org"
                               (sp/create-entity (:storage *ctx*) :user
                                                 {:username "mole" :password-hash "h" :org "victim"}))
                  nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :authz/forbidden (:type (ex-data ex)))))))
