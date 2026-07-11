(ns graphden.byo
  "Bring-your-own executor — the runnable assembly (docs/SCALING.md § external
   executor). A customer runs THIS on their own hardware to serve one org's
   app; the graph lives in the platform's Postgres and is read over HTTP.

   Shape (why it's not the normal system): a BYO executor has NO Postgres, so
   it can't sync the package graph to a DB, run the service registry, or LISTEN
   on `graphden_events`. Instead it:

     1. loads the packages LOCALLY for their base-fn IMPLS (Clojure code) and
        registers them in memory — NO DB sync (the graph already exists on the
        hub);
     2. reads the graph over HTTP into a read-only `RemoteStorage`
        (`GET /api/export/graph-rows`, org-scoped, branch-pinned);
     3. compiles it and serves the org's app handler over HTTP directly (not
        via the PG-backed service reconciler);
     4. stays fresh via an SSE source that refreshes + recompiles on each
        `fn:invalidate` the hub pushes.

   `start-byo!` returns a handle; `stop-byo!` tears it down. `-main` reads the
   config from the environment for `clojure -M -m graphden.byo`."
  (:require
    [clojure.string :as str]
    [clojure.tools.logging :as log]
    [graphden.executor.compile-runtime :as cr]
    [graphden.executor.context :as ectx]
    [graphden.executor.interface :as exec]
    [graphden.executor.registry.core :as registry]
    [graphden.packages.loader :as pkg]
    [graphden.storage.protocol.core :as sp]
    [graphden.storage.remote.core :as remote]
    [graphden.storage.remote.sse :as remote-sse]
    [graphden.system.core :as sys]
    [graphden.tenancy.context :as tc]
    [org.httpkit.server :as hk]))


(def default-packages
  "The packages a BYO executor loads for their base-fn impls — the same set a
   hosted pod runs."
  ["core" "storage" "web" "app"])


(def default-timeout-ms
  "Wall-clock bound on one BYO app-handler request. A runaway/blocking graph
   handler would otherwise pin an httpkit worker; enough of them exhaust the
   pool. More generous than the cloud's 10s — it's the customer's own box, not
   shared infra — but still bounded so one bad request can't take the service
   down. (Independent of effects: this is a reliability guard, not a sandbox.)"
  30000)


(defn- register-impls-in-memory!
  "Register the packages' base-fn impls + type-aliases in the process registry
   WITHOUT any DB sync (unlike `:exec/base-fns`, which writes to storage). The
   graph itself is fetched from the hub, so only the Clojure impls are needed
   locally. `extra-base-fns` is merged on top (mirrors the system seam).
   Returns the `{fn-name → impl}` map for the ctx."
  [packages extra-base-fns]
  (let [base-fns-map (merge (registry/compute-base-fns-map (:base-fn-defs packages))
                            extra-base-fns)]
    (sys/register-type-aliases! (:fn-defs packages))
    (doseq [[fn-name impl] base-fns-map]
      (exec/register-base-fn! fn-name impl))
    base-fns-map))


