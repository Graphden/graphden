(ns graphden.packages.web.errors.impls
  "Base-fn impls for the web/errors module — thin boundaries over
   `graphden.web.errors`, the single :type → HTTP mapping."
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [graphden.web.errors :as errors]))


(defbase _error-boundary-catch
  "Catch request Exceptions; delegate response policy to the supplied callback.
   Java Errors and the successful response pass through untouched."
  [handler on-error]
  (errors/wrap-error-boundary handler on-error))


(defbase _safe-request-error
  "Log/count a request fault and return only the centrally redacted envelope."
  [exception]
  (errors/report-throwable exception))


(def impls
  {:_error-boundary-catch {:impl _error-boundary-catch :taint-propagate? true}
   :_safe-request-error {:impl _safe-request-error :taint-propagate? true}})
