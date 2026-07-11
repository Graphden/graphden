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


(defn resolve-app-org
  "The org named by the request's host (subdomain or verified custom domain),
   or nil — apex / unresolvable means it's not an app request (→ editor/API)."
  [request org-resolver base-domain host-resolver]
  (or (subdomain/org-from-request org-resolver request base-domain)
      (domain/org-from-request host-resolver request)))


(defn read-handler-fn-id
  "Read an org's `:handler-fn-id` (or nil — org absent / app unconfigured).
   Reads `:org` in the CURRENT (platform/public) context — the dispatch
   consults the app-router BEFORE the request-scope binds an org, so the
   tenant-forbidden read guard (which fires only for org ≠ public) doesn't
   block it."
  [storage org]
  (some-> (first (sp/query-entities storage :org {:name org})) :handler-fn-id))


(defn app-handler-target
  "Resolve the request to its app target, or nil when it's not an app request.
   Returns `{:org <slug> :handler-fn-id <uuid-or-nil>}` (nil handler → 404
   upstream)."
  [storage request org-resolver base-domain host-resolver]
  (when-let [org (resolve-app-org request org-resolver base-domain host-resolver)]
    {:org org :handler-fn-id (read-handler-fn-id storage org)}))


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


(def ^:private app-misdirected
  "421 — this pod's `:executor-orgs` shard doesn't hold this org, so its
   handler fn was never compiled here. Say so instead of 404'ing the fn
   and letting the tenant think their app is undeployed."
  {:status 421
   :headers {"Content-Type" "text/plain"}
   :body "This app is served by a different executor."})


(def default-app-timeout-ms
  "Wall-clock budget for a tenant handler (§3.4 step 7). Untrusted tenant code
   must be time-bounded — without it a single hanging/spinning handler blocks a
   request thread (DoS)."
  10000)


(defn make-app-router
  "Build the `:app-router` seam — `(fn [ctx request] ring-response-or-nil)`.
   `org-resolver` + `base-domain` (subdomain) and `host-resolver` (custom
   domain) feed `app-handler-target`; `timeout-ms` bounds the handler."
  ([org-resolver base-domain host-resolver]
   (make-app-router org-resolver base-domain host-resolver default-app-timeout-ms))
  ([org-resolver base-domain host-resolver timeout-ms]
   (fn [ctx request]
     (when-let [org (resolve-app-org request org-resolver base-domain host-resolver)]
       ;; Read the handler fresh per request — a single indexed unique-name
       ;; `:org` lookup, negligible next to the graph-handler `execute` below,
       ;; and always current (a `set-org-handler!` deploy takes effect at once).
       (let [handler-fn-id (read-handler-fn-id (:storage ctx) org)]
         (cond
           ;; Wrong executor → 421, for either reason (checked BEFORE the
           ;; handler verdict so it never reads as "not deployed"):
           ;;   - the org isn't in this pod's shard (nothing of theirs is
           ;;     compiled here);
           ;;   - it's a `:byo` org on a hosted pod — the graph is stored here
           ;;     but running it is the customer's executor's job. A BYO
           ;;     executor pod (`:byo-executor?`) serves it. `:org` is read in
           ;;     the platform context, same as `read-handler-fn-id` above.
           (or (not (cr/org-in-shard? (:executor-orgs ctx) org))
               (and (not (:byo-executor? ctx)) (tc/byo-org? (:storage ctx) org)))
           app-misdirected

           (not handler-fn-id)
           app-not-configured

           :else
           (let [result
                 (cr/run-with-timeout
                   timeout-ms
                   (fn []
                     ;; Cooperative cancellation: each `execute` step calls
                     ;; `check-cancel!`, so on `future-cancel` (interrupt) a graph
                     ;; handler aborts instead of leaking a thread.
                     (binding [cr/*cancel-check*
                               #(when (Thread/.isInterrupted (Thread/currentThread))
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
               :else result))))))))
