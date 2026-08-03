(ns graphden.tenancy.app-router
  "FaaS app-routing (PLATFORM_PLAN §3.4, Track C model A). A request to a
   TENANT's APP is served by that org's handler fn ORG-SCOPED + EFFECT-GATED —
   the tenant's code runs INSIDE the platform's sandbox, never owning a server
   (FaaS, not PaaS). The branch-router's `dispatch` delegates here; this
   resolves the app target, looks up its handler, and executes it.

   Apps live on the SEPARATE apps-domain (`graphden.app`), isolated from the
   editor's `graphden.dev` origin. An app is addressed by:

   - `<label>.<apps-domain>` (e.g. `shop.graphden.app`) → the GLOBAL `:app-route`
     for `label` → its org + handler;
   - a verified custom domain → the org's app (a `:domain`-pinned label, else the
     org's default `:handler-fn-id`).

   Anything ELSE — the apex, an `<org>.graphden.dev` editor subdomain, `app.` —
   yields nil and falls through to the editor/API flow (token-authority). The
   editor's org binding comes from the request-scope's own subdomain guard, not
   here."
  (:require
    [graphden.executor.compile-runtime :as cr]
    [graphden.storage.protocol.core :as sp]
    [graphden.tenancy.app-route :as app-route]
    [graphden.tenancy.context :as tc]
    [graphden.tenancy.domain :as domain]
    [graphden.tenancy.subdomain :as subdomain]))


(defn read-handler-fn-id
  "The handler fn for a resolved app target (or nil — app unconfigured). A
   `:label` target resolves the GLOBAL `:app-route`; a label-less target (a
   custom domain with no pinned app) reads the org's default `:handler-fn-id`.
   Reads in the CURRENT (platform/public) context — the dispatch consults the
   app-router BEFORE the request-scope binds an org, so the tenant-forbidden read
   guard (org ≠ public only) doesn't block it."
  [storage {:keys [org label]}]
  (if label
    (:handler-fn-id (app-route/route-by-label storage label))
    (some-> (first (sp/query-entities storage :org {:name org})) :handler-fn-id)))


(defn app-handler-target
  "Resolve the request to its app target, or nil when it's not an app request
   (→ editor/API). Returns `{:org <slug> :label? <label> :handler-fn-id <uuid-
   or-nil>}`; a nil handler → 404 upstream. For a `<label>.<apps-domain>` host
   the org + handler come from the GLOBAL app-route (the label's owner is
   authoritative); a custom domain uses the `:domain` row's target."
  [storage request apps-domain host-resolver]
  (if-let [label (subdomain/extract-subdomain (get-in request [:headers "host"]) apps-domain)]
    ;; A host under the apps-domain is ALWAYS an app request (a missing route →
    ;; 404 upstream, never a fall-through to the editor — the apps-domain serves
    ;; no editor).
    (let [row (app-route/route-by-label storage label)]
      {:org (:org row) :label label :handler-fn-id (:handler-fn-id row)})
    (when-let [target (domain/target-from-request host-resolver request)]
      (assoc target :handler-fn-id (read-handler-fn-id storage target)))))


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
   `apps-domain` (the flat apps namespace, e.g. `graphden.app`) + `host-resolver`
   (custom domains) feed `app-handler-target`; `timeout-ms` bounds the handler.
   Base-domain (`graphden.dev`) subdomains are NOT app requests here — they fall
   through to the editor."
  ([apps-domain host-resolver]
   (make-app-router apps-domain host-resolver default-app-timeout-ms))
  ([apps-domain host-resolver timeout-ms]
   (fn [ctx request]
     ;; Resolve the app target + its handler fresh per request — an indexed
     ;; unique-key lookup (`:app-route` by label / custom `:domain`), negligible
     ;; next to the graph-handler `execute` below, and always current (a
     ;; `set-org-handler!` / app-route write takes effect at once).
     (when-let [{:keys [org handler-fn-id]}
                (app-handler-target (:storage ctx) request apps-domain host-resolver)]
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
