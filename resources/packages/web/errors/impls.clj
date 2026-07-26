(ns graphden.packages.web.errors.impls
  "Base-fn impls for the web/errors module — thin boundaries over
   `graphden.web.errors`, the single :type → HTTP mapping."
  (:require
    [cheshire.core :as json]
    [graphden.executor.defbase :refer [defbase]]
    [graphden.web.errors :as errors]))


(defbase error-boundary-wrap
  "Ring wrap: any throw from the inner handler becomes its mapped
   status + safe JSON body (opaque ref for non-author-facing types)
   instead of an opaque type-losing 500. Library-adapter boundary —
   the mapping itself lives in `graphden.web.errors`."
  [handler]
  (errors/wrap-error-boundary handler))


(defbase error-http-status
  "HTTP status for an error `:type` keyword (or its text form) — the
   central `graphden.web.errors/status-for` table. Response-building
   fn-defs bind their `:status` through this so every family answers
   with the same status for the same type."
  [error-type]
  (errors/status-for (cond-> error-type (string? error-type) keyword)))


(defbase json-envelope-response
  "Ring JSON response whose STATUS comes from the envelope itself:
   `:http-status` (default 200) is read and stripped, the rest is the
   body. 429 additionally carries `Retry-After: 1`. The graph-side
   rejection builders declare their status right where they build the
   envelope — one response seam, statuses visible at the source.
   Mirrors `html-action-response` for the JSON families."
  [envelope]
  ;; NB: no self-shadowing let over a defbase arg — the macro's symbol
  ;; substitution skips the shadowed scope including the shadow's own
  ;; RHS.
  (let [env (or envelope {})
        status (or (:http-status env) 200)
        body (dissoc env :http-status)]
    {:status status
     :headers (cond-> {"Content-Type" "application/json"}
                (= 429 status) (assoc "Retry-After" "1"))
     :body (json/generate-string body)}))


(def impls
  {:error-boundary-wrap error-boundary-wrap
   :error-http-status error-http-status
   :json-envelope-response json-envelope-response})
