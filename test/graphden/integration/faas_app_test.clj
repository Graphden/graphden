(ns ^:integration graphden.integration.faas-app-test
  "Addon-active FaaS app-routing (PLATFORM_PLAN §3.4) — the INTEGRATED cloud
   chain, not the isolated pieces: an org's handler fn, resolved from its
   subdomain and executed by the app-router, runs SANDBOXED (effect-gated) and
   in the org's context (so OrgScoped confines it). The reusable addon-active
   fixture below — real `Versioned(OrgScoped(Postgres))` + the `:org`/`:grant`
   schema + a full package bootstrap — underpins this and future multi-tenant
   integration tests (the project's first addon-active harness)."
  (:require
    [clojure.test :refer [deftest is testing use-fixtures]]
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
    [graphden.tenancy.grant-schema :as grant-schema]
    [graphden.tenancy.org-schema :as org-schema]
    [graphden.tenancy.storage :as ts]
    [graphden.tenancy.subdomain :as subdomain]
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
