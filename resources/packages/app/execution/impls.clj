(ns graphden.packages.app.execution.impls
  "Implementations for the app/execution package — POST/GET/cancel
   delegate to `graphden.crud.fn-execution`. The implicit `ctx`
   symbol is in scope via defbase; it carries the storage handle the
   stage functions read."
  (:require
    [clojure.string]
    [graphden.crud.fn-execution :as fn-exec]
    [graphden.crud.request :as request]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.services.reconciler :as recon]
    [graphden.storage.protocol.core :as sp]))


;; --- POST /api/execute ---

(defbase _execute-parsed
  [request]
  (fn-exec/parse-execute-request request))


(defbase _execute-validation
  [parsed]
  (fn-exec/validate-execute ctx parsed))


(defbase _execute-apply
  [parsed]
  (fn-exec/apply-execute ctx parsed))


(defbase _execute-rejected?
  [validation]
  ;; A `validate-*` stage returns nil when well-formed or
  ;; `{:ok false …}` when rejected. The `:cond` graph dispatches
  ;; on truthiness of this predicate.
  (some? validation))


;; --- GET /api/execute/:id ---

(defn- path-id
  "Pull the execution id from the URL. Reitit threads matched path
   params into `:path-params` AFTER its enrich-request middleware
   runs — but some handler paths (e.g. middleware-wrapped routes)
   are invoked with the raw http-kit request that hasn't gone
   through enrich, so `:path-params` is nil. Fall back to parsing
   the URI directly: `/api/execute/<id>` or
   `/api/execute/<id>/cancel` — the UUID is the 3rd path segment."
  [request]
  (let [raw (or (get-in request [:path-params :id])
                (let [segs (-> (:uri request "") (clojure.string/split #"/"))]
                  ;; segs is `[\"\" \"api\" \"execute\" id …]`
                  (get (vec (remove empty? segs)) 2)))]
    (request/parse-uuid-or-clear raw)))


(defbase _get-execution
  [request]
  (let [id (path-id request)]
    (or (when id (fn-exec/get-execution ctx id))
        {:ok false :error (str "Execution not found: "
                               (get-in request [:path-params :id]))})))


;; --- POST /api/execute/:id/cancel ---

(defbase _cancel-execution
  [request]
  (let [id (path-id request)]
    (or (when id (fn-exec/cancel-execution! ctx id))
        {:ok false :error (str "Execution not found: "
                               (get-in request [:path-params :id]))})))


;; --- GET /api/executions?fn-id=X ---

(defn- query-param
  "Pull a named query-string parameter from `request`, tolerating both
   reitit's enriched shapes (`:query-params` keyed by string OR
   keyword) AND raw http-kit requests that haven't gone through the
   enrich middleware — those land here with just `:query-string`.
   Returns the raw string value, or nil when the param isn't present."
  [request param-name]
  (or (get-in request [:query-params param-name])
      (get-in request [:query-params (keyword param-name)])
      (some->> (:query-string request)
               (re-find (re-pattern (str "(?:^|&)" param-name "=([^&]+)")))
               second)))


(defn- query-fn-id
  [request]
  (request/parse-uuid-or-clear (query-param request "fn-id")))


(defn- query-limit
  "Optional `?limit=N` query param. Returns parsed long or nil — the
   crud layer applies clamping + the default. Tolerates malformed
   input (non-numeric → nil) so a bad query never 500s."
  [request]
  (when-let [raw (query-param request "limit")]
    (try (Long/parseLong (str raw))
         (catch NumberFormatException _ nil))))


(defbase _list-executions-by-fn
  [request]
  (let [fn-id (query-fn-id request)
        limit (query-limit request)]
    (if fn-id
      {:ok true :executions (fn-exec/list-executions-for-fn ctx fn-id limit)}
      {:ok false :error "missing or invalid ?fn-id query parameter"})))


;; --- POST /api/services/reconcile ---
;;
;; Hot-reload trigger for the service reconciler. Phase 1 has no
;; periodic poll — admins call this endpoint after creating /
;; modifying / disabling :service rows through generic CRUD so the
;; in-process running atom catches up without a pod restart.

(defbase _reconcile-services
  [_request]
  (let [summary (recon/reconcile-once! ctx recon/running)]
    {:ok true :reconcile summary}))


;; --- GET /api/services ---
;;
;; List every :service row merged with its in-process running state.
;; Used by the editor's "Only services" sidebar filter, the
;; "Make service" row-actions popover, and the per-fn service badge.

(defn- enrich-running
  "Pull the in-process entry for `service-id` out of the running atom
   and reshape into a JSON-safe map. nil when nothing is registered."
  [service-id]
  (when-let [entry (get @recon/running service-id)]
    {:stopper-set?    (boolean (:stopper entry))
     :started-at      (some-> (:started-at entry) str)
     :start-attempts  (:start-attempts entry)
     :start-failed-at (some-> (:start-failed-at entry) str)}))


(defn- fn-name-by-id
  "Index `:fn` rows from storage. Single query → constant-time
   lookups for the per-service join below."
  [storage]
  (into {} (map (juxt :id :name)) (sp/query-entities storage :fn {})))


(defbase _list-services
  [_request]
  (let [storage  (request/require-storage ctx)
        services (sp/query-entities storage :service {})
        names    (fn-name-by-id storage)
        rows     (mapv
                   (fn [s]
                     {:id (:id s)
                      :fn-id (:fn-id s)
                      :fn-name (get names (:fn-id s))
                      :enabled? (:enabled? s)
                      :restart-policy (:restart-policy s)
                      :running (enrich-running (:id s))})
                   services)
        legacy   (when-let [h @recon/legacy-handle]
                   {:fn-id (:fn-id h)
                    :fn-name (get names (:fn-id h))})]
    {:ok true
     :services rows
     :legacy-fallback legacy}))


(def impls
  {:_execute-parsed          _execute-parsed
   :_execute-validation      _execute-validation
   :_execute-apply           _execute-apply
   :_execute-rejected?       _execute-rejected?
   :_get-execution           _get-execution
   :_cancel-execution        _cancel-execution
   :_list-executions-by-fn   _list-executions-by-fn
   :_reconcile-services      _reconcile-services
   :_list-services           _list-services})
