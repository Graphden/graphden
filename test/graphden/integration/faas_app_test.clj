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
    [graphden.system.branch-router :as br]
    [graphden.system.core :as sys]
    [graphden.system.tenancy-router :as tr]
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
(def ^:dynamic *base-pool* nil)   ; base datasource for raw-SQL tests


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
             ;; `tenancy-admin` is the addon-only fns-package (route-collection
             ;; seam, §6) — it carries the grants panel + `:list-grants` /
             ;; `:tenancy-router`, which moved out of core `app`. Bootstrapped
             ;; here because this fixture IS the addon-active harness.
             _ (sys/bootstrap-from-packages! storage ["core" "web" "app" "tenancy-admin"] {:skip-type-check? true})
             ;; Mirror prod's ctx shape (§4 Design B): the privileged
             ;; structural-read storage so the registry compiles org-agnostically
             ;; (every org's fns), with isolation held at runtime by `:storage`.
             ctx (-> (exec/create-context {:storage storage})
                     (assoc :pg-storage base
                            :compile-storage (vs/->VersionedStorage
                                               base (vs/current-branch-id storage))))
             _ (cr/rebuild! ctx)]
         (try
           (binding [*ctx* ctx
                     *base-pool* (:pool base)
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
  (sp/create-entity (:storage *ctx*) :grant {:subject-id "alice" :capability "write" :namespace "acme"})
  (sp/create-entity (:storage *ctx*) :grant {:subject-id "bob" :capability "read" :namespace "shared"})
  (let [handler-id (fn-id-of (:storage *ctx*) "_partial-grants-admin-handler")
        resp (cr/execute *ctx* handler-id {})
        body (:body resp)]
    (testing "200 + the grants table with the seeded rows, NOT the degraded notice"
      (is (= 200 (:status resp)))
      (is (str/includes? body "grants-admin-table"))
      (is (str/includes? body "alice"))
      (is (str/includes? body "shared"))
      (is (not (str/includes? body "Tenancy addon not active"))))
    (testing "the panel is HTMX-native — no client-JS fetch; create/delete via hx-*"
      ;; create form: a real <form hx-post=/api/grants> swapping the panel
      (is (str/includes? body "<form"))
      (is (str/includes? body "hx-post=\"/api/grants\"") "create form posts to the route path")
      (is (str/includes? body "data-grants-panel") "stable hx-target for the create swap")
      (is (str/includes? body "type=\"submit\""))
      (is (str/includes? body "required"))
      ;; delete button: hx-delete to the generic entity endpoint + hx-swap=delete
      (is (str/includes? body "hx-delete=\"/api/entities/grant/"))
      (is (str/includes? body "hx-swap=\"delete\""))
      (is (str/includes? body "hx-confirm=\"Delete this grant?\""))
      ;; no leftover client-JS dispatch hooks
      (is (not (str/includes? body "data-act=\"create-grant\"")))
      (is (not (str/includes? body "data-act=\"delete-grant\""))))))


;; ---------------------------------------------------------------------------
;; Route-collection seam (§6) — the addon's control-plane router, installed
;; into the tenancy-routing singleton, serves its migrated routes and FALLS
;; THROUGH (nil) for everything else, so `br/dispatch` continues to the main
;; app router. This is what `:tenancy/router-install` wires at boot.
;; ---------------------------------------------------------------------------
(deftest tenancy-router-routes-control-plane-and-falls-through
  ;; The compiled `:tenancy-router` IS what `:tenancy/router-install` hangs on
  ;; the singleton; here we drive it directly (no global mutation, so this NS
  ;; stays parallel-safe — the end-to-end `br/dispatch` → singleton path is
  ;; covered by `graphden.integration.grants-admin-test`, which is `^:serial`).
  (let [router (cr/execute-by-name *ctx* "tenancy-router" {})]
    (testing "a migrated control-plane path is served by the router"
      (let [resp (tr/dispatch router {:request-method :get :uri "/partials/grants-admin"})]
        ;; Matched → a Ring response (200 if authed, 401 from the route's
        ;; auth-required middleware otherwise — either way NON-nil, so the
        ;; seam serves it and does NOT fall through to the main router).
        (is (some? resp) "tenancy router matched /partials/grants-admin")
        (is (integer? (:status resp)) "produced a Ring response")))
    (testing "a non-control-plane path returns nil → fall through to the main router"
      (is (nil? (tr/dispatch router {:request-method :get :uri "/health"})))
      (is (nil? (tr/dispatch router {:request-method :get :uri "/api/graph/layout"}))))
    (testing "a nil router (addon absent) → nil (transparent pass-through)"
      (is (nil? (tr/dispatch nil {:request-method :get :uri "/partials/grants-admin"}))))))


(deftest grants-panel-is-org-gated
  ;; The panel reads `:grant` (a tenant-forbidden entity) via OrgScoped, so
  ;; the platform (public org) sees the rows but a tenant (org ≠ public) gets
  ;; an empty table — `:grant` is read-hidden. This is WHY the seam dispatches
  ;; INSIDE the request-scope (`*current-org*` bound): outside it, a tenant's
  ;; read would run as public and enumerate every org's grants.
  ;; The Subject cell joins the user row by :subject-id; with no such
  ;; user the panel shows the raw id — a distinctive marker to assert
  ;; org-gating on.
  (let [marker (str (random-uuid))]
    (sp/create-entity (:storage *ctx*) :grant
                      {:subject-id marker :capability "admin" :namespace "ops"})
    (let [handler-id (fn-id-of (:storage *ctx*) "_partial-grants-admin-handler")]
      (testing "platform (public org) sees the grant"
        (is (str/includes? (:body (tc/with-org tc/public-org (cr/execute *ctx* handler-id {})))
                           marker)))
      (testing "a tenant (org ≠ public) does NOT — the panel is org-gated"
        (is (not (str/includes? (:body (tc/with-org "tenant-x" (cr/execute *ctx* handler-id {})))
                                marker)))))))


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
      ;; This tests the PROVIDER roundtrip (user/org carried through create→
      ;; hash→store→read), not user-mgmt — create-token resolves :user-id from
      ;; whatever `alice` user happens to exist, so compare without it.
      (is (= {:authenticated? true :user "alice" :org "acme"}
             (dissoc (auth/authenticate provider (bearer-req "s3cr3t-bearer")) :user-id))))
    (testing "an unknown bearer fails closed"
      (is (= {:authenticated? false} (auth/authenticate provider (bearer-req "wrong-token")))))
    (testing "no bearer → not authenticated"
      (is (= {:authenticated? false} (auth/authenticate provider {:headers {}}))))))


