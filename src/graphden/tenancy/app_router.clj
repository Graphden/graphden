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


(def ^:private app-timeout
  {:status 504
   :headers {"Content-Type" "text/plain"}
   :body "Application timed out."})


(def default-app-timeout-ms
  "Wall-clock budget for a tenant handler (§3.4 step 7). Untrusted tenant code
   must be time-bounded — without it a single hanging/spinning handler blocks a
   request thread (DoS)."
  10000)


(defn run-with-timeout
  "Run `thunk` in a future bounded by `timeout-ms`. Returns its value, or
   `::timeout` (cancelling the future) when it overruns, or `::error` when it
   throws. Generic — the caller arranges cooperative cancellation."
  [timeout-ms thunk]
  (let [fut (future (thunk))
        result (try (deref fut timeout-ms ::timeout)
                    (catch Throwable _ ::error))]
    (when (identical? result ::timeout) (future-cancel fut))
    result))


(defn make-app-router
  "Build the `:app-router` seam — `(fn [ctx request] ring-response-or-nil)`.
   `org-resolver` + `base-domain` (subdomain) and `host-resolver` (custom
   domain) feed `app-handler-target`; `timeout-ms` bounds the handler."
  ([org-resolver base-domain host-resolver]
   (make-app-router org-resolver base-domain host-resolver default-app-timeout-ms))
  ([org-resolver base-domain host-resolver timeout-ms]
   (fn [ctx request]
     (when-let [{:keys [org handler-fn-id]}
                (app-handler-target (:storage ctx) request
                                    org-resolver base-domain host-resolver)]
       (if-not handler-fn-id
         app-not-configured
         (let [result
               (run-with-timeout
                 timeout-ms
                 (fn []
                   ;; Cooperative cancellation: each `execute` step calls
                   ;; `check-cancel!`, so on `future-cancel` (interrupt) a graph
                   ;; handler aborts instead of leaking a thread.
                   (binding [cr/*cancel-check*
                             #(when (.isInterrupted (Thread/currentThread))
                                (throw (InterruptedException. "app handler cancelled")))]
                     ;; Run INSIDE the sandbox (binding lands on the future's
                     ;; thread, where the thunk runs): `:allowed-effects` forbids
                     ;; env/io/network/process; `:execute-guard` nil — the app is
                     ;; the org's PUBLIC face, not a user-gated `/api/execute`;
                     ;; `*current-org*` bound so OrgScoped confines reads.
                     (tc/with-org org
                                  (cr/execute (assoc ctx
                                                     :allowed-effects cr/default-cloud-allowed-effects
                                                     :execute-guard nil)
                                              handler-fn-id
                                              {:request request})))))]
           (cond
             (identical? result ::timeout) app-timeout
             (identical? result ::error) app-error
             :else result)))))))
