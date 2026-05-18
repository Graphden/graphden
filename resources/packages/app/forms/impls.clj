(ns graphden.packages.app.forms.impls
  "Implementations for the app/forms base functions — the
   `/api/value-form` parse / validate / apply stages.

   Each `defbase` is a thin shim delegating to
   `graphden.crud.value-form`; the implicit `ctx` symbol is passed
   through where storage / the executor registry are needed."
  (:require
    [graphden.crud.value-form :as value-form]
    [graphden.executor.defbase :refer [defbase]]))


(defbase _value-form-parsed
  [request]
  (value-form/parse-value-form-request request))


(defbase _value-form-validation
  [parsed]
  (value-form/validate-value-form parsed))


(defbase _value-form-apply
  [parsed]
  (value-form/apply-value-form parsed ctx))


;; The package loader pairs each base-fn declared in `fns.edn` with its
;; impl by looking up this `impls` map (keyword name -> impl fn).
(def impls
  {:_value-form-parsed     _value-form-parsed
   :_value-form-validation _value-form-validation
   :_value-form-apply      _value-form-apply})
