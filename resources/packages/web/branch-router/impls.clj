(ns graphden.packages.web.branch-router.impls
  "Impl for `web.branch-router/branch-routing-wrap`. The body is a
   short Ring callable — the real routing logic lives in
   `graphden.system.branch-router`."
  (:require
    [graphden.executor.defbase :refer [defbase]]
    [graphden.system.branch-router :as br]))


(defbase branch-routing-wrap
  [base-handler request]
  ;; Runs ONCE per HTTP request — hof-wrap binds the incoming request
  ;; to `:request` and invokes this fn-graph's closure. We then ask
  ;; the router which branch's handler to call. No router installed
  ;; (test paths, or the brief window before `:exec/branch-router`
  ;; fires) → call `base-handler` directly (single-branch fallback).
  (if-let [router (br/current-router)]
    (br/dispatch router request)
    (base-handler request)))


(def impls
  {:branch-routing-wrap branch-routing-wrap})
