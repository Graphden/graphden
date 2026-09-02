(ns ^:integration graphden.packages.app.tenant-effect-budget-test
  "The cloud's request-level effect gate vs the `app` package's own routes.

   On the cloud EVERY tenant request — signed-in, demo, anonymous-with-a-
   session — runs under `cr/cloud-request-allowed-effects`
   (docs/TENANCY_SEAM.md § Effect gate). A platform partial / API whose
   effect closure reaches outside that set answers 403 for every tenant,
   which is exactly how the branch popover (`GRAPHDEN_HUB_URL` via `:env`,
   2026-08-28) and the feedback probe + intake (2026-08-29) broke in
   production for five days with every single-tenant test green: nothing
   ran a platform handler under the restricted set.

   Two guards:
   1. `app-routes-stay-inside-the-request-gate` — STATIC: every route in
      `app.routes`, its handler's computed effect closure (the type-check
      sweep's rich-types entry) minus the allowed set must be covered by
      the explicit ledger below, and every ledger entry must still be
      needed (stale entries rot the contract).
   2. the executed cases — the three handlers of the incident, run under
      the restricted ctx, answer 200."
  (:require
    [cheshire.core :as json]
    [clojure.set :as set]
    [clojure.test :refer [deftest is testing use-fixtures]]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.registry.core :as registry]
    [graphden.executor.test-setup :as setup]
    [graphden.packages.loader :as loader]
    [graphden.system.deploy-config :as deploy-config]
    [graphden.test-infra.golden-app :as ga]))


(use-fixtures :once (ga/fixture (ns-name *ns*)))


