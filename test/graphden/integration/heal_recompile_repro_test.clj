(ns ^:integration graphden.integration.heal-recompile-repro-test
  "Regression guard for the graph-epoch HEAL thread's registry
   isolation (2026-08-23): a heal fired from an isolated test thread
   used to rebuild ctxs against an EMPTY rich-types registry — base-fn
   markers (`:lazy-seq-args` on `:cond`) vanished, the recompiled
   router evaluated cond clauses EAGERLY, and every later dispatch
   died with a ClassCast in `update-keys` on the \"/api\" route
   string. The branch-router now conveys the isolation overrides onto
   the heal thread (`branch_router` § graph-epoch-heal); this ns
   drives the exact trigger — repeated branch creates + branch
   bundle-syncs with dispatches between — and asserts the router
   stays healthy."
  (:require
    [clojure.test :refer [deftest is use-fixtures]]
    [graphden.auth.provider :as auth]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry-core]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.sync :as pkg-sync]
    [graphden.storage.protocol.core :as sp]
    [graphden.system.branch-router :as br]
    [graphden.test-infra.shared-bootstrap :as sb]
    [graphden.versioning.storage.core :as vs]))


(def ^:dynamic *router* nil)
(def ^:dynamic *storage* nil)
(def ^:private tok "heal-repro-token")


(use-fixtures :once
  (setup/create-container-fixture)
  exec/with-clean-registry
  exec/with-isolated-rich-types
  (fn [t]
    (let [pkgs ["core" "web" "app" "registry" "mcp"]
          _ (reset! registry-core/*rich-types-override*
                    (sb/ensure-swept-rich-types! pkgs))
          {:keys [storage]} (setup/bootstrap-crud-graph-from-golden!*
                              "graphden.integration.heal-recompile-repro-test"
                              pkgs)
          ctx (exec/create-context {:storage storage
                                    :auth-provider (auth/single-token-provider tok)})
          _ (cr/rebuild! ctx)
          router (br/create-router ctx "_app-ring-response"
                                   {:optional-handler-fn-names ["_registry-ring-response"]})]
      (try (binding [*router* router *storage* storage] (t))
           (finally (sp/close storage))))))


(defn- hit!
  []
  (:status (br/dispatch *router*
                        {:request-method :get
                         :uri "/api/packages"
                         :headers {"authorization" (str "Bearer " tok)}
                         :query-string nil :body nil})))


(deftest branches-plus-dispatches-do-not-poison-the-router
  (dotimes [i 12]
    (vs/create-branch! *storage* (str "repro/b" i))
    (is (= 200 (hit!)) (str "dispatch after branch " i))))


(deftest branch-syncs-plus-dispatches-do-not-poison-the-router
  ;; The MCP upsert-fn-defs shape (shipped pre-import): switch to a branch,
  ;; sync-bundle!, delta-invalidate the TARGET branch ctx, then dispatch on
  ;; main again — repeated. This is pure develop-path code.
  (dotimes [i 10]
    (let [branch (vs/create-branch! *storage* (str "repro/s" i))
          on-branch (vs/switch-branch *storage* (:id branch))
          fn-ids (pkg-sync/sync-bundle!
                   on-branch
                   [{:name (keyword (str "repro-fn-" i))
                     :namespace "repro.demo"
                     :parent :add :args {:nums [1 2]}}])]
      (graphden.executor.context/invalidate-graph-cache!
        (br/ctx-for *router* (:id branch))
        fn-ids)
      (is (= 200 (hit!)) (str "dispatch after branch sync " i)))))
