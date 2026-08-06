(ns graphden.packages.web.ring-adapter.impls
  "Base-fns for the Ring adapter. Currently just the auth seam
   (docs/TENANCY_SEAM.md § Auth seam): `:authenticate-request` delegates the
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


(defbase auth-active?
  []
  ;; Whether authentication is turned ON for this deployment: true iff an
  ;; auth provider is wired (the `auth` addon's single-token provider, or the
  ;; tenancy addon's storage-token provider). No provider ⇒ auth is OFF ⇒ the
  ;; auth-required middleware passes everything through (self-hosted "just run
  ;; it locally, no login"). This is the switch the provider-aware middleware
  ;; reads so the SAME auth-required routes are gated when auth is on and open
  ;; when it's off.
  (some? (:auth-provider ctx)))


(def impls
  {:authenticate-request authenticate-request
   :auth-active? auth-active?})
