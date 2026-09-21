(ns graphden.packages.web.errors.impls
  "Base-fn impls for the web/errors module — thin boundaries over
   `graphden.web.errors`, the single :type → HTTP mapping."
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [graphden.web.errors :as errors]))


(defbase error-boundary-wrap
  "Ring wrap: any throw from the inner handler becomes its mapped
   status + safe JSON body (opaque ref for non-author-facing types)
   instead of an opaque type-losing 500. Library-adapter boundary —
   the mapping itself lives in `graphden.web.errors`."
  [handler]
  (errors/wrap-error-boundary handler))


;; :json-envelope-response is a GRAPH fn-def now (web/errors/fns.edn) —
;; status extraction, body strip, JSON serialise (`:to-json-string`) and
;; the 429 → `Retry-After: 1` header policy all compose over
;; `:ring-response`, so the retry policy is graph-visible and tunable
;; instead of a literal nailed into a Clojure impl.


(def impls
  {:error-boundary-wrap error-boundary-wrap})
