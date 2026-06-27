(ns graphden.tenancy.app-router
  "FaaS app-routing (PLATFORM_PLAN §3.4). A request to a TENANT's subdomain
   (or a verified custom domain) is the tenant's APP: the branch-router's
   `dispatch` delegates here, this resolves the org, looks up its handler fn
   (`:org.handler-fn-id`), and executes it ORG-SCOPED + EFFECT-GATED. So the
   tenant's code runs INSIDE the platform's sandbox — it never owns a server
   (FaaS, not PaaS).

   An apex / platform request (no resolvable subdomain) yields nil and falls
   through to the normal editor/API flow (token-authority)."
  (:require
    [graphden.executor.compile-runtime :as cr]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.domain :as domain]
    [graphden.tenancy.subdomain :as subdomain]))


(defn app-handler-target
  "Resolve the request to its app target, or nil when it's not an app request
   (apex / unresolvable host → editor/API).

   Returns `{:org <slug> :handler-fn-id <uuid-or-nil>}`; a nil `:handler-fn-id`
   means the org exists but hasn't configured its app yet (→ 404 upstream).
   Reads `:org` in the CURRENT (platform/public) context — dispatch consults
   this BEFORE the request-scope binds an org, so the tenant-forbidden read
   guard (which fires only for org ≠ public) doesn't block it."
  [storage request org-resolver base-domain host-resolver]
  (when-let [org (or (subdomain/org-from-request org-resolver request base-domain)
                     (domain/org-from-request host-resolver request))]
    {:org org
     :handler-fn-id (some-> (first (sp/query-entities storage :org {:name org}))
                            :handler-fn-id)}))


(def ^:private app-not-configured
  {:status 404
   :headers {"Content-Type" "text/plain"}
   :body "This app is not configured yet."})


(def ^:private app-error
  {:status 500
   :headers {"Content-Type" "text/plain"}
   :body "Application error."})


(defn make-app-router
  "Build the `:app-router` seam — `(fn [ctx request] ring-response-or-nil)`.
   `org-resolver` + `base-domain` (subdomain) and `host-resolver` (custom
   domain) feed `app-handler-target`."
  [org-resolver base-domain host-resolver]
  (fn [ctx request]
    (when-let [{:keys [org handler-fn-id]}
               (app-handler-target (:storage ctx) request
                                   org-resolver base-domain host-resolver)]
      (if-not handler-fn-id
        app-not-configured
        (try
          (tc/with-org org
                       ;; Run the org's handler INSIDE the sandbox: `:allowed-effects`
                       ;; forbids env/io/network/process; `:execute-guard` nil — the app
                       ;; is the org's PUBLIC face, not a user-gated `/api/execute`;
                       ;; `*current-org*` bound so OrgScoped confines reads to the org.
                       (cr/execute (assoc ctx
                                          :allowed-effects cr/default-cloud-allowed-effects
                                          :execute-guard nil)
                                   handler-fn-id
                                   {:request request}))
          (catch Exception _ app-error))))))
