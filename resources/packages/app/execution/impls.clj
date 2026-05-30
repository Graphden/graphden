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


;; --- C18 atoms: get-execution + cancel-execution variant-2.
;; Both handlers share the path-id parser AND the dynamic 404
;; builder (same text either way); each has its own apply
;; (read vs cancel-mutation).

(defbase _exec-id-parsed
  [request]
  {:id (path-id request)
   :id-raw (or (get-in request [:path-params :id])
               (let [segs (-> (:uri request "") (clojure.string/split #"/"))]
                 (get (vec (remove empty? segs)) 2)))})


(defbase _exec-err-not-found
  [parsed]
  {:ok false :error (str "Execution not found: " (:id-raw parsed))})


;; GET /api/execute/:id atoms

(defbase _get-exec-loaded
  [parsed]
  (when-let [id (:id parsed)]
    (fn-exec/get-execution ctx id)))


(defbase _get-exec-missing?
  [loaded]
  (nil? loaded))


(defbase _get-exec-apply
  [loaded]
  loaded)


;; POST /api/execute/:id/cancel atoms

(defbase _cancel-exec-applied
  [parsed]
  (when-let [id (:id parsed)]
    (fn-exec/cancel-execution! ctx id)))


(defbase _cancel-exec-missing?
  [applied]
  (nil? applied))


(defbase _cancel-exec-apply
  [applied]
  applied)


;; --- GET /api/executions?fn-id=X (C6 atoms) ---
;; Two query shapes share this endpoint:
;;   ?fn-id=X         → executions of X as it resolves on the current
;;                      branch (drives the execute popover's history)
;;   ?fn-version-id=Y → executions of the SPECIFIC version row Y
;;                      (drives the `⌛` panel's per-version expand)
;; If both are present `fn-version-id` wins. `:_list-executions-by-fn`
;; is now a `:cond` graph fn-def in fns.edn composing these atoms.

(defbase _list-exec-parsed
  [request]
  (fn-exec/parse-list-executions-request request))


(defbase _list-exec-no-anchor?
  [parsed]
  (and (nil? (:version-id parsed)) (nil? (:fn-id parsed))))


(defbase _list-exec-by-version?
  [parsed]
  (some? (:version-id parsed)))


(defbase _list-exec-apply-by-version
  [parsed]
  (fn-exec/apply-list-executions-by-version parsed ctx))


(defbase _list-exec-apply-by-fn
  [parsed]
  (fn-exec/apply-list-executions-by-fn parsed ctx))


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


;; --- C17 atoms: list-services linear ETL decomposition.
;; Five named steps glued by a `:cond`-free graph fn-def — pure
;; variant-1 data composition so each stage is visible. The
;; previously-monolithic 20-line body splits into the conceptual
;; pipeline: load services + fn-name-index → enrich each → maybe
;; build legacy fallback → wrap as final response. Each atom is a
;; 1-3-line wrap over the helpers above.

(defbase _list-services-rows
  [_request]
  (sp/query-entities (request/require-storage ctx) :service {}))


(defbase _list-services-fn-names
  [_request]
  (fn-name-by-id (request/require-storage ctx)))


(defbase _list-services-enriched
  [rows names]
  (mapv (fn [s]
          {:id (:id s)
           :fn-id (:fn-id s)
           :fn-name (get names (:fn-id s))
           :enabled? (:enabled? s)
           :restart-policy (:restart-policy s)
           :running (enrich-running (:id s))})
        rows))


(defbase _list-services-legacy
  [names]
  (when-let [h @recon/legacy-handle]
    {:fn-id (:fn-id h)
     :fn-name (get names (:fn-id h))}))


(defbase _list-services
  [enriched legacy]
  {:ok true
   :services enriched
   :legacy-fallback legacy})


(def impls
  {:_execute-parsed          _execute-parsed
   :_execute-validation      _execute-validation
   :_execute-apply           _execute-apply
   :_execute-rejected?       _execute-rejected?
   :_exec-id-parsed          _exec-id-parsed
   :_exec-err-not-found      _exec-err-not-found
   :_get-exec-loaded         _get-exec-loaded
   :_get-exec-missing?       _get-exec-missing?
   :_get-exec-apply          _get-exec-apply
   :_cancel-exec-applied     _cancel-exec-applied
   :_cancel-exec-missing?    _cancel-exec-missing?
   :_cancel-exec-apply       _cancel-exec-apply
   :_list-exec-parsed        _list-exec-parsed
   :_list-exec-no-anchor?    _list-exec-no-anchor?
   :_list-exec-by-version?   _list-exec-by-version?
   :_list-exec-apply-by-version _list-exec-apply-by-version
   :_list-exec-apply-by-fn   _list-exec-apply-by-fn
   :_reconcile-services      _reconcile-services
   :_list-services-rows      _list-services-rows
   :_list-services-fn-names  _list-services-fn-names
   :_list-services-enriched  _list-services-enriched
   :_list-services-legacy    _list-services-legacy
   :_list-services           _list-services})
