(ns graphden.tenancy.app-router
  "FaaS app-routing (PLATFORM_PLAN §3.4). A request to a TENANT's app is served
   by that org's handler fn ORG-SCOPED + EFFECT-GATED — the tenant's code runs
   INSIDE the platform's sandbox, never owning a server (FaaS, not PaaS). The
   branch-router's `dispatch` delegates here; this resolves the app target,
   looks up its handler, and executes it. An app is addressed by (Track C):

   - a TWO-level `<label>.<org>.base` subdomain → the org's named app
     `(org, label)` via `:app-route`;
   - a single-level `<org>.base` subdomain → the org's default `:handler-fn-id`
     (legacy; C2b relocates the org root to the editor);
   - a verified custom domain → the org's default `:handler-fn-id`.

   An apex / platform request (no resolvable app target) yields nil and falls
   through to the normal editor/API flow (token-authority)."
  (:require
    [graphden.executor.compile-runtime :as cr]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.app-route :as app-route]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.domain :as domain]
    [graphden.tenancy.subdomain :as subdomain]))


(defn resolve-app-target
  "The app a request addresses, or nil when it's not an app request (→
   editor/API). Resolution, most-specific first:

   1. a TWO-level `<label>.<org>.base` subdomain → `{:org <org> :label <label>}`
      (Track C — one of the org's named apps);
   2. a single-level `<org>.base` subdomain → `{:org <org>}` (the org's default
      app — legacy, `:org.handler-fn-id`; C2b relocates this to the editor);
   3. a verified custom domain → `{:org <org>}` (the org's default app).

   The apex / an unresolvable host → nil."
  [request org-resolver base-domain host-resolver]
  (or (subdomain/app-from-request org-resolver request base-domain)
      (when-let [org (subdomain/org-from-request org-resolver request base-domain)]
        {:org org})
      (when-let [org (domain/org-from-request host-resolver request)]
        {:org org})))


(defn read-handler-fn-id
  "The handler fn for a resolved app target (or nil — app unconfigured / org
   absent). A `:label` target reads the `(org, label)` `:app-route`; a
   label-less target (single-level subdomain / custom domain) reads the org's
   default `:handler-fn-id`. Reads in the CURRENT (platform/public) context —
   the dispatch consults the app-router BEFORE the request-scope binds an org,
   so the tenant-forbidden read guard (org ≠ public only) doesn't block it."
  [storage {:keys [org label]}]
  (if label
    (app-route/handler-fn-id-for storage org label)
    (some-> (first (sp/query-entities storage :org {:name org})) :handler-fn-id)))


(defn app-handler-target
  "Resolve the request to its app target, or nil when it's not an app request.
   Returns `{:org <slug> :handler-fn-id <uuid-or-nil>}` (plus `:label` for a
   named-app subdomain); a nil handler → 404 upstream."
  [storage request org-resolver base-domain host-resolver]
  (when-let [target (resolve-app-target request org-resolver base-domain host-resolver)]
    (assoc target :handler-fn-id (read-handler-fn-id storage target))))


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
     ;; Resolve the app target + its handler fresh per request — an indexed
     ;; unique-key lookup (`:app-route` or `:org`), negligible next to the
     ;; graph-handler `execute` below, and always current (a `set-org-handler!`
     ;; / app-route write takes effect at once).
     (when-let [{:keys [org handler-fn-id]}
                (app-handler-target (:storage ctx) request org-resolver base-domain host-resolver)]
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
         ;; Fleet forward-hop (T2.6): before 421'ing, ask the `:fleet-forward`
         ;; seam whether this org's cell is placed on another executor and, if
         ;; so, proxy the request there. nil (no placement, byo, or no seam) →
         ;; the 421 backstop. `handler-fn-id` is the cell's entry.
         (or (when-let [fwd (:fleet-forward ctx)]
               (fwd request org handler-fn-id))
             app-misdirected)

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
             ;; run-with-timeout returns ITS namespace's sentinels
             ;; (`::compile-runtime/{timeout,error}`) — must be matched
             ;; qualified (`::cr/…`), NOT bare `::timeout`/`::error`
             ;; (which would resolve to THIS ns and silently never
             ;; match → an errored handler returns the raw sentinel
             ;; keyword instead of a 500). byo.clj does this correctly.
             (identical? result ::cr/timeout) app-timeout
             (identical? result ::cr/error) app-error
             :else result)))))))