(defn- app-handler
  "Ring handler that runs the org's app fn for every request — the BYO
   executor's whole job. Org-scoped (the graph is already org-scoped in
   RemoteStorage), no execute-guard (the app is the org's public face), and
   time-bounded with cooperative cancellation like the hub app-router.

   Effects are NOT clamped. A BYO executor runs the customer's OWN graph on
   the customer's OWN hardware — the same trust posture as a self-hosted
   deployment, which runs with no effect gate. The cloud clamp
   (`default-cloud-allowed-effects`) exists to protect the SHARED platform
   from untrusted co-located tenant code; here there is no shared platform to
   protect, and clamping would break the core BYO case of an app that calls
   external APIs. So `:allowed-effects` is left unset (nil ⇒ the gate is a
   no-op, `compile-runtime/*allowed-effects*`)."
  [ctx org handler-fn-id]
  (fn [request]
    (let [result
          (tc/with-org org
                       (cr/run-with-timeout
                         default-timeout-ms
                         (fn []
                           ;; Cooperative cancel: each execute step consults
                           ;; `*cancel-check*`, so `future-cancel` (interrupt)
                           ;; on timeout aborts the handler instead of leaking
                           ;; a thread.
                           (binding [cr/*cancel-check*
                                     #(when (Thread/.isInterrupted (Thread/currentThread))
                                        (throw (InterruptedException. "app handler cancelled")))]
                             (cr/execute (assoc ctx :execute-guard nil)
                                         handler-fn-id
                                         {:request request})))))]
      (cond
        (identical? result ::cr/timeout)
        {:status 504 :headers {"Content-Type" "text/plain"} :body "Application timed out."}
        (identical? result ::cr/error)
        {:status 500 :headers {"Content-Type" "text/plain"} :body "Application error."}
        :else result))))


(defn start-byo!
  "Assemble + start a BYO executor. Returns a handle for `stop-byo!`.

   Config:
   - `:hub-url`      the hub's APP base URL (serves `/api/export/graph-rows`),
                     e.g. \"https://hub.example.com\"
   - `:sse-url`      the hub's SSE RELAY base URL (a DIFFERENT port —
                     `GRAPHDEN_SSE_PORT`), e.g. \"https://hub.example.com:8081\".
                     nil ⇒ no live refresh (bootstrap-only).
   - `:token`        this executor's bearer (mint one on the hub with
                     `POST /api/tokens`)
   - `:org`          the org this executor serves (its slug)
   - `:branch`       branch to pin (nil ⇒ main)
   - `:handler-fn`   name of the org's app-handler fn to run per request
   - `:port`         HTTP port to serve on
   - `:packages`     package set to load impls from (default `default-packages`)
   - `:extra-base-fns` optional `{fn-name → impl}` merged over the package impls

   The hub-side prep: create the org, mint this token, point the org at its
   handler, and flip it byo (`POST /api/orgs/execution-mode`)."
  [{:keys [hub-url sse-url token org branch handler-fn port packages extra-base-fns]
    :or {packages default-packages}}]
  ;; Preflight so a forgotten env var is a clear message, not a confusing
  ;; "bootstrap GET failed" or NPE deeper in.
  (doseq [[k v] {:hub-url hub-url :token token :org org :handler-fn handler-fn :port port}]
    (when (or (nil? v) (and (string? v) (str/blank? v)))
      (throw (ex-info (str "BYO executor missing required config: " k)
                      {:type :byo/missing-config :key k}))))
  (log/info "Starting BYO executor" {:hub hub-url :org org :branch branch :port port})
  (let [loaded (when (seq packages) (pkg/load-packages packages))
        base-fns (register-impls-in-memory! loaded extra-base-fns)
        storage (remote/create-remote-storage hub-url token branch)
        ctx (ectx/create-context {:storage storage
                                  :base-fns base-fns
                                  :executor-orgs #{org}
                                  :byo-executor? true})
        _ (cr/rebuild! ctx)
        handler-fn-id (:id (first (sp/query-entities storage :fn {:name handler-fn})))
        _ (when-not handler-fn-id
            (throw (ex-info (str "BYO handler fn not found in the graph: " handler-fn)
                            {:type :byo/handler-not-found :handler-fn handler-fn})))
        server (hk/run-server (app-handler ctx org handler-fn-id) {:port port})
        ;; Stay fresh: on any fn-invalidate the hub pushes, refetch the org's
        ;; bundle + recompile. (The relay already fans out only this org's
        ;; events + public ones.) The relay is on its OWN URL/port. nil ⇒
        ;; bootstrap-only (no live refresh).
        source (when sse-url
                 (remote-sse/start-source!
                   {:hub-url sse-url :token token
                    :on-event (fn [event]
                                (when (= :fn (:kind event))
                                  (remote/refresh! storage)
                                  (cr/rebuild! ctx)))}))]
    (log/info "BYO executor serving" {:org org :handler handler-fn :port port})
    {:server server :source source :ctx ctx :storage storage}))


(defn stop-byo!
  "Stop a BYO executor handle: SSE source + HTTP server."
  [{:keys [server source]}]
  (when source (remote-sse/stop-source! source))
  (when server (server))
  (log/info "BYO executor stopped"))


(defn -main
  "Entry point — reads config from the environment:
   `GRAPHDEN_HUB_URL`, `GRAPHDEN_SSE_URL` (optional — the hub's SSE relay;
   unset ⇒ bootstrap-only, no live refresh), `GRAPHDEN_EXECUTOR_TOKEN`,
   `GRAPHDEN_EXECUTOR_ORG`, `GRAPHDEN_EXECUTOR_BRANCH` (optional),
   `GRAPHDEN_APP_HANDLER_FN`, `GRAPHDEN_PORT` (default 8080)."
  [& _args]
  (let [handle (start-byo!
                 {:hub-url (System/getenv "GRAPHDEN_HUB_URL")
                  :sse-url (System/getenv "GRAPHDEN_SSE_URL")
                  :token (System/getenv "GRAPHDEN_EXECUTOR_TOKEN")
                  :org (System/getenv "GRAPHDEN_EXECUTOR_ORG")
                  :branch (System/getenv "GRAPHDEN_EXECUTOR_BRANCH")
                  :handler-fn (or (System/getenv "GRAPHDEN_APP_HANDLER_FN") "_app-ring-response")
                  :port (or (some-> (System/getenv "GRAPHDEN_PORT") parse-long) 8080)})]
    (Runtime/.addShutdownHook (Runtime/getRuntime) (Thread. #(stop-byo! handle)))
    @(promise)))
