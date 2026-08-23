(ns ^:integration graphden.integration.graph-rows-route-test
  "End-to-end coverage for `GET /api/export/graph-rows` — the route a BYO
   executor bootstraps from (docs/SCALING.md § External BYO executor).

   Why this test exists: every BYO test (`byo_test`, `storage.remote.*`)
   stubs the hub with a bare httpkit handler that IGNORES `Authorization`
   and `X-Graphden-Branch`, so the real graph-composed route — its
   `:get-auth-required` middleware chain, its EDN wire shape, and its
   branch pinning through the branch router — had zero coverage. A
   regression here bricks every remote executor's bootstrap while the
   BYO suite stays green.

   Dispatched through `br/dispatch` (the same closure http-kit invokes),
   like `auth_middleware_test`."
  (:require
    [clojure.edn :as edn]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry-core]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.records.wire :as wire]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.test-infra.shared-bootstrap :as sb]
    [graphden.versioning.storage.core :as vs]))


(def ^:dynamic *router* nil)
(def ^:dynamic *storage* nil)


(def ^:private test-auth-token "graph-rows-test-token-abc123")


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-clean-registry
  exec/with-isolated-rich-types
  (fn [t]
    ;; Same golden-clone fixture shape as optional_packages_dispatch_test —
    ;; the CANONICAL `[core web app registry mcp]` bundle (an identical set
    ;; shares one golden clone), the auth seam wired so `:get-auth-required`
    ;; has a provider, and the registry's per-branch handler served the
    ;; production way (`:optional-handler-fn-names`).
    (let [pkgs ["core" "web" "app" "registry" "mcp"]
          _ (reset! registry-core/*rich-types-override*
                    (sb/ensure-swept-rich-types! pkgs))
          {:keys [storage]} (setup/bootstrap-crud-graph-from-golden!*
                              "graphden.integration.graph-rows-route-test"
                              pkgs)
          ctx (exec/create-context
                {:storage storage
                 :auth-provider (auth/single-token-provider test-auth-token)})
          _ (cr/rebuild! ctx)
          router (br/create-router
                   ctx "_app-ring-response"
                   {:optional-handler-fn-names ["_registry-ring-response"]})]
      (try
        (binding [*router* router
                  *storage* storage]
          (t))
        (finally (sp/close storage))))))


(defn- get-graph-rows
  [headers]
  (br/dispatch *router*
               {:request-method :get
                :uri "/api/export/graph-rows"
                :headers headers
                :query-string nil
                :body nil}))


(defn- parse-rows
  [resp]
  (edn/read-string {:readers wire/wire-readers} (str (:body resp))))


(deftest graph-rows-rejects-a-missing-or-wrong-token
  (testing "no Authorization header → 401 (the bundle carries the whole graph
            — incl. vault paths by design — so it must fail closed)"
    (is (= 401 (:status (get-graph-rows {})))))
  (testing "a wrong bearer → 401"
    (is (= 401 (:status (get-graph-rows {"authorization" "Bearer nope"}))))))


(deftest graph-rows-serves-the-five-tables-over-edn
  (let [resp (get-graph-rows {"authorization" (str "Bearer " test-auth-token)})]
    (is (= 200 (:status resp)))
    (let [rows (parse-rows resp)]
      (testing "the bundle is exactly the five graph tables"
        (is (= #{:fns :slots :fn-slots :bindings :list-items} (set (keys rows)))))
      (testing "it carries the platform fns a BYO executor compiles from"
        (is (some #(= "add" (:name %)) (:fns rows)))
        (is (pos? (count (:bindings rows))))))))


(deftest graph-rows-pins-to-the-requested-branch
  ;; A BYO executor pins one branch via X-Graphden-Branch; a fn that exists
  ;; only on that branch must appear exactly there.
  (let [branch (vs/create-branch! *storage* "byo-pin")
        dev (vs/switch-branch *storage* (:id branch))
        fn-row (sp/create-entity dev :fn {:name "byo-branch-only-fn"})
        on-branch? (fn [resp]
                     (boolean (some #(= (:id fn-row) (:id %))
                                    (:fns (parse-rows resp)))))]
    (testing "the branch header scopes the bundle to that branch"
      (is (on-branch? (get-graph-rows {"authorization" (str "Bearer " test-auth-token)
                                       "x-graphden-branch" "byo-pin"}))))
    (testing "without the header the bundle is main's — the branch fn is absent"
      (is (not (on-branch? (get-graph-rows
                             {"authorization" (str "Bearer " test-auth-token)})))))))
