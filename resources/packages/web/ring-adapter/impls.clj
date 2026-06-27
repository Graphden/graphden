(ns graphden.packages.web.ring-adapter.impls
  "Base-fns for the Ring adapter. Currently just the auth seam
   (PLATFORM_PLAN §3.0 / §4.1): `:authenticate-request` delegates the
   authentication decision to the context's pluggable `:auth-provider`,
   so the auth middleware is identical whether core's single-token
   provider or the tenancy addon's session/JWT provider is wired."
  (:require
    [graphden.auth.provider :as auth]
    [graphden.executor.defbase :refer [defbase]]))


(defbase authenticate-request
  [request]
  ;; Delegate to the injected provider. Defensive: a context with no
  ;; provider wired authenticates NOTHING (secure default) rather than
  ;; throwing — so any half-configured ctx fails closed.
  (if-let [provider (:auth-provider ctx)]
    (auth/authenticate provider request)
    {:authenticated? false}))


(def impls
  {:authenticate-request authenticate-request})
