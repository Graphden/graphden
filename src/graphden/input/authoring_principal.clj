(ns graphden.input.authoring-principal
  "Request-local authentication for named input authoring in a standalone host."
  (:require
    [graphden.auth.provider :as auth]
    [graphden.packages.owned :as owned]
    [graphden.tenancy.context :as tenancy]))


(def ^:dynamic ^:private *standalone-proof* nil)


(defn with-standalone-request
  [ctx request f]
  (if (and (:auth-provider ctx) (seq (::owned/primitive-input-types (meta (:base-fns ctx)))))
    (binding [*standalone-proof* (delay (auth/authenticate (:auth-provider ctx) request))]
      (f))
    (f)))


(defn current
  []
  (or tenancy/*current-principal* (some-> *standalone-proof* deref)))