(deftest create-grant-fn-def-validates-and-writes
  ;; :create-grant is a graph composition (grants/fns.edn): capability
  ;; validated against the closed vocabulary, username resolved to its
  ;; stable id, row written via :create-entity.
  (let [storage (:storage *ctx*)
        run! (fn [nm args] (cr/execute *ctx* (fn-id-of storage nm) args))]
    (testing "a valid capability writes the grant with the resolved stable subject-id"
      (let [user (sp/create-entity storage :user {:username "grants-carol"
                                                  :password-hash "x" :org "acme"})]
        (run! "create-grant" {:subject "grants-carol" :capability "admin" :namespace "ops"})
        ;; :subject (username) is no longer written — the row carries
        ;; the stable id only.
        (let [row (first (sp/query-entities storage :grant
                                            {:subject-id (str (:id user))}))]
          (is (some? row))
          (is (= "admin" (:capability row)))
          (is (nil? (:subject row))
              "the denormalized username column is retired"))))
    (testing "an unknown capability throws — no silently-dead grant row"
      (is (thrown? Exception
            (run! "create-grant" {:subject "eve" :capability "notacap" :namespace "acme"})))
      (is (empty? (filter #(= "notacap" (:capability %))
                          (sp/query-entities storage :grant {})))))))


(deftest registration-fn-defs-drive-provisioning
  ;; create-org / set-org-handler / set-org-execution-mode are pure graph
  ;; compositions (tenancy-admin/registration/fns.edn) — drive them through
  ;; the executor and verify the rows they write / the memo they drop.
  (let [storage (:storage *ctx*)
        run! (fn [nm args] (cr/execute *ctx* (fn-id-of storage nm) args))
        org-row #(first (sp/query-entities storage :org {:name %}))]
    (testing ":create-org registers an org row by slug"
      (run! "create-org" {:name "prov-org"})
      (is (some? (org-row "prov-org"))))
    (testing ":set-org-handler resolves the org and parses the string fn-id to a UUID"
      (let [fid (:env *fn-id*)]
        (run! "set-org-handler" {:name "prov-org" :handler-fn-id (str fid)})
        (is (= fid (:handler-fn-id (org-row "prov-org"))))))
    (testing ":set-org-handler → nil (no write) for a missing org"
      (is (nil? (run! "set-org-handler"
                      {:name "ghost-org" :handler-fn-id (str (random-uuid))}))))
    (testing ":set-org-execution-mode flips the mode and drops the byo memo"
      (run! "set-org-execution-mode" {:name "prov-org" :execution-mode "byo"})
      (is (= "byo" (:execution-mode (org-row "prov-org"))))
      (is (true? (tc/byo-org? storage "prov-org"))
          "the flip is visible immediately — the memo was dropped"))
    (testing ":set-org-execution-mode throws for a bad slug"
      (is (thrown? Exception
            (run! "set-org-execution-mode" {:name "ghost-org" :execution-mode "byo"}))))))


(deftest tenant-cannot-mint-tokens
  (testing "a tenant writing :token directly is denied (no self-escalation)"
    (let [ex (try (tc/with-org "evil-org"
                               (sp/create-entity (:storage *ctx*) :token
                                                 {:token-hash "deadbeef" :user "evil" :user-id "evil" :org "victim"}))
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
        ;; `:login` mirrors the addon seam's 4-arg shape (request feeds the
        ;; per-IP rate limiter in prod); the test ignores it.
        seam-ctx (assoc *ctx* :user-ops {:create-user users/create-user!
                                         :login (fn [c u p _req] (users/login! c u p))})
        provider (tauth/storage-token-provider (:storage *ctx*))]
    (testing "operator creates a user (invoke-create-user base-fn → seam)"
      (cr/execute seam-ctx create-id {:username "alice" :password "s3cret-pw" :org "acme"})
      (is (some? (first (sp/query-entities (:storage *ctx*) :user {:username "alice"})))))
    (testing "login with the right password → a session token that authenticates"
      (let [result (cr/execute seam-ctx login-id {:username "alice" :password "s3cret-pw"})
            token (:token result)]
        (is (string? token))
        (let [p (auth/authenticate provider (bearer-req token))]
          ;; user-id is the user's real (uuid) id — compare the rest, then
          ;; pin that login DID stamp a stable id (the whole point of P1).
          (is (= {:authenticated? true :user "alice" :org "acme"} (dissoc p :user-id)))
          ;; user-id is the user's real id in STRING form (str of the uuid).
          (is (uuid? (parse-uuid (:user-id p))) "login stamps the stable user-id"))))
    (testing "login with the wrong password → nil (no token minted)"
      (is (nil? (cr/execute seam-ctx login-id {:username "alice" :password "WRONG"}))))
    (testing "login for an unknown user → nil"
      (is (nil? (cr/execute seam-ctx login-id {:username "ghost" :password "x"}))))))


(deftest org-scoped-executions-isolated-and-finishable
  ;; §4 org-scoped :fn-execution — the case the deferral worried about: the
  ;; create runs in the request scope (stamped), the terminal UPDATE runs in a
  ;; binding-conveyed completion future (inherits *current-org* → own-guard
  ;; passes). Plus cross-org isolation.
  (let [fv-id (:id (first (sp/query-entities (:storage *ctx*) :fn-version {})))
        mk (fn [org]
             (tc/with-org org
                          (sp/create-entity (:storage *ctx*) :fn-execution
                                            {:fn-version-id fv-id
                                             :started-at (java.time.Instant/now)
                                             :status :pending})))]
    (is (some? fv-id) "fixture has a :fn-version to reference")
    (let [acme-exec (mk "exec-acme")
          beta-exec (mk "exec-beta")]
      (testing "the row is stamped with the creating org"
        (is (= "exec-acme" (:org-id acme-exec))))
      (testing "a terminal UPDATE in a binding-conveyed future passes the own-guard"
        @(tc/with-org "exec-acme"
                      (future (sp/update-entity (:storage *ctx*) :fn-execution
                                                (:id acme-exec) {:status :succeeded})))
        ;; Read back in the SAME org — a scoped row is invisible to other orgs
        ;; (incl. public), which is exactly the isolation we want.
        (is (= :succeeded (:status (tc/with-org "exec-acme"
                                                (sp/read-entity (:storage *ctx*) :fn-execution (:id acme-exec)))))))
      (testing "a tenant sees only its OWN executions, never another org's"
        (let [acme-visible (tc/with-org "exec-acme"
                                        (set (map :id (sp/query-entities (:storage *ctx*) :fn-execution {}))))]
          (is (contains? acme-visible (:id acme-exec)))
          (is (not (contains? acme-visible (:id beta-exec)))))))))


(deftest org-scoped-branches-isolated
  ;; §4 org-scoped :branch (Design B). A tenant creates branches stamped with
  ;; its org and sees own + public (main). The branch-router resolves own+public
  ;; but returns nil for another org's branch, and the org-keyed ref-cache never
  ;; hands org-A's branch to org-B (which would run B in A's ctx).
  (let [main-id (:id (first (tc/with-org tc/public-org
                                         (sp/query-entities (:storage *ctx*) :branch {:name "main"}))))
        mk (fn [org nm]
             (tc/with-org org
                          (sp/create-entity (:storage *ctx*) :branch
                                            {:name nm :created-at (java.time.Instant/now)})))
        router {:base-ctx *ctx* :default-branch-id main-id :ref-cache (atom {})}]
    (is (some? main-id) "fixture has the public main branch")
    (let [acme-b (mk "br-acme" "br-acme-feature")
          _beta-b (mk "br-beta" "br-beta-feature")]
      (testing "create stamps the creating org"
        (is (= "br-acme" (:org-id acme-b))))
      (testing "a tenant sees its OWN branches + public main, never another org's"
        (let [acme-visible (tc/with-org "br-acme"
                                        (set (map :name (sp/query-entities (:storage *ctx*) :branch {}))))]
          (is (contains? acme-visible "br-acme-feature"))
          (is (contains? acme-visible "main"))
          (is (not (contains? acme-visible "br-beta-feature")))))
      (testing "the router resolves own + public, but nil for a foreign branch"
        (is (= (:id acme-b) (tc/with-org "br-acme" (br/resolve-branch-id router "br-acme-feature"))))
        (is (= main-id (tc/with-org "br-beta" (br/resolve-branch-id router "main"))))
        (is (nil? (tc/with-org "br-beta" (br/resolve-branch-id router "br-acme-feature")))))
      (testing "the org-keyed cache never leaks org-A's branch to org-B"
        ;; acme cached [br-acme, ref] above; beta's lookup keys [br-beta, ref] →
        ;; miss → org-scoped resolve → still nil (no cross-org cache hit).
        (is (nil? (tc/with-org "br-beta" (br/resolve-branch-id router "br-acme-feature"))))))))


(deftest per-org-branch-names-are-isolated
  ;; §4 follow-up: branch names are unique PER ORG (UNIQUE (org-id, name) NULLS
  ;; NOT DISTINCT) — two orgs may reuse a name with no cross-org collision /
  ;; existence leak, but within one org a name is still unique.
  (let [mk (fn [org nm]
             (tc/with-org org
                          (sp/create-entity (:storage *ctx*) :branch
                                            {:name nm :created-at (java.time.Instant/now)})))]
    (testing "distinct orgs may both have a branch with the same name"
      (is (some? (mk "pob-acme" "shared")))
      (is (some? (mk "pob-beta" "shared"))))
    (testing "...but the same name twice in ONE org is a unique violation"
      (is (thrown? Exception (mk "pob-acme" "shared"))))))


(deftest tenant-cannot-create-users
  (testing "a tenant writing :user directly is denied (no cross-org account mint)"
    (let [ex (try (tc/with-org "evil-org"
                               (sp/create-entity (:storage *ctx*) :user
                                                 {:username "mole" :password-hash "h" :org "victim"}))
                  nil (catch clojure.lang.ExceptionInfo e e))]
      (is (= :authz/forbidden (:type (ex-data ex)))))))


;; ---------------------------------------------------------------------------
;; Session TTL (§4.1) — the provider rejects expired tokens; logout deletes the
;; row so a token can't be replayed after sign-out.
;; ---------------------------------------------------------------------------
(deftest session-token-ttl-expiry
  (let [provider (tauth/storage-token-provider (:storage *ctx*))
        now (System/currentTimeMillis)]
    (sp/create-entity (:storage *ctx*) :token
                      {:token-hash (tauth/token-hash "ttl-live") :user "u" :user-id "u" :org "acme"
                       :expires-at (+ now 100000)})
    (sp/create-entity (:storage *ctx*) :token
                      {:token-hash (tauth/token-hash "ttl-dead") :user "u" :user-id "u" :org "acme"
                       :expires-at (- now 1000)})
    (sp/create-entity (:storage *ctx*) :token
                      {:token-hash (tauth/token-hash "ttl-none") :user "u" :user-id "u" :org "acme"})
    (testing "a non-expired token authenticates"
      (is (= {:authenticated? true :user "u" :user-id "u" :org "acme"} (auth/authenticate provider (bearer-req "ttl-live")))))
    (testing "an expired token fails closed (like an unknown token)"
      (is (= {:authenticated? false} (auth/authenticate provider (bearer-req "ttl-dead")))))
    (testing "a NULL-expiry token (operator API key) never expires"
      (is (= {:authenticated? true :user "u" :user-id "u" :org "acme"} (auth/authenticate provider (bearer-req "ttl-none")))))))


(deftest users-panel-renders-real-users-without-hashes
  (users/create-user! *ctx* "panel-carol" "pw-carol" "acme")
  (let [handler-id (fn-id-of (:storage *ctx*) "_partial-users-admin-handler")
        resp (cr/execute *ctx* handler-id {})
        body (:body resp)]
    (testing "200 + the users table with the user, NOT the degraded notice"
      (is (= 200 (:status resp)))
      (is (str/includes? body "grants-admin-table"))
      (is (str/includes? body "panel-carol")))
    (testing "the password hash is NEVER in the rendered panel (list-users strips it)"
      (is (not (str/includes? body "$2a$")))   ; no bcrypt hash
      (is (not (str/includes? body "Tenancy addon not active"))))
    (testing "the panel is HTMX-native — create/delete via hx-*, no client-JS fetch"
      (is (str/includes? body "<form"))
      (is (str/includes? body "hx-post=\"/api/users\""))
      (is (str/includes? body "data-users-panel"))
      ;; delete → dedicated cascade route (removes tokens + grants too)
      (is (str/includes? body "hx-delete=\"/api/users/"))
      (is (str/includes? body "hx-swap=\"delete\""))
      (is (str/includes? body "hx-confirm=\"Delete this user?\""))
      ;; per-row reset-password form → POST /api/users/:id/password
      (is (str/includes? body "/password\""))
      (is (str/includes? body "placeholder=\"new password\""))
      (is (not (str/includes? body "data-act=\"create-user\"")))
      (is (not (str/includes? body "data-act=\"delete-user\""))))))


(deftest login-sets-ttl-and-logout-invalidates
  (let [login-id (fn-id-of (:storage *ctx*) "invoke-login")
        logout-id (fn-id-of (:storage *ctx*) "invoke-logout")
        seam-ctx (assoc *ctx* :user-ops {:create-user users/create-user!
                                         :login users/login!
                                         :logout users/logout!})
        provider (tauth/storage-token-provider (:storage *ctx*))]
    (users/create-user! *ctx* "bob" "pw-bob" "acme")
    (let [token (:token (cr/execute seam-ctx login-id {:username "bob" :password "pw-bob"}))]
      (testing "login mints a token with a future TTL"
        (let [row (first (sp/query-entities (:storage *ctx*) :token {:token-hash (tauth/token-hash token)}))]
          (is (number? (:expires-at row)))
          (is (> (:expires-at row) (System/currentTimeMillis)))))
      (testing "the session authenticates before logout"
        (is (:authenticated? (auth/authenticate provider (bearer-req token)))))
      (testing "invoke-logout base-fn deletes the token → it no longer authenticates"
        (is (true? (cr/execute seam-ctx logout-id {:request (bearer-req token)})))
        (is (= {:authenticated? false} (auth/authenticate provider (bearer-req token))))))))


(deftest logout-all-invalidates-every-session-of-the-user
  (let [provider (tauth/storage-token-provider (:storage *ctx*))
        future-ms (+ (System/currentTimeMillis) 100000)
        seed (fn [tok user]
               (sp/create-entity (:storage *ctx*) :token
                                 {:token-hash (tauth/token-hash tok) :user user :org "acme"
                                  :expires-at future-ms}))]
    (seed "m1" "multi") (seed "m2" "multi") (seed "m3" "multi")
    (seed "other1" "bystander")
    (testing "logout-all deletes ALL the current user's sessions, never another user's"
      (let [n (binding [tc/*current-principal* {:user "multi" :user-id "multi" :org "acme"}]
                (users/logout-all! *ctx*))]
        (is (= 3 n))
        (is (= {:authenticated? false} (auth/authenticate provider (bearer-req "m1"))))
        (is (= {:authenticated? false} (auth/authenticate provider (bearer-req "m3"))))
        (is (= {:authenticated? true :user "bystander" :org "acme"}
               (dissoc (auth/authenticate provider (bearer-req "other1")) :user-id)))))
    (testing "unauthenticated caller → 0 (no principal)"
      (is (zero? (binding [tc/*current-principal* nil] (users/logout-all! *ctx*)))))))


(deftest session-cleanup-reaps-expired-tokens
  (let [now (System/currentTimeMillis)
        present? (fn [t]
                   (some? (first (sp/query-entities (:storage *ctx*) :token
                                                    {:token-hash (tauth/token-hash t)}))))]
    (sp/create-entity (:storage *ctx*) :token
                      {:token-hash (tauth/token-hash "clean-live") :user "u" :user-id "u" :org "acme" :expires-at (+ now 100000)})
    (sp/create-entity (:storage *ctx*) :token
                      {:token-hash (tauth/token-hash "clean-dead1") :user "u" :user-id "u" :org "acme" :expires-at (- now 5000)})
    (sp/create-entity (:storage *ctx*) :token
                      {:token-hash (tauth/token-hash "clean-dead2") :user "u" :user-id "u" :org "acme" :expires-at (dec now)})
    (sp/create-entity (:storage *ctx*) :token
                      {:token-hash (tauth/token-hash "clean-apikey") :user "u" :user-id "u" :org "acme"})
    (let [deleted (users/cleanup-expired-tokens! *base-pool*)]
      (testing "the sweep deletes the expired rows (returns a count ≥ the two seeded)"
        (is (>= deleted 2)))
      (testing "expired rows are gone; live + NULL-expiry (API key) survive"
        (is (not (present? "clean-dead1")))
        (is (not (present? "clean-dead2")))
        (is (present? "clean-live"))
        (is (present? "clean-apikey"))))))


;; ---------------------------------------------------------------------------
;; Self-serve signup (§4.1) — a fresh account creates a NEW org (never joins an
;; existing one) and auto-logs-in.
;; ---------------------------------------------------------------------------
(deftest login-handler-401-on-bad-creds
  ;; Bad credentials → a real 401, not 200-with-empty-body. We execute the
  ;; handler ONCE: the eager-compiled closure caches per fn-id across direct
  ;; cr/execute calls, so a second execute of the same handler would reuse the
  ;; first's result — a harness artifact the production request-callable avoids
  ;; with a fresh per-request cache. The success path (non-blank token → html-ok
  ;; 200) is the trivial :else branch, covered by the login round-trip tests.
  (let [handler-id (fn-id-of (:storage *ctx*) "_login-handler")
        seam-ctx (assoc *ctx* :user-ops {:create-user users/create-user! :login users/login!
                                         :logout users/logout! :signup users/signup!})
        bad-req {:request-method :post
                 :headers {"content-type" "application/x-www-form-urlencoded"}
                 :body "username=h401&password=WRONG"}]
    (users/create-user! *ctx* "h401" "pw401" "acme")
    (testing "bad credentials → 401 with a message (not 200 + empty body)"
      (let [resp (cr/execute seam-ctx handler-id {:request bad-req})]
        (is (= 401 (:status resp)))
        (is (= "Invalid username or password." (:body resp)))))))


(deftest signup-handler-429-when-rate-limited
  ;; The :signup seam returns a {:rate-limited true} sentinel for an over-quota
  ;; IP; the handler maps it to 429 (distinct from a 401 credential failure).
  ;; One execute (per-fn-id closure cache, like the 401 test).
  (let [handler-id (fn-id-of (:storage *ctx*) "_signup-handler")
        seam-ctx (assoc *ctx* :user-ops {:signup (fn [& _] {:rate-limited true})})
        req {:request-method :post
             :headers {"content-type" "application/x-www-form-urlencoded"}
             :body "username=x&password=y&org=z"}]
    (testing "an over-quota signup → 429 (not 401)"
      (let [resp (cr/execute seam-ctx handler-id {:request req})]
        (is (= 429 (:status resp)))))))


(deftest self-serve-signup-creates-org-user-and-logs-in
  (let [signup-id (fn-id-of (:storage *ctx*) "invoke-signup")
        seam-ctx (assoc *ctx* :user-ops {:create-user users/create-user! :login users/login!
                                         :logout users/logout! :signup users/signup!})
        provider (tauth/storage-token-provider (:storage *ctx*))]
    (testing "a fresh signup creates the org + user and returns a working session token"
      (let [token (:token (cr/execute seam-ctx signup-id {:username "newbie" :password "pw-new" :org "newco"}))]
        (is (string? token))
        (is (some? (first (sp/query-entities (:storage *ctx*) :org {:name "newco"}))))
        (is (some? (first (sp/query-entities (:storage *ctx*) :user {:username "newbie"}))))
        (is (= {:authenticated? true :user "newbie" :org "newco"}
               (dissoc (auth/authenticate provider (bearer-req token)) :user-id)))))
    (testing "a taken username → nil (no second account)"
      (is (nil? (cr/execute seam-ctx signup-id {:username "newbie" :password "x" :org "other-org"})))
      (is (nil? (first (sp/query-entities (:storage *ctx*) :org {:name "other-org"}))) "no partial org created"))
    (testing "a taken org → nil (signup can't join an existing org)"
      (is (nil? (cr/execute seam-ctx signup-id {:username "newbie2" :password "x" :org "newco"}))))
    (testing "blank fields → nil"
      (is (nil? (cr/execute seam-ctx signup-id {:username "" :password "x" :org "z"}))))
    (testing "the reserved platform org is rejected — no self-serve platform-admin"
      ;; `org="public"` would mint a principal every tenancy gate treats as the
      ;; trusted operator (effect gate off, RLS unset, write guard skipped) —
      ;; a full sandbox escape. Signup must refuse it and create nothing.
      (is (nil? (cr/execute seam-ctx signup-id
                            {:username "attacker" :password "x" :org tc/public-org})))
      (is (nil? (first (sp/query-entities (:storage *ctx*) :user {:username "attacker"})))
          "no user created for the reserved org"))))


;; ---------------------------------------------------------------------
;; Account admin (§4.1) — reset-password + cascade-delete. Operator ops
;; over the addon-active stack; :user/:token/:grant are operator-only.
;; ---------------------------------------------------------------------
(deftest delete-user-cascades-tokens-and-grants
  (let [storage (:storage *ctx*)
        user (users/create-user! *ctx* "cascade-victim" "pw" "acme")
        uid (:id user)]
    ;; Rows carry the STABLE id (the only key writers stamp and the
    ;; cascade matches on since the by-username union was retired).
    (tc/with-org tc/public-org
                 (sp/create-entity storage :token {:token-hash "cv-h1" :user "cascade-victim"
                                                   :user-id (str uid) :org "acme"})
                 (sp/create-entity storage :token {:token-hash "cv-h2" :user "cascade-victim"
                                                   :user-id (str uid) :org "acme"}))
    (sp/create-entity storage :grant {:subject-id (str uid) :capability "write" :namespace "acme"})
    (testing "delete-user! removes the user + all their tokens + grants"
      (let [res (users/delete-user! *ctx* uid)]
        (is (= 2 (:tokens-deleted res)))
        (is (= 1 (:grants-deleted res)))
        (is (nil? (sp/read-entity storage :user uid)))
        (is (empty? (tc/with-org tc/public-org
                                 (sp/query-entities storage :token {:user-id (str uid)}))))
        (is (empty? (sp/query-entities storage :grant {:subject-id (str uid)})))))
    (testing "deleting a nonexistent user throws :user/not-found"
      (is (thrown? clojure.lang.ExceptionInfo
            (users/delete-user! *ctx* (random-uuid)))))))


(deftest reset-password-updates-hash-and-kills-sessions
  (let [storage (:storage *ctx*)
        user (users/create-user! *ctx* "reset-me" "old-pw" "acme")
        uid (:id user)]
    ;; Seed the CURRENT token shape — login! stamps :user-id, and the
    ;; invalidation matches on it (the by-username path is retired).
    (tc/with-org tc/public-org
                 (sp/create-entity storage :token {:token-hash "rm-sess1" :user "reset-me"
                                                   :user-id (str uid) :org "acme"}))
    (testing "reset-password! sets a new hash + invalidates every session"
      (let [res (users/reset-password! *ctx* uid "new-pw")]
        (is (= 1 (:sessions-invalidated res)))
        (is (empty? (tc/with-org tc/public-org
                                 (sp/query-entities storage :token {:user-id (str uid)}))))
        (is (some? (users/login! *ctx* "reset-me" "new-pw")) "new password logs in")
        (is (nil? (users/login! *ctx* "reset-me" "old-pw")) "old password rejected")))))


(deftest user-admin-ops-are-operator-only
  ;; Regression guard for the IDOR that forcing public-org would reopen:
  ;; :user/:token/:grant are tenant-forbidden, so a caller whose org ≠
  ;; public MUST NOT be able to delete or reset another account.
  (let [storage (:storage *ctx*)
        user (users/create-user! *ctx* "idor-victim" "pw" "acme")
        uid (:id user)]
    (testing "a tenant (org ≠ public) cannot delete another account"
      (is (thrown? clojure.lang.ExceptionInfo
            (tc/with-org "attacker-org" (users/delete-user! *ctx* uid))))
      (is (some? (tc/with-org tc/public-org (sp/read-entity storage :user uid)))
          "target account still exists — the tenant's delete was denied"))
    (testing "a tenant cannot reset another account's password"
      (is (thrown? clojure.lang.ExceptionInfo
            (tc/with-org "attacker-org" (users/reset-password! *ctx* uid "attacker-pw")))))
    (testing "the operator (public-org) CAN reset + delete"
      (is (map? (tc/with-org tc/public-org (users/reset-password! *ctx* uid "fresh-pw"))))
      (is (map? (tc/with-org tc/public-org (users/delete-user! *ctx* uid))))
      (is (nil? (tc/with-org tc/public-org (sp/read-entity storage :user uid)))))))


(deftest reset-password-rejects-blank
  (let [user (users/create-user! *ctx* "blank-pw-user" "pw" "acme")]
    (testing "a blank new password is rejected (would otherwise brick the account)"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"password required"
            (users/reset-password! *ctx* (:id user) "")))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"password required"
            (users/reset-password! *ctx* (:id user) "   "))))))