;; Routes whose closure legitimately reaches outside the request-level set,
;; each with the reason it is safe. Extending this ledger is a DECISION —
;; the route will 403 for every cloud tenant, so the entry must say why
;; that is acceptable (operator-only surface, or served under the addon's
;; exact-path widening for the platform UI shell).
(def ^:private ui-shell
  "The platform UI shell — the editor page + its fixed asset bundles read
   the classpath (`:io`). The tenancy addon widens the request set by
   exactly `:io` for these EXACT GET paths (`platform-ui-shell-paths`); a
   caller-chosen classpath path (`/assets/baseline`, `/partials/asset-edit`)
   stays gated — that is what keeps a tenant from pulling `cloud/prod.edn`
   through them."
  {:effects #{:io} :why "UI shell — the addon widens :io for this exact GET path"})


(def ^:private operator-only
  {:effects #{:io} :why "operator surface — a tenant session is refused (403) by design"})


(def ^:private vault-ops
  {:effects #{:network}
   :why "vault-backed secret op — `operator-only!` refuses every gated (tenant) execution by design; the cloud's per-org secrets are not vault paths a tenant may touch"})


(def ^:private outside-the-gate
  {;; Operator scrape endpoints — JVM info (hostname / user via env).
   :metrics {:effects #{:io :env} :why "operator scrape (JVM / env info)"}
   :metrics-prometheus {:effects #{:io :env} :why "operator scrape (JVM / env info)"}
   ;; UI shell (see `ui-shell`). The editor PAGE itself is not here: its
   ;; only classpath read was the build-hashes resource, now a cached
   ;; effect-free read.
   :editor-js-asset ui-shell
   :editor-css-asset ui-shell
   :codemirror-js-asset ui-shell
   :htmx-js-asset ui-shell
   :htmx-sse-js-asset ui-shell
   :graphden-runtime-js-asset ui-shell
   :graphden-components-css-asset ui-shell
   ;; Operate → Assets (system-only on the cloud — a stored-XSS surface).
   :api-assets-baseline operator-only
   :partial-asset-edit operator-only
   ;; Services are a dedicated-pod / self-host feature; the reconcile
   ;; endpoint drives the supervisor.
   :api-services-reconcile operator-only
   ;; Raw vault ops.
   :api-secret-delete (update vault-ops :effects conj :io)
   :api-secret-inline-binding vault-ops
   :api-secret-rotate-value vault-ops})


(def ^:private app-routes
  "`{route-name handler-name}` for every route fn-def in the `app` package —
   a route is any fn-def binding both `:path` and `:handler`."
  (delay
    (into (sorted-map)
          (keep (fn [{:keys [name args]}]
                  (when (and name (:path args) (keyword? (:handler args)))
                    [name (:handler args)])))
          (:fn-defs (loader/load-packages ["app"])))))


(defn- closure-effects
  [handler-name]
  (when-let [id (ga/fn-id handler-name)]
    (or (:effects (registry/rich-type-of-id id)) #{})))


(deftest app-routes-stay-inside-the-request-gate
  (let [resolved (into {}
                       (keep (fn [[route handler]]
                               (when (ga/fn-id handler) [route handler])))
                       @app-routes)]
    (is (< 50 (count resolved)) "the app route table resolved through the golden bootstrap")
    (doseq [[route handler] resolved
            :let [extra (set/difference (closure-effects handler)
                                        cr/cloud-request-allowed-effects)
                  allowed (get-in outside-the-gate [route :effects] #{})]]
      (is (empty? (set/difference extra allowed))
          (str route " (" handler ") reaches " extra
               " — outside cloud-request-allowed-effects "
               cr/cloud-request-allowed-effects
               ". A deployment setting goes through :deploy-config (boot"
               " snapshot), a notification through the alerter; if the route"
               " is genuinely operator-only, add it to the ledger WITH a reason.")))
    (testing "the ledger carries no stale entries"
      (doseq [[route {:keys [effects]}] outside-the-gate
              :let [handler (get resolved route)
                    extra (when handler
                            (set/difference (closure-effects handler)
                                            cr/cloud-request-allowed-effects))]]
        (is handler (str "ledger route " route " no longer exists"))
        (is (= effects extra)
            (str "ledger for " route " says " effects " but the closure reaches " extra))))))


(defn- restricted-bootstrap
  []
  (update ga/*bootstrap* :ctx assoc :allowed-effects cr/cloud-request-allowed-effects))


(deftest branch-popover-renders-for-a-tenant-request
  (let [resp (setup/via-graph (restricted-bootstrap) :_partial-branch-popover-handler
                              {:request-method :get :uri "/partials/branch-popover"})]
    (is (= 200 (:status resp)))
    (is (re-find #"branch-popover" (str (:body resp))))))


(deftest feedback-config-answers-for-a-tenant-request
  (try
    (deploy-config/install! {:feedback-url "https://intake.example/api/feedback"})
    (let [resp (setup/via-graph (restricted-bootstrap) :feedback-config-handler
                                {:request-method :get :uri "/api/feedback/config"})]
      (is (= 200 (:status resp)))
      (is (= {"url" "https://intake.example/api/feedback"}
             (json/parse-string (str (:body resp))))))
    (finally (deploy-config/clear!))))


(deftest feedback-intake-accepts-from-a-tenant-session
  ;; Armed via the snapshot; the honeypot-filled body takes the "pretend
  ;; success, store nothing" arm, so the golden DB is untouched — what is
  ;; under test is that the arm-gate + parse + ladder run under the
  ;; restricted ctx without reaching :env / :network.
  (try
    (deploy-config/install! {:feedback-intake "1"})
    (let [resp (setup/via-graph (restricted-bootstrap) :feedback-handler
                                {:request-method :post :uri "/api/feedback"
                                 :headers {"content-type" "text/plain"}
                                 :remote-addr "127.0.0.1"
                                 :body (json/generate-string
                                         {:category "bug" :text "probe" :website "http://bot"})})]
      (is (= 200 (:status resp)))
      (is (= {"ok" true} (json/parse-string (str (:body resp))))))
    (finally (deploy-config/clear!))))
